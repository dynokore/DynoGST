package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.breakglass.SealedAccountKeeper
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffOutcome
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffUseCase
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * INV-T-24873: every outcome of `signInBreakGlass` is one audit entry (action `signInBreakGlass`, detail the closed
 * outcome name) and one security event of class sign_in (INV-REQ-1384; owner ruling 2026-11-18, option A).
 */
class SecurityEventSignInTest {
    private val mapper = JsonMapper.builder().build()

    private class Collector : AutoCloseable {
        val received = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()
        private val pool = Executors.newCachedThreadPool()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = pool
                createContext("/") { exchange ->
                    val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    paths += exchange.requestMethod + " " + exchange.requestURI.path
                    received += body
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }

        val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

        fun await(count: Int): List<String> {
            val end = System.nanoTime() + 5_000_000_000L
            while (received.size < count && System.nanoTime() < end) Thread.sleep(10)
            Thread.sleep(300)
            return received.toList()
        }

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val useCase = context.getBean(SignInWithBackoffUseCase::class.java)
        val credential: String =
            tenants.bind(tenant, ACTOR) {
                tx.inTransaction { context.getBean(SealedAccountKeeper::class.java).createInitial()!!.value }
            }

        fun session(
            unlockRef: String,
            status: String = "UNLOCKED",
        ) = BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "insert into access.break_glass_session (id, tenant_id, unlock_ref, session_hours, unlocked_at, " +
                        "expires_at, approved_by, status, created_at, created_by, updated_at, updated_by, version) " +
                        "values (?::uuid, ?::uuid, ?, 8, now(), now() + interval '30 days', " +
                        "array['IDN-ADM','IDN-APP'], ?, now(), 'seed', now(), 'seed', 0)",
                ).use {
                    it.setString(1, UUID.randomUUID().toString())
                    it.setString(2, tenant.toString())
                    it.setString(3, unlockRef)
                    it.setString(4, status)
                    it.executeUpdate()
                }
        }

        fun signIn(
            unlockRef: String,
            presented: String,
        ): SignInWithBackoffOutcome = tenants.bind(tenant, ACTOR) { useCase.handle(ACTOR, unlockRef, presented) }

        /** A back-off state of [failures] whose wait has passed. */
        fun counter(
            unlockRef: String,
            failures: Int,
        ) = BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "insert into access.break_glass_signin_counter (tenant_id, unlock_ref, failures, next_try_at, " +
                        "updated_at, updated_by) values (?::uuid, ?, ?, now() - interval '1 hour', now(), 'seed')",
                ).use {
                    it.setString(1, tenant.toString())
                    it.setString(2, unlockRef)
                    it.setInt(3, failures)
                    it.executeUpdate()
                }
        }

        fun failures(unlockRef: String): Int? =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select failures from access.break_glass_signin_counter where tenant_id = ?::uuid and unlock_ref = ?",
                    ).use {
                        it.setString(1, tenant.toString())
                        it.setString(2, unlockRef)
                        it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
                    }
            }

        /** The stored audit rows of `signInBreakGlass` of this tenant, as stored. */
        fun audits(): List<JsonNode> =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement(
                        "SELECT data::text FROM access.outbox WHERE tenant_id = ?::uuid " +
                            "AND type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1' AND data->>'action' = ?",
                    ).use { statement ->
                        statement.setString(1, tenant.toString())
                        statement.setString(2, "signInBreakGlass")
                        statement.executeQuery().use { rows ->
                            buildList { while (rows.next()) add(mapper.readTree(rows.getString(1))) }
                        }
                    }
            }
    }

    private fun run(block: (Env, Collector) -> Unit) {
        Collector().use { collector ->
            BoundaryFixture
                .runner()
                .withPropertyValues(
                    "gst.bindings.telemetry-export=bundled-otel",
                    "gst.bindings.telemetry-export-otlp-endpoint=${collector.endpoint}",
                ).run { context ->
                    context.startupFailure?.let { throw it }
                    block(Env(context), collector)
                }
        }
    }

    private fun attributes(body: String): Map<String, String> =
        mapper
            .readTree(body)
            .at("/resourceLogs/0/scopeLogs/0/logRecords/0")
            .get("attributes")
            .associate { it.get("key").asString() to it.at("/value/stringValue").asString() }

    private fun assertSignInRecord(
        body: String,
        env: Env,
    ) = assertThat(attributes(body))
        .isEqualTo(
            mapOf(
                "gst.security.class" to "sign_in",
                "user.id" to ACTOR,
                "gst.tenant.id" to env.tenant.toString(),
            ),
        )

    private fun assertOneEntry(
        env: Env,
        detail: String,
        scope: String,
    ) {
        val audits = env.audits()
        assertThat(audits).hasSize(1)
        val entry = audits.single()
        assertThat(entry.get("actor").asString()).isEqualTo(ACTOR)
        assertThat(entry.get("action").asString()).isEqualTo("signInBreakGlass")
        assertThat(entry.toString()).contains("\"$detail\"").contains(scope)
        assertThat(entry.toString()).doesNotContain(env.credential).doesNotContain(WRONG)
    }

    @Test
    fun `INV-AC-24872 a correct credential writes one SIGNED_IN entry and yields one sign_in record`() =
        run { env, collector ->
            env.session("BG-A")
            assertThat(env.signIn("BG-A", env.credential)).isInstanceOf(SignInWithBackoffOutcome.SignedIn::class.java)
            assertOneEntry(env, "SIGNED_IN", "break-glass:BG-A")
            val records = collector.await(1)
            assertThat(records).hasSize(1)
            assertThat(collector.paths).containsExactly("POST /v1/logs")
            assertSignInRecord(records.single(), env)
        }

    @Test
    fun `INV-AC-24872 a wrong credential writes one WRONG_CREDENTIAL entry and yields one sign_in record`() =
        run { env, collector ->
            env.session("BG-B")
            assertThat(env.signIn("BG-B", WRONG)).isEqualTo(SignInWithBackoffOutcome.WrongCredential)
            assertOneEntry(env, "WRONG_CREDENTIAL", "break-glass:BG-B")
            val records = collector.await(1)
            assertThat(records).hasSize(1)
            assertSignInRecord(records.single(), env)
        }

    @Test
    fun `INV-AC-24872 an unknown reference writes one NOT_FOUND entry with the fixed subject unknown`() =
        run { env, collector ->
            assertThat(env.signIn("BG-CHOSEN-BY-CALLER", WRONG)).isEqualTo(SignInWithBackoffOutcome.NotFound)
            assertOneEntry(env, "NOT_FOUND", "break-glass:unknown")
            assertThat(env.audits().single().toString()).doesNotContain("BG-CHOSEN-BY-CALLER")
            val records = collector.await(1)
            assertThat(records).hasSize(1)
            assertSignInRecord(records.single(), env)
        }

    @Test
    fun `INV-AC-24872 a closed unlock writes one NOT_OPEN entry and yields one sign_in record`() =
        run { env, collector ->
            env.session("BG-C", "SEALED")
            assertThat(env.signIn("BG-C", env.credential)).isEqualTo(SignInWithBackoffOutcome.NotOpen)
            assertOneEntry(env, "NOT_OPEN", "break-glass:BG-C")
            val records = collector.await(1)
            assertThat(records).hasSize(1)
            assertSignInRecord(records.single(), env)
        }

    @Test
    fun `INV-AC-24872 a call during the back-off writes one WAITING entry, one record and leaves the counter`() =
        run { env, collector ->
            env.session("BG-D")
            repeat(WRONG_BEFORE_WAIT) { env.signIn("BG-D", WRONG) }
            val counter = env.failures("BG-D")
            val before = env.audits().size
            assertThat(collector.await(before)).hasSize(before)
            val waiting = env.signIn("BG-D", env.credential)
            assertThat(waiting).isInstanceOf(SignInWithBackoffOutcome.Waiting::class.java)
            assertThat(env.failures("BG-D")).isEqualTo(counter)
            val audits = env.audits()
            assertThat(audits).hasSize(before + 1)
            assertThat(audits.map { it.toString() }.last { it.contains("WAITING") }).contains("break-glass:BG-D")
            val records = collector.await(before + 1)
            assertThat(records).hasSize(before + 1)
            assertSignInRecord(records.last(), env)
        }

    @Test
    fun `INV-AC-24872 the fifth wrong credential, an alert count, writes one entry and not two`() =
        run { env, collector ->
            env.session("BG-E")
            env.counter("BG-E", ALERT_COUNT - 1)
            assertThat(env.signIn("BG-E", WRONG)).isEqualTo(SignInWithBackoffOutcome.WrongCredential)
            assertThat(env.failures("BG-E")).isEqualTo(ALERT_COUNT)
            assertOneEntry(env, "WRONG_CREDENTIAL", "break-glass:BG-E")
            assertThat(collector.await(1)).hasSize(1)
        }

    @Test
    fun `INV-AC-24872 a rollback of the use case transaction leaves no audit entry`() =
        run { env, collector ->
            env.session("BG-F")
            assertThatThrownBy {
                env.tenants.bind(env.tenant, ACTOR) {
                    env.tx.inTransaction {
                        env.useCase.handle(ACTOR, "BG-F", env.credential)
                        error("outer rollback")
                    }
                }
            }.hasMessageContaining("outer rollback")
            assertThat(env.audits()).isEmpty()
            assertThat(collector.await(1)).isEmpty()
        }

    @Test
    fun `INV-AC-24872 a sign-in yields only the class sign_in`() =
        run { env, collector ->
            env.session("BG-G")
            env.signIn("BG-G", env.credential)
            assertThat(collector.await(1).map { attributes(it)["gst.security.class"] }).containsExactly("sign_in")
        }

    private companion object {
        const val ACTOR = "IDN-ADM"
        const val WRONG = "wrong-sealing-credential"
        const val WRONG_BEFORE_WAIT = 3
        const val ALERT_COUNT = 5
    }
}
