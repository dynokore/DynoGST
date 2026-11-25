package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.SubmissionScopeController
import com.dynokore.gst.tenancy.application.period.GetSubmissionScope
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.SubmissionScopePermissions
import com.dynokore.gst.tenancy.contract.api.SubmissionScopeApi
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.SubmissionScopeResponse
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * getSubmissionScope over PostgreSQL with the real application context (INV-REQ-1086, INV-AC-27012): the caller is
 * decided by the permission key `transaction.ingest` through the replica double, never by a role name.
 */
class SubmissionScopeApiTest {
    private val key = SubmissionScopePermissions.READ

    /** The controller with the clock fixed at [instant] (UTC), the way `PeriodsController` is built in its tests. */
    private fun PeriodEnv.controllerAt(instant: String): SubmissionScopeController {
        val clock =
            DefaultListableBeanFactory().apply {
                registerSingleton("clock", Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
            }
        val c = env.context
        return SubmissionScopeController(
            c.getBean(GetSubmissionScope::class.java),
            c.getBean(PeriodAccess::class.java),
            clock.getBeanProvider(Clock::class.java),
            c.getBean(TransactionRunner::class.java),
        )
    }

    private fun PeriodEnv.setGoLive(
        entity: EntityResponse,
        date: String,
    ) {
        val id = entityId(entity)
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("update tenancy.entity set go_live_date = ? where id = ?").use {
                it.setObject(1, LocalDate.parse(date))
                it.setObject(2, id)
                check(it.executeUpdate() == 1)
            }
        }
    }

    private fun codeOf(block: () -> Unit): ClosedErrorCode? =
        try {
            block()
            null
        } catch (refusal: ApiRefusal) {
            refusal.code
        }

    /** Two stored quarters and a go-live date; the answer is asked at [instant]. */
    private fun PeriodEnv.answerAt(
        entity: EntityResponse,
        instant: String,
    ): SubmissionScopeResponse = controllerAt(instant).getSubmissionScope(entity.entityRef).body!!

    private fun runScoped(block: (PeriodEnv, EntityResponse) -> Unit) {
        runPeriods { period, entity ->
            period.insertRecord(entity, record("2026Q3", "2026-07-01", "2026-09-30", "2026-10-31"))
            period.insertRecord(entity, record("2026Q4", "2026-10-01", "2026-12-31", "2027-01-31"))
            period.setGoLive(entity, "2026-10-05")
            period.actAs(entity, "integration.engineer")
            block(period, entity)
        }
    }

    @Test
    fun `INV-AC-27012 the answer carries the stored go-live date`() {
        runScoped { period, entity ->
            val body = period.answerAt(entity, "2026-11-20T00:00:00Z")
            assertThat(body.entityRef).isEqualTo(entity.entityRef)
            assertThat(body.submissionActivatedOn).isEqualTo(LocalDate.parse("2026-10-05"))
        }
    }

    @Test
    fun `INV-AC-27012 the answer carries the current period start and end`() {
        runScoped { period, entity ->
            // the last day is inside the period; 16:30 UTC is already the next Singapore day (the calendar zone)
            val last = period.answerAt(entity, "2026-12-31T10:00:00Z").currentPeriod!!
            assertThat(last.periodStart).isEqualTo(LocalDate.parse("2026-10-01"))
            assertThat(last.periodEnd).isEqualTo(LocalDate.parse("2026-12-31"))
            assertThat(period.answerAt(entity, "2026-12-31T16:30:00Z").currentPeriod).isNull()
        }
    }

    @Test
    fun `INV-AC-27012 an entity with no go-live date and no current period answers neither member`() {
        runPeriods { period, entity ->
            period.actAs(entity, "integration.engineer")
            // the controller bean of the real context (the wiring)
            val api = period.env.context.getBean(SubmissionScopeApi::class.java)
            val response = api.getSubmissionScope(entity.entityRef)
            assertThat(response.statusCode.value()).isEqualTo(200)
            assertThat(response.body!!.submissionActivatedOn).isNull()
            assertThat(response.body!!.currentPeriod).isNull()
        }
    }

    @Test
    fun `INV-AC-27012 an unknown entity is 404`() {
        runPeriods { period, entity ->
            period.actAs(entity, "integration.engineer")
            period.people.grant(PeriodEnv.PERIOD_CALLER, "ENT-UNKNOWN", "integration.engineer")
            val code = codeOf { period.controllerAt("2026-11-20T00:00:00Z").getSubmissionScope("ENT-UNKNOWN") }
            assertThat(code).isEqualTo(ClosedErrorCode.NOT_FOUND)
        }
    }

    @Test
    fun `INV-AC-27012 a caller without the permission is 403`() {
        runPeriods { period, entity ->
            period.setGoLive(entity, "2026-10-05")
            val controller = period.controllerAt("2026-11-20T00:00:00Z")
            // the key is asked and decided; roles that the catalogue does not permit for it are refused
            listOf("entity.admin", "tenant.admin", "tax.operator", "tax.approver").forEach { role ->
                period.actAs(entity, role)
                assertThat(codeOf { controller.getSubmissionScope(entity.entityRef) })
                    .describedAs(role)
                    .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            }
            period.actAs(entity)
            assertThat(codeOf { controller.getSubmissionScope(entity.entityRef) })
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(period.people.asked).contains(key)
            period.actAs(entity, "integration.engineer")
            assertThat(controller.getSubmissionScope(entity.entityRef).statusCode.value()).isEqualTo(200)
        }
    }
}
