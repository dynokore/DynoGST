package com.dynokore.gst.notification.adapters.delivery

/**
 * Configuration of the SMTP relay adapter of the EMAIL channel (INV-REQ-817). Every key has no default: a missing or
 * malformed key is a start failure naming the key (fail closed). The timeouts and the table size are numbers named by
 * configuration, not by code.
 */
data class SmtpDeliveryProperties(
    val host: String,
    val port: Int,
    val fromAddress: String,
    val credentialSecretRef: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val writeTimeoutMs: Int,
    val statusTableSize: Int,
) {
    init {
        require(host.isNotBlank()) { "$HOST must not be blank" }
        require(port in 1..MAX_PORT) { "$PORT must be a port number" }
        require(fromAddress.isNotBlank()) { "$FROM_ADDRESS must not be blank" }
        require(credentialSecretRef.isNotBlank()) { "$CREDENTIAL_SECRET_REF must not be blank" }
        require(connectTimeoutMs > 0) { "$CONNECT_TIMEOUT_MS must be positive" }
        require(readTimeoutMs > 0) { "$READ_TIMEOUT_MS must be positive" }
        require(writeTimeoutMs > 0) { "$WRITE_TIMEOUT_MS must be positive" }
        require(statusTableSize > 0) { "$STATUS_TABLE_SIZE must be positive" }
    }

    companion object {
        private const val MAX_PORT = 65535

        const val HOST = "gst.message-delivery.email.smtp.host"
        const val PORT = "gst.message-delivery.email.smtp.port"
        const val FROM_ADDRESS = "gst.message-delivery.email.smtp.from-address"
        const val CREDENTIAL_SECRET_REF = "gst.message-delivery.email.smtp.credential-secret-ref"
        const val CONNECT_TIMEOUT_MS = "gst.message-delivery.email.smtp.connect-timeout-ms"
        const val READ_TIMEOUT_MS = "gst.message-delivery.email.smtp.read-timeout-ms"
        const val WRITE_TIMEOUT_MS = "gst.message-delivery.email.smtp.write-timeout-ms"
        const val STATUS_TABLE_SIZE = "gst.message-delivery.email.smtp.status-table-size"

        /** Reads every key through [lookup]; a missing or non-numeric key throws IllegalStateException naming it. */
        fun from(lookup: (String) -> String?): SmtpDeliveryProperties {
            fun text(key: String): String = checkNotNull(lookup(key)) { "missing configuration key $key" }

            fun number(key: String): Int =
                checkNotNull(text(key).trim().toIntOrNull()) { "configuration key $key must be a number" }
            return SmtpDeliveryProperties(
                host = text(HOST),
                port = number(PORT),
                fromAddress = text(FROM_ADDRESS),
                credentialSecretRef = text(CREDENTIAL_SECRET_REF),
                connectTimeoutMs = number(CONNECT_TIMEOUT_MS),
                readTimeoutMs = number(READ_TIMEOUT_MS),
                writeTimeoutMs = number(WRITE_TIMEOUT_MS),
                statusTableSize = number(STATUS_TABLE_SIZE),
            )
        }
    }
}
