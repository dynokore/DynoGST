package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.rules.adapters.persistence.JooqPackArmingStore
import com.dynokore.gst.rules.adapters.web.DeltaAcknowledgementReader
import com.dynokore.gst.rules.adapters.web.StoredPackDeltaAcknowledgements
import com.dynokore.gst.rules.application.AcknowledgementPermissionPort
import com.dynokore.gst.rules.application.CompleteDeltaReader
import com.dynokore.gst.rules.application.TenantReviewPolicyReader
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val READER = "u-reader"
private const val ACKNOWLEDGER = "u-ack"
private const val ACKNOWLEDGE_ROWS = "select count(*) from rules.pack_delta_ack"

/** Counts the reads of the stored acknowledgement, to show that a refused caller reads nothing. */
object AcknowledgementReads {
    val count = AtomicInteger()
}

@TestConfiguration(proxyBeanMethods = false)
class CountingAcknowledgementReadFixtures {
    /** The same wiring as the production bean, with a counter in front of the store read. */
    @Bean
    @Primary
    @Suppress("LongParameterList") // the collaborators of the production bean, repeated to count its store reads
    fun countingStoredAcknowledgements(
        permission: AcknowledgementPermissionPort,
        store: JooqPackArmingStore,
        transactions: TransactionRunner,
        policies: TenantReviewPolicyReader,
        clock: Clock,
    ): StoredPackDeltaAcknowledgements {
        val deltas =
            object : CompleteDeltaReader by store {
                override fun completedAt(
                    tenantId: String,
                    packType: String,
                    version: String,
                ) = transactions.inTransaction { store.completedAt(tenantId, packType, version) }
            }
        val reader =
            DeltaAcknowledgementReader { tenantId, packType, version ->
                AcknowledgementReads.count.incrementAndGet()
                transactions.inTransaction { store.find(tenantId, packType, version) }
            }
        return StoredPackDeltaAcknowledgements(permission, reader, store, deltas, policies, clock)
    }
}

/** `getPackDeltaAcknowledgement` through the real context (INV-T-27762; INV-REQ-1008). */
@SpringBootTest(
    classes = [
        PolicyFixtures::class,
        ImportFixtures::class,
        ArmingFixtures::class,
        CountingAcknowledgementReadFixtures::class,
        RulesApplication::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PackDeltaAcknowledgementReadBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired replica: AuthorityReplica,
    @Autowired clock: MovableClock,
    @Autowired context: ApplicationContext,
    @Autowired policy: PolicyReplica,
) : ArmingHarness(port, replica, clock, context, policy) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            register(registry)
            registry.add("gst.form") { "saas" }
        }
    }

    private val json = JsonMapper.builder().build()

    private fun read(
        user: String?,
        type: String,
        v: String,
        at: TenantId = tenant,
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(
                URI("http://localhost:$port/internal/v1/rules/regulatory-packs/$type/$v/acknowledgement"),
            )
        if (user != null) builder.header("Authorization", "Bearer $user|$at|")
        builder.header("X-Workload-Token", workloadToken("console-bff"))
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun rows() = scalar(ACKNOWLEDGE_ROWS)

    private fun acknowledged(): String {
        val v = seedApproved()
        delta(tenant, "CALENDAR", v, daysAgo = 3)
        grant(ACKNOWLEDGER, roles(DeploymentForm.SAAS, "regulatory-pack.acknowledge").single())
        acknowledge(ACKNOWLEDGER, "CALENDAR", v).statusCode() shouldBe 200
        return v
    }

    @Test
    fun `INV-AC-27761 the stored acknowledgement is answered equal to what was stored and no row is written`() {
        val v = seedApproved()
        delta(tenant, "CALENDAR", v, daysAgo = 3)
        grant(ACKNOWLEDGER, roles(DeploymentForm.SAAS, "regulatory-pack.acknowledge").single())
        val written =
            json.readTree(
                acknowledge(ACKNOWLEDGER, "CALENDAR", v).also { it.statusCode() shouldBe 200 }.body(),
            )
        val stored = clock.instant()
        grant(READER, roles(DeploymentForm.SAAS, "regulatory-pack.acknowledge").single())
        val before = rows()
        val answer = read(READER, "CALENDAR", v)
        answer.statusCode() shouldBe 200
        val body = json.readTree(answer.body())
        body.properties().map { it.key }.toSet() shouldBe
            setOf("packType", "version", "acknowledgedBy", "acknowledgedAt", "graceWindowEnd")
        body["packType"].asString() shouldBe "CALENDAR"
        body["version"].asString() shouldBe v
        body["acknowledgedBy"].asString() shouldBe ACKNOWLEDGER
        Instant.parse(body["acknowledgedAt"].asString()) shouldBe stored
        body["graceWindowEnd"].asString() shouldBe written["graceWindowEnd"].asString()
        body["graceWindowEnd"].asString() shouldBe LocalDate.now(clock).plusDays(11).toString()
        rows() shouldBe before
    }

    @Test
    fun `INV-AC-27761 a pack the tenant has not acknowledged answers 404 NOT_FOUND`() {
        acknowledged()
        val other = seedApproved()
        delta(tenant, "CALENDAR", other, daysAgo = 3) // a write by the read path would be possible here
        grant(READER, roles(DeploymentForm.SAAS, "regulatory-pack.acknowledge").single())
        val before = rows()
        val answer = read(READER, "CALENDAR", other)
        answer.statusCode() shouldBe 404
        answer.body() shouldContain "SRT-005"
        rows() shouldBe before
    }

    @Test
    fun `INV-AC-27761 a caller without regulatory-pack_acknowledge is refused 403 SRT-004 and the store is not read`() {
        val v = acknowledged()
        grant("u-armer", armerOf(DeploymentForm.SAAS))
        val reads = AcknowledgementReads.count.get()
        val before = rows()
        val answer = read("u-armer", "CALENDAR", v)
        answer.statusCode() shouldBe 403
        answer.body() shouldContain "SRT-004"
        AcknowledgementReads.count.get() shouldBe reads
        rows() shouldBe before
    }

    @Test
    fun `INV-AC-27761 a second tenant asking for the first tenant's pack and version answers 404`() {
        val v = acknowledged()
        val second = TenantId(UUID.randomUUID())
        grant(READER, roles(DeploymentForm.SAAS, "regulatory-pack.acknowledge").single(), second)
        val answer = read(READER, "CALENDAR", v, second)
        answer.statusCode() shouldBe 404
        answer.body() shouldNotContain ACKNOWLEDGER
        answer.body() shouldNotContain "acknowledgedBy"
    }
}
