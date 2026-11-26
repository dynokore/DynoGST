package com.dynokore.gst.consoleBff.adapters.clients

import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.DefaultUriBuilderFactory
import org.springframework.web.util.UriBuilder
import org.springframework.web.util.UriBuilderFactory
import java.net.URI

/**
 * The one way a [RestClient] for a generated owner client is built when its calls may send a query (RFC 3986; data is
 * never read as a URI template). The generated transport gives the query values to [UriBuilder.queryParams] and then
 * calls `build` with the path variables, so a value is template text there: a `+` stays a `+` (the owner reads a
 * space) and `{name}` is expanded. Here every value of every query parameter is replaced by a generated URI variable
 * and bound to it. [DefaultUriBuilderFactory] encodes a variable value strictly, so it reaches the owner
 * percent-encoded and unchanged, opaque cursors included (API-18). Paths and path variables are not changed.
 */
internal object GeneratedQueryValues {
    /** A copy of [builder] with its interceptors; the given builder is not changed. */
    fun restClient(
        builder: RestClient.Builder,
        baseUrl: String,
    ): RestClient =
        builder
            .clone()
            .uriBuilderFactory(BindingUriBuilderFactory(DefaultUriBuilderFactory(baseUrl)))
            .build()

    private class BindingUriBuilderFactory(
        private val delegate: UriBuilderFactory,
    ) : UriBuilderFactory by delegate {
        override fun uriString(uriTemplate: String): UriBuilder = BindingUriBuilder(delegate.uriString(uriTemplate))
    }

    /** For one URI. The generated transport calls `queryParams`, then `build` on what it returns. */
    private class BindingUriBuilder(
        private val delegate: UriBuilder,
    ) : UriBuilder by delegate {
        private val bound = mutableMapOf<String, String>()

        override fun queryParams(params: MultiValueMap<String, String>): UriBuilder {
            val placeholders = LinkedMultiValueMap<String, String>()
            params.forEach { (name, values) ->
                placeholders[name] =
                    values.map { value ->
                        val variable = "$VARIABLE_PREFIX${bound.size}"
                        bound[variable] = value
                        "{$variable}"
                    }
            }
            delegate.queryParams(placeholders)
            return this
        }

        override fun build(uriVariables: Map<String, *>): URI {
            // fail closed: a path variable and a query value never share a name, so neither takes the other's place
            require(bound.keys.none { it in uriVariables }) { "a path variable uses a reserved query variable name" }
            return delegate.build(uriVariables + bound)
        }
    }

    private const val VARIABLE_PREFIX = "gstQueryValue"
}
