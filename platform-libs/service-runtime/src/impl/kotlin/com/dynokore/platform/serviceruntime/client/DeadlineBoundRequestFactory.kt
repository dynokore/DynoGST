package com.dynokore.platform.serviceruntime.client

import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

/**
 * The `ClientHttpRequestFactory` of the internal `RestClient` (INV-AC-28249, INV-REQ-1203): a request created while a
 * deadline is bound ([CurrentDeadline]) gets a read timeout no longer than what that deadline has left, so the request
 * itself ends at the bound instead of running on after the caller gave up. A request created with no deadline bound
 * gets no timeout of its own.
 *
 * Mechanism (Spring Framework 7.0.x): [JdkClientHttpRequestFactory.setReadTimeout] becomes the JDK
 * `HttpRequest.Builder.timeout` of every request that factory creates, and Spring offers no per-request override on a
 * shared factory; so this factory creates one cheap [JdkClientHttpRequestFactory] per request over one shared
 * [HttpClient], with the timeout fixed at that moment. The clock is supplied by the caller (CS-10).
 */
class DeadlineBoundRequestFactory(
    private val clock: Clock,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : ClientHttpRequestFactory {
    override fun createRequest(
        uri: URI,
        httpMethod: HttpMethod,
    ): ClientHttpRequest {
        val factory = JdkClientHttpRequestFactory(httpClient)
        CurrentDeadline.current()?.let { deadline ->
            // A zero timeout is illegal for the JDK; an exhausted deadline gets the shortest legal one.
            factory.setReadTimeout(maxOf(deadline.remaining(clock), MIN_TIMEOUT))
        }
        return factory.createRequest(uri, httpMethod)
    }

    private companion object {
        val MIN_TIMEOUT: Duration = Duration.ofMillis(1)
    }
}
