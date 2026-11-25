package com.dynokore.gst.canonical.application.reprocessing

import com.dynokore.gst.canonical.application.aggregate.AggregateSupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.SupplierAnswer
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.application.document.CreateDocumentVersion
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.application.document.SourceLink
import com.dynokore.gst.canonical.application.document.VersionKey
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.WindowDocument
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Handler of `IdentityTripleReprocessingRequested` over fakes of its ports; the fake transaction rolls back whole. */
class IdentityTripleReprocessingTest {
    private val tenant = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val otherTenant = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    private val doc1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val doc2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val date = LocalDate.parse("2026-03-20")
    private val t1 = SupplierIdentity("S1", "P1", "G1")
    private val t2 = SupplierIdentity("S2", "P2", "G2")

    /** The fake database: versions by (tenant, doc), markers, staged events; snapshot and restore for rollback. */
    private class World {
        val versions = mutableMapOf<Pair<UUID, UUID>, MutableList<NewVersion>>()
        val entities = mutableMapOf<Pair<UUID, UUID>, String>()
        val markers = mutableSetOf<Pair<UUID, String>>()
        val staged = mutableListOf<CanonicalVersionCreatedFacts>()
        val readTenants = mutableListOf<UUID>()
        var conflict = false
        var depth = 0

        private fun copyOfVersions() = versions.mapValues { it.value.toMutableList() }

        fun <T> transaction(block: () -> T): T {
            val outer = depth == 0
            val saved = if (outer) Triple(copyOfVersions(), markers.toSet(), staged.toList()) else null
            depth++
            try {
                return block()
            } catch (e: RuntimeException) {
                if (saved != null) {
                    versions.clear()
                    versions.putAll(saved.first)
                    markers.clear()
                    markers.addAll(saved.second)
                    staged.clear()
                    staged.addAll(saved.third)
                }
                throw e
            } finally {
                depth--
            }
        }
    }

    private val world = World()
    private val logs = mutableListOf<String>()
    private var answer: SupplierAnswer = SupplierAnswer.Found(t2)
    private val resolveCalls = mutableListOf<LocalDate>()
    private var resolveRetryOn: LocalDate? = null

    private fun json(t: SupplierIdentity) = """{"a":1,"identityTriple":{"value":${tripleText(t)}},"z":[1,2]}"""

    private fun tripleText(t: SupplierIdentity) =
        """{"senderId":"${t.senderId}","supplierId":"${t.supplierId}","supplierGstn":"${t.supplierGstn}"}"""

    private fun version(canonical: String) = NewVersion(canonical, null, null, null, null, null, date, "someone")

    private fun seed(
        doc: UUID,
        entity: String = "E",
        canonical: String = json(t1),
        inTenant: UUID = tenant,
        transactionDate: LocalDate = date,
    ) {
        world.versions[inTenant to doc] = mutableListOf(version(canonical).copy(transactionDate = transactionDate))
        world.entities[inTenant to doc] = entity
    }

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = world.transaction(block)
        }

    private val store =
        object : DocumentStore {
            override fun insert(
                document: NewDocument,
                link: SourceLink,
            ) = error("not used")

            override fun createVersion(
                docUuid: UUID,
                expectedVersion: Int,
                version: NewVersion,
                link: SourceLink,
            ): UpdateOutcome {
                val list = world.versions[tenant to docUuid]
                if (world.conflict || list == null || list.size != expectedVersion) return UpdateOutcome.Conflict
                list += version
                return UpdateOutcome.Updated(list.size.toLong())
            }

            override fun rebuildDerived(
                docUuid: UUID,
                version: Int,
            ) = error("not used")

            override fun versionKeys(tenantId: UUID): List<VersionKey> = error("not used")

            override fun readWindow(
                tenantId: UUID,
                entityId: UUID,
                from: LocalDate,
                to: LocalDate,
                pageSize: Int,
                afterDocUuid: UUID?,
            ): List<WindowDocument> = error("not used")
        }

    private val handler =
        IdentityTripleReprocessing(
            runner,
            { t, e -> world.markers.add(t to e) },
            { t, d ->
                world.readTenants += t
                world.versions[t to d]?.let { list ->
                    CurrentDocumentVersion(
                        list.size,
                        list.last(),
                        CanonicalVersionCreatedFacts(
                            d,
                            world.entities.getValue(t to d),
                            list.size,
                            list.last().transactionDate,
                            "2026-Q1",
                            "IN_SCOPE",
                            emptyList(),
                            "trace",
                            list.last().canonical,
                            null,
                            RoutingFactsData(RoutingDirection.SALE, RoutingNetworkPath.NOT_ON_NETWORK),
                        ),
                    )
                }
            },
            object : IdentityTripleDocuments {
                private val pattern = Regex("""\{"senderId":"(\w*)","supplierId":"(\w*)","supplierGstn":"(\w*)"}""")

                override fun tripleOf(canonicalJson: String): SupplierIdentity? =
                    pattern.find(canonicalJson)?.destructured?.let { (a, b, c) -> SupplierIdentity(a, b, c) }

                override fun withTriple(
                    canonicalJson: String,
                    triple: SupplierIdentity,
                ): String = pattern.replace(canonicalJson) { tripleText(triple) }
            },
            AggregateSupplierIdentity { _, _, d ->
                resolveCalls += d
                if (d == resolveRetryOn) SupplierAnswer.Retry("masterdata down") else answer
            },
            CreateDocumentVersion(store, runner),
            object : CanonicalVersionCreatedPublisher {
                override fun stage(facts: CanonicalVersionCreatedFacts) {
                    world.staged += facts
                }
            },
            "svc-canonical",
            { logs += it },
        )

    private fun run(
        entity: String = "E",
        docs: List<UUID> = listOf(doc1),
        event: String = "ev-1",
    ) = handler.handle(tenant, entity, docs, event)

    @Test
    fun `INV-AC-26779 case 1 a differing triple stores version current plus 1 under the same Doc UUID`() {
        seed(doc1)
        run()
        val list = world.versions.getValue(tenant to doc1)
        list.size shouldBe 2
        list[1].canonical shouldBe json(t2)
        list[1].createdBy shouldBe "svc-canonical"
        world.staged.size shouldBe 1
        world.staged[0].docUuid shouldBe doc1
        world.staged[0].version shouldBe 2
        world.staged[0].canonicalDocument shouldBe json(t2)
    }

    @Test
    fun `INV-AC-26779 case 2 an unchanged triple stores and stages nothing`() {
        seed(doc1)
        answer = SupplierAnswer.Found(t1)
        run()
        world.versions.getValue(tenant to doc1).size shouldBe 1
        world.staged shouldBe emptyList()
        resolveCalls shouldBe listOf(date)
    }

    @Test
    fun `INV-AC-26779 case 3 a document of another entity than entityRef stores nothing`() {
        seed(doc1, entity = "E")
        run(entity = "OTHER")
        world.versions.getValue(tenant to doc1).size shouldBe 1
        world.staged shouldBe emptyList()
        resolveCalls shouldBe emptyList()
        logs.single().contains(doc1.toString()) shouldBe true
    }

    @Test
    fun `INV-AC-26779 case 4 a document without identityTriple stores nothing`() {
        seed(doc1, canonical = """{"a":1}""")
        run()
        world.versions.getValue(tenant to doc1).size shouldBe 1
        world.staged shouldBe emptyList()
        logs.single().contains(doc1.toString()) shouldBe true
    }

    @Test
    fun `INV-AC-26779 case 5 the same event id twice stores one version only`() {
        seed(doc1)
        run()
        run()
        world.versions.getValue(tenant to doc1).size shouldBe 2
        world.staged.size shouldBe 1
        world.markers shouldBe setOf(tenant to "ev-1")
        resolveCalls.size shouldBe 1
    }

    @Test
    fun `INV-AC-26779 case 6 SupplierAnswer Retry leaves no version and no marker, raises the retry outcome`() {
        seed(doc1)
        answer = SupplierAnswer.Retry("masterdata down")
        shouldThrow<IdentityTripleReprocessingRetry> { run() }
        world.versions.getValue(tenant to doc1).size shouldBe 1
        world.markers shouldBe emptySet()
        world.staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26779 case 7 UpdateOutcome Conflict leaves no version and no marker, raises the retry outcome`() {
        seed(doc1)
        world.conflict = true
        shouldThrow<IdentityTripleReprocessingRetry> { run() }
        world.versions.getValue(tenant to doc1).size shouldBe 1
        world.markers shouldBe emptySet()
        world.staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26779 case 8 a Doc UUID of another tenant is not read, the port gets the event tenant only`() {
        seed(doc1, inTenant = otherTenant)
        run()
        world.readTenants shouldBe listOf(tenant)
        world.versions.getValue(otherTenant to doc1).size shouldBe 1
        world.staged shouldBe emptyList()
        logs.single().contains(doc1.toString()) shouldBe true
    }

    @Test
    fun `INV-AC-26779 case 9 a page where the second Doc UUID fails with Retry leaves the first unchanged too`() {
        val later = LocalDate.parse("2026-04-01")
        seed(doc1)
        seed(doc2, transactionDate = later)
        resolveRetryOn = later
        shouldThrow<IdentityTripleReprocessingRetry> { run(docs = listOf(doc1, doc2)) }
        world.versions.getValue(tenant to doc1).size shouldBe 1
        world.versions.getValue(tenant to doc2).size shouldBe 1
        world.markers shouldBe emptySet()
        world.staged shouldBe emptyList()
        resolveCalls shouldBe listOf(date, later)
    }
}
