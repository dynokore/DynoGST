package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqReconciliationWorkPapers
import com.dynokore.gst.evidence.adapters.persistence.PackageSizeRefusal
import com.dynokore.gst.evidence.adapters.persistence.WorkPaperIntegrityRefusal
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectKeyPrefix
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * INV-AC-28218: the scoped work-paper read over PostgreSQL (forced row-level security) and a recording fake store.
 * T1 holds E1/P1 (two papers), E1/P2 (one large), E2/P1 (one); T2 holds E1/P1 (one).
 */
class ReconciliationWorkPapersScopedDbTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val a1 = "reconciliation/$t1/A1/v1"
    private val a2 = "reconciliation/$t1/A2/v1"
    private val big = "reconciliation/$t1/B1/v1"
    private val other = "reconciliation/$t1/C1/v1"
    private val foreign = "reconciliation/$t2/D1/v1"

    private class Fake : ObjectStore {
        val objects = linkedMapOf<String, ByteArray>()
        val reads = mutableListOf<String>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            objects[key.value] = bytes
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray {
            reads += key.value
            return objects[key.value] ?: error("no such object")
        }

        override fun bytesUnder(prefix: ObjectKeyPrefix): Long =
            objects.filterKeys { it.startsWith(prefix.value) }.values.sumOf { it.size.toLong() }
    }

    private fun paper(
        period: String,
        padding: Int = 0,
    ) = """{"header":{"periodRef":"$period"},"pad":"${"x".repeat(padding)}"}""".toByteArray()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).let { HexFormat.of().formatHex(it) }

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES)).also { c.commit() }
        }

    @Suppress("LongParameterList")
    private fun row(
        tenant: UUID,
        entity: String,
        period: String,
        reference: String,
        bytes: ByteArray,
        store: Fake,
    ) {
        store.objects[reference] = bytes
        tx(tenant) { dsl ->
            dsl.execute(
                "insert into evidence.evidence_artefact (id, tenant_id, entity_ref, artefact_type, reference, " +
                    "hash, event_id, recorded_at, period_ref) " +
                    "values (?, ?, ?, 'RECONCILIATION_WORK_PAPER', ?, ?, ?, now(), ?)",
                UUID.randomUUID(),
                tenant,
                entity,
                reference,
                sha(bytes),
                UUID.randomUUID().toString(),
                period,
            )
        }
    }

    private val bytesA1 = paper("P1")
    private val bytesA2 = paper("P1", 5)

    private fun fixture(): Fake {
        val store = Fake()
        row(t1, "E1", "P1", a1, bytesA1, store)
        row(t1, "E1", "P1", a2, bytesA2, store)
        row(t1, "E1", "P2", big, paper("P2", 5_000), store)
        row(t1, "E2", "P1", other, paper("P1"), store)
        row(t2, "E1", "P1", foreign, paper("P1"), store)
        return store
    }

    private fun read(
        store: Fake,
        tenant: UUID = t1,
        asked: UUID = tenant,
        maxBytes: Long = 1_000_000,
    ) = tx(tenant) { dsl ->
        JooqReconciliationWorkPapers(dsl, store, maxBytes).candidates(TenantId(asked), "E1", "P1")
    }

    @Test
    fun `INV-AC-28218 the item names equal those of one entity and period of one tenant`() {
        val c = read(fixture())
        c.map { it.item.name } shouldBe listOf("A1-v1.json", "A2-v1.json")
        c.all { it.entity.value == "E1" && it.period.value == "P1" && it.tenant == TenantId(t1) } shouldBe true
    }

    @Test
    fun `INV-AC-28218 the store is asked for exactly the references of the matching rows`() {
        val store = fixture()
        read(store)
        store.reads.sorted() shouldBe listOf(a1, a2)
    }

    @Test
    fun `INV-AC-28218 a total above maxBytes refuses and a large tenant still returns the small period`() {
        val store = fixture()
        val total = (bytesA1.size + bytesA2.size).toLong()
        shouldThrow<PackageSizeRefusal> { read(store, maxBytes = total - 1) }
        read(store, maxBytes = total).size shouldBe 2
    }

    @Test
    fun `INV-AC-28218 altered bytes raise the integrity refusal`() {
        val store = fixture()
        store.objects[a1] = bytesA1.copyOf().also { it[0] = (it[0] + 1).toByte() }
        shouldThrow<WorkPaperIntegrityRefusal> { read(store) }.reference shouldBe a1
    }

    @Test
    fun `INV-AC-28218 a read under another bound tenant returns nothing of the first tenant`() {
        val store = fixture()
        read(store, tenant = t2, asked = t1) shouldBe emptyList()
        store.reads shouldBe emptyList()
    }
}
