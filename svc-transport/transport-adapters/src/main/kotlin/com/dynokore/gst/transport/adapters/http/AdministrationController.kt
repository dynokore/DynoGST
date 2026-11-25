package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.adapters.persistence.ConnectionRecord
import com.dynokore.gst.transport.adapters.persistence.JooqConnectionRecords
import com.dynokore.gst.transport.adapters.persistence.JooqCredentialRotationLog
import com.dynokore.gst.transport.application.AccessPointConnectionStore
import com.dynokore.gst.transport.application.ClearAcknowledgementRoute
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.application.AdapterNotRegistered
import com.dynokore.gst.transport.application.CredentialRef
import com.dynokore.gst.transport.application.ElectAcknowledgementRoute
import com.dynokore.gst.transport.application.FailoverAdapter
import com.dynokore.gst.transport.application.RotateCredential
import com.dynokore.gst.transport.application.RotateResult
import com.dynokore.gst.transport.application.SetConnectionEntities
import com.dynokore.gst.transport.application.SetTenantCeiling
import com.dynokore.gst.transport.application.TestConnection
import com.dynokore.gst.transport.contract.api.AdministrationApi
import com.dynokore.gst.transport.contract.model.AcknowledgementRouteResponse
import com.dynokore.gst.transport.contract.model.ApConnection
import com.dynokore.gst.transport.contract.model.ConnectionTestResult
import com.dynokore.gst.transport.contract.model.CredentialRotationResult
import com.dynokore.gst.transport.contract.model.ElectAcknowledgementRouteRequest
import com.dynokore.gst.transport.contract.model.FailoverAdapterRequest
import com.dynokore.gst.transport.contract.model.FailoverResult
import com.dynokore.gst.transport.contract.model.RotateCredentialRequest
import com.dynokore.gst.transport.contract.model.SetConnectionEntitiesRequest
import com.dynokore.gst.transport.contract.model.SetConnectionEntitiesResponse
import com.dynokore.gst.transport.contract.model.SetThroughputCeilingRequest
import com.dynokore.gst.transport.domain.CeilingChange
import com.dynokore.gst.transport.domain.CeilingRefusal
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.transport.application.ConnectionTestResult as TestOutcome

/**
 * The `administration` operations of the transport contract (TPL-08). `electAcknowledgementRoute` and
 * `clearAcknowledgementRoute` (INV-REQ-1310; rulings INV-Q-9678 A, INV-Q-9680 A), `failoverAdapter` (INV-REQ-796),
 * `setConnectionEntities` and `testConnection` (INV-REQ-835, INV-REQ-836; INV-T-18675) are implemented here;
 * `rotateCredential` (INV-REQ-837; INV-T-18676) asks `ap-credential.manage`.
 *
 * Both are end-user commands at tenant scope; [RouteCaller] decides the permission. The connection is named by
 * `connectionRef` = the `adapter_id` of the bound tenant's connection; a ref naming no record of that tenant is
 * 404 SRT-005 with nothing recorded or published. The refusals are mapped by [AdministrationAdvice].
 */
@RestController
@Suppress("LongParameterList") // one collaborator per operation of the interface, wired by the composition root
open class AdministrationController(
    private val elect: ElectAcknowledgementRoute,
    private val clear: ClearAcknowledgementRoute,
    private val connections: AccessPointConnectionStore,
    private val caller: RouteCaller,
    private val scope: TenantScope,
    private val failover: FailoverAdapter,
    private val setEntities: SetConnectionEntities,
    private val testConnection: TestConnection,
    private val commands: IdempotentCommands,
    private val clock: Clock,
    private val rotate: RotateCredential,
    private val rotations: JooqCredentialRotationLog,
    private val window: RotationWindow,
    private val ceilings: SetTenantCeiling,
    private val records: JooqConnectionRecords,
) : AdministrationApi {
    /**
     * `setThroughputCeiling` (INV-REQ-1317, owner ruling 2026-11-18 A): asks `vp-56.set` (the set permission of VP-56),
     * decided per request, never a role name. The connection is read under the tenant predicate: a ref of another
     * tenant is 404 SRT-005 and its row is untouched. A ceiling above the agreed rate is 422 POL-008 and a connection
     * with no agreed rate recorded is 422 POL-010 (fail closed); nothing is stored in either. A value of zero or below
     * is refused by the request schema (400 SRT-001). The change and the key record commit together; the actor bound
     * for the change is the acting user ID.
     */
    override fun setThroughputCeiling(
        connectionRef: String,
        idempotencyKey: UUID,
        setThroughputCeilingRequest: SetThroughputCeilingRequest,
    ): ResponseEntity<ApConnection> {
        val user = caller.require(RouteCaller.THROUGHPUT_CEILING_SET_PERMISSION)
        val tenant = TenantRef(user.tenant.value.toString())
        val value = setThroughputCeilingRequest.throughputCeiling
        return scope.bind(user.tenant, user.actor) {
            commands.once(
                user.actor,
                idempotencyKey,
                "PUT setThroughputCeiling $connectionRef ${value.toPlainString()}",
                ApConnection::class.java,
            ) {
                val before = records.load(tenant, connectionRef) ?: throw ConnectionNotFound(connectionRef)
                if (before.agreedRate == null) throw CatalogueRefusal(HTTP_UNPROCESSABLE, BOUND_UNAVAILABLE, BOUND_TITLE)
                store(AdapterId(connectionRef), value)
                (records.load(tenant, connectionRef) ?: throw ConnectionNotFound(connectionRef)).toResponse()
            }
        }
    }

    private fun store(
        adapter: AdapterId,
        value: BigDecimal,
    ) {
        val change =
            try {
                ceilings.set(adapter, value)
            } catch (_: AdapterNotRegistered) {
                throw CatalogueRefusal(HTTP_UNPROCESSABLE, BOUND_UNAVAILABLE, BOUND_TITLE)
            }
        if (change is CeilingChange.Refused) {
            throw when (change.reason) {
                CeilingRefusal.ABOVE_AGREED_RATE -> CatalogueRefusal(HTTP_UNPROCESSABLE, LOOSER, LOOSER_TITLE)
                CeilingRefusal.NOT_POSITIVE -> ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
            }
        }
    }

    private fun ConnectionRecord.toResponse() =
        ApConnection(
            connectionRef = adapterRef,
            adapterRef = adapterRef,
            entityRefs = entityRefs.toSet(),
            version = version.toInt(),
            throughputCeiling = (tenantCeiling ?: agreedRate)?.plain(),
            agreedRate = agreedRate?.plain(),
        )

    /** The column keeps three decimals; the answer shows the number without trailing zeros and never as an exponent. */
    private fun BigDecimal.plain(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    /**
     * `rotateCredential`: asks `ap-credential.manage`. The new reference is named in the request and is only a vault
     * reference, never a secret. Under the `Idempotency-Key` the rotation and the key record commit together, so a
     * repeat returns the original body; a repeat of the reference under another key returns the original outcome and
     * starts no second window. A refusal (no window configured, unknown reference, rotation in progress) changes
     * nothing.
     */
    override fun rotateCredential(
        adapterRef: String,
        idempotencyKey: UUID,
        rotateCredentialRequest: RotateCredentialRequest,
    ): ResponseEntity<CredentialRotationResult> {
        val user = caller.require(RouteCaller.AP_CREDENTIAL_MANAGE_PERMISSION)
        val credential = reference(rotateCredentialRequest.credentialId)
        val tenant = TenantRef(user.tenant.value.toString())
        return scope.bind(user.tenant, user.actor) {
            commands.once(
                user.actor,
                idempotencyKey,
                "POST rotateCredential $adapterRef ${credential.value}",
                CredentialRotationResult::class.java,
            ) {
                connections.load(tenant, adapterRef) ?: throw ConnectionNotFound(adapterRef)
                rotation(adapterRef, credential, user.actor)
            }
        }
    }

    private fun reference(credentialId: String): CredentialRef {
        if (credentialId.isBlank()) throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        return CredentialRef(credentialId)
    }

    private fun rotation(
        adapterRef: String,
        credential: CredentialRef,
        actor: String,
    ): CredentialRotationResult {
        val adapter = AdapterId(adapterRef)
        val result = rotate.rotate(adapter, credential, window.value, actor)
        if (result is RotateResult.Refused) throw CredentialRotationRefused(result.reason)
        val outcome = (result as RotateResult.Rotated).outcome
        val at = rotations.rotatedAt(adapter, credential) ?: clock.instant()
        return CredentialRotationResult(
            adapterRef = adapterRef,
            credentialId = credential.value,
            overlapActive = clock.instant().isBefore(outcome.overlapEnd),
            rotatedAt = OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
        )
    }

    /**
     * `failoverAdapter`: asks `adapter.failover`; the entity is `entityRef` of the request and the target
     * `targetAdapterRef`, never inferred. A target that is not a registered adapter is 404 SRT-005 with nothing
     * changed; a repeat for an entity already on the target is a no-op that answers 200 again.
     */
    override fun failoverAdapter(
        adapterRef: String,
        idempotencyKey: UUID,
        failoverAdapterRequest: FailoverAdapterRequest,
    ): ResponseEntity<FailoverResult> {
        val user = caller.require(RouteCaller.ADAPTER_FAILOVER_PERMISSION)
        if (failoverAdapterRequest.entityRef.isBlank()) throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        scope.bind(user.tenant, user.actor) {
            failover.failover(
                EntityRef(failoverAdapterRequest.entityRef),
                AdapterId(failoverAdapterRequest.targetAdapterRef),
            )
        }
        return ResponseEntity.ok(
            FailoverResult(
                adapterRef,
                failoverAdapterRequest.targetAdapterRef,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            ),
        )
    }

    /**
     * `setConnectionEntities`: asks `adapter.configure`. Under the `Idempotency-Key` (API-15) the replacement and the
     * key record commit together, so a repeat answers the original outcome and publishes once. SUB-018, SUB-019 and
     * a repeated ref are refused by the use case before anything is stored or published.
     */
    override fun setConnectionEntities(
        connectionRef: String,
        idempotencyKey: UUID,
        setConnectionEntitiesRequest: SetConnectionEntitiesRequest,
    ): ResponseEntity<SetConnectionEntitiesResponse> {
        val user = caller.require(RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val tenant = TenantRef(user.tenant.value.toString())
        val refs = setConnectionEntitiesRequest.entityRefs.toList()
        return scope.bind(user.tenant, user.actor) {
            commands.once(
                user.actor,
                idempotencyKey,
                "PUT setConnectionEntities $connectionRef ${refs.sorted()}",
                SetConnectionEntitiesResponse::class.java,
            ) {
                setEntities.set(tenant, connectionRef, refs)
                val state = connections.load(tenant, connectionRef) ?: throw ConnectionNotFound(connectionRef)
                SetConnectionEntitiesResponse(state.entityRefs.map { it.value }.toSet(), state.version.toInt())
            }
        }
    }

    /**
     * `testConnection`: asks `adapter.configure`, then runs the probe through the use case: `resultClass` is the class
     * of the probe, `detail` a code only and `state` the breaker state after the outcome. A ref naming no record of the
     * tenant is 404 SRT-005 before the AP is called.
     */
    override fun testConnection(
        adapterRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<ConnectionTestResult> {
        val user = caller.require(RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val tenant = TenantRef(user.tenant.value.toString())
        val result =
            scope.bind(user.tenant, user.actor) {
                connections.load(tenant, adapterRef) ?: throw ConnectionNotFound(adapterRef)
                testConnection.test(tenant, adapterRef)
            }
        return ResponseEntity.ok(result.toResponse())
    }

    private fun TestOutcome.toResponse() =
        ConnectionTestResult(
            adapterRef = connectionRef,
            state = ConnectionTestResult.State.valueOf(state.name),
            resultClass = ConnectionTestResult.ResultClass.valueOf(classification.name),
            detail = detail,
            checkedAt = OffsetDateTime.ofInstant(checkedAt, ZoneOffset.UTC),
        )

    override fun electAcknowledgementRoute(
        connectionRef: String,
        idempotencyKey: UUID,
        electAcknowledgementRouteRequest: ElectAcknowledgementRouteRequest,
    ): ResponseEntity<AcknowledgementRouteResponse> =
        command(connectionRef) { tenant ->
            elect.elect(tenant, connectionRef, electAcknowledgementRouteRequest.acknowledgementRoute.value)
        }

    override fun clearAcknowledgementRoute(
        connectionRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<AcknowledgementRouteResponse> =
        command(connectionRef) { tenant -> clear.clear(tenant, connectionRef) }

    private fun command(
        connectionRef: String,
        change: (TenantRef) -> Unit,
    ): ResponseEntity<AcknowledgementRouteResponse> {
        val user = caller.require()
        val tenant = TenantRef(user.tenant.value.toString())
        val state =
            scope.bind(user.tenant, user.actor) {
                change(tenant)
                connections.load(tenant, connectionRef) ?: throw ConnectionNotFound(connectionRef)
            }
        return ResponseEntity.ok(
            AcknowledgementRouteResponse(
                entityRefs = state.entityRefs.map { it.value }.toSet(),
                version = state.version.toInt(),
                acknowledgementRoute =
                    state.route?.let {
                        AcknowledgementRouteResponse.AcknowledgementRoute.valueOf(
                            it.name,
                        )
                    },
            ),
        )
    }
}

private const val HTTP_UNPROCESSABLE = 422
private const val LOOSER = "POL-008"
private const val LOOSER_TITLE = "Looser than a broader scope"
private const val BOUND_UNAVAILABLE = "POL-010"
private const val BOUND_TITLE = "Bound unavailable"

/** The overlap window of a credential rotation (INV-REQ-837): configuration with no default, null while unset. */
class RotationWindow(
    val value: Duration?,
)
