package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.http.ChainPageCursor
import com.dynokore.gst.evidence.adapters.http.LookupController
import com.dynokore.gst.evidence.adapters.http.LookupRefusal
import com.dynokore.gst.evidence.adapters.http.LookupRefusalAdvice
import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocator
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocatorStore
import com.dynokore.gst.evidence.application.integrity.IntegrityFailureRow
import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.application.lookup.AcknowledgementChainLookup
import com.dynokore.gst.evidence.application.lookup.ChainCursor
import com.dynokore.gst.evidence.application.lookup.ChainItem
import com.dynokore.gst.evidence.application.lookup.ChainItemKind
import com.dynokore.gst.evidence.application.lookup.ChainRecordReader
import com.dynokore.gst.evidence.application.lookup.ChainRecords
import com.dynokore.gst.evidence.application.lookup.DecisionTraceLookup
import com.dynokore.gst.evidence.application.lookup.DecisionTraceReader
import com.dynokore.gst.evidence.application.lookup.ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT
import com.dynokore.gst.evidence.application.lookup.ReverseLookup
import com.dynokore.gst.evidence.application.lookup.SubmissionIndexReader
import com.dynokore.gst.evidence.application.lookup.TransmittedVersionLookup
import com.dynokore.gst.evidence.application.lookup.TransmittedVersionReader
import com.dynokore.gst.evidence.domain.integrity.IntegrityState
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
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
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class LookupControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val at = Instant.parse("2026-03-20T03:10:00Z")
    private val calls = mutableListOf<String>()
    private val chainEntity = "ENT-1"
    private val chainPeriod = "2026-03"

    private fun item(
        kind: ChainItemKind,
        reference: String,
        rank: Long,
    ) = ChainItem(kind, at.plusSeconds(rank), reference, rank, ChainCursor(at.plusSeconds(rank), kind, 0))

    private val payload = item(ChainItemKind.PAYLOAD, "payload/$doc/v2/0", 1)
    private val ack = item(ChainItemKind.ACKNOWLEDGEMENT, "A1", 2)

    @TempDir
    lateinit var mount: Path

    private val k1 = "k".repeat(32)
    private val k2 = "o".repeat(32)
    private val otherTenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    private fun putKey(
        value: String,
        name: String = ChainPageCursor.CURRENT_SECRET,
    ) {
        Files.writeString(mount.resolve(name), value)
    }

    @BeforeEach
    fun currentCursorKey() = putKey(k1)

    private inner class Locators : AcknowledgementLocatorStore {
        override fun insert(
            tenant: TenantId,
            eventId: String,
            ackId: String,
            docUuid: UUID,
            version: Int,
            recordedAt: Instant,
        ): Boolean = error("a lookup never writes")

        override fun find(
            tenant: TenantId,
            ackId: String,
        ): AcknowledgementLocator? {
            calls += "find"
            return if (tenant == this@LookupControllerTest.tenant && ackId == "A1") {
                AcknowledgementLocator("A1", doc, 2)
            } else {
                null
            }
        }
    }

    private inner class Reader(
        private val answer: ChainRecords,
    ) : ChainRecordReader {
        override fun read(
            tenant: TenantId,
            ackId: String,
            docUuid: UUID,
            version: Int,
        ): ChainRecords {
            calls += "read"
            return answer
        }
    }

    private inner class Integrity(
        private val state: IntegrityState,
    ) : IntegrityStore {
        override fun lock(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ) = error("a lookup never writes")

        override fun failureExists(
            tenant: TenantId,
            row: IntegrityFailureRow,
        ): Boolean = error("a lookup never writes")

        override fun insertFailure(
            tenant: TenantId,
            row: IntegrityFailureRow,
        ) = error("a lookup never writes")

        override fun insertRun(
            tenant: TenantId,
            row: IntegrityRunRow,
        ) = error("a lookup never writes")

        override fun latestRun(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ): IntegrityRunRow? {
            check(entityRef == chainEntity && period == chainPeriod) { "integrity read for $entityRef/$period" }
            val result =
                when (state) {
                    IntegrityState.VERIFIED -> RunResult.VERIFIED
                    IntegrityState.FAILED -> RunResult.FAILED
                    IntegrityState.NEVER_RUN -> return null
                }
            return IntegrityRunRow(entityRef, period, "run-1", at, result, RunCause.VERIFICATION, 2, "digest")
        }
    }

    private class Audit : AuditedOperations {
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

    private fun replica(granted: Boolean): AuthorityReplica {
        val roles =
            OperationRoleMap {
                if (granted &&
                    it == ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT
                ) {
                    mapOf("auditor" to CellAllowance.PERMITTED)
                } else {
                    emptyMap()
                }
            }
        val unavailable = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        val record =
            AuthorityRecord.RoleGrantRecord(
                "g1",
                RoleGrantState.ACTIVE,
                RoleGrant(
                    "auditor",
                    GrantScope.TENANT,
                    tenant.value.toString(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null,
                ),
            )
        replica.accept(AuthorityChanged(tenant, "user-42", 1, record))
        replica.onSnapshotComplete(1)
        replica.observeHead(1)
        return replica
    }

    private fun controller(
        caller: EndUserPrincipal?,
        records: ChainRecords = ChainRecords.Complete(listOf(ack, payload), chainEntity, chainPeriod),
        granted: Boolean = true,
        state: IntegrityState = IntegrityState.VERIFIED,
        cursorClock: Clock = clock,
    ): LookupController {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        val lookup =
            AcknowledgementChainLookup(
                replica(granted),
                Locators(),
                Reader(records),
                IntegrityStatusQueryHandler(Integrity(state)),
                AuditedReads(Audit()),
            )
        return LookupController(
            lookup,
            ReverseLookup(
                replica(granted),
                unusedIndex,
                IntegrityStatusQueryHandler(Integrity(state)),
                AuditedReads(Audit()),
            ),
            DecisionTraceLookup(replica(granted), DecisionTraceReader { _, _, _ -> error("not used") }),
            TransmittedVersionLookup(replica(granted), TransmittedVersionReader { _, _, _ -> error("not used") }),
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
            ChainPageCursor(MountedFileSecretStore(mount)),
            cursorClock,
        )
    }

    private val unusedIndex = SubmissionIndexReader { _, _, _, _, _ -> error("not used") }

    private fun user(
        tenant: TenantId? = this.tenant,
        actor: String = "user-42",
    ) = EndUserPrincipal(actor, tenant, emptySet())

    private fun refusal(block: () -> Unit): Triple<Int, String, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refused = thrown as? LookupRefusal ?: throw AssertionError("expected LookupRefusal, got $thrown")
        val response = LookupRefusalAdvice().refuse(refused)
        val body = response.body!!
        val code =
            Regex("\"code\"\\s*:\\s*\"([^\"]*)\"")
                .find(body)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return Triple(response.statusCode.value(), code, body)
    }

    private fun refused(
        controller: LookupController,
        ackId: String = "A1",
        limit: Int = 100,
        cursor: String? = null,
    ) = refusal { controller.getAcknowledgementChain(ackId, limit, cursor) }

    @Test
    fun `INV-AC-20576 found answers 200 with the items, ackId, docUuid and version and no problem`() {
        val response = controller(user()).getAcknowledgementChain("A1", 100, null)
        response.statusCode.value() shouldBe 200
        val body = response.body!!
        body.ackId shouldBe "A1"
        body.docUuid shouldBe doc
        body.version shouldBe 2
        body.imported shouldBe false
        body.items.map { it.kind.value } shouldBe listOf("PAYLOAD", "ACKNOWLEDGEMENT")
        body.items.map { it.sequence } shouldBe listOf(1, 2)
        body.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-20576 integrityStatus equals the use case state on both pages, no reproduction`() {
        IntegrityState.entries.forEach { state ->
            val c = controller(user(), state = state)
            val first = c.getAcknowledgementChain("A1", 1, null).body!!
            val second = c.getAcknowledgementChain("A1", 1, first.nextCursor).body!!
            first.integrityStatus!!.name shouldBe state.name
            second.integrityStatus!!.name shouldBe state.name
            first.reproduction shouldBe null
            second.reproduction shouldBe null
        }
    }

    @Test
    fun `INV-AC-20576 a page with a limit carries an opaque cursor that reads the rest`() {
        val c = controller(user())
        val first = c.getAcknowledgementChain("A1", 1, null).body!!
        first.items.map { it.sequence } shouldBe listOf(1)
        val second = c.getAcknowledgementChain("A1", 1, first.nextCursor).body!!
        second.items.map { it.sequence } shouldBe listOf(2)
        second.nextCursor shouldBe null
        // a cursor made for another id is a contract violation
        refused(c, ackId = "A2", limit = 1, cursor = first.nextCursor).first shouldBe 400
    }

    @Test
    fun `INV-AC-20576 an unknown id answers 404 SRT-005 and the code is compared exactly`() {
        val (status, code, body) = refused(controller(user()), ackId = "A9")
        status shouldBe 404
        code shouldBe "SRT-005"
        (code == "SRT-0050") shouldBe false
        (code == "SRT-004") shouldBe false
        (body.contains("\"SRT-005\"")) shouldBe true
    }

    @Test
    fun `INV-AC-20576 a caller without the permission key answers 403 SRT-004`() {
        val (status, code) = refused(controller(user(), granted = false))
        status shouldBe 403
        code shouldBe "SRT-004"
    }

    @Test
    fun `INV-AC-20576 a stored link with no record answers 500 SRT-010, never an empty 200`() {
        val (status, code) = refused(controller(user(), records = ChainRecords.Incomplete))
        status shouldBe 500
        code shouldBe "SRT-010"
    }

    @Test
    fun `INV-AC-20576 a caller with no verified tenant answers 401 SRT-003 and no store is read`() {
        listOf(null, user(tenant = null)).forEach { caller ->
            val (status, code) = refused(controller(caller))
            status shouldBe 401
            code shouldBe "SRT-003"
        }
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20576 a malformed ack id, limit 0 and 501 and a bad cursor answer 400 SRT-001, no store read`() {
        val c = controller(user())
        listOf(
            refused(c, ackId = " "),
            refused(c, ackId = "A".repeat(101)),
            refused(c, ackId = "A\u0000"),
            refused(c, limit = 0),
            refused(c, limit = 501),
            refused(c, cursor = "not-a-cursor"),
        ).forEach { (status, code) ->
            status shouldBe 400
            code shouldBe "SRT-001"
        }
        calls shouldBe emptyList()
        c.getAcknowledgementChain("A1", 500, null).statusCode.value() shouldBe 200
    }

    // INV-AC-29278: U1 is user-42, who holds the key of the lookup; U2 is user-43.

    private fun at(afterIssue: Duration) = Clock.offset(clock, afterIssue)

    /** The next cursor of U1's lookup of A1 issued at the fixed clock; the use case calls are then forgotten. */
    private fun nextCursor(): String {
        val next = controller(user()).getAcknowledgementChain("A1", 1, null).body!!.nextCursor!!
        calls.clear()
        return next
    }

    private fun assertCursorRefused(
        cursor: String,
        caller: EndUserPrincipal = user(),
        ackId: String = "A1",
        cursorClock: Clock = clock,
    ) {
        val (status, code) =
            refused(controller(caller, cursorClock = cursorClock), ackId = ackId, limit = 1, cursor = cursor)
        status shouldBe 400
        code shouldBe "SRT-001"
        calls shouldBe emptyList()
    }

    private fun assertSecondPage(
        cursor: String,
        cursorClock: Clock = clock,
    ) {
        val page =
            controller(user(), cursorClock = cursorClock).getAcknowledgementChain("A1", 1, cursor).body!!
        page.items.map { it.sequence } shouldBe listOf(2)
        page.items.map { it.kind.value } shouldBe listOf("ACKNOWLEDGEMENT")
        page.nextCursor shouldBe null
        calls shouldBe listOf("find", "read")
        calls.clear()
    }

    @Test
    fun `INV-AC-29278 the unchanged cursor within 24 hours, the 24th hour included, starts after its position`() {
        val cursor = nextCursor()
        listOf(Duration.ZERO, Duration.ofHours(1), Duration.ofHours(24)).forEach { assertSecondPage(cursor, at(it)) }
    }

    @Test
    fun `INV-AC-29278 a cursor after the 24th hour is 400 SRT-001 with no store read`() {
        val cursor = nextCursor()
        listOf(Duration.ofHours(24).plusMillis(1), Duration.ofDays(2)).forEach {
            assertCursorRefused(cursor, cursorClock = at(it))
        }
    }

    @Test
    fun `INV-AC-29278 an altered cursor is 400 SRT-001 with no store read`() {
        val cursor = nextCursor()
        val (layout, payload, tag) = cursor.split('.')
        val fields = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8).split('\n')

        fun withField(
            index: Int,
            value: String,
        ): String {
            val body = fields.toMutableList().also { it[index] = value }.joinToString("\n")
            return "$layout.${Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())}.$tag"
        }
        val flipped = (if (tag[0] == 'A') "B" else "A") + tag.drop(1)
        listOf(
            "$layout.$payload.$flipped",
            cursor.dropLast(1),
            "$cursor.x",
            "",
            withField(0, at.toString()),
            withField(1, "DECISION_TRACE"),
            withField(2, "9"),
        ).forEach { withClue(it) { assertCursorRefused(it) } }
    }

    @Test
    fun `INV-AC-29278 an expired cursor with a rewritten issue time is 400 SRT-001 with no store read`() {
        val (layout, payload, tag) = nextCursor().split('.')
        val later = at(Duration.ofHours(30))
        val fields = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8).split('\n')
        val body = (fields.dropLast(1) + later.instant().toEpochMilli().toString()).joinToString("\n")
        val renewed = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
        assertCursorRefused("$layout.$renewed.$tag", cursorClock = later)
    }

    @Test
    fun `INV-AC-29278 a cursor of another user is 400 SRT-001 with no store read`() {
        assertCursorRefused(nextCursor(), caller = user(actor = "user-43"))
    }

    @Test
    fun `INV-AC-29278 a cursor of another tenant is 400 SRT-001 with no store read`() {
        assertCursorRefused(nextCursor(), caller = user(tenant = otherTenant))
    }

    @Test
    fun `INV-AC-29278 a cursor of another Acknowledgement ID is 400 SRT-001 with no store read`() {
        assertCursorRefused(nextCursor(), ackId = "A2")
    }

    @Test
    fun `INV-AC-29278 the former unsigned form is 400 SRT-001 with no store read`() {
        val former =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("A1\u0000${payload.key.appliedAt}\u0000PAYLOAD\u00000".toByteArray())
        assertCursorRefused(former)
    }

    @Test
    fun `INV-AC-29278 a K1 cursor is accepted while K1 is the previous key and refused once it is neither`() {
        val cursor = nextCursor()
        putKey(k2)
        putKey(k1, ChainPageCursor.PREVIOUS_SECRET)
        assertSecondPage(cursor)
        Files.delete(mount.resolve(ChainPageCursor.PREVIOUS_SECRET))
        assertCursorRefused(cursor)
    }

    @Test
    fun `INV-AC-29278 after the key moved to K2 a new cursor is signed with K2, not with the previous K1`() {
        putKey(k2)
        putKey(k1, ChainPageCursor.PREVIOUS_SECRET)
        val cursor = nextCursor()
        Files.delete(mount.resolve(ChainPageCursor.PREVIOUS_SECRET))
        assertSecondPage(cursor)
    }

    @Test
    fun `INV-AC-29278 with no usable current key a request that must issue or open a cursor fails, no cursor sent`() {
        val cursor = nextCursor()
        putKey(k1, ChainPageCursor.PREVIOUS_SECRET)
        listOf(null, "", " ".repeat(40), "k".repeat(31)).forEach { bad ->
            val key = mount.resolve(ChainPageCursor.CURRENT_SECRET)
            if (bad == null) Files.delete(key) else putKey(bad)
            withClue("current key '$bad'") {
                // not a LookupRefusal: the exception leaves the controller with no body and Spring answers 500
                shouldThrow<IllegalStateException> { controller(user()).getAcknowledgementChain("A1", 1, null) }
                calls.clear()
                shouldThrow<IllegalStateException> { controller(user()).getAcknowledgementChain("A1", 1, cursor) }
                calls shouldBe emptyList()
                // a whole chain on one page neither issues nor opens a cursor
                val whole = controller(user()).getAcknowledgementChain("A1", 100, null).body!!
                whole.nextCursor shouldBe null
                calls.clear()
            }
        }
    }
}
