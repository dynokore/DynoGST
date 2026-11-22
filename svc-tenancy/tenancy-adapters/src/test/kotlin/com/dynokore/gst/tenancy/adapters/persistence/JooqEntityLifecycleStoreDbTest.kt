package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.domain.LifecycleState
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-27010 over PostgreSQL with forced row-level security: `JooqEntityLifecycleStore.setLifecycleState` records
 * the go-live date once (COALESCE) and changes the state; each case reads the stored row back as the superuser.
 */
class JooqEntityLifecycleStoreDbTest {
    private val d1 = LocalDate.of(2026, 3, 2)
    private val d2 = LocalDate.of(2026, 9, 17)

    private class Seed(
        val tenant: TenantId,
        val e1: UUID,
        val e2: UUID,
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

    private fun insertEntity(
        dsl: DSLContext,
        tenant: TenantId,
        group: UUID,
        id: UUID,
        label: String,
    ) {
        dsl
            .insertInto(ENTITY)
            .set(ENTITY.ID, id)
            .set(ENTITY.TENANT_ID, tenant.value)
            .set(ENTITY.REF, "e-$label")
            .set(ENTITY.GROUP_ID, group)
            .set(ENTITY.LEGAL_NAME, "Entity $label")
            .set(ENTITY.CONSTITUTION, "COMPANY")
            .set(ENTITY.CODE, "E$label".uppercase())
            .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
            .set(ENTITY.STRUCTURE_VERSION, dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long)
            .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** A tenant with one group and two entities, both DRAFT with a null go_live_date. */
    private fun seed(): Seed {
        val tag = UUID.randomUUID().toString().take(8)
        val tenant = TenantId(UUID.randomUUID())
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        Db.superuser { dsl ->
            fun version() = dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long
            dsl
                .insertInto(TENANT)
                .set(TENANT.TENANT_ID, tenant.value)
                .set(TENANT.REF, "T-$tag")
                .set(TENANT.LEGAL_NAME, "Legal")
                .set(TENANT.PLAN_REF, "plan")
                .set(TENANT.CONTRACT_REFERENCE, "c-$tag")
                .set(TENANT.STRUCTURE_VERSION, version())
                .set(TENANT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            val group = UUID.randomUUID()
            dsl
                .insertInto(TENANT_GROUP)
                .set(TENANT_GROUP.ID, group)
                .set(TENANT_GROUP.TENANT_ID, tenant.value)
                .set(TENANT_GROUP.REF, "g-$tag")
                .set(TENANT_GROUP.NAME, "Group")
                .set(TENANT_GROUP.KIND, "DIVISIONAL")
                .set(TENANT_GROUP.STRUCTURE_VERSION, version())
                .set(TENANT_GROUP.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            ids.forEachIndexed { index, id -> insertEntity(dsl, tenant, group, id, "$index$tag") }
        }
        return Seed(tenant, ids[0], ids[1])
    }

    private fun refOf(id: UUID): String =
        Db.superuser {
            it
                .select(ENTITY.REF)
                .from(ENTITY)
                .where(ENTITY.ID.eq(id))
                .fetchOne(ENTITY.REF)!!
        }

    private fun write(
        tenant: TenantId,
        entity: UUID,
        state: LifecycleState,
        date: LocalDate?,
        ref: String = refOf(entity),
    ) = Db.bound(tenant) { JooqEntityLifecycleStore(it, context(tenant)).setLifecycleState(ref, state, 1L, date) }

    private fun stored(entity: UUID): Pair<LocalDate?, String> =
        Db.superuser { dsl ->
            val r =
                dsl
                    .select(
                        ENTITY.GO_LIVE_DATE,
                        ENTITY.LIFECYCLE_STATE,
                    ).from(ENTITY)
                    .where(ENTITY.ID.eq(entity))
                    .fetchOne()!!
            r[ENTITY.GO_LIVE_DATE] to r[ENTITY.LIFECYCLE_STATE]!!
        }

    @Test
    fun `INV-AC-27010 store a first write with a date sets go_live_date and the state`() {
        val s = seed()
        write(s.tenant, s.e1, LifecycleState.LIVE, d1)
        stored(s.e1) shouldBe (d1 to "LIVE")
    }

    @Test
    fun `INV-AC-27010 store a second write with a different date keeps the first`() {
        val s = seed()
        write(s.tenant, s.e1, LifecycleState.LIVE, d1)
        write(s.tenant, s.e1, LifecycleState.LIVE, d2)
        stored(s.e1) shouldBe (d1 to "LIVE")
    }

    @Test
    fun `INV-AC-27010 store a write for another entity is isolated`() {
        val s = seed()
        write(s.tenant, s.e1, LifecycleState.LIVE, d1)
        stored(s.e2) shouldBe (null to "DRAFT")
        val other = seed()
        write(other.tenant, other.e1, LifecycleState.LIVE, d2, ref = refOf(s.e1))
        stored(s.e1) shouldBe (d1 to "LIVE")
    }

    @Test
    fun `INV-AC-27010 store a write without a date keeps the recorded date and changes the state`() {
        val s = seed()
        write(s.tenant, s.e1, LifecycleState.LIVE, d1)
        write(s.tenant, s.e1, LifecycleState.SUSPENDED, null)
        stored(s.e1) shouldBe (d1 to "SUSPENDED")
        write(s.tenant, s.e2, LifecycleState.SUSPENDED, null)
        stored(s.e2) shouldBe (null to "SUSPENDED")
    }

    private object Db {
        private val RUNTIME_PASSWORD = randomHex()
        private const val ACTOR = "actor"
        private val KEY = randomHex()
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer(PgTestImages.postgres(PgTestImages.PG17))
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(*pgFastCommand)
                .withLabel("inv.agent", "c-inv-t-27011")

        init {
            container.start()
            Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
            val roles =
                java.nio.file.Files
                    .readString(Path.of("src/integrationTest/resources/roles.sql"))
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
                    s.execute("ALTER ROLE tenancy_runtime LOGIN PASSWORD '$RUNTIME_PASSWORD'")
                    s.execute("GRANT CONNECT ON DATABASE ${container.databaseName} TO tenancy_runtime")
                    s.execute("GRANT USAGE ON SCHEMA tenancy TO tenancy_runtime")
                    s.execute(
                        "INSERT INTO tenancy.binding_key (slot, secret) VALUES ('current', convert_to('$KEY', 'UTF8'))",
                    )
                }
            }
        }

        fun <T> superuser(block: (DSLContext) -> T): T =
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
                block(DSL.using(it, SQLDialect.POSTGRES))
            }

        /** A runtime-role transaction bound to [tenant] and [actor] as TransactionRunnerImpl does, then committed. */
        fun <T> bound(
            tenant: TenantId,
            block: (DSLContext) -> T,
        ): T =
            DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                val expiry = BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(30))
                val signature = BindingSignature.sign(KEY.toByteArray(Charsets.UTF_8), tenant, ACTOR, expiry)
                c
                    .prepareStatement(
                        "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                            "set_config('app.binding_sig', ?, true)",
                    ).use { s ->
                        s.setString(1, tenant.toString())
                        s.setString(2, ACTOR)
                        s.setString(3, signature)
                        s.execute()
                    }
                val result = block(DSL.using(c, SQLDialect.POSTGRES))
                c.commit()
                result
            }
    }
}

private fun randomHex(): String =
    ByteArray(32)
        .also {
            SecureRandom().nextBytes(it)
        }.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

// CI-FAST: a loaded CI host can need more than the 60 s Testcontainers default for initdb and its restart; a failed
// start is retried with a fresh container, and the throwaway test database skips fsync.
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
