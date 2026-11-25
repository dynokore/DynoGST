package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.http.LookupController
import com.dynokore.gst.evidence.adapters.http.LookupRefusal
import com.dynokore.gst.evidence.adapters.http.LookupRefusalAdvice
import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocator
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocatorStore
import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.application.lookup.AcknowledgementChainLookup
import com.dynokore.gst.evidence.application.lookup.ChainRecordReader
import com.dynokore.gst.evidence.application.lookup.ChainRecords
import com.dynokore.gst.evidence.application.lookup.DecisionTraceLookup
import com.dynokore.gst.evidence.application.lookup.DecisionTraceReader
import com.dynokore.gst.evidence.application.lookup.ENQUIRY_LOOKUP_REVERSE
import com.dynokore.gst.evidence.application.lookup.ReverseLookup
import com.dynokore.gst.evidence.application.lookup.ReverseLookupFilter
import com.dynokore.gst.evidence.application.lookup.ReverseLookupKey
import com.dynokore.gst.evidence.application.lookup.ReverseLookupRow
import com.dynokore.gst.evidence.application.lookup.SubmissionIndexReader
import com.dynokore.gst.evidence.application.lookup.TransmittedVersionLookup
import com.dynokore.gst.evidence.application.lookup.TransmittedVersionReader
import com.dynokore.gst.evidence.domain.integrity.IntegrityState
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20724: `reverseLookup` over HTTP; the use case is the one of INV-AC-20722 over a recording reader. */
class ReverseLookupControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val reads = mutableListOf<ReverseLookupFilter>()

    private fun rows(count: Int) =
        (1..count).map {
            ReverseLookupRow(
                ReverseLookupKey(LocalDate.parse("2026-03-20"), doc, it),
                "SENT",
                null,
                null,
                "ENT-1",
                "2026-03",
            )
        }

    private fun replica(granted: Boolean): AuthorityReplica =
        replica(if (granted) listOf(entityGrant("ENT-1")) else emptyList())

    private fun entityGrant(
        entity: String,
        role: String = "auditor",
    ) = RoleGrant(role, GrantScope.ENTITY, entity, Instant.parse("2026-01-01T00:00:00Z"), null)

    /** The role `auditor` holds the key; `preparer` holds another key only. [onRolesFor] records each role question. */
    private fun replica(
        grants: List<RoleGrant>,
        onRolesFor: (String) -> Unit = {},
    ): AuthorityReplica {
        val roles =
            OperationRoleMap {
                onRolesFor(it)
                when (it) {
                    ENQUIRY_LOOKUP_REVERSE -> mapOf("auditor" to CellAllowance.PERMITTED)
                    OTHER_KEY -> mapOf("preparer" to CellAllowance.PERMITTED)
                    else -> emptyMap()
                }
            }
        val unavailable = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        grants.forEachIndexed { index, grant ->
            replica.accept(
                AuthorityChanged(
                    tenant,
                    "user-42",
                    index + 1L,
                    AuthorityRecord.RoleGrantRecord("g$index", RoleGrantState.ACTIVE, grant),
                ),
            )
        }
        replica.onSnapshotComplete(maxOf(grants.size, 1).toLong())
        replica.observeHead(maxOf(grants.size, 1).toLong())
        return replica
    }

    private val audit =
        object : AuditedOperations {
            override fun <T> change(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = error("not used")

            override fun <T> read(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = block()
        }

    private fun controller(
        caller: EndUserPrincipal?,
        granted: Boolean = true,
        stored: Int = 2,
        integrity: IntegrityState = IntegrityState.VERIFIED,
    ): LookupController {
        val reader =
            scopedReader { entities, filter, limit ->
                reads += filter
                rows(stored).filter { it.entityRef in entities }.take(limit)
            }
        return controller(caller, replica(granted), reader, integrity)
    }

    /** A reader that serves `readScoped` only: the unscoped `read` fails the test. */
    private fun scopedReader(serve: (Set<String>, ReverseLookupFilter, Int) -> List<ReverseLookupRow>) =
        object : SubmissionIndexReader {
            override fun read(
                tenant: TenantId,
                filter: ReverseLookupFilter,
                after: ReverseLookupKey?,
                limit: Int,
            ): List<ReverseLookupRow> = error("the unscoped read is not used")

            override fun readScoped(
                tenant: TenantId,
                entities: Set<String>,
                filter: ReverseLookupFilter,
                after: ReverseLookupKey?,
                limit: Int,
            ): List<ReverseLookupRow> = serve(entities, filter, limit)
        }

    private fun controller(
        caller: EndUserPrincipal?,
        authority: AuthorityReplica,
        reader: SubmissionIndexReader,
        integrity: IntegrityState = IntegrityState.VERIFIED,
    ): LookupController {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        val chain =
            AcknowledgementChainLookup(
                replica(false),
                object : AcknowledgementLocatorStore {
                    override fun insert(
                        tenant: TenantId,
                        eventId: String,
                        ackId: String,
                        docUuid: UUID,
                        version: Int,
                        recordedAt: Instant,
                    ): Boolean = error("not used")

                    override fun find(
                        tenant: TenantId,
                        ackId: String,
                    ): AcknowledgementLocator? = error("not used")
                },
                ChainRecordReader { _, _, _, _ -> ChainRecords.Incomplete },
                IntegrityStatusQueryHandler(unusedIntegrity()),
                AuditedReads(audit),
            )
        return LookupController(
            chain,
            ReverseLookup(authority, reader, IntegrityStatusQueryHandler(runs(integrity)), AuditedReads(audit)),
            DecisionTraceLookup(replica(false), DecisionTraceReader { _, _, _ -> error("not used") }),
            TransmittedVersionLookup(replica(false), TransmittedVersionReader { _, _, _ -> error("not used") }),
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
        )
    }

    private fun runs(state: IntegrityState): IntegrityStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IntegrityStore::class.java)) { _, method, _ ->
            check(method.name.startsWith("latestRun")) { "a lookup never writes: ${method.name}" }
            when (state) {
                IntegrityState.VERIFIED -> run(RunResult.VERIFIED)
                IntegrityState.FAILED -> run(RunResult.FAILED)
                IntegrityState.NEVER_RUN -> null
            }
        } as IntegrityStore

    private fun run(result: RunResult) =
        IntegrityRunRow(
            "ENT-1",
            "2026-03",
            "run-1",
            Instant.parse("2026-03-20T03:10:00Z"),
            result,
            RunCause.VERIFICATION,
            2,
            "d",
        )

    private fun unusedIntegrity(): IntegrityStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IntegrityStore::class.java)) { _, _, _ ->
            error("not used")
        } as IntegrityStore

    private fun user(tenant: TenantId? = this.tenant) = EndUserPrincipal("user-42", tenant, emptySet())

    private fun refused(block: () -> Unit): Pair<Int, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refusal = thrown as? LookupRefusal ?: throw AssertionError("expected LookupRefusal, got $thrown")
        val response = LookupRefusalAdvice().refuse(refusal)
        val code =
            Regex("\"code\"\\s*:\\s*\"([^\"]*)\"")
                .find(response.body!!)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return response.statusCode.value() to code
    }

    private fun call(
        c: LookupController,
        counterparty: String? = null,
        categoryCode: String? = null,
        limit: Int = 100,
        cursor: String? = null,
    ) = c.reverseLookup(counterparty, null, null, null, null, null, categoryCode, limit, cursor)

    @Test
    fun `INV-AC-20724 a valid request answers 200 with the page items and a cursor when more rows exist`() {
        val response = call(controller(user(), stored = 3), limit = 2)
        response.statusCode.value() shouldBe 200
        val body = response.body!!
        body.items.map { it.version } shouldBe listOf(1, 2)
        body.items.map { it.state } shouldBe listOf("SENT", "SENT")
        body.items.map { it.docUuid } shouldBe listOf(doc, doc)
        (body.nextCursor != null) shouldBe true
        reads.size shouldBe 1
    }

    @Test
    fun `INV-AC-20724 integrityStatus is exactly the use case state in every 200, the empty page included`() {
        IntegrityState.entries.forEach { state ->
            call(controller(user(), integrity = state)).body!!.integrityStatus!!.value shouldBe state.name
            call(controller(user(), stored = 0, integrity = state)).body!!.integrityStatus!!.value shouldBe "NEVER_RUN"
        }
        (call(controller(user())).body!!.integrityStatus!!.value == "verified") shouldBe false
    }

    @Test
    fun `INV-AC-20724 limit 0, limit 501 and a bad cursor answer 400 SRT-001 and no store is read`() {
        val c = controller(user())
        listOf(
            refused { call(c, limit = 0) },
            refused { call(c, limit = 501) },
            refused { call(c, cursor = "not-a-cursor") },
        ).forEach { (status, code) ->
            status shouldBe 400
            code shouldBe "SRT-001"
            (code == "SRT-0010") shouldBe false
        }
        reads shouldBe emptyList()
        call(c, limit = 500).statusCode.value() shouldBe 200
    }

    @Test
    fun `INV-AC-20724 categoryCode and a counterparty of 8 characters are answered 200, never refused`() {
        val c = controller(user())
        call(c, counterparty = "12345678", categoryCode = "SR").statusCode.value() shouldBe 200
        call(c, counterparty = "ACME-PTE").statusCode.value() shouldBe 200
        reads.map { it.counterparty to it.categoryCode } shouldBe listOf("12345678" to "SR", "ACME-PTE" to null)
    }

    @Test
    fun `INV-AC-20724 a caller without the key answers 403 SRT-004 and no store is read`() {
        val (status, code) = refused { call(controller(user(), granted = false)) }
        status shouldBe 403
        code shouldBe "SRT-004"
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20724 a caller with no verified tenant answers 401 SRT-003 and no store is read`() {
        listOf(null, user(tenant = null)).forEach { caller ->
            val (status, code) = refused { call(controller(caller)) }
            status shouldBe 401
            code shouldBe "SRT-003"
        }
        reads shouldBe emptyList()
    }

    /** One counterparty UEN with a document in each entity; "a" and "A1" are look-alikes of "A". */
    private val stored =
        listOf("A", "B", "C", "a", "A1").mapIndexed { index, entity ->
            ReverseLookupRow(
                ReverseLookupKey(LocalDate.parse("2026-03-20"), UUID(0, index + 1L), 1),
                "SENT",
                null,
                null,
                entity,
                "2026-03",
            )
        }
    private val documentOf = stored.associate { it.entityRef!! to it.key.docUuid }
    private val scopes = mutableListOf<Set<String>>()

    private fun searched(authority: AuthorityReplica): LookupController {
        val reader =
            scopedReader { entities, filter, limit ->
                reads += filter
                scopes += entities
                stored.filter { filter.counterparty == UEN && it.entityRef in entities }.take(limit)
            }
        return controller(user(), authority, reader)
    }

    private fun documents(authority: AuthorityReplica): List<UUID?> =
        call(searched(authority), counterparty = UEN).body!!.items.map { it.docUuid }

    @Test
    fun `INV-AC-1065 a caller scoped to A and B gets the documents of A and B and none of C`() {
        val items = documents(replica(listOf(entityGrant("A"), entityGrant("B"))))
        items shouldBe listOf(documentOf["A"], documentOf["B"])
        (documentOf["C"] in items) shouldBe false
        scopes shouldBe listOf(setOf("A", "B"))
    }

    @Test
    fun `INV-AC-1065 an entity grant of another key only is 403 SRT-004 and the reader is not called`() {
        val authority = replica(listOf(entityGrant("A", "preparer"), entityGrant("B", "preparer")))
        refused { call(searched(authority), counterparty = UEN) } shouldBe (403 to "SRT-004")
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1065 a caller scoped to A only gets the documents of A and none of B or C`() {
        // an entity grant of another key on B does not widen the set
        val authority = replica(listOf(entityGrant("A"), entityGrant("B", "preparer")))
        documents(authority) shouldBe listOf(documentOf["A"])
        scopes shouldBe listOf(setOf("A"))
    }

    @Test
    fun `INV-AC-1065 a caller with no grant is 403 SRT-004 and the reader is not called`() {
        refused { call(searched(replica(emptyList())), counterparty = UEN) } shouldBe (403 to "SRT-004")
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1065 the controller asks no tenant decision for the key`() {
        val asked = mutableListOf<Pair<String, Boolean>>()
        // each role question is recorded with whether it came through decideTenant
        val authority =
            replica(listOf(entityGrant("A"))) { key ->
                asked += key to Thread.currentThread().stackTrace.any(::isTenantDecision)
            }
        // positive control: the recorder does see a tenant decision
        authority.decideTenant(tenant, Principal("user-42", PrincipalKind.HUMAN), ENQUIRY_LOOKUP_REVERSE)
        asked shouldBe listOf(ENQUIRY_LOOKUP_REVERSE to true)
        asked.clear()
        documents(authority) shouldBe listOf(documentOf["A"])
        asked shouldBe listOf(ENQUIRY_LOOKUP_REVERSE to false)
    }

    /** A value-class parameter mangles the JVM name to `decideTenant-<hash>`. */
    private fun isTenantDecision(frame: StackTraceElement): Boolean =
        frame.className == AuthorityReplica::class.java.name &&
            frame.methodName.substringBefore('-') == "decideTenant"

    private companion object {
        const val UEN = "201912345K"
        const val OTHER_KEY = "enquiry-lookup.by-acknowledgement"
    }
}
