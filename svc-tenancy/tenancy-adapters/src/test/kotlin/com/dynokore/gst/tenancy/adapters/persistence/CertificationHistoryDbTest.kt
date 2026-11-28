package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SANDBOX_CERTIFICATION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.certification.CertificationHistoryEntry
import com.dynokore.gst.tenancy.application.certification.CertificationHistoryPage
import com.dynokore.gst.tenancy.application.certification.CertificationHistoryPosition
import com.dynokore.gst.tenancy.application.certification.CertificationRecord
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** INV-AC-29132 over PostgreSQL, on the Flyway-built schema, as the runtime role under forced row-level security. */
class CertificationHistoryDbTest {
    private val d1 = Instant.parse("2026-11-01T00:00:00.000001Z")
    private val d2 = Instant.parse("2026-11-05T00:00:00Z")
    private val d3 = Instant.parse("2026-11-09T00:00:00Z")

    private class Seed(
        val tenant: TenantId,
        val ref: String,
    )

    private fun context(id: TenantId) =
        object : TenantContext {
            override fun current(): TenantId = id

            override fun currentActor(): String = "actor"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private fun record(
        seed: Seed,
        suite: String,
        at: Instant,
        failed: List<String>? = null,
    ) = CertificationRecord(
        seed.ref,
        if (failed == null) CertificationOutcome.PASSED else CertificationOutcome.FAILED,
        suite,
        failed,
        "U-$suite",
        at,
    )

    /** Stores one run through the production writer, in a transaction of its own. */
    private fun append(
        seed: Seed,
        record: CertificationRecord,
    ) = Db.bound(seed.tenant) { JooqCertificationStore(it, context(seed.tenant)) { d3 }.append(record) }

    private fun page(
        reader: TenantId,
        ref: String,
        limit: Int,
        after: CertificationHistoryPosition? = null,
    ): CertificationHistoryPage = Db.bound(reader) { JooqCertificationHistory(it).page(ref, limit, after) }

    private fun walk(
        seed: Seed,
        limit: Int,
    ): List<CertificationHistoryEntry> {
        val entries = mutableListOf<CertificationHistoryEntry>()
        var after: CertificationHistoryPosition? = null
        var pages = 0
        do {
            val next = page(seed.tenant, seed.ref, limit, after)
            (next.entries.size in 1..limit) shouldBe true
            entries += next.entries
            after = next.last
            check(++pages <= MAX_PAGES) { "the pages do not end" }
        } while (after != null)
        return entries
    }

    private fun seed(
        tenant: TenantId? = null,
        group: UUID? = null,
    ): Pair<Seed, UUID> {
        val tag = UUID.randomUUID().toString().take(8)
        val id = tenant ?: TenantId(UUID.randomUUID())
        val ref = "e-$tag"
        val groupId =
            Db.superuser { dsl ->
                val owner = group ?: insertTenantAndGroup(dsl, id, tag)
                insertEntity(dsl, id, owner, ref, tag)
                owner
            }
        return Seed(id, ref) to groupId
    }

    private fun insertEntity(
        dsl: DSLContext,
        tenant: TenantId,
        group: UUID,
        ref: String,
        tag: String,
    ) {
        dsl
            .insertInto(ENTITY)
            .set(ENTITY.ID, UUID.randomUUID())
            .set(ENTITY.TENANT_ID, tenant.value)
            .set(ENTITY.REF, ref)
            .set(ENTITY.GROUP_ID, group)
            .set(ENTITY.LEGAL_NAME, "Entity")
            .set(ENTITY.CONSTITUTION, "COMPANY")
            .set(ENTITY.CODE, "E$tag".uppercase())
            .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
            .set(ENTITY.STRUCTURE_VERSION, version(dsl))
            .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    private fun version(dsl: DSLContext) = dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long

    private fun insertTenantAndGroup(
        dsl: DSLContext,
        tenant: TenantId,
        tag: String,
    ): UUID {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl
            .insertInto(TENANT)
            .set(TENANT.TENANT_ID, tenant.value)
            .set(TENANT.REF, "T-$tag")
            .set(TENANT.LEGAL_NAME, "Legal")
            .set(TENANT.PLAN_REF, "plan")
            .set(TENANT.CONTRACT_REFERENCE, "c-$tag")
            .set(TENANT.STRUCTURE_VERSION, version(dsl))
            .set(TENANT.CREATED_AT, now)
            .execute()
        val group = UUID.randomUUID()
        dsl
            .insertInto(TENANT_GROUP)
            .set(TENANT_GROUP.ID, group)
            .set(TENANT_GROUP.TENANT_ID, tenant.value)
            .set(TENANT_GROUP.REF, "g-$tag")
            .set(TENANT_GROUP.NAME, "Group")
            .set(TENANT_GROUP.KIND, "DIVISIONAL")
            .set(TENANT_GROUP.STRUCTURE_VERSION, version(dsl))
            .set(TENANT_GROUP.CREATED_AT, now)
            .execute()
        return group
    }

    @Test
    fun `INV-AC-29132 every run of the entity is read once, by recorded time then sequence, at every page size`() {
        val (first, group) = seed()
        val other = seed(first.tenant, group).first
        // stored out of time order; two runs share d2; the same suite version and result repeats
        append(first, record(first, "3.0", d3))
        append(first, record(first, "2.0", d2, listOf("S-1", "S-2")))
        append(other, record(other, "9.0", d2))
        append(first, record(first, "2.1", d2))
        append(first, record(first, "1.0", d1))
        append(first, record(first, "1.0", d1))

        val all = page(first.tenant, first.ref, 500)
        all.last shouldBe null
        all.entries.map { it.record } shouldBe
            listOf(
                record(first, "1.0", d1),
                record(first, "1.0", d1),
                record(first, "2.0", d2, listOf("S-1", "S-2")),
                record(first, "2.1", d2),
                record(first, "3.0", d3),
            )
        val sequences = all.entries.map { it.sequence }
        sequences.all { it >= 1 } shouldBe true
        sequences.toSet().size shouldBe sequences.size
        (sequences[0] < sequences[1] && sequences[2] < sequences[3]) shouldBe true
        (1..6).forEach { walk(first, it) shouldBe all.entries }
        page(first.tenant, first.ref, 5).last shouldBe null
        page(first.tenant, first.ref, 4).last shouldBe CertificationHistoryPosition(d2, sequences[3])
        page(first.tenant, other.ref, 500).entries.map { it.record } shouldBe listOf(record(other, "9.0", d2))
    }

    @Test
    fun `INV-AC-29132 runs with one recorded time are ordered by sequence, not by their place in the table`() {
        val seed = seed().first
        val suites = listOf("1", "2", "3", "4")
        suites.forEach { append(seed, record(seed, it, d2)) }
        // rewrite the rows from the last to the first sequence but one, so the table holds them out of sequence order
        Db.superuser { dsl ->
            val c = SANDBOX_CERTIFICATION
            listOf("3", "2", "1").forEach { suite ->
                dsl
                    .update(c)
                    .set(c.SUITE_VERSION, suite)
                    .where(c.TENANT_ID.eq(seed.tenant.value).and(c.SUITE_VERSION.eq(suite)))
                    .execute() shouldBe 1
            }
        }
        // no index may supply the order: only the query's own ORDER BY does
        val entries =
            Db.bound(seed.tenant) { dsl ->
                listOf("enable_indexscan", "enable_indexonlyscan", "enable_bitmapscan").forEach {
                    dsl.setLocal(DSL.name(it), DSL.inline("off")).execute()
                }
                JooqCertificationHistory(dsl).page(seed.ref, 500, null).entries
            }
        entries.map { it.record.suiteVersion } shouldBe suites
        entries.map { it.sequence } shouldBe entries.map { it.sequence }.sorted()
        walk(seed, 1) shouldBe entries
    }

    @Test
    fun `INV-AC-29132 another tenant reads nothing`() {
        val owner = seed().first
        val stranger = seed().first
        append(owner, record(owner, "1.0", d1))
        append(stranger, record(stranger, "7.0", d1))

        val foreign = page(stranger.tenant, owner.ref, 500)
        foreign.entries.shouldBeEmpty()
        foreign.last shouldBe null
        page(stranger.tenant, stranger.ref, 500).entries.map { it.record } shouldBe
            listOf(record(stranger, "7.0", d1))
        page(owner.tenant, owner.ref, 500).entries.map { it.record } shouldBe listOf(record(owner, "1.0", d1))
    }

    @Test
    fun `INV-AC-29132 the database assigns the sequence and the runtime role can neither supply nor change it`() {
        val seed = seed().first
        append(seed, record(seed, "1.0", d1))
        val stored = page(seed.tenant, seed.ref, 1).entries.single().sequence
        val c = SANDBOX_CERTIFICATION
        assertThrows(DataAccessException::class.java) {
            Db.bound(seed.tenant) { it.update(c).set(c.SUITE_VERSION, "2.0").execute() }
        }
        assertThrows(DataAccessException::class.java) {
            Db.bound(seed.tenant) {
                it.execute(
                    "insert into tenancy.sandbox_certification " +
                        "(id, tenant_id, entity_id, outcome, suite_version, recorded_by, recorded_at, seq) " +
                        "select gen_random_uuid(), tenant_id, entity_id, outcome, suite_version, recorded_by, " +
                        "recorded_at, seq + 1000 from tenancy.sandbox_certification",
                )
            }
        }
        page(seed.tenant, seed.ref, 500).entries.map { it.sequence } shouldBe listOf(stored)
        Db.superuser {
            it.fetchValue(
                "select pg_get_constraintdef(oid) from pg_constraint " +
                    "where conname = 'sandbox_certification_seq_unique'",
            )
        } shouldBe "UNIQUE (tenant_id, entity_id, seq)"
    }

    private object Db {
        // made for each run, so the file holds no key or password literal
        private val random = SecureRandom()
        private val password = secret()
        private val key = secret()
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer(PgTestImages.postgres(PgTestImages.PG17))
                .withStartupTimeout(Duration.ofMinutes(3))
                .withStartupAttempts(3)
                .withCommand(
                    "postgres",
                    "-c",
                    "fsync=off",
                    "-c",
                    "synchronous_commit=off",
                    "-c",
                    "full_page_writes=off",
                ).withLabel("inv.agent", "c-inv-t-29135")

        init {
            container.start()
            Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
            val roles = Files.readString(Path.of("src/integrationTest/resources/roles.sql"))
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                c.autoCommit = true
                c.createStatement().use { s ->
                    s.execute(roles)
                    s.execute(
                        "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;\n" +
                            "CREATE SCHEMA IF NOT EXISTS tenancy AUTHORIZATION tenancy_sys;\n" +
                            "SET ROLE tenancy_sys;\n" +
                            BindingKeyMigration.ddl("tenancy", "tenancy_sys", "tenancy_runtime") +
                            "\nRESET ROLE;",
                    )
                }
            }
            Flyway
                .configure()
                .dataSource(container.jdbcUrl, container.username, container.password)
                .schemas("tenancy")
                .defaultSchema("tenancy")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                c.createStatement().use { s ->
                    s.execute("ALTER ROLE tenancy_runtime LOGIN PASSWORD '$password'")
                    s.execute("GRANT CONNECT ON DATABASE ${container.databaseName} TO tenancy_runtime")
                    s.execute("GRANT USAGE ON SCHEMA tenancy TO tenancy_runtime")
                    s.execute(
                        "INSERT INTO tenancy.binding_key (slot, secret) VALUES ('current', convert_to('$key', 'UTF8'))",
                    )
                }
            }
        }

        private fun secret(): String = HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes))

        fun <T> superuser(block: (DSLContext) -> T): T =
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
                block(DSL.using(it, SQLDialect.POSTGRES))
            }

        /** A runtime-role transaction bound to [tenant] as TransactionRunnerImpl does, then committed. */
        fun <T> bound(
            tenant: TenantId,
            block: (DSLContext) -> T,
        ): T =
            DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", password).use { c ->
                c.autoCommit = false
                val actor = "svc:tenancy-svc"
                val expiry = BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(30))
                val signature = BindingSignature.sign(key.toByteArray(Charsets.UTF_8), tenant, actor, expiry)
                c
                    .prepareStatement(
                        "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                            "set_config('app.binding_sig', ?, true)",
                    ).use { s ->
                        s.setString(1, tenant.toString())
                        s.setString(2, actor)
                        s.setString(3, signature)
                        s.execute()
                    }
                val result = block(DSL.using(c, SQLDialect.POSTGRES))
                c.commit()
                result
            }
    }

    private companion object {
        const val MAX_PAGES = 20
    }
}
