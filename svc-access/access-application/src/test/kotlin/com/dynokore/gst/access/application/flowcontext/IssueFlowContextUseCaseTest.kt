package com.dynokore.gst.access.application.flowcontext

import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IssueFlowContextUseCaseTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val calls = mutableListOf<FlowContextClaims>()
    private var unavailable = false
    private val now = Instant.parse("2026-11-23T10:00:00Z")

    private fun useCase(at: Instant = now) =
        IssueFlowContextUseCase(
            { c ->
                calls += c
                if (unavailable) throw SigningKeyUnavailable("no key")
                "compact-1"
            },
            Clock.fixed(at, ZoneOffset.UTC),
        )

    private fun issue(
        caller: String?,
        tenant: TenantId = t1,
        entity: FlowEntity = FlowEntity(FlowEntityKind.ENTITY, "E1"),
        uc: IssueFlowContextUseCase = useCase(),
    ) = uc.issue(caller, tenant, entity, FlowPrincipalKind.HUMAN, "F1")

    @Test
    fun `INV-AC-27492 case 1 ingestion-svc is issued with exp = iat + 900`() {
        val r = issue("ingestion-svc")
        val exp = now.epochSecond + 900
        assertEquals(IssueFlowContextResult.Issued("compact-1", exp), r)
        assertEquals(
            listOf(
                FlowContextClaims(
                    t1,
                    FlowEntity(FlowEntityKind.ENTITY, "E1"),
                    FlowPrincipalKind.HUMAN,
                    "ingestion-svc",
                    "F1",
                    now.epochSecond,
                    exp,
                ),
            ),
            calls,
        )
    }

    @Test
    fun `INV-AC-27492 case 2 control-plane-svc with tenant-wide entity and no ref`() {
        val e = FlowEntity(FlowEntityKind.TENANT_WIDE)
        val r = issue("control-plane-svc", entity = e)
        assertEquals(IssueFlowContextResult.Issued("compact-1", now.epochSecond + 900), r)
        assertEquals(e, calls.single().entity)
        assertEquals("control-plane-svc", calls.single().callerService)
    }

    @Test
    fun `INV-AC-27492 case 3 submission-svc is not served`() = notServed("submission-svc")

    @Test
    fun `INV-AC-27492 case 4 absent caller is not served`() = notServed(null)

    @Test
    fun `INV-AC-27492 look-alike ingestion-svc-x is not served`() = notServed("ingestion-svc-x")

    @Test
    fun `INV-AC-27492 look-alike Ingestion-Svc is not served`() = notServed("Ingestion-Svc")

    @Test
    fun `INV-AC-27492 look-alike trailing space is not served`() = notServed("ingestion-svc ")

    @Test
    fun `INV-AC-27492 the four listed callers are served`() {
        listOf("ingestion-svc", "canonical-svc", "payload-svc", "control-plane-svc").forEach {
            assertTrue(issue(it) is IssueFlowContextResult.Issued, it)
        }
        assertEquals(4, calls.size)
    }

    @Test
    fun `INV-AC-27492 case 5 platform tenant is an invalid request`() {
        assertEquals(IssueFlowContextResult.InvalidRequest, issue("ingestion-svc", tenant = TenantId.PLATFORM))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `INV-AC-27492 case 6 entity without ref is an invalid request`() {
        assertEquals(
            IssueFlowContextResult.InvalidRequest,
            issue("ingestion-svc", entity = FlowEntity(FlowEntityKind.ENTITY)),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `INV-AC-27492 case 7 unavailable key is signing unavailable`() {
        unavailable = true
        assertEquals(IssueFlowContextResult.SigningUnavailable, issue("ingestion-svc"))
    }

    @Test
    fun `INV-AC-27492 the caller check comes first and the port is not called`() {
        assertEquals(IssueFlowContextResult.CallerNotServed, issue("submission-svc", tenant = TenantId.PLATFORM))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `INV-AC-27492 a clock moved one second moves iat and exp by one second and keeps 900`() {
        issue("ingestion-svc")
        issue("ingestion-svc", uc = useCase(now.plusSeconds(1)))
        assertEquals(calls[0].iat + 1, calls[1].iat)
        assertEquals(calls[0].exp + 1, calls[1].exp)
        calls.forEach { assertEquals(900L, it.exp - it.iat) }
    }

    @Test
    fun `INV-AC-27492 the lifetime is the constant 900 and the use case takes no lifetime argument`() {
        assertEquals(900L, FLOW_CONTEXT_LIFETIME_SECONDS)
        val issue = IssueFlowContextUseCase::class.java.declaredMethods.single { it.name.startsWith("issue") }
        assertEquals(
            listOf("String", "UUID", "FlowEntity", "FlowPrincipalKind", "String"),
            issue.parameterTypes.map { it.simpleName },
        )
    }

    private fun notServed(caller: String?) {
        assertEquals(IssueFlowContextResult.CallerNotServed, issue(caller))
        assertTrue(calls.isEmpty())
    }
}
