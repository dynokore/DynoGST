package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.application.CodeCataloguePort
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.ReplicaFallbackDouble
import com.dynokore.gst.rules.served.subjectOf
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val AUTHOR_KEY = "mapping-pack.author"
private const val CALLER = "console-bff"
private const val DIFFS = "/internal/v1/rules/mapping-pack-diffs"
private const val PACK_ENTITY = "E-1"
private val DIFF_TENANT: TenantId = TenantId.of("0a000000-0000-0000-0000-000000026960")

/** Counts the catalogue reads: the active pack is read, and then the use case runs, only after the permission passed. */
@TestConfiguration(proxyBeanMethods = false)
@Import(MappingAllowingPorts::class)
class MappingPackBundleDiffFixtures {
    companion object {
        val catalogueReads = AtomicInteger(0)

        @Bean
        @JvmStatic
        fun countingCatalogues(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (bean is CodeCataloguePort) {
                        CodeCataloguePort { tenant, date ->
                            catalogueReads.incrementAndGet()
                            bean.catalogueAt(tenant, date)
                        }
                    } else {
                        bean
                    }
            }
    }
}

/**
 * INV-AC-26951 (INV-REQ-1128 behaviour 4): `diffMappingPackBundle` answers the diff of a bundle part against the
 * active mapping pack of the tenant and changes nothing; the scoped key `mapping-pack.author` is decided by the real
 * `AuthorityReplica` on real PostgreSQL, the role is read from the generated `OperationRoles` map.
 */
@SpringBootTest(
    classes = [PolicyFixtures::class, MappingPackBundleDiffFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class MappingPackBundleDiffBoundaryTest
    @Autowired
    constructor(
        @LocalServerPort port: Int,
        private val replica: AuthorityReplica,
        private val clock: MovableClock,
    ) : MappingPackSupport(port) {
        private val tenant = DIFF_TENANT
        private val reads = MappingPackBundleDiffFixtures.catalogueReads

        @BeforeEach
        fun freshReplica() {
            ReplicaFallbackDouble.reset()
            replica.tracker.admitIncarnation(epoch.get())
            replica.onSnapshotComplete(0)
            replica.tracker.onApplied(versions.get())
            activePack()
        }

        private fun bearer(user: String) = "Bearer $user|$tenant|"

        private fun newUser(withKey: Boolean): String {
            val user = "u-" + UUID.randomUUID().toString().take(8)
            if (withKey) {
                val role = OperationRoles.rolesFor(DeploymentForm.SAAS, AUTHOR_KEY).keys.first()
                replica.accept(
                    AuthorityChanged(
                        tenant,
                        user,
                        versions.incrementAndGet(),
                        AuthorityRecord.RoleGrantRecord(
                            "g-$user-$role",
                            RoleGrantState.ACTIVE,
                            RoleGrant(
                                role,
                                GrantScope.TENANT,
                                tenant.toString(),
                                clock.instant().minus(Duration.ofHours(1)),
                                null,
                            ),
                        ),
                        epoch.get(),
                    ),
                )
            }
            return user
        }

        /** The ARMED mapping pack of the tenant, in force since yesterday: STD/SR and ZER/ZR, both PRIMARY. */
        private fun activePack() {
            if (value(tenant, "select count(*) from rules.pack_registry where pack_version = 'act-26951'") == 1L) return
            val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()
            value(
                tenant,
                "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                    "effective_from, effective_to, min_engine_version, supersedes, registered_by, entity_ref) " +
                    "values (?::uuid, ?::uuid, 'MAP-act-26951', 'MAPPING', 'act-26951', 'TENANT_MAPPING', 'ARMED', " +
                    "?::date, null, '1.0.0', null, 'tenancy-probe', '$PACK_ENTITY') returning id",
                UUID.randomUUID().toString(),
                tenant.toString(),
                yesterday,
            )
            listOf("STD" to "SR", "ZER" to "ZR").forEach { (code, iras) ->
                value(
                    tenant,
                    "insert into rules.pack_entry_source (id, tenant_id, pack_type, pack_version, entry_id, " +
                        "source_class, internal_tax_code, iras_code, source_citation) values (?::uuid, ?::uuid, " +
                        "'MAPPING', 'act-26951', ?, 'PRIMARY', ?, ?, 'cite-$code') returning id",
                    UUID.randomUUID().toString(),
                    tenant.toString(),
                    code,
                    code,
                    iras,
                )
            }
        }

        private fun rule(
            code: String,
            iras: String,
            rate: String,
        ) = """{"internalTaxCode":"$code","description":"SUPPLY_STANDARD_RATED","irasCode":"$iras","rate":"$rate",""" +
            """"sourceClass":"PRIMARY","citation":"cite-$code"}"""

        private fun part(vararg rules: String) = """{"bundleId":"B-26951","entries":[${rules.joinToString(",")}]}"""

        private fun diff(
            body: String,
            user: String,
        ): HttpResponse<String> = call("POST", DIFFS, body, bearer(user))

        /** The active pack as stored: registry rows and entries of the tenant, as text. */
        private fun activeState() =
            listOf("pack_registry", "pack_entry_source", "outbox").associateWith { table ->
                rows(tenant, "select t::text as r from rules.\"$table\" t order by 1").map { it["r"] }
            } + ("shared" to snapshot().toString().let { listOf(it) })

        private fun code(answer: HttpResponse<String>): String? =
            Regex(""""code"\s*:\s*"([^"]*)"""").find(answer.body())?.groupValues?.get(1)

        @Test
        fun `INV-AC-26951 a part differing in one rule answers 200 with one CHANGED change and the active pack is equal`() {
            val before = activeState()
            val answer = diff(part(rule("STD", "SR", "8"), rule("ZER", "ZR", "9")), newUser(withKey = true))
            withClue(answer.body()) { answer.statusCode() shouldBe 200 }
            json(answer) shouldBe
                mapper.readTree(
                    """{"changes":[{"path":"rules/STD/rate","change":"CHANGED","current":"9","proposed":"8"}]}""",
                )
            activeState() shouldBe before
        }

        @Test
        fun `INV-AC-26951 a caller without mapping-pack author is refused 403 SRT-004 and the use case is not run`() {
            val before = activeState()
            val readsBefore = reads.get()
            val answer = diff(part(rule("STD", "SR", "8"), rule("ZER", "ZR", "9")), newUser(withKey = false))
            withClue(answer.body()) {
                answer.statusCode() shouldBe 403
                code(answer) shouldBe "SRT-004"
            }
            answer.body() shouldNotContain "changes"
            reads.get() shouldBe readsBefore
            activeState() shouldBe before
        }

        @Test
        fun `INV-AC-26951 a part with a duplicate internalTaxCode answers 400 SRT-001 and the active pack is equal`() {
            val before = activeState()
            val answer = diff(part(rule("STD", "SR", "8"), rule("STD", "SR", "9")), newUser(withKey = true))
            withClue(answer.body()) {
                answer.statusCode() shouldBe 400
                code(answer) shouldBe "SRT-001"
            }
            activeState() shouldBe before
        }

        companion object {
            val versions = AtomicLong(0)
            val epoch = AtomicLong(1)

            @JvmStatic
            @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) {
                registerCatalogue(registry)
                registry.add("service-runtime.modules.schema") { "rules" }
                registry.add("service-runtime.idempotency.schema") { "rules" }
                registry.add("inv.tenancy.reference-tables") { "rules.pack_registry,rules.pack_entry_source" }
                registry.add("service-runtime.workload-identity.callers[0].service") { CALLER }
                registry.add("service-runtime.workload-identity.callers[0].subject") { subjectOf(CALLER) }
                registry.add("gst.form") { "saas" }
            }
        }
    }
