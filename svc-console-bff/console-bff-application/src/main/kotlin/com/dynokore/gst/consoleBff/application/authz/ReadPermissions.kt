package com.dynokore.gst.consoleBff.application.authz

/**
 * The permission key of a read operation of console-bff (INV-REQ-871 behavior 1, INV-Q-10027): the key the authority
 * replica is asked about, never a role name.
 */
fun interface ReadPermissions {
    /** The permission key of the route ([method], [pathTemplate]), or null when the route is unmapped (fail closed). */
    fun keyFor(
        method: String,
        pathTemplate: String,
    ): String?

    /**
     * The permission key of the request ([method], concrete [path]), where [queryValue] returns the single non-blank
     * value of a named query parameter, or null when it is missing, blank or given more than once. Defaults to the
     * path-only lookup, so an implementer that needs no query value is unchanged.
     */
    fun keyFor(
        method: String,
        path: String,
        queryValue: (String) -> String?,
    ): String? = keyFor(method, path)

    /**
     * The name of the query parameter that carries the entity a read of the route names, or null when the read names
     * no entity (then it is decided at tenant scope). Meaningful only for a mapped route.
     */
    fun entityParameterFor(
        method: String,
        pathTemplate: String,
    ): String? = null

    /**
     * The name of the path variable (a `{name}` segment of the route template) that carries the entity a read of the
     * route names, or null when the entity is not taken from the path. [pathTemplate] may be the concrete request
     * path; the route is matched by segment equality.
     */
    fun entityPathVariableFor(
        method: String,
        pathTemplate: String,
    ): String? = null

    /**
     * The entity ref a read of the concrete [path] names through its path variable: the single non-blank path segment
     * at the position of the variable, or null when the route names no path variable or does not match.
     */
    fun entityRefFor(
        method: String,
        path: String,
    ): String? = null

    /**
     * Whether the route is marked as an entity list read: a read naming no entity that also admits a caller allowed
     * on at least one entity; its rows are narrowed to the allowed entities behind the read. False for every route
     * not marked, so such a read is decided at tenant scope alone.
     */
    fun admitsAnyEntityFor(
        method: String,
        pathTemplate: String,
    ): Boolean = false
}
