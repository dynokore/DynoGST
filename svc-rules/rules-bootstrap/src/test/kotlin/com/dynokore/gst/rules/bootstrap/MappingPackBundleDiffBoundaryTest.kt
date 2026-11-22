package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.domain.resolution.TransactionDate
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
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
import java.util.concurrent.atomic.AtomicLong

private const val AUTHOR_KEY = "mapping-pack.author"
private const val CALLER = "console-bff"
private const val DIFFS = "/internal/v1/rules/mapping-pack-diffs"
private const val PACK_ENTITY = "E-1"
private val DIFF_TENANT: TenantId = TenantId.of("0a000000-0000-0000-0000-000000026960")

/** A tenant that holds no mapping pack at all, while [DIFF_TENANT] holds an active one. */
private val BARE_TENANT: TenantId = TenantId.of("0a000000-0000-0000-0000-000000026961")

/** The fixtures of the boundary test: the build properties of the mapping pack ports. */
@TestConfiguration(proxyBeanMethods = false)
@Import(MappingAllowingPorts::class)
class MappingPackBundleDiffFixtures

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
        private val reader: PackContentsReader,
        private val tx: TransactionRunner,
        private val tenants: TenantContext,
    ) : MappingPackSupport(port) {
        private val tenant = DIFF_TENANT

        @BeforeEach
        fun freshReplica() {
            ReplicaFallbackDouble.reset()
            replica.tracker.admitIncarnation(epoch.get())
            replica.onSnapshotComplete(0)
            replica.tracker.onApplied(versions.get())
            activePack()
            draftPack()
        }

        private fun bearer(
            user: String,
            of: TenantId = tenant,
        ) = "Bearer $user|$of|"

        private fun newUser(
            withKey: Boolean,
            tenant: TenantId = this.tenant,
        ): String {
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
                    "min_engine_version, registered_by, label, entity_ref) values (?::uuid, ?::uuid, " +
                    "'MAP-act-26951', 'MAPPING', 'act-26951', 'TENANT_MAPPING', 'DRAFT', '1.0.0', 'tenancy-probe', " +
                    "'act-26951', " +
                    "'$PACK_ENTITY') returning id",
                UUID.randomUUID().toString(),
                tenant.toString(),
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
            listOf("SUBMITTED", "APPROVED").forEach {
                value(
                    tenant,
                    "update rules.pack_registry set status = '$it' where pack_version = 'act-26951' returning id",
                )
            }
            value(
                tenant,
                "update rules.pack_registry set status = 'ARMED', effective_from = ?::date " +
                    "where pack_version = 'act-26951' returning id",
                yesterday,
            )
        }

        /** A DRAFT mapping pack of the same tenant whose entries differ from the active one: it must never be read. */
        private fun draftPack() {
            if (value(tenant, "select count(*) from rules.pack_registry where pack_version = 'dra-26951'") == 1L) return
            value(
                tenant,
                "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                    "min_engine_version, registered_by, label, entity_ref) values (?::uuid, ?::uuid, " +
                    "'MAP-dra-26951', 'MAPPING', 'dra-26951', 'TENANT_MAPPING', 'DRAFT', '1.0.0', 'tenancy-probe', " +
                    "'dra-26951', '$PACK_ENTITY') returning id",
                UUID.randomUUID().toString(),
                tenant.toString(),
            )
            value(
                tenant,
                "insert into rules.pack_entry_source (id, tenant_id, pack_type, pack_version, entry_id, " +
                    "source_class, internal_tax_code, iras_code) values (?::uuid, ?::uuid, 'MAPPING', " +
                    "'dra-26951', 'STD', 'SECONDARY', 'STD', 'ZR') returning id",
                UUID.randomUUID().toString(),
                tenant.toString(),
            )
        }

        /**
         * The catalogue entry in force for the tenant today. The database is shared by the test classes of the JVM and
         * each registers its own platform catalogue, so the group and rate the diff compares against are read.
         */
        private fun catalogueEntry(iras: String) =
            checkNotNull(
                tenants.bind(tenant, "boundary-test") {
                    tx.inTransaction {
                        reader.catalogueAt(tenant, TransactionDate(LocalDate.now(ZoneOffset.UTC)))
                    }
                },
            ).entryOf(iras)!!

        /** A rule of the bundle; [rate] defaults to the rate of the catalogue entry, so only a given rate differs. */
        private fun rule(
            code: String,
            iras: String,
            rate: String? = null,
        ) = """{"internalTaxCode":"$code","description":"${catalogueEntry(iras).group}","irasCode":"$iras",""" +
            """"rate":"${rate ?: catalogueEntry(iras).annexERate}","sourceClass":"PRIMARY","citation":"cite-$code"}"""

        private fun part(vararg rules: String) = """{"bundleId":"B-26951","entries":[${rules.joinToString(",")}]}"""

        private fun diff(
            body: String,
            user: String,
            of: TenantId = tenant,
        ): HttpResponse<String> = call("POST", DIFFS, body, bearer(user, of))

        /** The active pack as stored: registry rows and entries of the tenant, as text. */
        private fun activeState() =
            listOf("pack_registry", "pack_entry_source", "outbox").associateWith { table ->
                rows(tenant, "select t::text as r from rules.\"$table\" t order by 1").map { it["r"] }
            } + ("shared" to snapshot().toString().let { listOf(it) })

        private fun code(answer: HttpResponse<String>): String? =
            Regex(""""code"\s*:\s*"([^"]*)"""").find(answer.body())?.groupValues?.get(1)

        @Test
        fun `INV-AC-26951 a part differing in one rule answers 200 with one CHANGED change and no pack change`() {
            val before = activeState()
            val current = catalogueEntry("SR").annexERate
            val proposed = if (current == "8") "7" else "8"
            val answer = diff(part(rule("STD", "SR", proposed), rule("ZER", "ZR")), newUser(withKey = true))
            withClue(answer.body()) { answer.statusCode() shouldBe 200 }
            json(answer) shouldBe
                mapper.readTree(
                    """{"changes":[{"path":"rules/STD/rate","change":"CHANGED",""" +
                        """"current":"$current","proposed":"$proposed"}]}""",
                )
            activeState() shouldBe before
        }

        @Test
        fun `INV-AC-26951 a caller without mapping-pack author is refused 403 SRT-004 and the use case is not run`() {
            val before = activeState()
            val user = newUser(withKey = false)
            val answer = diff(part(rule("STD", "SR", "8"), rule("ZER", "ZR", "9")), user)
            withClue(answer.body()) {
                answer.statusCode() shouldBe 403
                code(answer) shouldBe "SRT-004"
            }
            answer.body() shouldNotContain "changes"
            // the duplicate check is the use case's: a refusal 403 instead of 400 shows that the use case did not run
            val duplicate = diff(part(rule("STD", "SR", "8"), rule("STD", "SR", "9")), user)
            withClue(duplicate.body()) {
                duplicate.statusCode() shouldBe 403
                code(duplicate) shouldBe "SRT-004"
            }
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

        @Test
        fun `INV-AC-26951 an absent member is no change and an empty description is a change`() {
            val bare =
                """{"bundleId":"B-26951","entries":[{"internalTaxCode":"STD","irasCode":"SR",""" +
                    """"sourceClass":"PRIMARY"},""" +
                    """{"internalTaxCode":"ZER","irasCode":"ZR","sourceClass":"PRIMARY"}]}"""
            val user = newUser(withKey = true)
            val absent = diff(bare, user)
            withClue(absent.body()) { absent.statusCode() shouldBe 200 }
            json(absent) shouldBe mapper.readTree("""{"changes":[]}""")
            val nullMembers =
                """{"bundleId":"B-26951","entries":[{"internalTaxCode":"STD","description":null,"irasCode":"SR",""" +
                    """"rate":null,"sourceClass":"PRIMARY","citation":null},""" +
                    """{"internalTaxCode":"ZER","irasCode":"ZR","sourceClass":"PRIMARY"}]}"""
            json(diff(nullMembers, user)) shouldBe mapper.readTree("""{"changes":[]}""")
            val current = catalogueEntry("SR").group
            val empty =
                """{"bundleId":"B-26951","entries":[{"internalTaxCode":"STD","description":"","irasCode":"SR",""" +
                    """"sourceClass":"PRIMARY"},""" +
                    """{"internalTaxCode":"ZER","irasCode":"ZR","sourceClass":"PRIMARY"}]}"""
            val answer = diff(empty, user)
            withClue(answer.body()) { answer.statusCode() shouldBe 200 }
            json(answer) shouldBe
                mapper.readTree(
                    """{"changes":[{"path":"rules/STD/description","change":"CHANGED",""" +
                        """"current":"$current","proposed":""}]}""",
                )
        }

        @Test
        fun `INV-AC-26951 a tenant without an active pack gets every entry ADDED and nothing REMOVED`() {
            val user = newUser(withKey = true, tenant = BARE_TENANT)
            val answer = diff(part(rule("STD", "SR"), rule("ZER", "ZR")), user, BARE_TENANT)
            withClue(answer.body()) { answer.statusCode() shouldBe 200 }
            json(answer) shouldBe
                mapper.readTree(
                    """{"changes":[{"path":"rules/STD","change":"ADDED"},{"path":"rules/ZER","change":"ADDED"}]}""",
                )
        }

        companion object {
            val versions = AtomicLong(0)
            val epoch = AtomicLong(1)

            @JvmStatic
            @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) {
                registerCatalogue(registry)
                registry.add("service-runtime.idempotency.schema") { "rules" }
                registry.add("service-runtime.workload-identity.callers[0].service") { CALLER }
                registry.add("service-runtime.workload-identity.callers[0].subject") { subjectOf(CALLER) }
                registry.add("gst.form") { "saas" }
            }
        }
    }
