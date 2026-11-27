package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.RulePackEventsHandler
import com.dynokore.gst.evidence.adapters.persistence.JooqBreachTermsReplica
import com.dynokore.gst.evidence.adapters.persistence.jooq.Evidence
import com.dynokore.gst.evidence.domain.cases.BreachTerms
import com.dynokore.platform.configresolver.ConfigSchema
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import com.dynokore.platform.tenancyjooq.TableClasses
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldNotBeInstanceOf
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The platform-scoped declarations of evidence-bootstrap's application.yaml (`inv.tenancy`). */
private val PLATFORM_SCOPED = listOf("evidence.config_purge_tenant", "evidence.write_installation_setting")

/** The tables of the replica, declared REFERENCE here; INV-T-29321 declares them in the service's application.yaml. */
private val REFERENCE =
    listOf("type", "version", "activation").map { "evidence.breach_terms_pack_$it" }

/** The table classes as service-runtime builds them: the libraries' declarations and the service's, verified. */
private fun serviceTables(): TenancyTables {
    val present =
        Evidence.EVIDENCE.tables
            .map { it.qualifiedName.name.joinToString(".") }
            .toSet()
    val libraries =
        ConfigSchema.platformScopedTables(EvidenceDb.SCHEMA) +
            OutboxSchema.platformScopedTables(EvidenceDb.SCHEMA) +
            JobSchema.platformScopedTables(EvidenceDb.SCHEMA) +
            EventLogSchema.platformScopedTables()
    return TableClasses
        .of(platformScoped = (PLATFORM_SCOPED + libraries.filter { it in present }).distinct(), reference = REFERENCE)
        .verify(Evidence.EVIDENCE)
}

/**
 * The harness of INV-AC-29315 over the real PostgreSQL. Each event is applied as the inbox consumer applies it: one
 * transaction bound to the envelope tenant, committed on return and rolled back on a throw.
 */
open class BreachTermsReplicaHarness {
    protected val platform = TenantId.PLATFORM
    protected val tenant = TenantId(UUID.randomUUID())
    protected val day = LocalDate.parse("2026-03-01")

    @BeforeEach
    fun emptyReplica() {
        EvidenceDb.superuser().use { c ->
            c.createStatement().use {
                it.execute(
                    "truncate evidence.breach_terms_pack_activation, evidence.breach_terms_pack_version, " +
                        "evidence.breach_terms_pack_type",
                )
            }
        }
    }

    private val tenantContext = TenantContextImpl()
    private val runner =
        TransactionRunnerImpl(
            PGSimpleDataSource().apply {
                setUrl(EvidenceDb.jdbcUrl)
                user = EvidenceDb.RUNTIME
                password = EvidenceDb.RUNTIME_PASSWORD
            },
            tenantContext,
            null,
            object : BindingKeySource {
                override fun currentKey() = EvidenceDb.bindingKey.copyOf() // the runner clears the array it gets

                override fun previousKey(): ByteArray? = null
            },
        )

    /** The service's DSLContext: the tenancy-jooq listeners over the generated schema (CS-18, CS-19). */
    private val serviceDsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                serviceTables(),
                tenantContext,
                runner,
            ).dsl()

    protected fun <T> bound(
        who: TenantId,
        block: (DSLContext) -> T,
    ): T {
        val inTransaction = { runner.inTransaction { block(serviceDsl) } }
        return if (who.isPlatform) {
            tenantContext.bindPlatform(SignedBindings.ACTOR, inTransaction)
        } else {
            tenantContext.bind(who, SignedBindings.ACTOR, inTransaction)
        }
    }

    /** A signed runtime session without the framework predicate: the database layer alone. */
    protected fun <T> session(
        who: TenantId,
        block: (Connection) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, who.value)
            runCatching { block(c) }
                .onSuccess { c.commit() }
                .onFailure { c.rollback() }
                .getOrThrow()
        }

    protected fun deliver(event: Envelope): HandleOutcome =
        bound(event.tenant) { dsl -> RulePackEventsHandler(JooqBreachTermsReplica(dsl)).handle(event) }

    protected fun terms(
        on: LocalDate,
        who: TenantId = platform,
    ): BreachTerms? = bound(who) { dsl -> JooqBreachTermsReplica(dsl).activeTerms(on) }

    protected fun envelope(
        type: String,
        data: String,
        who: TenantId,
        at: Instant = PRODUCED,
        id: String = UUID.randomUUID().toString(),
    ) = Envelope(
        id = EventId(id),
        source = "/gst/rules-svc",
        type = type,
        subject = "pack",
        time = at,
        tenant = who,
        entity = if (who.isPlatform) EntityRef.PLATFORM else EntityRef("ENT-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "rules-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    protected fun contents(
        assessment: String = "72",
        notification: String = "48",
        source: String = "\"counsel-1\"",
        extra: String = "",
    ) = """{"assessmentWithinHours":$assessment,"notificationWithinHours":$notification,""" +
        """"sourceReference":$source$extra}"""

    protected fun staged(
        identifier: String = "pack-a",
        type: String = "breach-terms",
        version: String = "1",
        artefact: String = """{"contents":${contents()}}""",
        who: TenantId = platform,
    ) = envelope(
        RulePackEventsHandler.STAGED_TYPE,
        """{"packIdentifier":"$identifier","packType":"$type","version":"$version","artefact":$artefact}""",
        who,
    )

    protected fun stagedWith(
        contents: String,
        identifier: String = "pack-a",
        version: String = "1",
        at: Instant = PRODUCED,
        id: String = UUID.randomUUID().toString(),
    ) = staged(identifier = identifier, version = version, artefact = """{"contents":$contents}""")
        .copy(time = at, id = EventId(id))

    protected fun activated(
        identifier: String = "pack-a",
        version: String = "1",
        from: LocalDate = day,
        who: TenantId = platform,
        at: Instant = PRODUCED,
    ) = envelope(
        RulePackEventsHandler.ACTIVATED_TYPE,
        """{"packIdentifier":"$identifier","version":"$version","effectiveFrom":"$from"}""",
        who,
        at,
    )

    protected fun query(sql: String): List<String> =
        EvidenceDb.superuser().use { c ->
            c.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

    protected fun typeRows() =
        query(
            "select pack_identifier || '|' || pack_type from evidence.breach_terms_pack_type " +
                "order by pack_identifier collate \"C\"",
        )

    protected fun versionRows() =
        query(
            "select pack_identifier || '|' || pack_version || '|' || assessment_within_hours || '|' || " +
                "notification_within_hours || '|' || source_reference from evidence.breach_terms_pack_version " +
                "order by 1",
        )

    protected fun activationRows() =
        query(
            "select pack_identifier || '|' || pack_version || '|' || effective_from " +
                "from evidence.breach_terms_pack_activation order by applied_seq",
        )

    protected fun everything() = listOf(typeRows(), versionRows(), activationRows())

    protected val empty = listOf(emptyList<String>(), emptyList(), emptyList())

    protected fun refused(
        event: Envelope,
        reason: String,
    ): String {
        val message = shouldThrow<ContractViolation> { deliver(event) }.message.orEmpty()
        message shouldStartWith "$reason:"
        return message
    }

    protected val seventyTwo = BreachTerms(Duration.ofHours(72), Duration.ofHours(48))

    protected fun hours(assessment: Long) = BreachTerms(Duration.ofHours(assessment), Duration.ofHours(48))

    protected companion object {
        val PRODUCED: Instant = Instant.parse("2026-02-01T00:00:00Z")
    }
}

/** INV-AC-29315: which staged events and contents the replica stores or refuses. */
class BreachTermsReplicaDbTest : BreachTermsReplicaHarness() {
    @Test
    fun `INV-AC-29315 a staged event of any type without contents is refused and no table gets a row`() {
        for (type in listOf("breach-terms", "calendar")) {
            for (artefact in listOf("{}", """{"contents":null}""", """{"manifest":{}}""", "null", "[]")) {
                for (who in listOf(platform, tenant)) {
                    withClue("$type $artefact") {
                        refused(staged(type = type, artefact = artefact, who = who), "PACK_CONTENTS_MISSING")
                        everything() shouldBe empty
                    }
                }
            }
        }
        val noArtefact = """{"packIdentifier":"pack-a","packType":"breach-terms","version":"1"}"""
        refused(envelope(RulePackEventsHandler.STAGED_TYPE, noArtefact, platform), "PACK_CONTENTS_MISSING")
        everything() shouldBe empty
    }

    @Test
    fun `INV-AC-29315 missing contents is reported before every other refusal`() {
        deliver(staged(type = "calendar"))
        val before = everything()
        refused(staged(artefact = "{}"), "PACK_CONTENTS_MISSING")
        refused(staged(artefact = "{}", who = tenant), "PACK_CONTENTS_MISSING")
        everything() shouldBe before
    }

    @Test
    fun `INV-AC-29315 invalid breach terms are refused and no table gets a row, the type record included`() {
        val invalid =
            listOf(
                "{}",
                "\"text\"",
                "[]",
                "true",
                contents(assessment = "0"),
                contents(assessment = "-1"),
                contents(assessment = "1.5"),
                contents(assessment = "0.5"),
                contents(assessment = "24.0000000000000000001"),
                contents(assessment = "\"72\""),
                contents(assessment = "null"),
                contents(assessment = "[72]"),
                contents(assessment = "1e30"),
                contents(assessment = "1e1000000"),
                contents(assessment = "2562047788015216"),
                contents(notification = "0"),
                contents(notification = "\"48\""),
                contents(notification = "true"),
                contents(source = "\"\""),
                contents(source = "7"),
                contents(source = "null"),
                contents(source = "[\"a\"]"),
                contents(source = "\"a\\u0000b\""),
                """{"notificationWithinHours":48,"sourceReference":"s"}""",
                """{"assessmentWithinHours":72,"sourceReference":"s"}""",
                """{"assessmentWithinHours":72,"notificationWithinHours":48}""",
                """{"AssessmentWithinHours":72,"notificationWithinHours":48,"sourceReference":"s"}""",
            )
        for (bad in invalid) {
            withClue(bad) {
                refused(stagedWith(bad), "PACK_CONTENTS_INVALID")
                everything() shouldBe empty
            }
        }
    }

    @Test
    fun `INV-AC-29315 the smallest and a whole decimal figure are accepted and unknown members are ignored`() {
        deliver(stagedWith(contents(assessment = "1", notification = "24.0", extra = ""","other":{"a":1}""")))
        versionRows() shouldBe listOf("pack-a|1|1|24|counsel-1")
    }

    @Test
    fun `INV-AC-29315 an equal redelivery changes nothing`() {
        deliver(staged())
        deliver(activated())
        val before = everything()
        deliver(staged()) shouldBe HandleOutcome.IGNORED
        deliver(stagedWith(contents(extra = ""","unknown":1"""))) shouldBe HandleOutcome.IGNORED
        deliver(activated()) shouldBe HandleOutcome.IGNORED
        everything() shouldBe before
    }

    @Test
    fun `INV-AC-29315 other contents for a stored version are refused and the stored row stays`() {
        deliver(staged())
        val before = everything()
        for (other in listOf(
            contents(assessment = "71"),
            contents(notification = "47"),
            contents(source = "\"counsel-2\""),
        )) {
            withClue(other) {
                val message = refused(stagedWith(other), "PACK_VERSION_CONFLICT")
                message shouldNotContain "counsel"
                message shouldNotContain "pack-a"
                everything() shouldBe before
            }
        }
        deliver(stagedWith(contents(assessment = "71"), version = "2")) shouldBe HandleOutcome.APPLIED
    }

    @Test
    fun `INV-AC-29315 invalid contents for a stored version are reported before the version conflict`() {
        deliver(staged())
        val before = everything()
        refused(stagedWith(contents(assessment = "0")), "PACK_CONTENTS_INVALID")
        everything() shouldBe before
    }

    @Test
    fun `INV-AC-29315 only the wire value breach-terms stores a version`() {
        val lookAlikes = listOf("breach-terms-v2", "breach_terms", "BREACH_TERMS", "breach-terms ", "Breach-Terms")
        lookAlikes.forEachIndexed { index, type ->
            withClue(type) {
                deliver(staged(identifier = "look-$index", type = type)) shouldBe HandleOutcome.IGNORED
                versionRows().shouldBeEmpty()
                // its activation is of another type: ignored
                deliver(activated(identifier = "look-$index")) shouldBe HandleOutcome.IGNORED
                activationRows().shouldBeEmpty()
            }
        }
        typeRows() shouldBe lookAlikes.mapIndexed { index, type -> "look-$index|$type" }
    }

    @Test
    fun `INV-AC-29315 any other event type is refused and writes nothing`() {
        for (type in listOf(
            "com.dynokore.gst.rules.RulePackStaged.v1",
            "com.dynokore.gst.rules.RulePackActivated.v1",
            "com.dynokore.gst.platform.RulePackStaged.v2",
            "com.dynokore.gst.platform.RulePackStagedX.v1",
            "com.dynokore.gst.platform.PackCurrencyChanged.v1",
        )) {
            // an envelope takes the platform tenant for a platform event type only
            val who = if (Envelope.isPlatformType(type)) platform else tenant
            withClue(type) {
                val event = envelope(type, staged().dataJson, who)
                val violation = shouldThrow<ContractViolation> { deliver(event) }
                violation.message shouldBe "the event type is not a platform rule pack event this consumer handles"
                everything() shouldBe empty
            }
        }
    }

    @Test
    fun `INV-AC-29315 a payload without a needed member is refused and writes nothing`() {
        val artefact = """"artefact":{"contents":${contents()}}"""
        val payloads =
            listOf(
                "not json",
                """{"packType":"breach-terms","version":"1",$artefact}""",
                """{"packIdentifier":"p","version":"1",$artefact}""",
                """{"packIdentifier":"p","packType":"breach-terms",$artefact}""",
                """{"packIdentifier":"","packType":"breach-terms","version":"1",$artefact}""",
                """{"packIdentifier":"p","packType":["breach-terms"],"version":"1",$artefact}""",
            )
        for (data in payloads) {
            withClue(data) {
                shouldThrow<ContractViolation> {
                    deliver(envelope(RulePackEventsHandler.STAGED_TYPE, data, platform))
                }
                everything() shouldBe empty
            }
        }
        deliver(staged())
        val noDate = """{"packIdentifier":"pack-a","version":"1","effectiveFrom":"secret-value"}"""
        val violation =
            shouldThrow<ContractViolation> {
                deliver(envelope(RulePackEventsHandler.ACTIVATED_TYPE, noDate, platform))
            }
        violation.message.orEmpty() shouldNotContain "secret-value"
        activationRows().shouldBeEmpty()
    }
}

/** INV-AC-29315: the recorded pack type, the platform tenant, activations and the active terms. */
class BreachTermsTypeAndActivationDbTest : BreachTermsReplicaHarness() {
    @Test
    fun `INV-AC-29315 staged then activated gives the terms from the effective date and none before it`() {
        deliver(staged()) shouldBe HandleOutcome.APPLIED
        terms(day) shouldBe null
        deliver(activated()) shouldBe HandleOutcome.APPLIED
        typeRows() shouldBe listOf("pack-a|breach-terms")
        versionRows() shouldBe listOf("pack-a|1|72|48|counsel-1")
        terms(day) shouldBe seventyTwo
        terms(day.minusDays(1)) shouldBe null
    }

    @Test
    fun `INV-AC-29315 type conflict with breach-terms recorded first keeps type and version and activation applies`() {
        deliver(staged())
        val before = everything()
        for (event in listOf(staged(type = "calendar"), staged(type = "calendar", version = "2"))) {
            val message = refused(event, "PACK_TYPE_CONFLICT")
            message shouldNotContain "calendar"
            message shouldNotContain "pack-a"
            everything() shouldBe before
        }
        deliver(activated()) shouldBe HandleOutcome.APPLIED
        typeRows() shouldBe listOf("pack-a|breach-terms")
        terms(day) shouldBe seventyTwo
    }

    @Test
    fun `INV-AC-29315 type conflict with another type recorded first stores no version`() {
        deliver(staged(type = "calendar")) shouldBe HandleOutcome.IGNORED
        typeRows() shouldBe listOf("pack-a|calendar")
        val before = everything()
        refused(staged(), "PACK_TYPE_CONFLICT")
        everything() shouldBe before
        versionRows().shouldBeEmpty()
    }

    @Test
    fun `INV-AC-29315 the type conflict is reported before invalid contents and before the version conflict`() {
        deliver(staged(type = "calendar"))
        val before = everything()
        refused(stagedWith("{}"), "PACK_TYPE_CONFLICT")
        everything() shouldBe before
    }

    @Test
    fun `INV-AC-29315 a staging of another type from another tenant is a type conflict and writes nothing`() {
        deliver(staged())
        val before = everything()
        refused(staged(type = "calendar", who = tenant), "PACK_TYPE_CONFLICT")
        everything() shouldBe before
    }

    @Test
    fun `INV-AC-29315 breach terms from another tenant are refused as not platform before any other check`() {
        refused(staged(who = tenant), "PACK_NOT_PLATFORM")
        everything() shouldBe empty
        deliver(staged(type = "calendar"))
        val before = everything()
        // not platform comes before the type conflict and before the contents check
        refused(staged(who = tenant), "PACK_NOT_PLATFORM")
        refused(staged(artefact = """{"contents":{}}""", who = tenant), "PACK_NOT_PLATFORM")
        everything() shouldBe before
    }

    @Test
    fun `INV-AC-29315 another tenant's staging of another type is ignored and cannot block the operator pack`() {
        deliver(staged(type = "calendar", who = tenant)) shouldBe HandleOutcome.IGNORED
        everything() shouldBe empty
        deliver(staged()) shouldBe HandleOutcome.APPLIED
        typeRows() shouldBe listOf("pack-a|breach-terms")
        // an equal type from another tenant is not a conflict
        val before = everything()
        deliver(staged(type = "calendar", identifier = "pack-b", who = tenant)) shouldBe HandleOutcome.IGNORED
        deliver(staged(type = "calendar", identifier = "pack-b")) shouldBe HandleOutcome.IGNORED
        deliver(staged(type = "calendar", identifier = "pack-b", who = tenant)) shouldBe HandleOutcome.IGNORED
        typeRows() shouldBe listOf("pack-a|breach-terms", "pack-b|calendar")
        versionRows() shouldBe before[1]
    }

    @Test
    fun `INV-AC-29315 an activation from another tenant is ignored and writes nothing`() {
        deliver(staged())
        val before = everything()
        deliver(activated(who = tenant)) shouldBe HandleOutcome.IGNORED
        deliver(activated(identifier = "never-staged", who = tenant)) shouldBe HandleOutcome.IGNORED
        everything() shouldBe before
        terms(day) shouldBe null
    }

    @Test
    fun `INV-AC-29315 an identifier that differs by letter case or a suffix is not a conflict`() {
        deliver(staged(type = "calendar"))
        deliver(staged(identifier = "Pack-A")) shouldBe HandleOutcome.APPLIED
        deliver(staged(identifier = "pack-a-2")) shouldBe HandleOutcome.APPLIED
        typeRows() shouldBe listOf("Pack-A|breach-terms", "pack-a|calendar", "pack-a-2|breach-terms")
    }

    @Test
    fun `INV-AC-29315 an activation of an unknown pack or an unstaged version is retried, not dead-lettered`() {
        shouldThrowAny { deliver(activated()) }.shouldNotBeInstanceOf<ContractViolation>()
        everything() shouldBe empty
        deliver(staged())
        val before = everything()
        shouldThrow<IllegalStateException> { deliver(activated(version = "2")) }
        shouldThrow<IllegalStateException> { deliver(activated(identifier = "pack-b")) }
        everything() shouldBe before
        terms(day) shouldBe null
        // the retry succeeds once the version is staged
        deliver(stagedWith(contents(assessment = "10"), version = "2"))
        deliver(activated(version = "2")) shouldBe HandleOutcome.APPLIED
        terms(day) shouldBe BreachTerms(Duration.ofHours(10), Duration.ofHours(48))
    }

    @Test
    fun `INV-AC-29315 the terms on a date are of the latest effective activation and the later staged wins a tie`() {
        deliver(stagedWith(contents(assessment = "1"), version = "1", at = PRODUCED))
        deliver(stagedWith(contents(assessment = "2"), version = "2", at = PRODUCED.plusSeconds(1)))
        deliver(
            stagedWith(contents(assessment = "3"), identifier = "pack-b", version = "1", at = PRODUCED.plusSeconds(2)),
        )
        terms(day) shouldBe null
        // applied out of date order: the effective date decides, not the order of arrival
        deliver(activated(version = "2", from = day.plusDays(10)))
        deliver(activated(version = "1", from = day))
        terms(day.minusDays(1)) shouldBe null
        terms(day) shouldBe hours(1)
        terms(day.plusDays(9)) shouldBe hours(1)
        terms(day.plusDays(10)) shouldBe hours(2)
        // a tie on the effective date, across identifiers too: the later staged version wins
        deliver(activated(identifier = "pack-b", from = day))
        terms(day) shouldBe hours(3)
        // an activation that is already stored adds no row, also after another one on its date
        val before = everything()
        deliver(activated(version = "1", from = day)) shouldBe HandleOutcome.IGNORED
        everything() shouldBe before
        // the same version from another date adds a row
        deliver(activated(version = "1", from = day.plusDays(1))) shouldBe HandleOutcome.APPLIED
        terms(day) shouldBe hours(3)
        terms(day.plusDays(1)) shouldBe hours(1)
        terms(day.plusDays(10)) shouldBe hours(2)
    }

    @Test
    fun `INV-AC-29315 a tenant binding reads the active terms and cannot write the replica`() {
        deliver(staged())
        deliver(activated())
        terms(day, tenant) shouldBe seventyTwo
        bound(tenant) { JooqBreachTermsReplica(it).typeOf("pack-a") } shouldBe "breach-terms"
        val before = everything()
        shouldThrowAny {
            bound(tenant) { dsl -> JooqBreachTermsReplica(dsl).recordType("pack-z", "calendar") }
        }
        everything() shouldBe before
    }

    @Test
    fun `INV-AC-29315 a tenant cannot write a platform row and the runtime login cannot change or delete one`() {
        deliver(staged())
        deliver(activated())
        val before = everything()
        shouldThrowAny {
            session(tenant) { c ->
                c.createStatement().use {
                    it.execute(
                        "insert into evidence.breach_terms_pack_type values ('${platform.value}', 'p', 'calendar')",
                    )
                }
            }
        }
        for (table in listOf("type", "version", "activation")) {
            for (sql in listOf(
                "insert into evidence.breach_terms_pack_$table (tenant_id) " +
                    "select tenant_id from evidence.breach_terms_pack_$table",
                "update evidence.breach_terms_pack_$table set pack_identifier = 'x'",
                "delete from evidence.breach_terms_pack_$table",
            )) {
                withClue(sql) {
                    val refused =
                        shouldThrow<SQLException> {
                            session(platform) { c -> c.createStatement().use { it.execute(sql) } }
                        }
                    refused.sqlState shouldBe INSUFFICIENT_PRIVILEGE
                }
            }
        }
        everything() shouldBe before
    }

    @Test
    fun `INV-AC-29315 two concurrent first stagings cannot both set the type`() {
        val second =
            bound(platform) { dsl ->
                JooqBreachTermsReplica(dsl).recordType("pack-a", "breach-terms") shouldBe "breach-terms"
                CompletableFuture
                    .supplyAsync {
                        bound(platform) { JooqBreachTermsReplica(it).recordType("pack-a", "calendar") }
                    }.also { awaitLockWait("record_breach_terms_pack_type") }
            }
        second.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe "breach-terms"
        typeRows() shouldBe listOf("pack-a|breach-terms")
    }

    @Test
    fun `INV-AC-29315 two concurrent deliveries of one activation store one row`() {
        deliver(staged())
        val second =
            bound(platform) { dsl ->
                RulePackEventsHandler(JooqBreachTermsReplica(dsl)).handle(activated()) shouldBe HandleOutcome.APPLIED
                // the same activation under another event id, while the first delivery is not committed
                CompletableFuture
                    .supplyAsync { deliver(activated()) }
                    .also { awaitLockWait("record_breach_terms_activation", it::isDone) }
            }
        // the losing copy fails neither the delivery nor the retry: it just adds no row
        second.get(WAIT_SECONDS, TimeUnit.SECONDS)
        activationRows() shouldBe listOf("pack-a|1|$day")
        terms(day) shouldBe seventyTwo
    }

    /**
     * Returns once the second call of [function] waits on a lock: it is behind the uncommitted first one. [over] also
     * ends the wait: a second delivery that finished without waiting is judged by the assertions that follow.
     */
    private fun awaitLockWait(
        function: String,
        over: () -> Boolean = { false },
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        val waiting =
            "select count(*) from pg_stat_activity where datname = current_database() " +
                "and wait_event_type = 'Lock' and query ilike 'select%$function%'"
        while (!over() && query(waiting).single() == "0") {
            check(System.nanoTime() < deadline) { "the second call never waited on the first" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 30L
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}

/** INV-AC-29315: the same-date order is the registration order of the versions at the producer. */
class BreachTermsSameDateOrderDbTest : BreachTermsReplicaHarness() {
    private val earlier = PRODUCED
    private val later = PRODUCED.plusSeconds(60)

    /** The envelope time of the activation of V1 is later than that of V2: activation times decide nothing. */
    private fun activateV1() = activated(version = "1", at = PRODUCED.plusSeconds(900))

    private fun activateV2() = activated(version = "2", at = PRODUCED.plusSeconds(600))

    private fun stageV1(
        at: Instant = earlier,
        id: String = UUID.randomUUID().toString(),
    ) = stagedWith(contents(assessment = "1"), version = "1", at = at, id = id)

    private fun stageV2(
        at: Instant = later,
        id: String = UUID.randomUUID().toString(),
    ) = stagedWith(contents(assessment = "2"), version = "2", at = at, id = id)

    private fun stagings() =
        query(
            "select pack_version || '|' || extract(epoch from staged_at)::bigint || '|' || staged_event_id " +
                "from evidence.breach_terms_pack_version order by 1",
        )

    @Test
    fun `INV-AC-29315 an older activation applied late does not win, V2 staged first`() {
        deliver(stageV2())
        deliver(stageV1())
        deliver(activateV2()) shouldBe HandleOutcome.APPLIED
        deliver(activateV1()) shouldBe HandleOutcome.APPLIED
        terms(day) shouldBe hours(2)
    }

    @Test
    fun `INV-AC-29315 an older activation applied late does not win, V1 staged first`() {
        deliver(stageV1())
        deliver(stageV2())
        deliver(activateV2())
        deliver(activateV1())
        terms(day) shouldBe hours(2)
    }

    @Test
    fun `INV-AC-29315 the later staged version also wins when its activation is applied last`() {
        deliver(stageV1())
        deliver(stageV2())
        deliver(activateV1())
        deliver(activateV2())
        terms(day) shouldBe hours(2)
    }

    @Test
    fun `INV-AC-29315 on equal staged times the greater event id wins in both orders of application`() {
        // compared as text under the C collation: lower case is after upper case, and "9" is after "10"
        for ((smaller, greater) in listOf("event-a" to "event-b", "event-B" to "event-a", "event-10" to "event-9")) {
            for (greaterLast in listOf(true, false)) {
                withClue("$smaller $greater $greaterLast") {
                    emptyReplica()
                    deliver(stageV1(at = earlier, id = smaller))
                    deliver(stageV2(at = earlier, id = greater))
                    val order = listOf(activateV1(), activateV2())
                    (if (greaterLast) order else order.reversed()).forEach { deliver(it) }
                    terms(day) shouldBe hours(2)
                }
            }
        }
    }

    @Test
    fun `INV-AC-29315 a later effective date wins over a later staged time`() {
        deliver(stageV1(at = later))
        deliver(stageV2(at = earlier))
        deliver(activated(version = "2", from = day.plusDays(1)))
        deliver(activated(version = "1", from = day))
        terms(day.plusDays(1)) shouldBe hours(2)
        terms(day.plusDays(5)) shouldBe hours(2)
        terms(day) shouldBe hours(1)
    }

    @Test
    fun `INV-AC-29315 a later staging with equal contents keeps the first staged time and event id`() {
        deliver(stageV1(id = "first-1"))
        deliver(stageV2(id = "first-2"))
        val before = stagings()
        before shouldBe listOf("1|${earlier.epochSecond}|first-1", "2|${later.epochSecond}|first-2")
        deliver(stageV1(at = later.plusSeconds(60), id = "second-1")) shouldBe HandleOutcome.IGNORED
        stagings() shouldBe before
        deliver(activateV2())
        deliver(activateV1())
        terms(day) shouldBe hours(2)
    }

    @Test
    fun `INV-AC-29315 a redelivered activation adds no row`() {
        deliver(stageV1())
        deliver(stageV2())
        val first = activateV1()
        deliver(first)
        deliver(activateV2())
        val before = activationRows()
        before shouldBe listOf("pack-a|1|$day", "pack-a|2|$day")
        deliver(first) shouldBe HandleOutcome.IGNORED
        deliver(activateV1()) shouldBe HandleOutcome.IGNORED
        activationRows() shouldBe before
        terms(day) shouldBe hours(2)
    }
}

/** INV-AC-29315: what the database itself refuses, whatever the store does. */
class BreachTermsDatabaseDbTest : BreachTermsReplicaHarness() {
    private val functions =
        mapOf(
            "record_breach_terms_pack_type" to "'p', 'calendar'",
            "store_breach_terms_pack_version" to "'p', '1', 1, 1, 's', timestamptz '2026-02-01Z', 'e'",
            "record_breach_terms_activation" to "'pack-a', '1', date '2026-03-02'",
        )

    private fun sqlStateOf(block: () -> Unit): String? = shouldThrow<SQLException> { block() }.sqlState

    private fun asSuperuser(sql: String) {
        EvidenceDb.superuser().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun call(
        who: TenantId,
        function: String,
        arguments: String,
    ) = session(who) { c -> c.createStatement().use { it.execute("select evidence.$function($arguments)") } }

    @Test
    fun `INV-AC-29315 each write function is a definer function of evidence_sys that PUBLIC cannot execute`() {
        for (function in functions.keys) {
            withClue(function) {
                query(
                    "select pg_get_userbyid(proowner) || '|' || prosecdef || '|' || " +
                        "array_to_string(proconfig, ';') from pg_proc " +
                        "where pronamespace = 'evidence'::regnamespace and proname = '$function'",
                ) shouldBe listOf("evidence_sys|true|search_path=pg_catalog, evidence, pg_temp")
                // grantee 0 is PUBLIC; a null proacl would be the default, which grants PUBLIC
                query(
                    "select case when a.grantee = 0 then 'PUBLIC' else pg_get_userbyid(a.grantee) end " +
                        "from pg_proc p, aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a " +
                        "where p.pronamespace = 'evidence'::regnamespace and p.proname = '$function' " +
                        "and a.privilege_type = 'EXECUTE' order by 1",
                ) shouldBe listOf("evidence_runtime", "evidence_sys")
            }
        }
    }

    @Test
    fun `INV-AC-29315 each write function refuses a tenant binding with 42501 and writes nothing`() {
        deliver(staged())
        val before = everything()
        for ((function, arguments) in functions) {
            withClue(function) {
                sqlStateOf { call(tenant, function, arguments) } shouldBe "42501"
                everything() shouldBe before
            }
        }
    }

    @Test
    fun `INV-AC-29315 each write function refuses a null argument and writes the platform tenant itself`() {
        deliver(staged())
        val before = everything()
        for ((function, arguments) in functions) {
            val values = arguments.split(", ")
            values.indices.forEach { index ->
                val withNull = values.mapIndexed { i, value -> if (i == index) "null" else value }.joinToString()
                withClue("$function($withNull)") {
                    sqlStateOf { call(platform, function, withNull) } shouldBe "22004"
                    everything() shouldBe before
                }
            }
        }
        functions.forEach { (function, arguments) -> call(platform, function, arguments) }
        for (table in listOf("type", "version", "activation")) {
            query("select distinct tenant_id::text from evidence.breach_terms_pack_$table") shouldBe
                listOf(platform.value.toString())
        }
    }

    @Test
    fun `INV-AC-29315 the runtime login may select and may not write any of the three tables`() {
        for (table in listOf("type", "version", "activation")) {
            withClue(table) {
                val may =
                    listOf("select", "insert", "update", "delete", "truncate").filter { privilege ->
                        query(
                            "select has_table_privilege('evidence_runtime', " +
                                "'evidence.breach_terms_pack_$table', '$privilege')",
                        ).single() == "t"
                    }
                may shouldBe listOf("select")
                // no column grant either
                for (privilege in listOf("insert", "update")) {
                    query(
                        "select has_any_column_privilege('evidence_runtime', " +
                            "'evidence.breach_terms_pack_$table', '$privilege')",
                    ) shouldBe listOf("f")
                }
            }
        }
    }

    @Test
    fun `INV-AC-29315 a type row of another tenant is refused by the database`() {
        sqlStateOf {
            asSuperuser("insert into evidence.breach_terms_pack_type values ('${tenant.value}', 'p', 'calendar')")
        } shouldBe CHECK_VIOLATION
        everything() shouldBe empty
    }

    @Test
    fun `INV-AC-29315 a version row of another tenant is refused by the database`() {
        sqlStateOf {
            asSuperuser(
                "insert into evidence.breach_terms_pack_version " +
                    "values ('${tenant.value}', 'p', '1', 1, 1, 's', now(), 'e')",
            )
        } shouldBe CHECK_VIOLATION
        everything() shouldBe empty
    }

    @Test
    fun `INV-AC-29315 an activation row of another tenant is refused by the database`() {
        // the check answers before the foreign key, whose trigger runs after the row checks
        sqlStateOf { asSuperuser(activationOf(tenant, "p", "1")) } shouldBe CHECK_VIOLATION
        everything() shouldBe empty
    }

    @Test
    fun `INV-AC-29315 an activation of a version that is not stored is refused by the database`() {
        deliver(staged(identifier = "pack-b", version = "7"))
        val before = everything()
        for ((identifier, version) in listOf("pack-a" to "7", "pack-b" to "8", "Pack-B" to "7")) {
            withClue("$identifier $version") {
                sqlStateOf { asSuperuser(activationOf(platform, identifier, version)) } shouldBe FOREIGN_KEY_VIOLATION
                everything() shouldBe before
            }
        }
        asSuperuser(activationOf(platform, "pack-b", "7"))
    }

    private fun activationOf(
        who: TenantId,
        identifier: String,
        version: String,
    ) = "insert into evidence.breach_terms_pack_activation (tenant_id, pack_identifier, pack_version, " +
        "effective_from) values ('${who.value}', '$identifier', '$version', '$day')"

    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val FOREIGN_KEY_VIOLATION = "23503"
    }
}
