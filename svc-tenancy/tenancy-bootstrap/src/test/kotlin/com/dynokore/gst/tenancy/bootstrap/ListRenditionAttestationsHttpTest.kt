package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.PackDeltaCursor
import com.dynokore.gst.tenancy.adapters.http.PeriodListCursor
import com.dynokore.gst.tenancy.adapters.http.PeriodsController
import com.dynokore.gst.tenancy.adapters.http.RenditionAttestationCursor
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.ListPeriodRecords
import com.dynokore.gst.tenancy.application.period.PackDeltaPreview
import com.dynokore.gst.tenancy.application.period.PagePosition
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.RecordExtension
import com.dynokore.gst.tenancy.application.rendition.ListRenditionAttestations
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestationHistory
import com.dynokore.gst.tenancy.application.rendition.RenditionEntities
import com.dynokore.gst.tenancy.application.rendition.RenditionHistoryPage
import com.dynokore.gst.tenancy.application.rendition.RenditionHistoryPosition
import com.dynokore.gst.tenancy.application.rendition.RenditionPermissions
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.RenditionAttestationRecordList
import com.dynokore.gst.tenancy.contract.model.RenditionResponsibilityRequest
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val USER = "identity:USR-L1"
private val INVALID = ClosedErrorCode.CONTRACT_VIOLATION

/**
 * listRenditionAttestations at the service boundary (INV-T-27611, INV-REQ-1036): the page of the contract, the key
 * decided per request before any read, the limit and cursor refusals, the unknown entity and the two-page walk.
 */
class ListRenditionAttestationsHttpTest {
    private class Counting(
        private val inner: RenditionAttestationHistory,
    ) : RenditionAttestationHistory by inner {
        val pages = AtomicInteger()

        override fun page(
            entityRef: String,
            limit: Int,
            after: RenditionHistoryPosition?,
        ): RenditionHistoryPage {
            pages.incrementAndGet()
            return inner.page(entityRef, limit, after)
        }
    }

    private inner class Env(
        val e: ProfileEnv,
        val entity: EntityResponse,
    ) {
        val people: TestPeople = e.context.getBean(TestPeople::class.java)
        val history = Counting(e.context.getBean(RenditionAttestationHistory::class.java))
        val api: PeriodsController =
            e.context.let { c ->
                val clock = DefaultListableBeanFactory().apply { registerSingleton("clock", Clock.systemUTC()) }
                PeriodsController(
                    c.getBean(ConfirmPeriods::class.java),
                    c.getBean(RecordExtension::class.java),
                    c.getBean(ListPeriodRecords::class.java),
                    c.getBean(PeriodListCursor::class.java),
                    c.getBean(CallerSource::class.java),
                    clock.getBeanProvider(Clock::class.java),
                    c.getBean(PeriodAccess::class.java),
                    c.getBean(PackDeltaPreview::class.java),
                    c.getBean(PackDeltaCursor::class.java),
                    ListRenditionAttestations(
                        c.getBean(TransactionRunner::class.java),
                        c.getBean(PeriodAccess::class.java),
                        c.getBean(RenditionEntities::class.java),
                        history,
                    ),
                    c.getBean(RenditionAttestationCursor::class.java),
                )
            }

        fun record(
            documentClass: String,
            renderer: RenditionResponsibilityRequest.Renderer = RenditionResponsibilityRequest.Renderer.SELF,
        ) {
            e.api.recordRenditionResponsibility(
                entity.entityRef,
                UUID.randomUUID(),
                RenditionResponsibilityRequest(documentClass, renderer, USER),
            )
        }

        fun list(
            limit: Int = 100,
            cursor: String? = null,
            entityRef: String = entity.entityRef,
        ): RenditionAttestationRecordList = api.listRenditionAttestations(entityRef, limit, cursor).body!!

        fun statusOf(block: () -> Unit): ClosedErrorCode? =
            try {
                block()
                null
            } catch (refusal: ApiRefusal) {
                refusal.code
            }
    }

    private fun run(
        holder: Boolean = true,
        block: (Env) -> Unit,
    ) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val profile = ProfileEnv(context)
            profile.withEntity { _, entity ->
                val env = Env(profile, entity)
                env.people.caller = USER
                env.people.grantRole(USER, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
                if (holder) env.people.grantPermission(USER, entity.entityRef, RenditionPermissions.EVIDENCE_VIEW)
                block(env)
            }
        }
    }

    @Test
    fun `INV-AC-27601 a holder gets the page of the contract with the stored user ID and the history sequence`() {
        run { env ->
            env.record("TYPE_1A", RenditionResponsibilityRequest.Renderer.SELF)
            env.record("TYPE_1B", RenditionResponsibilityRequest.Renderer.AGENT)
            val page = env.list()
            assertThat(page.nextCursor).isNull()
            assertThat(page.items.map { it.documentClass }).containsExactly("TYPE_1A", "TYPE_1B")
            assertThat(page.items.map { it.renderer.value }).containsExactly("SELF", "AGENT")
            assertThat(page.items.map { it.attestedBy }).containsOnly(USER)
            assertThat(page.items.map { it.sequence }).isSorted().doesNotHaveDuplicates().allMatch { it >= 1 }
            assertThat(page.items.map { it.attestedAt }).isSorted()
            assertThat(env.people.asked).contains(RenditionPermissions.EVIDENCE_VIEW)
        }
    }

    @Test
    fun `INV-AC-27601 the sequence is the history row number and not the index of the page`() {
        run { env ->
            env.record("TYPE_1A")
            env.record("TYPE_1A", RenditionResponsibilityRequest.Renderer.AGENT)
            env.record("TYPE_1B")
            val first = env.list(limit = 1)
            val second = env.list(limit = 2, cursor = first.nextCursor)
            val rows = first.items + second.items
            // the history sequence is unique within the entity and ascends with the order of the page
            assertThat(rows.map { it.sequence }).isSorted().doesNotHaveDuplicates()
            assertThat(env.list().items.map { it.sequence }).isEqualTo(rows.map { it.sequence })
            assertThat(rows.map { it.sequence }).isNotEqualTo(listOf(0L, 1L, 2L))
        }
    }

    @Test
    fun `INV-AC-27601 the record of a replaced attestation and the replacement are both listed`() {
        run { env ->
            env.record("TYPE_1A", RenditionResponsibilityRequest.Renderer.SELF)
            env.record("TYPE_1A", RenditionResponsibilityRequest.Renderer.AGGREGATOR)
            val items = env.list().items.filter { it.documentClass == "TYPE_1A" }
            assertThat(items.map { it.renderer.value }).contains("SELF", "AGGREGATOR")
            assertThat(items.map { it.sequence }).doesNotHaveDuplicates()
        }
    }

    @Test
    fun `INV-AC-27601 the order is attestedAt then sequence, not the sequence alone`() {
        run { env ->
            env.record("TYPE_1A")
            // a later row (higher seq) that carries an earlier attestedAt must come first
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "insert into tenancy.rendition_attestation_history " +
                            "(tenant_id, entity_id, document_class, kind, renderer, attested_by, attested_at, state) " +
                            "select h.tenant_id, h.entity_id, 'TYPE_1B', 'ATTESTED', 'SELF', h.attested_by, " +
                            "h.attested_at - interval '1 day', 'CURRENT' " +
                            "from tenancy.rendition_attestation_history h join tenancy.entity e " +
                            "on e.tenant_id = h.tenant_id and e.id = h.entity_id where e.ref = ? limit 1",
                    ).use {
                        it.setString(1, env.entity.entityRef)
                        check(it.executeUpdate() == 1) { "no row inserted" }
                    }
            }
            val all = env.list().items
            assertThat(all.map { it.documentClass }).containsExactly("TYPE_1B", "TYPE_1A")
            assertThat(all.map { it.sequence }).isNotEqualTo(all.map { it.sequence }.sorted())
            val first = env.list(limit = 1)
            assertThat(first.items.single().documentClass).isEqualTo("TYPE_1B")
            val second = env.list(limit = 1, cursor = first.nextCursor)
            assertThat(second.items.single().documentClass).isEqualTo("TYPE_1A")
            assertThat(second.nextCursor).isNull()
        }
    }

    @Test
    fun `INV-AC-27601 the second page has no nextCursor and the pages together list every row once`() {
        run { env ->
            env.record("TYPE_1A")
            env.record("TYPE_1B")
            env.record("TYPE_2")
            val first = env.list(limit = 2)
            assertThat(first.items).hasSize(2)
            assertThat(first.nextCursor).isNotBlank()
            val second = env.list(limit = 2, cursor = first.nextCursor)
            assertThat(second.items).hasSize(1)
            assertThat(second.nextCursor).isNull()
            assertThat((first.items + second.items).map { it.sequence }).doesNotHaveDuplicates()
            assertThat((first.items + second.items).map { it.sequence }).isEqualTo(env.list().items.map { it.sequence })
        }
    }

    @Test
    fun `INV-AC-27601 a non-holder gets 403 and the store is not read`() {
        run(holder = false) { env ->
            env.record("TYPE_1A")
            assertThat(env.statusOf { env.list() }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.statusOf { env.list(cursor = "ra1.x.y") }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.statusOf { env.list(limit = 0) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.history.pages.get()).isZero()
        }
    }

    @Test
    fun `INV-AC-27601 limit 0 and limit 501 are 400 and no store read`() {
        run { env ->
            assertThat(env.statusOf { env.list(limit = 0) }).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(env.statusOf { env.list(limit = 501) }).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(env.history.pages.get()).isZero()
            assertThat(env.statusOf { env.list(limit = 500) }).isNull()
            assertThat(env.statusOf { env.list(limit = 1) }).isNull()
        }
    }

    @Test
    fun `INV-AC-27601 a tampered cursor and a cursor of listPeriodRecords are 400`() {
        run { env ->
            env.record("TYPE_1A")
            env.record("TYPE_1B")
            val cursor = env.list(limit = 1).nextCursor!!
            val flipped = cursor.dropLast(1) + (if (cursor.last() == 'A') 'B' else 'A')
            assertThat(env.statusOf { env.list(limit = 1, cursor = flipped) }).isEqualTo(INVALID)
            assertThat(env.statusOf { env.list(limit = 1, cursor = "ra1.AAAA.AAAA") }).isEqualTo(INVALID)
            val foreign =
                env.e.context
                    .getBean(PeriodListCursor::class.java)
                    .issue(env.entity.entityRef, USER, PagePosition(LocalDate.parse("2026-01-01"), "P1"), Instant.now())
            assertThat(env.statusOf { env.list(limit = 1, cursor = foreign) }).isEqualTo(INVALID)
            assertThat(env.statusOf { env.list(limit = 1, cursor = cursor) }).isNull()
        }
    }

    @Test
    fun `INV-AC-27601 an unknown entity is 404`() {
        run { env ->
            env.people.grantPermission(USER, "NO-SUCH-ENTITY", RenditionPermissions.EVIDENCE_VIEW)
            assertThat(env.statusOf { env.list(entityRef = "NO-SUCH-ENTITY") }).isEqualTo(ClosedErrorCode.NOT_FOUND)
        }
    }
}
