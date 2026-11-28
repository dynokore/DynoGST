package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.PeriodListCursor
import com.dynokore.gst.tenancy.adapters.http.PeriodsController
import com.dynokore.gst.tenancy.adapters.http.RenditionAttestationCursor
import com.dynokore.gst.tenancy.adapters.http.SandboxCertificationCursor
import com.dynokore.gst.tenancy.application.certification.CertificationHistory
import com.dynokore.gst.tenancy.application.certification.CertificationHistoryPosition
import com.dynokore.gst.tenancy.application.certification.CertifyOutcome
import com.dynokore.gst.tenancy.application.period.PagePosition
import com.dynokore.gst.tenancy.application.rendition.RenditionHistoryPosition
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.SandboxCertificationListResponse
import com.dynokore.gst.tenancy.contract.model.SandboxCertificationRecord
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

private const val SECOND_HOLDER = "identity:USR-C3"
private val BAD_REQUEST = ClosedErrorCode.CONTRACT_VIOLATION

/**
 * listSandboxCertifications through the controller bean of the real application context over PostgreSQL (INV-T-29134,
 * INV-REQ-1008): the runs are written through certifySandbox and read back page by page.
 */
class ListSandboxCertificationsHttpTest {
    /** Counts the page reads of the history bean the context wires into the use case. */
    private class HistoryReads : BeanPostProcessor {
        val pages = AtomicInteger()

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any =
            if (bean is CertificationHistory) {
                CertificationHistory { entityRef, limit, after ->
                    pages.incrementAndGet()
                    bean.page(entityRef, limit, after)
                }
            } else {
                bean
            }
    }

    private class Env(
        val certification: CertificationEnv,
        val entity: EntityResponse,
        val reads: HistoryReads,
    ) {
        val api: PeriodsController = certification.context().getBean(PeriodsController::class.java)

        fun run(
            suite: String,
            failed: List<String>? = null,
            by: String = CERTIFIER,
        ) = run {
            certification.actAs(by, entity, RECORD_KEY)
            val outcome = if (failed == null) CertificationOutcome.PASSED else CertificationOutcome.FAILED
            (certification.certify(entity, outcome, failed, suite) as CertifyOutcome.Recorded).record
        }

        fun list(
            limit: Int = 100,
            cursor: String? = null,
            entityRef: String = entity.entityRef,
        ): SandboxCertificationListResponse = api.listSandboxCertifications(entityRef, limit, cursor).body!!

        fun statusOf(block: () -> Unit): ClosedErrorCode? =
            try {
                block()
                null
            } catch (refusal: ApiRefusal) {
                refusal.code
            }

        /** Every row of the schema a list call could write: runs, certification state, outbox and idempotency. */
        fun written(): List<Any?> =
            listOf(certification.rows(entity), certification.state(entity), certification.stored(entity)) +
                BoundaryFixture.tableNames().sorted().map { table -> table to rowCount(table) }

        private fun rowCount(table: String): Long =
            BoundaryFixture.asSuperuser { c ->
                c.prepareStatement("select count(*) from tenancy.\"$table\"").use { s ->
                    s.executeQuery().use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }
    }

    private fun withEnv(block: (Env) -> Unit) {
        val log = TopicLog()
        val reads = HistoryReads()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .withBean(HistoryReads::class.java, { reads })
            .run { context ->
                context.startupFailure?.let { throw it }
                val profile = ProfileEnv(context)
                profile.withEntity { _, entity ->
                    val env = Env(CertificationEnv(profile, log), entity, reads)
                    env.certification.actAs(CERTIFIER, entity, RECORD_KEY)
                    block(env)
                }
            }
    }

    private fun Env.threeRuns() =
        listOf(
            run("2026.1", failed = listOf("SC-2", "SC-1")),
            run("2026.1", by = SECOND_HOLDER),
            run("2026.2"),
        ).also { certification.people.caller = CERTIFIER }

    @Test
    fun `INV-AC-29133 the pages together equal the recorded runs by value in the published order`() {
        withEnv { env ->
            val recorded = env.threeRuns()
            val before = env.written()
            val first = env.list(limit = 1)
            val second = env.list(limit = 1, cursor = first.nextCursor)
            val third = env.list(limit = 1, cursor = second.nextCursor)
            assertThat(listOf(first, second, third).map { it.items.size }).containsOnly(1)
            assertThat(first.nextCursor).startsWith("sc1.")
            assertThat(second.nextCursor).startsWith("sc1.")
            assertThat(third.nextCursor).isNull()
            val items = first.items + second.items + third.items
            val expected =
                recorded.zip(items.map { it.sequence }).map { (run, sequence) ->
                    SandboxCertificationRecord(
                        suiteVersion = run.suiteVersion,
                        outcome = SandboxCertificationRecord.Outcome.valueOf(run.outcome.name),
                        recordedBy = run.recordedBy,
                        recordedAt = OffsetDateTime.parse(run.recordedAt.toString()),
                        sequence = sequence,
                        failedScenarios = run.failedScenarios,
                    )
                }
            assertThat(items).isEqualTo(expected)
            assertThat(items.map { it.recordedBy }).containsExactly(CERTIFIER, SECOND_HOLDER, CERTIFIER)
            val stored =
                env.certification
                    .rows(env.entity)
                    .map { env.certification.payload(it) }
                    .map { it["seq"].asLong() }
            assertThat(items.map { it.sequence }).containsExactlyElementsOf(stored).isSorted()
            assertThat(env.list().items).isEqualTo(items)
            assertThat(env.list().nextCursor).isNull()
            // a last page that is exactly full carries no cursor
            assertThat(env.list(limit = 3).nextCursor).isNull()
            val rest = env.list(limit = 2, cursor = first.nextCursor)
            assertThat(rest.items).isEqualTo(items.drop(1))
            assertThat(rest.nextCursor).isNull()
            assertThat(env.written()).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-29133 the body omits failedScenarios for a PASSED run and nextCursor on the last page`() {
        withEnv { env ->
            env.threeRuns()
            val mapper = JsonMapper.builder().build()
            val body = mapper.readTree(mapper.writeValueAsString(env.list()))
            assertThat(body.has("nextCursor")).isFalse()
            val items = body["items"].values().toList()
            assertThat(items.map { it["outcome"].asString() }).containsExactly("FAILED", "PASSED", "PASSED")
            assertThat(items.map { it.has("failedScenarios") }).containsExactly(true, false, false)
            assertThat(items[0]["failedScenarios"].values().map { it.asString() }).containsExactly("SC-2", "SC-1")
            assertThat(mapper.readTree(mapper.writeValueAsString(env.list(limit = 1))).has("nextCursor")).isTrue()
        }
    }

    @Test
    fun `INV-AC-29133 an entity with no run answers an empty page`() {
        withEnv { env ->
            val page = env.list()
            assertThat(page.items).isEmpty()
            assertThat(page.nextCursor).isNull()
        }
    }

    @Test
    fun `INV-AC-29133 a non-holder gets 403 before the cursor and the limit, with no store read and no write`() {
        withEnv { env ->
            env.threeRuns()
            val cursor = env.list(limit = 1).nextCursor
            env.certification.actAs(CONFIG_ONLY, env.entity, "entity-config.manage")
            val before = env.written()
            val reads = env.reads.pages.get()
            listOf(
                { env.list() },
                { env.list(cursor = cursor) },
                { env.list(cursor = "sc1.x.y") },
                { env.list(limit = 0) },
                { env.list(entityRef = "NO-SUCH-ENTITY") },
            ).forEach { call -> assertThat(env.statusOf { call() }).isEqualTo(ClosedErrorCode.ACCESS_DENIED) }
            assertThat(env.certification.people.asked).contains(RECORD_KEY)
            assertThat(env.reads.pages.get()).isEqualTo(reads)
            assertThat(env.written()).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-29133 a limit outside 1 to 500 is 400 with no store read`() {
        withEnv { env ->
            env.threeRuns()
            val before = env.written()
            listOf(Int.MIN_VALUE, -1, 0, 501, Int.MAX_VALUE).forEach { limit ->
                assertThat(
                    env.statusOf { env.list(limit = limit) },
                ).describedAs("limit %s", limit).isEqualTo(BAD_REQUEST)
            }
            assertThat(env.reads.pages.get()).isZero()
            assertThat(env.list(limit = 1).items).hasSize(1)
            assertThat(env.list(limit = 500).items).hasSize(3)
            assertThat(env.reads.pages.get()).isEqualTo(2)
            assertThat(env.written()).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-29133 a tampered, foreign or expired cursor is 400 with no store read`() {
        withEnv { env ->
            env.threeRuns()
            val cursor = env.list(limit = 1).nextCursor!!
            val context = env.certification.context()
            val own = context.getBean(SandboxCertificationCursor::class.java)
            val ref = env.entity.entityRef
            val now = Instant.now()
            val position = CertificationHistoryPosition(now, 1L)
            val tag = cursor.substringAfterLast('.')
            val refusedCursors =
                mapOf(
                    "tampered" to cursor.removeSuffix(tag) + (if (tag.first() == 'A') 'B' else 'A') + tag.drop(1),
                    "empty" to "",
                    "of listPeriodRecords" to
                        context
                            .getBean(PeriodListCursor::class.java)
                            .issue(ref, CERTIFIER, PagePosition(LocalDate.parse("2026-01-01"), "P1"), now),
                    "of listRenditionAttestations" to
                        context
                            .getBean(RenditionAttestationCursor::class.java)
                            .issue(ref, CERTIFIER, RenditionHistoryPosition(now, 1L), now),
                    "of another entity" to own.issue("OTHER-ENTITY", CERTIFIER, position, now),
                    "of another user" to own.issue(ref, SECOND_HOLDER, position, now),
                    "expired" to own.issue(ref, CERTIFIER, position, now.minus(Duration.ofHours(25))),
                )
            val before = env.written()
            val reads = env.reads.pages.get()
            refusedCursors.forEach { (kind, token) ->
                assertThat(
                    env.statusOf { env.list(limit = 1, cursor = token) },
                ).describedAs(kind).isEqualTo(BAD_REQUEST)
            }
            assertThat(env.reads.pages.get()).isEqualTo(reads)
            assertThat(env.written()).isEqualTo(before)
            assertThat(env.statusOf { env.list(limit = 1, cursor = cursor) }).isNull()
        }
    }

    @Test
    fun `INV-AC-29133 a cursor of one holder is 400 for another holder`() {
        withEnv { env ->
            env.threeRuns()
            val cursor = env.list(limit = 1).nextCursor
            env.certification.actAs(SECOND_HOLDER, env.entity, RECORD_KEY)
            assertThat(env.statusOf { env.list(limit = 1, cursor = cursor) }).isEqualTo(BAD_REQUEST)
            assertThat(env.statusOf { env.list(limit = 1) }).isNull()
        }
    }

    @Test
    fun `INV-AC-29133 an unknown entity is 404`() {
        withEnv { env ->
            env.certification.people.grantPermission(CERTIFIER, "NO-SUCH-ENTITY", RECORD_KEY)
            val before = env.written()
            assertThat(env.statusOf { env.list(entityRef = "NO-SUCH-ENTITY") }).isEqualTo(ClosedErrorCode.NOT_FOUND)
            assertThat(env.reads.pages.get()).isZero()
            assertThat(env.written()).isEqualTo(before)
        }
    }
}
