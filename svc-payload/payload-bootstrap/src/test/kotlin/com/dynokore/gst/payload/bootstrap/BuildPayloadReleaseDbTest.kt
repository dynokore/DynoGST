package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.build.ActivePackRead
import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqCanonicalInputStore
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-26579 against a real database: the full TPL-18 context, the claimed build job and the outbox rows read as
 * the database owner. Only the family and the active pack are test beans.
 */
class BuildPayloadReleaseDbTest {
    private val mapper = ObjectMapper()
    private val readyType = "com.dynokore.gst.payload.PayloadReady.v1"
    private val rejectedType = "com.dynokore.gst.payload.PayloadRejected.v1"
    private val transactionDate = LocalDate.of(2026, 3, 20)

    /** Claims only the items of one document; a job another test left queued is completed unrun. */
    private class OneDocQueue(
        private val delegate: WorkQueue,
        private val scope: TenantScope,
        private val doc: UUID,
    ) : WorkQueue by delegate {
        override fun claimNext(): ClaimedItem? {
            while (true) {
                val item = delegate.claimNext() ?: return null
                if (item.partitionKey == doc.toString()) return item
                scope.bindWorkload(item.tenant) { scope.transactions.inTransaction { delegate.complete(item) } }
            }
        }
    }

    /** Seeds one version, runs its build job once and gives the stored PayloadReady and PayloadRejected rows. */
    private fun build(ctx: ApplicationContext): Pair<List<JsonNode>, List<JsonNode>> {
        val tenant = TenantId(UUID.randomUUID())
        val doc = UUID.randomUUID()
        val scope = ctx.getBean(TenantScope::class.java)
        scope.bind(tenant, "svc:payload-svc") {
            scope.transactions.inTransaction {
                ctx.getBean(JooqVersionFactsStore::class.java).record(doc, 1, VersionFacts("E-001", null))
                ctx
                    .getBean(JooqCanonicalInputStore::class.java)
                    .store(doc, 1, DOCUMENT, "SALE", true, transactionDate)
                ctx.getBean(JooqTypeDecisionStore::class.java).store(doc, 1, "TYPE_2", null)
                ctx.getBean(JooqBuildJobs::class.java).enqueueOnce(doc, 1)
            }
        }
        val queue = OneDocQueue(ctx.getBean(WorkQueue::class.java), scope, doc)
        BuildPayloadJobHandler(queue, scope, ctx.getBean(BuildPayload::class.java)).runOnce() shouldBe true
        return events(tenant.value, doc, readyType) to events(tenant.value, doc, rejectedType)
    }

    private fun events(
        tenant: UUID,
        doc: UUID,
        type: String,
    ): List<JsonNode> =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select data::text from payload.outbox where tenant_id = ? and doc_uuid = ? and type = ?",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.setObject(2, doc)
                    p.setString(3, type)
                    p.executeQuery().use { r ->
                        buildList {
                            while (r.next()) add(mapper.readTree(r.getString(1)))
                        }
                    }
                }
        }

    private fun context(
        dir: Path,
        calendar: Boolean,
    ) = (if (calendar) PayloadContext.online(dir) else PayloadContext.online(dir, calendar = null))
        .withAllowBeanDefinitionOverriding(true)
        .withBean(
            "specificationFamilySource",
            SpecificationFamilySource::class.java,
            { SpecificationFamilySource { _ -> "PINT_SG" } },
        ).withBean(
            ActivePackOffNetworkPrefix::class.java,
            { ActivePackOffNetworkPrefix { ActivePackRead("DB-TEST", MdePackOffNetworkPrefix("{}"), "{}") } },
        )

    @Test
    fun `INV-AC-26579 the stored PayloadReady names the chosen release in its regeneration key`(
        @TempDir dir: Path,
    ) {
        context(dir, calendar = true).run { ctx ->
            val (ready, rejected) = build(ctx)
            val key = ready.single()["regenerationKey"]
            key["specificationVersion"].asString() shouldBe "SYNTH-A"
            key["canonicalVersion"].asInt() shouldBe 1
            key["packVersions"].single()["packRef"].asString() shouldBe "MDE"
            key["packVersions"].single()["version"].asString() shouldBe "DB-TEST"
            ready.single()["specificationVersion"].asString() shouldBe "PINT_SG"
            rejected.size shouldBe 0
        }
    }

    @Test
    fun `INV-AC-26579 without a calendar the stored row is PayloadRejected with PAY-020 and NO_CALENDAR`(
        @TempDir dir: Path,
    ) {
        context(dir, calendar = false).run { ctx ->
            val before = LocalDate.now(ZoneOffset.UTC)
            val (ready, rejected) = build(ctx)
            val after = LocalDate.now(ZoneOffset.UTC)
            val row = rejected.single()
            row["code"].asString() shouldBe "PAY-020"
            row["validationTrace"]["reason"].asString() shouldBe "NO_CALENDAR"
            // the date of the service clock in the configured zone (UTC), not the transaction date
            val submitted = LocalDate.parse(row["validationTrace"]["submissionDate"].asString())
            (submitted in before..after) shouldBe true
            ready.size shouldBe 0
        }
    }

    private companion object {
        val DOCUMENT =
            """
            {"documentTypeCode":{"value":"380","classification":"NONE"},
             "parties":{"value":{
               "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A"},
               "buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A"}},"classification":"IDENTIFYING"},
             "documentNumber":{"value":"INV-2026-000123","classification":"NONE"},
             "documentDate":{"value":"2026-03-20","classification":"NONE"},
             "identityTriple":{"value":{"senderId":"0195:201912345A","supplierId":"201912345A",
               "supplierGstn":"M2-1234567-8"},"classification":"IDENTIFYING"}}
            """.trimIndent()
    }
}
