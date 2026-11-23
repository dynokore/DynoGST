package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.portfolio.GetPortfolio
import com.dynokore.gst.consoleBff.application.portfolio.PortfolioQuery
import com.dynokore.gst.consoleBff.application.portfolio.PortfolioResult
import com.dynokore.gst.consoleBff.contract.api.PortfolioApi
import com.dynokore.gst.consoleBff.contract.model.PortfolioCell
import com.dynokore.gst.consoleBff.contract.model.PortfolioPage
import com.dynokore.gst.consoleBff.contract.model.PortfolioReadiness
import com.dynokore.gst.consoleBff.contract.model.PortfolioRow
import com.dynokore.gst.consoleBff.domain.portfolio.Cell
import com.dynokore.gst.consoleBff.domain.portfolio.PeriodState
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.util.UUID

/** A refusal carrying its closed problem answer; [PortfolioController] writes it. */
class PortfolioRefusal(
    val answer: StoredResponse,
) : RuntimeException(null, null, false, false)

/**
 * GET /v1/portfolio (INV-REQ-904): the page of [GetPortfolio] served from the read model, no downstream call. The route
 * is refused without `period-readiness.view` by the read filter; the entity scope is decided in the use case per
 * request (INV-Q-10027). A cell holds the stored period state; only a due cell carries a readiness object; there is no
 * score member in any contract type.
 */
@RestController
open class PortfolioController(
    private val getPortfolio: GetPortfolio,
) : PortfolioApi {
    override fun getPortfolio(
        limit: Int,
        cursor: String?,
    ): ResponseEntity<PortfolioPage> {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val principal = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenant = request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String
        if (principal == null || tenant == null) refuse(Problems.unauthenticated(correlation()))
        return when (val result = getPortfolio.list(PortfolioQuery(tenant, principal, limit, cursor))) {
            is PortfolioResult.Listed -> {
                ResponseEntity.ok(PortfolioPage(result.rows.map(::toRow), result.nextCursor))
            }

            PortfolioResult.BadCursor -> {
                refuse(Problems.contractViolation("cursor is not valid", correlation()))
            }

            PortfolioResult.BadLimit -> {
                refuse(Problems.contractViolation("limit is out of bounds", correlation()))
            }
        }
    }

    @ExceptionHandler(PortfolioRefusal::class)
    fun refusal(refusal: PortfolioRefusal): ResponseEntity<String> = write(refusal.answer)

    /** A parameter the contract bounds (limit above its maximum) fails the generated validation: a contract violation. */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParameter(): ResponseEntity<String> =
        write(Problems.contractViolation("a request parameter is out of bounds", correlation()))

    /** jakarta's ConstraintViolationException is matched by class name (no compile dependency on jakarta.validation). */
    @ExceptionHandler(RuntimeException::class)
    fun constraintViolation(failure: RuntimeException): ResponseEntity<String> {
        if (failure.javaClass.name != CONSTRAINT_VIOLATION) throw failure
        return invalidParameter()
    }

    private fun write(answer: StoredResponse): ResponseEntity<String> =
        ResponseEntity
            .status(answer.status)
            .header("Content-Type", Problems.CONTENT_TYPE)
            .body(answer.body)

    private fun toRow(row: com.dynokore.gst.consoleBff.domain.portfolio.PortfolioRow) =
        PortfolioRow(row.entityRef, row.cells.map { (periodRef, cell) -> toCell(periodRef, cell) })

    private fun toCell(
        periodRef: String,
        cell: Cell,
    ) = PortfolioCell(
        periodRef = periodRef,
        periodState = PortfolioCell.PeriodState.forValue(cell.state.wire()),
        extensionGranted = cell.extensionGranted,
        filingDate = cell.filingDate,
        readiness =
            cell.verdict?.let {
                PortfolioReadiness(
                    PortfolioReadiness.Kind.forValue(it.name),
                    cell.blockedCount,
                )
            },
    )

    private fun PeriodState.wire(): String =
        when (this) {
            PeriodState.OPEN -> "open"
            PeriodState.DUE -> "due"
            PeriodState.FILED -> "filed"
            PeriodState.LATE -> "late"
        }

    private fun refuse(answer: StoredResponse): Nothing = throw PortfolioRefusal(answer)

    private fun correlation(): String = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"

    private companion object {
        const val CONSTRAINT_VIOLATION = "jakarta.validation.ConstraintViolationException"
    }
}
