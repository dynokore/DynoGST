package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqExposureWindowStore
import com.dynokore.gst.canonical.application.exposure.ExposedVersion
import com.dynokore.gst.canonical.application.exposure.ExposurePosition
import com.dynokore.gst.canonical.application.exposure.ExposureWindow
import com.dynokore.gst.canonical.application.exposure.ReadExposureWindow
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-28717: the two reads of the exposure window over the schema of svc-canonical, as the
 * `canonical_runtime` login bound to a tenant. Every test uses its own tenants and principal.
 */
class ExposureWindowDbTest {
    private val tenants = TenantContextImpl()
    private val transaction =
        TransactionRunnerImpl(
            PGSimpleDataSource().apply {
                setUrl(CanonicalDb.jdbcUrl)
                user = CanonicalDb.RUNTIME
                password = CanonicalDb.RUNTIME_PASSWORD
            },
            tenants,
            null,
            object : BindingKeySource {
                override fun currentKey(): ByteArray = CanonicalDb.bindingKey.copyOf()

                override fun previousKey(): ByteArray? = null
            },
        )
    private val dsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(
                    listOf("document", "document_version", "source_event").associate {
                        DSL.name("canonical", it) to TableClass.TENANT
                    },
                ),
                tenants,
                transaction,
            ).dsl()
    private val useCase = ReadExposureWindow(JooqExposureWindowStore(dsl, tenants), transaction)

    private val tenant = UUID.randomUUID()
    private val otherTenant = UUID.randomUUID()
    private val principal = "principal-" + UUID.randomUUID() + "-1"
    private val from = Instant.parse("2026-05-01T00:00:00Z")
    private val to = Instant.parse("2026-05-02T00:00:00Z")
    private val inside = Instant.parse("2026-05-01T12:00:00Z")

    /** The key of a seeded `source_event` row. */
    private data class Event(
        val system: String = "erp-a",
        val key: String = UUID.randomUUID().toString(),
        val revision: Long = 1,
    )

    /** Commits the `source_event` row of [event], bound to [doc], with [result] as its stored response body. */
    private fun seedEvent(
        event: Event,
        doc: UUID,
        owner: UUID = tenant,
        result: String = "{\"docUuid\":\"$doc\",\"version\":1}",
    ): Event {
        DocumentSql.execute(
            "insert into canonical.source_event (tenant_id, source_system, source_key, source_revision, doc_uuid, " +
                "result) values ('$owner', '${event.system}', '${event.key}', ${event.revision}, '$doc', " +
                "'$result'::jsonb)",
        )
        return event
    }

    private fun seedDocument(
        entityRef: String?,
        owner: UUID = tenant,
        doc: UUID = UUID.randomUUID(),
    ): Pair<UUID, UUID> {
        val entity = UUID.randomUUID()
        val ref = entityRef?.let { "'$it'" } ?: "null"
        DocumentSql.execute(
            "insert into canonical.document (doc_uuid, tenant_id, entity_id, entity_ref, direction, " +
                "document_type_code, document_number, issuer_party_key, fiscal_year, partition_date, " +
                "transaction_date, current_version, state) values ('$doc', '$owner', '$entity', $ref, 'SALE', 380, " +
                "'N-$doc', 'i', 2026, '2026-03-15', '2026-03-15', 1, 'READY')",
        )
        return doc to entity
    }

    /** One version linked to [event], written by the superuser so that `created_at` is chosen. */
    @Suppress("LongParameterList") // a row builder: each test names only the column it varies
    private fun seedVersion(
        document: Pair<UUID, UUID>,
        event: Event,
        version: Int = 1,
        at: Instant = inside,
        by: String = principal,
        owner: UUID = tenant,
    ) {
        val (doc, entity) = document
        DocumentSql.execute(
            "insert into canonical.document_version (doc_uuid, version, tenant_id, entity_id, partition_date, " +
                "canonical, scope_verdict, scope_pack_version, mapping_pack_version, specification_version, " +
                "created_at, created_by, source_system, source_key, source_revision) values ('$doc', $version, " +
                "'$owner', '$entity', '2026-03-15', '{}'::jsonb, 'IN_SCOPE', 's', 'm', 'PINT_SG', '$at', '$by', " +
                "'${event.system}', '${event.key}', ${event.revision})",
        )
    }

    /** One document with one version and its own linked event; the answer is the item a page lists for it. */
    @Suppress("LongParameterList") // a row builder: each test names only the column it varies
    private fun seed(
        entityRef: String?,
        at: Instant = inside,
        by: String = principal,
        owner: UUID = tenant,
        system: String = "erp-a",
        doc: UUID = UUID.randomUUID(),
    ): ExposedVersion {
        val document = seedDocument(entityRef, owner, doc)
        val event = seedEvent(Event(system), document.first, owner)
        seedVersion(document, event, at = at, by = by, owner = owner)
        return item(entityRef.orEmpty(), event, document.first, at = at)
    }

    private fun item(
        entityRef: String,
        event: Event,
        doc: UUID,
        version: Int = 1,
        at: Instant = inside,
    ) = ExposedVersion(entityRef, event.system, event.key, event.revision, doc, version, at)

    private fun page(
        permitted: Set<String>,
        limit: Int = ALL,
        after: ExposurePosition? = null,
        bound: UUID = tenant,
    ) = tenants.bind(TenantId(bound), SignedBindings.ACTOR) {
        useCase.page(ExposureWindow(principal, from, to), permitted, limit, after)
    }

    /** All pages of [limit] items, each continued after the last item of the one before. */
    private fun pages(
        permitted: Set<String>,
        limit: Int,
    ): List<List<ExposedVersion>> =
        generateSequence(page(permitted, limit).ifEmpty { null }) { last ->
            page(permitted, limit, last.last().position).ifEmpty { null }
        }.take(ALL).toList()

    /**
     * Five rows given in listing order, with each of the four order keys deciding one neighbouring pair, seeded in
     * another order.
     */
    private fun seedFiveInOrder(entityRefs: List<String> = List(FIVE) { "entity-a" }): List<ExposedVersion> {
        val (low, high) = listOf(UUID.randomUUID(), UUID.randomUUID()).sortedBy { it.toString() }
        val fifth = seed(entityRefs[4], at = from, system = "erp-b")
        val first = seed(entityRefs[0], at = from)
        val document = seedDocument(entityRefs[2], doc = low)
        val later = seedEvent(Event(), low)
        seedVersion(document, later, version = 2)
        seedVersion(document, later, version = 1)
        val fourth = seed(entityRefs[3], doc = high)
        val second = item(entityRefs[2], later, low)
        val third = item(entityRefs[2], later, low, version = 2)
        return listOf(first, second, third, fourth, fifth)
    }

    private fun entityRefs(
        by: String = principal,
        bound: UUID = tenant,
    ) = tenants.bind(TenantId(bound), SignedBindings.ACTOR) {
        useCase.entityRefs(ExposureWindow(by, from, to))
    }

    @Test
    fun `INV-AC-28717 E1 the answer is the distinct entityRefs of the principal, look-alikes excluded`() {
        seed("entity-a")
        seed("entity-a")
        seed("entity-b")
        seed("entity-longer", by = principal + "0")
        seed("entity-shorter", by = principal.dropLast(1))
        seed("entity-case", by = principal.uppercase())
        seed("entity-system", by = "system:aggregate-ready")
        entityRefs() shouldBe setOf("entity-a", "entity-b")
    }

    @Test
    fun `INV-AC-28717 E2 recordedFrom is included and recordedTo is excluded`() {
        seed("before", at = from.minusNanos(MICROSECOND))
        seed("at-from", at = from)
        seed("last", at = to.minusNanos(MICROSECOND))
        seed("at-to", at = to)
        entityRefs() shouldBe setOf("at-from", "last")
    }

    @Test
    fun `INV-AC-28717 E3 the rows of another tenant never appear`() {
        seed("mine")
        seed("theirs", owner = otherTenant)
        entityRefs() shouldBe setOf("mine")
        entityRefs(bound = otherTenant) shouldBe setOf("theirs")
    }

    @Test
    fun `INV-AC-28717 E4 one matching row without an entityRef fails the whole read`() {
        seed("entity-a")
        seed(null)
        shouldThrow<IllegalStateException> { entityRefs() }
    }

    @Test
    fun `INV-AC-28717 E5 a row without an entityRef that does not match does not fail the read`() {
        seed("entity-a")
        seed(null, at = from.minusNanos(MICROSECOND))
        seed(null, at = to)
        seed(null, by = principal + "0")
        seed(null, owner = otherTenant)
        entityRefs() shouldBe setOf("entity-a")
    }

    @Test
    fun `INV-AC-28717 E6 a window with no matching version answers no entityRef`() {
        seed("entity-a", by = principal + "0")
        entityRefs() shouldBe emptySet()
    }

    @Test
    fun `INV-AC-28717 E7 sub-microsecond bounds are compared exactly by both reads`() {
        val atT = seed("entity-a", at = inside)
        val next = seed("entity-b", at = inside.plusNanos(MICROSECOND))
        val permitted = setOf("entity-a", "entity-b")
        // Below, at and above the half microsecond, where rounding to the nearest one changes its direction.
        listOf(1L, 400L, 500L, 600L, 999L).forEach { nanos ->
            val bound = inside.plusNanos(nanos)
            val upTo = ExposureWindow(principal, from, bound)
            val since = ExposureWindow(principal, bound, to)
            tenants.bind(TenantId(tenant), SignedBindings.ACTOR) {
                useCase.entityRefs(upTo) shouldBe setOf("entity-a")
                useCase.page(upTo, permitted, ALL) shouldBe listOf(atT)
                useCase.entityRefs(since) shouldBe setOf("entity-b")
                useCase.page(since, permitted, ALL) shouldBe listOf(next)
            }
        }
    }

    @Test
    fun `INV-AC-28717 P1 of two revisions naming one version in their results only the linked one is listed, once`() {
        val document = seedDocument("entity-a")
        val first = seedEvent(Event(key = "invoice-${document.first}"), document.first)
        val later = seedEvent(first.copy(revision = 2), document.first)
        seedVersion(document, later)
        page(setOf("entity-a")) shouldBe listOf(item("entity-a", later, document.first))
    }

    @Test
    fun `INV-AC-28717 P2 two versions that link one event are both listed with its fields`() {
        val document = seedDocument("entity-a")
        val event = seedEvent(Event("erp-b", revision = 7), document.first)
        seedVersion(document, event, version = 1)
        seedVersion(document, event, version = 2)
        page(setOf("entity-a")) shouldBe
            listOf(
                item("entity-a", event, document.first, version = 1),
                item("entity-a", event, document.first, version = 2),
            )
    }

    @Test
    fun `INV-AC-28717 P3 a version whose linked event has a result without a version member is listed`() {
        val document = seedDocument("entity-a")
        val event = seedEvent(Event(), document.first, result = "{\"docUuid\":\"${document.first}\"}")
        seedVersion(document, event)
        page(setOf("entity-a")) shouldBe listOf(item("entity-a", event, document.first))
    }

    @Test
    fun `INV-AC-28717 P4 the page excludes a look-alike principal and another recorder`() {
        val mine = seed("entity-a")
        seed("entity-a", by = principal + "0")
        seed("entity-a", by = principal.dropLast(1))
        seed("entity-a", by = principal.uppercase())
        seed("entity-a", by = "system:aggregate-ready")
        page(setOf("entity-a")) shouldBe listOf(mine)
    }

    @Test
    fun `INV-AC-28717 P5 the page includes a version at recordedFrom and excludes one at recordedTo`() {
        seed("entity-a", at = from.minusNanos(MICROSECOND))
        val atFrom = seed("entity-a", at = from)
        val last = seed("entity-a", at = to.minusNanos(MICROSECOND))
        seed("entity-a", at = to)
        page(setOf("entity-a")) shouldBe listOf(atFrom, last)
    }

    @Test
    fun `INV-AC-28717 P6 the page never lists the rows of another tenant`() {
        val mine = seed("entity-a")
        val theirs = seed("entity-a", owner = otherTenant)
        page(setOf("entity-a")) shouldBe listOf(mine)
        page(setOf("entity-a"), bound = otherTenant) shouldBe listOf(theirs)
    }

    @Test
    fun `INV-AC-28717 P7 the order is sourceSystem, recordedAt, docUuid, version and paging keeps every item once`() {
        val rows = seedFiveInOrder()
        val unpaged = page(setOf("entity-a"))
        unpaged shouldBe rows
        val firstTwo = pages(setOf("entity-a"), 2).take(2)
        firstTwo shouldBe listOf(rows.subList(0, 2), rows.subList(2, 4))
        pages(setOf("entity-a"), 2).flatten() shouldBe unpaged
        pages(setOf("entity-a"), 1).flatten() shouldBe unpaged
    }

    @Test
    fun `INV-AC-28717 P8 the entity filter applies before the page limit`() {
        val rows =
            (0 until FIVE * 2).map {
                seed(if (it % 2 == 0) "entity-denied" else "entity-a", at = from.plusSeconds(it.toLong()))
            }
        val permitted = rows.filter { it.entityRef == "entity-a" }
        page(setOf("entity-a"), 2) shouldBe permitted.take(2)
        pages(setOf("entity-a"), 2).flatten() shouldBe permitted
    }

    @Test
    fun `INV-AC-28717 P9 every permitted entity is listed and a look-alike entityRef is not`() {
        val first = seed("entity-a", at = from)
        val second = seed("entity-b", at = from.plusSeconds(1))
        seed("entity-a0")
        seed("Entity-A")
        seed("entity-")
        page(setOf("entity-a", "entity-b")) shouldBe listOf(first, second)
        page(setOf("entity-%", "entity-_", "entity-a,entity-b")) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28717 P10 an empty permitted set returns nothing and runs no statement`() {
        seed("entity-a")
        page(emptySet()) shouldBe emptyList()
        // A context without a connection fails on any statement it is asked to run.
        JooqExposureWindowStore(DSL.using(SQLDialect.POSTGRES), tenants)
            .page(ExposureWindow(principal, from, to), emptySet(), ALL, null) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28717 N1 one matching row without an entityRef makes both reads fail`() {
        val first = seed("entity-a", at = from)
        seed("entity-a", at = from.plusSeconds(1))
        seed(null, system = "erp-z")
        shouldThrow<IllegalStateException> { entityRefs() }
        shouldThrow<IllegalStateException> { page(setOf("entity-a")) }
        shouldThrow<IllegalStateException> { page(setOf("entity-a"), 1) }
        shouldThrow<IllegalStateException> { page(setOf("entity-other")) }
        shouldThrow<IllegalStateException> { page(setOf("entity-a"), 1, first.position) }
    }

    @Test
    fun `INV-AC-28717 N2 a row without an entityRef before the cursor still fails the page`() {
        seed(null, at = from)
        val later = seed("entity-a", at = from.plusSeconds(1))
        shouldThrow<IllegalStateException> { page(setOf("entity-a"), 1, later.position) }
    }

    @Test
    fun `INV-AC-28717 N3 rows without an entityRef outside the window fail neither read`() {
        val mine = seed("entity-a")
        seed(null, at = from.minusNanos(MICROSECOND))
        seed(null, at = to)
        seed(null, by = principal + "0")
        seed(null, owner = otherTenant)
        entityRefs() shouldBe setOf("entity-a")
        page(setOf("entity-a")) shouldBe listOf(mine)
    }

    @Test
    fun `INV-AC-28717 L1 a limit below 1 is refused, since it would read no missing entityRef`() {
        seed(null)
        shouldThrow<IllegalArgumentException> { page(setOf("entity-a"), 0) }
    }

    private companion object {
        const val MICROSECOND = 1_000L // the resolution of timestamptz
        const val ALL = 100
        const val FIVE = 5
    }
}
