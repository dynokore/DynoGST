package com.dynokore.gst.notification.adapters.delivery

import java.net.URI

/**
 * Configuration of the HTTPS mail API adapter of the EMAIL channel (INV-REQ-817). Every key has no default: a missing
 * or malformed key is a start failure naming the key and never its value (fail closed). The timeouts and the table
 * size are numbers named by configuration, not by code. The endpoint must be an https URL.
 */
data class HttpsMailApiDeliveryProperties(
    val endpointUrl: String,
    val fromAddress: String,
    val credentialSecretRef: String,
    val connectTimeoutMs: Int,
    val requestTimeoutMs: Int,
    val statusTableSize: Int,
) {
    init {
        require(isHttps(endpointUrl)) { "$ENDPOINT_URL must be an https URL" }
        require(fromAddress.isNotBlank()) { "$FROM_ADDRESS must not be blank" }
        require(credentialSecretRef.isNotBlank()) { "$CREDENTIAL_SECRET_REF must not be blank" }
        require(connectTimeoutMs > 0) { "$CONNECT_TIMEOUT_MS must be positive" }
        require(requestTimeoutMs > 0) { "$REQUEST_TIMEOUT_MS must be positive" }
        require(statusTableSize > 0) { "$STATUS_TABLE_SIZE must be positive" }
    }

    /** The endpoint as a URI; valid by construction. */
    val endpoint: URI get() = URI.create(endpointUrl)

    companion object {
        const val ENDPOINT_URL = "gst.message-delivery.email.api.endpoint-url"
        const val FROM_ADDRESS = "gst.message-delivery.email.api.from-address"
        const val CREDENTIAL_SECRET_REF = "gst.message-delivery.email.api.credential-secret-ref"
        const val CONNECT_TIMEOUT_MS = "gst.message-delivery.email.api.connect-timeout-ms"
        const val REQUEST_TIMEOUT_MS = "gst.message-delivery.email.api.request-timeout-ms"
        const val STATUS_TABLE_SIZE = "gst.message-delivery.email.api.status-table-size"

        private fun isHttps(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull()
            return uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank()
        }

        /** Reads every key through [lookup]; a missing or non-numeric key throws IllegalStateException naming it. */
        fun from(lookup: (String) -> String?): HttpsMailApiDeliveryProperties {
            fun text(key: String): String = checkNotNull(lookup(key)) { "missing configuration key $key" }

            fun number(key: String): Int =
                checkNotNull(text(key).trim().toIntOrNull()) { "configuration key $key must be a number" }
            return HttpsMailApiDeliveryProperties(
                endpointUrl = text(ENDPOINT_URL),
                fromAddress = text(FROM_ADDRESS),
                credentialSecretRef = text(CREDENTIAL_SECRET_REF),
                connectTimeoutMs = number(CONNECT_TIMEOUT_MS),
                requestTimeoutMs = number(REQUEST_TIMEOUT_MS),
                statusTableSize = number(STATUS_TABLE_SIZE),
            )
        }
    }
}
