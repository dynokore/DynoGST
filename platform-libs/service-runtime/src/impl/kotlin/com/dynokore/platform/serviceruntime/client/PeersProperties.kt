package com.dynokore.platform.serviceruntime.client

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The addresses of the peer services a service calls (twelve-factor config: one key per backing-service address).
 * [accessSvc] is the base URL of access-svc, read by the flow-context issuer adapter and by product-runtime's
 * access-svc fallback; `service-runtime.peers.access-svc.base-url`.
 */
@ConfigurationProperties(prefix = "service-runtime.peers")
data class PeersProperties(
    val accessSvc: Peer = Peer(),
) {
    /** One peer: its [baseUrl], absent when the service does not call it. */
    data class Peer(
        val baseUrl: String? = null,
    )

    companion object {
        /** The property key of access-svc's base URL. */
        const val ACCESS_SVC_BASE_URL = "service-runtime.peers.access-svc.base-url"
    }
}
