package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.catalogue.VpOption
import com.dynokore.gst.evidence.adapters.events.RetentionNoticeMessageOutbox
import com.dynokore.gst.evidence.adapters.persistence.JooqCustodyHolderReader
import com.dynokore.gst.evidence.adapters.persistence.JooqNoticeCandidates
import com.dynokore.gst.evidence.adapters.persistence.JooqRetentionNotices
import com.dynokore.gst.evidence.adapters.retention.PolicyNoticeWindowSource
import com.dynokore.gst.evidence.adapters.retention.ResolvedNoticeWindow
import com.dynokore.gst.evidence.application.retention.CustodyHolderReader
import com.dynokore.gst.evidence.application.retention.NoticeAcknowledgement
import com.dynokore.gst.evidence.application.retention.NoticeCandidate
import com.dynokore.gst.evidence.application.retention.NoticeRun
import com.dynokore.gst.evidence.application.retention.NoticeWindowSetting
import com.dynokore.gst.evidence.application.retention.NoticeWindowSource
import com.dynokore.gst.evidence.application.retention.RetentionEndReader
import com.dynokore.gst.evidence.application.retention.RetentionNotice
import com.dynokore.gst.evidence.application.retention.RetentionNoticeRecorder
import com.dynokore.gst.evidence.application.retention.SendRetentionNotices
import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import com.dynokore.platform.configresolver.Bound
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.Direction
import com.dynokore.platform.configresolver.OptionSpec
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PrecedenceResolver
import com.dynokore.platform.configresolver.RelaxationAcknowledgement
import com.dynokore.platform.configresolver.RelaxationId
import com.dynokore.platform.configresolver.RelaxationReader
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingLookup
import com.dynokore.platform.configresolver.SettingSource
import com.dynokore.platform.configresolver.StoredSetting
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.TypedValueSpec
import com.dynokore.platform.configresolver.ValueType
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpClass
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The window source port: [days] in force and [acknowledgement] behind it, with every question recorded. */
class Source(
    private val days: Int = 1,
    private val acknowledgement: NoticeAcknowledgement? = null,
) : NoticeWindowSource {
    val windows = mutableListOf<Pair<String, LocalDate>>()
    val acknowledgements = mutableListOf<Triple<String, String, Instant>>()

    override fun window(
        tenant: String,
        on: LocalDate,
    ): NoticeWindow {
        windows += tenant to on
        return NoticeWindow(days)
    }

    override fun acknowledgement(
        tenant: String,
        entityRef: String,
        notifiedAt: Instant,
    ): NoticeAcknowledgement? {
        acknowledgements += Triple(tenant, entityRef, notifiedAt)
        return acknowledgement
    }
}

/** VP-66 and its neighbours over the pinned catalogue, with the real resolver. */
private object PinnedPolicy {
    fun pinnedCatalogue() =
        VariationPointCatalogue { key ->
            VariationPoint.entries.singleOrNull { it.id == key.value }?.let { definitionOf(key, it) }
        }

    fun pinnedResolver() = PrecedenceResolver(pinnedCatalogue(), DeploymentForm.SAAS) { _, _, _, _, _ -> null }

    fun storedShorter(days: String): SettingLookup {
        val stored =
            StoredSetting(
                UUID.randomUUID(),
                listOf(SelectedOption("SHORTER_ACKNOWLEDGED", days)),
                SettingSource.OVERRIDE,
                null,
                null,
                LocalDate.of(2026, 1, 1),
            )
        return SettingLookup { key, ref, _, source, _ ->
            stored.takeIf {
                key.value == "VP-66" && ref.level == ScopeLevel.INSTALLATION && source == SettingSource.OVERRIDE
            }
        }
    }

    fun acknowledgedBy(user: String?) =
        object : RelaxationReader {
            override fun acknowledgementsInForce(
                key: VariationPointKey,
                scope: PolicyScope,
                at: LocalDate,
            ): List<RelaxationAcknowledgement> =
                listOfNotNull(
                    user?.takeIf { key.value == "VP-66" }?.let {
                        RelaxationAcknowledgement(
                            UUID.randomUUID(),
                            RelaxationId(UUID.randomUUID()),
                            "tax.approver",
                            it,
                            LocalDate.of(2026, 1, 1),
                            Instant.parse("2026-01-01T00:00:00Z"),
                        )
                    },
                )
        }

    private fun definitionOf(
        key: VariationPointKey,
        row: VariationPoint,
    ) = VariationPointDefinition(
        key = key,
        vpClass = VpClass.valueOf(row.vpClass.name),
        modifiers = emptySet(),
        status = VpStatus.valueOf(row.status.name),
        scopeLevels = row.scopeLevels.orEmpty().mapTo(linkedSetOf()) { ScopeLevel.valueOf(it.name) },
        setPermission = row.setPermission,
        options = row.optionSpecs!!.map(::optionOf),
    )

    private fun optionOf(option: VpOption): OptionSpec {
        val typed = option.typedValue
        return OptionSpec(
            option.key,
            Strictness.valueOf(option.strictness.name),
            typed != null,
            typed?.let {
                TypedValueSpec(
                    ValueType.valueOf(it.type.name),
                    it.unit,
                    it.min?.let { bound -> Bound(number = bound.number) },
                    it.max?.let { bound -> Bound(number = bound.number) },
                    direction = it.direction?.let { d -> Direction.valueOf(d.name) } ?: Direction.NONE,
                    default = it.default?.let { bound -> Bound(number = bound.number) },
                )
            },
        )
    }
}

/** The tenant, rows and ports the database cases share. */
open class RetentionNoticeDbSupport {
    protected val tenant = UUID.randomUUID()
    protected val at = Instant.parse("2033-03-31T10:00:00Z")
    protected val clock = Clock.fixed(at, ZoneOffset.UTC)

    protected fun <T> runtime(
        bound: UUID = tenant,
        commit: Boolean = true,
        block: (Connection, DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, bound)
            val result = block(c, DSL.using(c, SQLDialect.POSTGRES))
            if (commit) c.commit() else c.rollback()
            result
        }

    protected fun <T> superuser(
        sql: String,
        vararg args: Any,
        read: (java.sql.PreparedStatement) -> T,
    ): T =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                read(st)
            }
        }

    protected fun values(
        sql: String,
        vararg args: Any,
    ): List<String> =
        superuser(sql, *args) { st ->
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    protected fun partition(
        owner: UUID = tenant,
        period: String = "2019-Q1",
        state: String = "ATTACHED",
        entity: String = "E1",
    ): String {
        runtime(owner) { c, _ ->
            RetentionPartitionFixture.ensureRetentionPartition(c, "decision_trace", owner, entity, period)
        }
        val id =
            values(
                "select partition_id from evidence.retention_partition where tenant_id = ? and period_from = ?",
                owner,
                period,
            ).single()
        superuser("update evidence.retention_partition set state = ? where partition_id = ?", state, id) {
            it.executeUpdate()
        }
        return id
    }

    protected fun holderRow(
        email: String,
        receivedAt: Instant,
        window: Int? = null,
    ) {
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "insert into evidence.offboarding_custody_holder values (?, ?, 'A Holder', ?, '+6590000000', ?, ?)",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, "OFF-" + UUID.randomUUID())
                    it.setString(3, email)
                    it.setObject(4, window, java.sql.Types.INTEGER)
                    it.setTimestamp(5, Timestamp.from(receivedAt))
                    it.executeUpdate()
                }
        }
    }

    protected fun pending(
        bound: UUID = tenant,
        asked: UUID = tenant,
    ): List<NoticeCandidate> = runtime(bound) { _, dsl -> JooqNoticeCandidates(dsl).pending(asked.toString()) }

    protected fun holder(
        bound: UUID = tenant,
        asked: UUID = tenant,
    ): CustodyHolderReader.CustodyHolder? =
        runtime(bound) { _, dsl -> JooqCustodyHolderReader(dsl).holder(asked.toString()) }

    protected fun notice(
        partitionId: String,
        days: Int = 90,
        notifiedAt: Instant = at,
    ) = RetentionNotice(
        partitionId,
        notifiedAt,
        days,
        listOf("holder@example.test"),
        "retention-notice",
        "EMAIL",
        mapOf(
            "periodStart" to "2019-01-01",
            "periodEnd" to "2019-03-31",
            "noticeWindowEndsAt" to "2033-06-29T10:00:00Z",
        ),
    )

    protected fun writer(dsl: DSLContext): OutboxWriter =
        JdbcOutboxWriter(
            dsl,
            EvidenceDb.SCHEMA,
            "evidence-svc",
            "instance-1",
            EventCatalogue(
                listOf(
                    CatalogueEntry(
                        RetentionNoticeMessageOutbox.EVENT_TYPE,
                        "gst.access.message-requested.v1",
                        setOf("evidence-svc"),
                    ),
                ),
            ),
            clock,
        )

    protected fun recorder(
        dsl: DSLContext,
        outbox: OutboxWriter = writer(dsl),
        source: NoticeWindowSource = Source(),
    ) = JooqRetentionNotices(dsl, source, RetentionNoticeMessageOutbox(outbox))

    protected fun record(
        notice: RetentionNotice,
        bound: UUID = tenant,
        commit: Boolean = true,
        source: NoticeWindowSource = Source(),
    ): Boolean = runtime(bound, commit) { _, dsl -> recorder(dsl, source = source).record(bound.toString(), notice) }

    protected fun acknowledgementColumns(partitionId: String): String =
        values(
            "select coalesce(acknowledged_by, 'null') || '|' || coalesce(acknowledged_sole_operator::text, 'null') " +
                "from evidence.retention_notice where partition_id = ?",
            partitionId,
        ).single()

    protected fun noticeRows(partitionId: String): List<String> =
        values(
            "select tenant_id::text || '|' || window_days || '|' || " +
                "extract(epoch from notified_at)::bigint || '|' || " +
                "extract(epoch from window_closes_at - notified_at)::bigint " +
                "from evidence.retention_notice where partition_id = ?",
            partitionId,
        )

    protected fun outboxRows(owner: UUID = tenant): List<String> =
        values("select type from ${EvidenceDb.SCHEMA}.outbox where tenant_id = ?", owner)

    protected fun send(
        dsl: DSLContext,
        recorder: RetentionNoticeRecorder,
        window: NoticeWindowSetting =
            object : NoticeWindowSetting {
                override fun days(tenant: String) = 90
            },
    ) = SendRetentionNotices(
        JooqNoticeCandidates(dsl),
        JooqCustodyHolderReader(dsl),
        window,
        object : RetentionEndReader {
            override fun retentionEnd(
                tenant: String,
                partitionId: String,
            ): Instant = at.minusSeconds(1)
        },
        recorder,
        clock,
    )
}

/** INV-AC-26996 at the database: the candidate reader, the holder reader and the notice recorder, real PostgreSQL. */
class RetentionNoticeDbTest : RetentionNoticeDbSupport() {
    @Test
    fun `INV-AC-26996 the candidates are the attached and detached partitions with no notice`() {
        val attached = partition(period = "2019-Q1")
        val detached = partition(period = "2019-Q2", state = "DETACHED")
        partition(period = "2019-Q3", state = "ARCHIVED")
        partition(period = "2019-Q4", state = "DROPPED")
        val noticed = partition(period = "2020-Q1")
        superuser(
            "insert into evidence.retention_notice values (?, ?, now(), 30, now())",
            tenant,
            noticed,
        ) { it.executeUpdate() }
        pending().sortedBy { it.periodFrom } shouldBe
            listOf(
                NoticeCandidate(attached, LocalDate.of(2019, 1, 1), LocalDate.of(2019, 3, 31)),
                NoticeCandidate(detached, LocalDate.of(2019, 4, 1), LocalDate.of(2019, 6, 30)),
            )
    }

    @Test
    fun `INV-AC-26996 a partition whose period cannot be dated stays a candidate`() {
        val id = partition(period = "P1")
        pending() shouldBe listOf(NoticeCandidate(id, LocalDate.MIN, LocalDate.MAX))
    }

    @Test
    fun `INV-AC-26996 another tenant reads no candidate and no holder of this tenant`() {
        partition()
        holderRow("a@example.test", at)
        val other = UUID.randomUUID()
        pending().size shouldBe 1
        holder()?.email shouldBe "a@example.test"
        // bound to another tenant and asking for this one: row level security shows no row
        pending(bound = other) shouldBe emptyList()
        holder(bound = other) shouldBe null
        pending(bound = other, asked = other) shouldBe emptyList()
        holder(bound = other, asked = other) shouldBe null
        // bound to this tenant and asking for another one reads nothing either
        pending(asked = other) shouldBe emptyList()
        holder(asked = other) shouldBe null
    }

    @Test
    fun `INV-AC-26996 the holder is the row received last, with its stored window`() {
        holderRow("old@example.test", at.minusSeconds(60), window = 200)
        holderRow("new@example.test", at, window = 120)
        holderRow("older@example.test", at.minusSeconds(120))
        holder() shouldBe CustodyHolderReader.CustodyHolder("A Holder", "new@example.test", "+6590000000", 120)
    }

    @Test
    fun `INV-AC-26996 two rows with an equal greatest received_at give null, an older tie does not`() {
        holderRow("a@example.test", at)
        holderRow("b@example.test", at)
        holder() shouldBe null
        holderRow("c@example.test", at.plusSeconds(1))
        holder()?.email shouldBe "c@example.test"
    }

    @Test
    fun `INV-AC-26996 no holder row gives null`() {
        holder() shouldBe null
    }

    @Test
    fun `INV-AC-26996 a record leaves one notice row and one outbox row of the type`() {
        val id = partition()
        record(notice(id, days = 90)) shouldBe true
        noticeRows(id) shouldBe listOf("$tenant|90|${at.epochSecond}|${90 * DAY}")
        outboxRows() shouldBe listOf("com.dynokore.gst.access.MessageRequested.v1")
    }

    @Test
    fun `INV-AC-26996 a rolled back caller transaction leaves neither row`() {
        val id = partition()
        record(notice(id), commit = false) shouldBe true
        noticeRows(id) shouldBe emptyList()
        outboxRows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 a failure of the second write leaves neither row`() {
        val id = partition()
        shouldThrow<IllegalStateException> {
            runtime { _, dsl ->
                // the real outbox row is written and the write then fails: both rows must vanish with the caller
                val real = writer(dsl)
                val failing =
                    object : OutboxWriter {
                        override fun append(
                            events: List<OutboundEvent>,
                            context: EventContext,
                        ): List<Envelope> {
                            real.append(events, context)
                            error("forced failure of the second write")
                        }
                    }
                recorder(dsl, failing).record(tenant.toString(), notice(id))
            }
        }
        noticeRows(id) shouldBe emptyList()
        outboxRows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 a second record for a partition returns false and leaves one row of each`() {
        val id = partition()
        record(notice(id)) shouldBe true
        record(notice(id, days = 120, notifiedAt = at.plusSeconds(3600))) shouldBe false
        noticeRows(id).size shouldBe 1
        outboxRows().size shouldBe 1
    }

    @Test
    fun `INV-AC-26996 a second concurrent record waits for the first and writes nothing`() {
        val id = partition()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val second =
                runtime { _, dsl ->
                    recorder(dsl).record(tenant.toString(), notice(id)) shouldBe true
                    val running = executor.submit<Boolean> { record(notice(id, notifiedAt = at.plusSeconds(1))) }
                    // the first transaction is open: the second one must be waiting for it
                    runCatching { running.get(WAIT_SECONDS, TimeUnit.SECONDS) }.isFailure shouldBe true
                    running
                }
            second.get(DONE_SECONDS, TimeUnit.SECONDS) shouldBe false
        } finally {
            executor.shutdownNow()
        }
        noticeRows(id).size shouldBe 1
        outboxRows().size shouldBe 1
    }

    @Test
    fun `INV-AC-26996 another tenant records nothing for this tenant's partition`() {
        val id = partition()
        val other = UUID.randomUUID()
        shouldThrow<IllegalStateException> { record(notice(id), bound = other) }
        noticeRows(id) shouldBe emptyList()
        outboxRows() shouldBe emptyList()
        outboxRows(other) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 the advisory lock of the tenant and partition is taken before the row is looked for`() {
        val id = partition()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting =
                EvidenceDb.superuser().use { holder ->
                    holder.autoCommit = false
                    holder
                        .prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")
                        .use {
                            it.setString(1, "retention-notice:$tenant:$id")
                            it.executeQuery()
                        }
                    // an upper-case spelling of the tenant is the same tenant and the same lock
                    val running =
                        executor.submit<Boolean> {
                            runtime { _, dsl -> recorder(dsl).record(tenant.toString().uppercase(), notice(id)) }
                        }
                    runCatching { running.get(WAIT_SECONDS, TimeUnit.SECONDS) }.isFailure shouldBe true
                    holder.rollback()
                    running
                }
            waiting.get(DONE_SECONDS, TimeUnit.SECONDS) shouldBe true
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `INV-AC-26996 a failing outbox write through the use case leaves no notice row and no outbox row`() {
        val id = partition()
        holderRow("a@example.test", at)
        val failure = IllegalStateException("the outbox write failed before writing anything")
        val failing =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> = throw failure
            }
        // one tenant transaction that commits only when the run returns
        val thrown =
            shouldThrow<IllegalStateException> {
                runtime { _, dsl -> send(dsl, recorder(dsl, failing)).run(tenant.toString()) }
            }
        thrown shouldBeSameInstanceAs failure
        noticeRows(id) shouldBe emptyList()
        outboxRows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 a later outbox failure rolls back the notices and messages written earlier in the run`() {
        val ids = listOf(partition(period = "2019-Q1"), partition(period = "2019-Q2"))
        holderRow("a@example.test", at)
        var appends = 0
        shouldThrow<IllegalStateException> {
            runtime { _, dsl ->
                val real = writer(dsl)
                val failingSecond =
                    object : OutboxWriter {
                        override fun append(
                            events: List<OutboundEvent>,
                            context: EventContext,
                        ): List<Envelope> {
                            check(++appends == 1) { "the second outbox write failed" }
                            return real.append(events, context)
                        }
                    }
                send(dsl, recorder(dsl, failingSecond)).run(tenant.toString())
            }
        }
        appends shouldBe 2
        ids.flatMap(::noticeRows) shouldBe emptyList()
        outboxRows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 the row holds the acknowledging user and sole-operator fact of the source`() {
        val notifiedAt = at.plusSeconds(37)
        listOf(true to "2019-Q1", false to "2019-Q2").forEach { (soleOperator, period) ->
            val id = partition(period = period, entity = "entity-$period")
            val source = Source(acknowledgement = NoticeAcknowledgement("user-$period", soleOperator))
            record(notice(id, notifiedAt = notifiedAt), source = source) shouldBe true
            acknowledgementColumns(id) shouldBe "user-$period|$soleOperator"
            source.acknowledgements shouldBe listOf(Triple(tenant.toString(), "entity-$period", notifiedAt))
        }
    }

    @Test
    fun `INV-AC-26996 without an acknowledgement both columns are null`() {
        val id = partition()
        record(notice(id)) shouldBe true
        acknowledgementColumns(id) shouldBe "null|null"
    }

    @Test
    fun `INV-AC-26996 a recorded 60-day acknowledged value gives a 60-day row with that user ID`() {
        val id = partition()
        holderRow("a@example.test", at)
        // the pinned row is still `planned`, which resolves as its default (INV-REQ-1251): read it as available
        val catalogue =
            VariationPointCatalogue { PinnedPolicy.pinnedCatalogue().require(it).copy(status = VpStatus.AVAILABLE) }
        val policy = PrecedenceResolver(catalogue, DeploymentForm.SAAS, PinnedPolicy.storedShorter("60"))
        val source = PolicyNoticeWindowSource(policy, PinnedPolicy.acknowledgedBy("user-who-acknowledged"))
        val run =
            runtime { _, dsl ->
                val window = ResolvedNoticeWindow(policy, catalogue, clock)
                send(dsl, recorder(dsl, source = source), window).run(tenant.toString())
            }
        run shouldBe NoticeRun(listOf(id), emptyList())
        noticeRows(id) shouldBe listOf("$tenant|60|${at.epochSecond}|${60 * DAY}")
        acknowledgementColumns(id) shouldBe "user-who-acknowledged|false"
        outboxRows().size shouldBe 1
    }

    @Test
    fun `INV-AC-26996 the default option of the pinned catalogue records a 90-day notice with no acknowledger`() {
        val id = partition()
        holderRow("a@example.test", at)
        val policy = PinnedPolicy.pinnedResolver()
        val source = PolicyNoticeWindowSource(policy, PinnedPolicy.acknowledgedBy(null))
        val run =
            runtime { _, dsl ->
                val window = ResolvedNoticeWindow(policy, PinnedPolicy.pinnedCatalogue(), clock)
                send(dsl, recorder(dsl, source = source), window).run(tenant.toString())
            }
        run shouldBe NoticeRun(listOf(id), emptyList())
        noticeRows(id) shouldBe listOf("$tenant|90|${at.epochSecond}|${90 * DAY}")
        acknowledgementColumns(id) shouldBe "null|null"
    }

    @Test
    fun `INV-AC-26996 a window in force longer than the notice window is refused and nothing is written`() {
        val id = partition()
        // 20:00 on 31 March in UTC is 1 April on the business calendar, the date the source is asked for
        val late = Instant.parse("2033-03-31T20:00:00Z")
        val source = Source(days = 91)
        shouldThrow<IllegalStateException> { record(notice(id, days = 90, notifiedAt = late), source = source) }
        source.windows shouldBe listOf(tenant.toString() to LocalDate.of(2033, 4, 1))
        source.acknowledgements shouldBe emptyList()
        // caught inside the transaction, which then commits: the refusal itself wrote nothing
        runtime { _, dsl ->
            shouldThrow<IllegalStateException> {
                recorder(dsl, source = Source(days = 91)).record(tenant.toString(), notice(id, days = 90))
            }
        }
        noticeRows(id) shouldBe emptyList()
        outboxRows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 a window in force equal to or shorter than the notice window records`() {
        listOf(90 to "2019-Q1", 89 to "2019-Q2").forEach { (inForce, period) ->
            val id = partition(period = period)
            record(notice(id, days = 90), source = Source(days = inForce)) shouldBe true
            noticeRows(id).size shouldBe 1
        }
        outboxRows().size shouldBe 2
    }

    @Test
    fun `INV-AC-26996 the outbox row is filed under the entity of the partition`() {
        val id = partition(entity = "entity-of-this-unit")
        record(notice(id)) shouldBe true
        values(
            "select entity_ref || '|' || subject from ${EvidenceDb.SCHEMA}.outbox where tenant_id = ?",
            tenant,
        ) shouldBe listOf("entity-of-this-unit|$id")
    }

    @Test
    fun `INV-AC-26996 a partition filed under the tenant-wide entity is refused and leaves neither row`() {
        val id = partition()
        superuser(
            "update evidence.retention_partition set entity_ref = ? where partition_id = ?",
            EntityRef.TENANT_WIDE.value,
            id,
        ) { it.executeUpdate() }
        shouldThrow<EmissionNotPermitted> { record(notice(id)) }
        noticeRows(id) shouldBe emptyList()
        outboxRows() shouldBe emptyList()
    }

    private companion object {
        const val DAY = 86_400L
        const val WAIT_SECONDS = 2L
        const val DONE_SECONDS = 30L
    }
}
