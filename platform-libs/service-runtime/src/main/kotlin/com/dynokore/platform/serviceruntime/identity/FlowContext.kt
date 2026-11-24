package com.dynokore.platform.serviceruntime.identity

import java.time.Clock
import java.util.UUID

/**
 * The signed flow context of a system flow (owner answer on INV-Q-9241, option B; INV-REQ-1202; schema
 * `access/schemas/flow-context.schema.json` of platform-contracts). A flow is authorised once at its entry (API-25);
 * the entry opens a context with [begin], and a callee of a listed operation verifies it, binds its tenant to RLS and
 * refuses an entity outside it ([requireEntity]).
 *
 * Only service-runtime makes one: the constructor is internal, so a service gets a context from [begin] (its entry) or
 * from [current] (a callee), and cannot write one. [toString] never holds the signed form.
 */
class FlowContext internal constructor(
    val tenant: String,
    val entity: FlowEntity,
    val principalKind: String,
    val callerService: String,
    val flowId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    internal val compact: String,
) {
    /**
     * Returns when the flow is [FlowEntity.tenantWide] or names exactly [entityRef] (string equality, case sensitive);
     * otherwise throws [FlowContextRefusedException], which service-runtime answers 403 SRT-004 with nothing stored.
     */
    fun requireEntity(entityRef: String) {
        if (entity.kind == EntityKind.TENANT_WIDE) return
        if (entity.ref != entityRef) throw FlowContextRefusedException(FlowContextRefusedException.ENTITY)
    }

    override fun toString(): String = "FlowContext(flowId=$flowId, tenant=$tenant, entity=$entity)"

    companion object {
        /** The request header of an inbound and an outbound call. */
        const val HEADER_NAME = "X-Flow-Context"

        /** The longest lifetime (`exp - iat`) the schema allows, in seconds. */
        const val MAX_LIFETIME_SECONDS = 900L

        /** The JOSE header `typ` of a flow context (RFC 8725 section 3.11); compared by string equality. */
        const val TYP = "flow-context+jwt"

        /**
         * The skew allowed between access-svc's `exp` and this service's clock (the single source:
         * `OperatorSessionTokens.CLOCK_SKEW` is derived from it).
         */
        const val CLOCK_SKEW_SECONDS = 60L

        private const val MAX_TEXT = 64
        private val holder = ThreadLocal<FlowContext>()

        /** The context the current thread's request or flow holds, or null. */
        fun current(): FlowContext? = holder.get()

        /**
         * Opens a context at a flow entry: the claims the entry gives (validated as the schema states; an invalid claim
         * is an [IllegalArgumentException]) are sent to [issuer], which is access-svc's `issueFlowContext` (owner
         * answer 2026-11-18, option A). access-svc sets the caller, `iat` and `exp` (a lifetime of at most
         * [MAX_LIFETIME_SECONDS]); the held context is built from its response. A response that expires at or before
         * `now` of [clock], or later than [MAX_LIFETIME_SECONDS] plus [CLOCK_SKEW_SECONDS] after it, is refused as
         * [FlowContextUnavailableException], like any failure of the issuer: no context is held (fail closed).
         * Run the flow under the result with [runWith].
         */
        @Suppress("LongParameterList")
        fun begin(
            issuer: FlowContextIssuer,
            clock: Clock,
            tenant: String,
            entity: FlowEntity,
            principalKind: String,
            callerService: String,
            flowId: String = UUID.randomUUID().toString(),
        ): FlowContext {
            require(tenant.length in 1..MAX_TEXT) { "tenant must be 1 to $MAX_TEXT characters" }
            require(flowId.length in 1..MAX_TEXT) { "flowId must be 1 to $MAX_TEXT characters" }
            require(principalKind.isNotEmpty()) { "principalKind must not be empty" }
            require(callerService.isNotEmpty()) { "callerService must not be empty" }
            val iat = clock.instant().epochSecond
            val issued = issuer.issue(FlowContextClaims(tenant, entity, principalKind, flowId))
            val exp = issued.expiresAt
            if (issued.compact.isEmpty() || exp <= iat || exp - iat > MAX_LIFETIME_SECONDS + CLOCK_SKEW_SECONDS) {
                throw FlowContextUnavailableException()
            }
            return FlowContext(tenant, entity, principalKind, callerService, flowId, iat, exp, issued.compact)
        }

        /** Runs [block] with [context] as the current context of this thread; the previous one is restored after. */
        fun <T> runWith(
            context: FlowContext,
            block: () -> T,
        ): T {
            val previous = holder.get()
            holder.set(context)
            try {
                return block()
            } finally {
                if (previous == null) holder.remove() else holder.set(previous)
            }
        }
    }
}

/** The entity scope of a flow: one entity, or the whole tenant. */
enum class EntityKind { ENTITY, TENANT_WIDE }

/** [kind] ENTITY holds a non-empty [ref]; TENANT_WIDE holds none. */
class FlowEntity private constructor(
    val kind: EntityKind,
    val ref: String?,
) {
    override fun equals(other: Any?): Boolean = other is FlowEntity && kind == other.kind && ref == other.ref

    override fun hashCode(): Int = listOf(kind, ref).hashCode()

    override fun toString(): String = if (ref == null) kind.name else "$kind:$ref"

    companion object {
        private const val MAX_REF = 64

        fun entity(ref: String): FlowEntity {
            require(ref.length in 1..MAX_REF) { "entity ref must be 1 to 64 characters" }
            return FlowEntity(EntityKind.ENTITY, ref)
        }

        fun tenantWide(): FlowEntity = FlowEntity(EntityKind.TENANT_WIDE, null)
    }
}

/**
 * The verification keys of flow contexts. [verify] returns the payload bytes of [compact] only when it is a JWS
 * compact serialization (RFC 7515) whose
 * signature verifies under an accepted key with an accepted algorithm; an unsigned value (`alg` none), an unknown key,
 * a bad signature or any malformed input gives null. It never throws for bad input.
 */
fun interface FlowContextKeys {
    fun verify(compact: String): ByteArray?
}

/** The claims a flow entry gives access-svc; the caller, `iat` and `exp` are set by access-svc, never by the entry. */
class FlowContextClaims(
    val tenant: String,
    val entity: FlowEntity,
    val principalKind: String,
    val flowId: String,
)

/** What access-svc issued: the compact form (JWS, RFC 7515) and its `exp` in epoch seconds. */
class IssuedFlowContext(
    val compact: String,
    val expiresAt: Long,
)

/**
 * Obtains the signed context of a flow from access-svc (`issueFlowContext`, owner answer 2026-11-18, option A). An
 * answer that is not a success, a timeout or a body without the context throws [FlowContextUnavailableException].
 */
fun interface FlowContextIssuer {
    fun issue(claims: FlowContextClaims): IssuedFlowContext
}

/** No context could be obtained at the flow entry; retryable, the entry answers 503 and holds no context. */
class FlowContextUnavailableException(
    cause: Throwable? = null,
) : RuntimeException("the flow context could not be obtained from access-svc", cause)

/**
 * A flow context refused the call; service-runtime answers 403 SRT-004 with nothing stored. [reason] is a fixed label,
 * never a value from the request.
 */
class FlowContextRefusedException internal constructor(
    val reason: String,
) : RuntimeException("flow context refused: $reason") {
    companion object {
        internal const val ENTITY = "entity"
    }
}
