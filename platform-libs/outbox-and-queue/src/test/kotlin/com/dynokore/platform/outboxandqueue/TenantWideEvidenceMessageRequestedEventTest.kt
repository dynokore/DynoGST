package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The envelope accepts the tenant-wide entity for one pair beside the five exact types: the type
 * MessageRequested of access together with the source of evidence-svc, both by string equality (owner ruling,
 * chat 2026-11-26, option A; INV-REQ-1162, INV-AC-29022), still under a real tenant.
 */
class TenantWideEvidenceMessageRequestedEventTest {
    private val json = EnvelopeJson()
    private val tenant = TenantId(UUID.randomUUID())

    private fun envelope(
        type: String,
        source: String,
        tenant: TenantId = this.tenant,
        entity: EntityRef = EntityRef.TENANT_WIDE,
    ) = Envelope(
        id = EventId(UuidV7().next(Instant.parse("2026-10-01T00:00:00Z")).toString()),
        source = source,
        type = type,
        subject = "message-1",
        time = Instant.parse("2026-10-01T00:00:00Z"),
        tenant = tenant,
        entity = entity,
        docUuid = null,
        transactionDate = LocalDate.of(2026, 9, 30),
        docSequence = null,
        instanceId = "evidence-svc-1",
        correlation = CorrelationId("c"),
        causation = CausationId("k"),
        dataJson = """{"version":1}""",
    )

    /** The wire text of the accepted pair with its type and source replaced. */
    private fun record(
        type: String,
        source: String,
    ) = json
        .write(envelope(ALLOWED, SOURCE))
        .replace("\"$ALLOWED\"", "\"$type\"")
        .replace("\"$SOURCE\"", "\"$source\"")

    private fun assertEntityRefRequired(record: String) {
        val refusal = assertThrows(ContractViolation::class.java, { json.read(record) }, record)
        assertTrue(refusal.message!!.contains("entityref is required"), "$record: ${refusal.message}")
    }

    @Test
    fun `INV-AC-29022 the pair with a real tenant and the tenant-wide entity round-trips without entityref`() {
        val written = envelope(ALLOWED, SOURCE)
        val text = json.write(written)
        assertFalse(text.contains("entityref") || text.contains("00000000-0000"), text)
        assertEquals(written, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
        assertEquals(tenant, json.read(text).tenant)
        assertTrue(Envelope.allowsTenantWide(ALLOWED, SOURCE))
        assertFalse(Envelope.allowsTenantWide(ALLOWED))
        assertFalse(Envelope.isPlatformType(ALLOWED))
    }

    @Test
    fun `INV-AC-29022 the pair is refused under the platform tenant`() {
        assertThrows(IllegalArgumentException::class.java) { envelope(ALLOWED, SOURCE, tenant = TenantId.PLATFORM) }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(ALLOWED, SOURCE, tenant = TenantId.PLATFORM, entity = EntityRef.PLATFORM)
        }
        // On the wire a record without tenantid is a platform record: the pair is not one.
        val tenantless = record(ALLOWED, SOURCE).replace("\"tenantid\":\"${tenant.value}\",", "")
        assertFalse(tenantless.contains("tenantid"), tenantless)
        assertThrows(ContractViolation::class.java) { json.read(tenantless) }
    }

    @Test
    fun `INV-AC-29022 the type from any other source is refused at construction and at read`() {
        REFUSED_SOURCES.forEach { source ->
            assertFalse(Envelope.allowsTenantWide(ALLOWED, source), source)
            assertThrows(IllegalArgumentException::class.java, { envelope(ALLOWED, source) }, source)
            assertEntityRefRequired(record(ALLOWED, source))
        }
    }

    @Test
    fun `INV-AC-29022 the second function compares the source by exact string equality`() {
        MALFORMED_SOURCES.forEach { source ->
            assertFalse(Envelope.allowsTenantWide(ALLOWED, source), source)
            assertThrows(IllegalArgumentException::class.java, { envelope(ALLOWED, source) }, source)
            assertThrows(ContractViolation::class.java, { json.read(record(ALLOWED, source)) }, source)
        }
    }

    @Test
    fun `INV-AC-29022 every type look-alike and unlisted domain type of the evidence source is refused`() {
        REFUSED_TYPES.forEach { type ->
            assertFalse(Envelope.allowsTenantWide(type, SOURCE), type)
            assertFalse(Envelope.allowsTenantWide(type), type)
            assertThrows(IllegalArgumentException::class.java, { envelope(type, SOURCE) }, type)
            assertEntityRefRequired(record(type, SOURCE))
        }
    }

    @Test
    fun `INV-AC-29022 a record of the pair without a source is refused`() {
        val sourceless = record(ALLOWED, SOURCE).replace("\"source\":\"$SOURCE\",", "")
        assertFalse(sourceless.contains("source"), sourceless)
        val refusal = assertThrows(ContractViolation::class.java) { json.read(sourceless) }
        assertTrue(refusal.message!!.contains("source is required"), refusal.message)
    }

    @Test
    fun `INV-AC-29022 a record of the pair naming the reserved tenant-wide id explicitly is refused`() {
        val reserved = "\"entityref\":\"${EntityRef.TENANT_WIDE}\",\"instanceid\""
        val named = record(ALLOWED, SOURCE).replace("\"instanceid\"", reserved)
        assertTrue(named.contains("entityref"), named)
        assertThrows(ContractViolation::class.java) { json.read(named) }
    }

    @Test
    fun `INV-AC-29022 the pair keeps a registered entity and the type keeps one from any source`() {
        (REFUSED_SOURCES + SOURCE).forEach { source ->
            val written = envelope(ALLOWED, source, entity = EntityRef("entity-1"))
            assertEquals(written, json.read(json.write(written)), source)
        }
    }

    @Test
    fun `INV-AC-29022 the five exact types are still accepted with any source`() {
        FIVE_TYPES.forEach { type ->
            assertTrue(Envelope.allowsTenantWide(type), type)
            (REFUSED_SOURCES + SOURCE).forEach { source ->
                assertTrue(Envelope.allowsTenantWide(type, source), "$type $source")
                val written = envelope(type, source)
                assertEquals(written, json.read(json.write(written)), "$type $source")
                assertThrows(IllegalArgumentException::class.java, {
                    envelope(type, source, tenant = TenantId.PLATFORM)
                }, "$type $source")
            }
        }
    }

    @Test
    fun `INV-AC-29022 a platform type behaves as before with any source`() {
        assertTrue(Envelope.isPlatformType(PLATFORM_TYPE))
        assertTrue(Envelope.allowsTenantWide(PLATFORM_TYPE))
        (REFUSED_SOURCES + SOURCE).forEach { source ->
            assertTrue(Envelope.allowsTenantWide(PLATFORM_TYPE, source), source)
            val wide = envelope(PLATFORM_TYPE, source)
            assertEquals(wide, json.read(json.write(wide)), source)
            val tenantless = envelope(PLATFORM_TYPE, source, tenant = TenantId.PLATFORM, entity = EntityRef.PLATFORM)
            assertEquals(tenantless, json.read(json.write(tenantless)), source)
        }
    }

    companion object {
        const val ALLOWED = "com.dynokore.gst.access.MessageRequested.v1"
        const val SOURCE = "/gst/evidence-svc"
        const val PLATFORM_TYPE = "com.dynokore.gst.platform.AuditEntryRecorded.v1"
        val FIVE_TYPES =
            listOf(
                "com.dynokore.gst.transport.AccessPointRouteChanged.v1",
                "com.dynokore.gst.tenancy.PlanLimitExceeded.v1",
                "com.dynokore.gst.submission.UnsubmittedDocumentCountMeasured.v1",
                "com.dynokore.gst.evidence.OffboardingExportFinished.v1",
                "com.dynokore.gst.tenancy.OffboardingExportRequested.v1",
            )

        /** Well-formed sources (`/gst/<service>`) that are not the one of evidence-svc. */
        val REFUSED_SOURCES =
            listOf(
                "/gst/access-svc",
                "/gst/control-plane-svc",
                "/gst/evidence-svcx",
                "/gst/xevidence-svc",
                "/gst/evidence-sv",
                "/gst/evidence",
            )

        /** Look-alikes that are not a well-formed source at all. */
        val MALFORMED_SOURCES =
            listOf(
                "/gst/Evidence-svc",
                "/GST/EVIDENCE-SVC",
                "/gst/evidence-svc/",
                "/gst/evidence-svc ",
                "evidence-svc",
                "/gst/evidence-svc,/gst/access-svc",
                "/gst/*",
                "*",
            )

        /** Well-formed event types that are not the one of the pair. */
        val REFUSED_TYPES =
            listOf(
                "com.dynokore.gst.access.MessageRequested.v2",
                "com.dynokore.gst.access.MessageRequested.v10",
                "com.dynokore.gst.access.messageRequested.v1",
                "com.dynokore.gst.access.MESSAGEREQUESTED.v1",
                "com.dynokore.gst.access.MessageRequestedX.v1",
                "com.dynokore.gst.access.XMessageRequested.v1",
                "com.dynokore.gst.accessx.MessageRequested.v1",
                "com.dynokore.gst.evidence.MessageRequested.v1",
                "com.dynokore.gst.platform.MessageRequested.v1",
                "com.dynokore.gst.evidence.EvidenceExported.v1",
                "com.dynokore.gst.access.RoleAssigned.v1",
            )
    }
}

/**
 * INV-AC-29022 on PostgreSQL: the outbox writer stores the pair tenant-wide for the service evidence-svc and
 * writes no row for another service, a type look-alike or the platform tenant. Both services are catalogued
 * producers of every type, so each refusal is the writer's entity rule and not the producer list.
 */
@Tag("integration")
class TenantWideEvidenceMessageRequestedEventIT {
    private val tenantWide = "00000000-0000-0000-0000-000000000000"

    private fun catalogue(): EventCatalogue {
        val types =
            listOf(TenantWideEvidenceMessageRequestedEventTest.ALLOWED) +
                TenantWideEvidenceMessageRequestedEventTest.REFUSED_TYPES
        val yaml =
            "topics:\n" +
                types
                    .mapIndexed { i, type ->
                        "  - topic: gst.access.t$i.v1\n    event_type: $type\n    content_topic: true\n" +
                            "    retained_while_resolvable: false\n    record_topic: false\n" +
                            "    producers:\n" +
                            PRODUCERS.joinToString("") { "      - $it\n" } +
                            "    subscribers:\n      - submission-svc\n"
                    }.joinToString("")
        return TopicsYamlCatalogue.parse(yaml)
    }

    private fun context(tenant: TenantId) =
        EventContext(
            tenant = tenant,
            entity = if (tenant.isPlatform) EntityRef.PLATFORM else EntityRef.TENANT_WIDE,
            docUuid = null,
            transactionDate = LocalDate.of(2026, 9, 30),
            aggregateKey = "message-1",
            aggregateVersion = 1,
            correlation = CorrelationId("corr"),
            causation = CausationId("cmd"),
        )

    private fun assertRefused(
        kit: ServiceKit,
        tenant: UUID,
        type: String,
        reason: String,
    ) {
        val refusal =
            assertThrows(EmissionNotPermitted::class.java, {
                kit.inTx(tenant) { kit.writer.append(listOf(OutboundEvent(type, "{}")), context(TenantId(tenant))) }
            }, "${kit.service} $type")
        assertTrue(refusal.message!!.contains(reason), "${kit.service} $type: ${refusal.message}")
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-29022 the writer stores the pair for evidence-svc only`(image: String) {
        OutboxHarness(image).use { harness ->
            val evidence = ServiceKit(harness, "evidence", "evidence-svc", catalogue())
            val tenant = UUID.randomUUID()
            val allowed = TenantWideEvidenceMessageRequestedEventTest.ALLOWED

            listOf("access-svc", "control-plane-svc", "evidence-svcx").forEach { service ->
                val other = ServiceKit(harness, "payload", service, catalogue())
                assertRefused(other, tenant, allowed, CANNOT_CARRY)
            }
            assertEquals(0, harness.count("select count(*) from payload.outbox"))

            TenantWideEvidenceMessageRequestedEventTest.REFUSED_TYPES.forEach { type ->
                assertRefused(evidence, tenant, type, CANNOT_CARRY)
            }
            assertThrows(EmissionNotPermitted::class.java) {
                evidence.inPlatformTx {
                    evidence.writer.append(listOf(OutboundEvent(allowed, "{}")), context(TenantId.PLATFORM))
                }
            }
            assertEquals(0, harness.count("select count(*) from evidence.outbox"))

            evidence.inTx(tenant) {
                evidence.writer.append(listOf(OutboundEvent(allowed, "{}")), context(TenantId(tenant)))
            }
            assertEquals(
                1,
                harness.count(
                    "select count(*) from evidence.outbox where entity_ref = '$tenantWide' " +
                        "and tenant_id = '$tenant' and source = '/gst/evidence-svc' and type = '$allowed'",
                ),
            )
            assertEquals(1, harness.count("select count(*) from evidence.outbox"))
            assertEquals(0, harness.count("select count(*) from payload.outbox"))
        }
    }

    private companion object {
        const val CANNOT_CARRY = "cannot carry the tenant-wide entity"
        val PRODUCERS = listOf("evidence-svc", "access-svc", "control-plane-svc", "evidence-svcx")
    }
}
