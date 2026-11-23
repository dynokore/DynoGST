package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolation
import jakarta.validation.constraints.Size
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.server.PathContainer
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Answers a bean-validation failure once for every service on service-runtime (INV-T-27821, INV-AC-27818; RFC 9110
 * section 15.5.14, RFC 9457, ARC-19, OWASP ASVS V5). 413 SRT-014 only when (1) the request's method and path are in the
 * generated list `META-INF/gst/request-too-large-operations.tsv`, (2) there is at least one violation and (3) every
 * violation is a `Size` violation on a `Collection` whose size is above the constraint's `max` (read from the unwrapped
 * [ConstraintViolation]). Every other validation failure is 400 SRT-001. The detail is fixed text; nothing the caller
 * sent is echoed. Fail closed: no list resource gives the empty set, so never 413.
 *
 * Lowest precedence: a service's own advice for the same exception wins.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class RequestTooLargeAdvice private constructor(
    private val operations: List<Pair<String, PathPattern>>,
) {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun notValid(
        e: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<String> =
        answer(
            request,
            e.bindingResult.allErrors.map { error ->
                violationOf { error.unwrap(it) }
            },
        )

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun methodNotValid(
        e: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val violations =
            e.parameterValidationResults.flatMap { result ->
                result.resolvableErrors.map { error -> violationOf { result.unwrap(error, it) } }
            } + e.crossParameterValidationResults.map { null } // a cross-parameter error is never a Size violation
        return answer(request, violations)
    }

    /** The unwrapped violation, or null when the error is not a bean-validation violation. */
    private fun violationOf(
        unwrap: (Class<ConstraintViolation<*>>) -> ConstraintViolation<*>,
    ): ConstraintViolation<*>? =
        try {
            unwrap(ConstraintViolation::class.java)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun answer(
        request: HttpServletRequest,
        violations: List<ConstraintViolation<*>?>,
    ): ResponseEntity<String> {
        val correlationId = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val tooLarge =
            listed(request) && violations.isNotEmpty() && violations.all { it != null && oversizedCollection(it) }
        val response =
            if (tooLarge) {
                Problems.closed(ClosedErrorCode.REQUEST_TOO_LARGE, correlationId, TOO_LARGE_DETAIL)
            } else {
                Problems.contractViolation(INVALID_DETAIL, correlationId)
            }
        return ResponseEntity
            .status(response.status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(response.body)
    }

    private fun oversizedCollection(violation: ConstraintViolation<*>): Boolean {
        val size = violation.constraintDescriptor.annotation as? Size ?: return false
        val value = violation.invalidValue as? Collection<*> ?: return false
        return value.size > size.max
    }

    private fun listed(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
        val container = PathContainer.parsePath(path)
        return operations.any { (method, template) -> method == request.method && template.matches(container) }
    }

    companion object {
        const val RESOURCE = "META-INF/gst/request-too-large-operations.tsv"
        private const val HEADER = "operationId\tmethod\tpath"
        private const val TOO_LARGE_DETAIL = "the request holds more items than the contract allows"
        private const val INVALID_DETAIL = "the request does not match the contract"

        /** Every classpath resource of the exact name, merged; a malformed line stops the start-up; none gives the empty set. */
        fun from(loader: ClassLoader): RequestTooLargeAdvice {
            val parser = PathPatternParser()
            val rows =
                loader.getResources(RESOURCE).toList().flatMap { url ->
                    url
                        .readText()
                        .removeSuffix("\n")
                        .takeIf { it.isNotEmpty() }
                        ?.split('\n')
                        .orEmpty()
                        .filter { it != HEADER }
                        .map { line -> parse(url, line, parser) }
                }
            return RequestTooLargeAdvice(rows)
        }

        private fun parse(
            url: URL,
            line: String,
            parser: PathPatternParser,
        ): Pair<String, PathPattern> {
            val cells = line.split('\t')
            check(cells.size == 3 && cells.all { it.isNotEmpty() }) {
                "$RESOURCE $url: a line needs exactly three non-empty TAB-separated cells"
            }
            check(cells[2].startsWith("/")) { "$RESOURCE $url: the path must start with /" }
            return cells[1] to parser.parse(cells[2])
        }

        private fun URL.readText(): String = openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }
}
