package com.dynokore.gst.consoleBff.adapters.authz

import com.dynokore.gst.consoleBff.application.authz.ReadPermissions

/**
 * The permission-key source of the read authorisation filter: one table of the reads console-bff serves, each with the
 * `x-operation.permission` of its operation in the console-bff contract (the key the generated operation-to-roles map
 * is asked about). No role name is held (INV-Q-10027). Method and path template are compared by equality only; any
 * other route is unmapped (null), so the filter refuses it (OWASP ASVS V4.1.1, deny by default).
 */
class CatalogueReadPermissions(
    private val domainEntries: List<DomainEntry> = DOMAIN_ENTRIES,
) : ReadPermissions {
    /** One served read: the route, the contract operation id and its permission key. */
    data class Entry(
        val method: String,
        val pathTemplate: String,
        val operationId: String,
        val permissionKey: String,
        /** The query parameter naming the entity the read is about; absent when the read names no entity. */
        val entityParameter: String? = null,
    )

    /**
     * One served read whose path template may hold `{name}` segments and whose permission key is chosen by the value
     * of one query parameter ([domainParameter]) through [keysByDomain] (exact, case-sensitive equality).
     */
    data class DomainEntry(
        val method: String,
        val pathTemplate: String,
        val operationId: String,
        val domainParameter: String,
        val keysByDomain: Map<String, String>,
    )

    override fun keyFor(
        method: String,
        pathTemplate: String,
    ): String? = entryOf(method, pathTemplate)?.permissionKey

    override fun keyFor(
        method: String,
        path: String,
        queryValue: (String) -> String?,
    ): String? =
        entryOf(method, path)?.permissionKey
            ?: domainEntries
                .firstOrNull { it.method == method && matches(it.pathTemplate, path) }
                ?.let { entry ->
                    queryValue(entry.domainParameter)?.takeIf { it.isNotBlank() }?.let { entry.keysByDomain[it] }
                }

    override fun entityParameterFor(
        method: String,
        pathTemplate: String,
    ): String? = entryOf(method, pathTemplate)?.entityParameter

    private fun entryOf(
        method: String,
        pathTemplate: String,
    ): Entry? = ENTRIES.firstOrNull { it.method == method && it.pathTemplate == pathTemplate }

    companion object {
        /**
         * The domain-keyed routes: previewConfigurationPromotion is decided by the direct-editing permission key of
         * the requested domain (INV-REQ-1128), at tenant scope as the route names no entity.
         */
        val DOMAIN_ENTRIES: List<DomainEntry> =
            listOf(
                DomainEntry(
                    "GET",
                    "/bff/configuration-bundles/{bundleId}/promotion-diff",
                    "previewConfigurationPromotion",
                    "domain",
                    mapOf("mapping-pack" to "mapping-pack.author", "identity-triple" to "entity-config.manage"),
                ),
            )

        /**
         * A template matches a path when both have the same number of `/`-separated segments and every segment is
         * equal, except that a `{name}` segment matches exactly one non-empty segment. A template without braces is
         * therefore compared by equality.
         */
        internal fun matches(
            template: String,
            path: String,
        ): Boolean {
            val t = template.split('/')
            val p = path.split('/')
            return t.size == p.size &&
                t.indices.all { i ->
                    val seg = t[i]
                    if (seg.length > 2 && seg.startsWith("{") && seg.endsWith("}")) p[i].isNotEmpty() else seg == p[i]
                }
        }

        val ENTRIES: List<Entry> =
            listOf(
                Entry("GET", "/v1/exceptions", "getExceptions", "exception.view"),
                Entry("GET", "/v1/portfolio", "getPortfolio", "period-readiness.view"),
            )
    }
}
