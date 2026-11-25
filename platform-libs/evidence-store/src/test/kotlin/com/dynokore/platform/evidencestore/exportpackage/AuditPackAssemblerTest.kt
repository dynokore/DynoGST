package com.dynokore.platform.evidencestore.exportpackage

import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.DIGEST_MISMATCH
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuditPackAssemblerTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
    private val e1 = EntityRef("E1")
    private val e2 = EntityRef("E2")
    private val p1 = PeriodRef("P1")
    private val p2 = PeriodRef("P2")
    private val at = Instant.parse("2026-10-31T00:00:00Z")
    private val report = "Result: PASSED\n"
    private val sig = byteArrayOf(0x30, 0x03, 1, 2, 3)
    private val pem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"

    private fun cand(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        cls: PackageContentClass,
        name: String,
    ) = PackageCandidate(tenant, entity, period, PackageItem(cls, name, "$name-bytes".toByteArray()))

    private fun sixOf(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        tag: String,
    ) = PackageContentClass.entries.map { cand(tenant, entity, period, it, "$tag-${it.directory}.dat") }

    private fun source(files: PackageFiles) =
        object : PackageSource {
            override fun paths(): Set<String> = files.paths.toSet()

            override fun read(path: String): ByteArray = files.bytes(path)!!
        }

    private fun assembled(result: AssemblyResult): AssemblyResult.Assembled {
        assertTrue(result is AssemblyResult.Assembled) { "expected Assembled but was $result" }
        return result as AssemblyResult.Assembled
    }

    private fun dataPaths(files: PackageFiles) = files.paths.filter { it.startsWith("data/") }

    private fun scope() = PackageScope.EntityPeriod(t1, e1, p1)

    private fun mixed() = sixOf(t1, e1, p1, "a") + sixOf(t1, e1, p2, "b").take(1) + sixOf(t1, e2, p1, "c").take(1)

    @Test
    fun `INV-AC-13403 entity and period scope includes exactly the six and excludes the others`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed(), at, report, sig, pem))
        assertEquals(6, r.included)
        assertEquals(2, r.excluded)
        val expected = PackageContentClass.entries.map { "data/${it.directory}/a-${it.directory}.dat" }.sorted()
        assertEquals(expected, dataPaths(r.files))
    }

    @Test
    fun `INV-AC-13403 a foreign tenant look-alike refuses the whole assembly`() {
        val foreign = cand(t2, e1, p1, PackageContentClass.AUDIT_LOG, "foreign.dat")
        val r = AuditPackAssembler.assemble(scope(), mixed() + foreign, at, report, sig, pem)
        assertEquals(AssemblyResult.Refused(AssemblyRefusal.FOREIGN_TENANT, "foreign.dat"), r)
    }

    @Test
    fun `INV-AC-13403 a foreign tenant item of another entity is refused, not dropped`() {
        val foreign = cand(t2, e2, p2, PackageContentClass.AUDIT_LOG, "foreign.dat")
        val r = AuditPackAssembler.assemble(scope(), mixed() + foreign, at, report, sig, pem)
        assertEquals(AssemblyResult.Refused(AssemblyRefusal.FOREIGN_TENANT, "foreign.dat"), r)
    }

    @Test
    fun `INV-AC-13403 a period differing only in case is excluded`() {
        val lower = cand(t1, e1, PeriodRef("p1"), PackageContentClass.AUDIT_LOG, "lower.dat")
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed() + lower, at, report, sig, pem))
        assertEquals(6, r.included)
        assertEquals(3, r.excluded)
        assertTrue(dataPaths(r.files).none { it.endsWith("lower.dat") })
    }

    @Test
    fun `INV-AC-13404 whole tenant package holds every candidate, is offboarding and verifies`() {
        val all = sixOf(t1, e1, p1, "a") + sixOf(t1, e1, p2, "b") + sixOf(t1, e2, p1, "c") + sixOf(t1, e2, p2, "d")
        val r = assembled(AuditPackAssembler.assemble(PackageScope.WholeTenant(t1), all, at, null, null, null))
        assertEquals(24, r.included)
        assertEquals(0, r.excluded)
        val bagInfo = String(r.files.bytes("bag-info.txt")!!)
        assertTrue("Package-Kind: OFFBOARDING" in bagInfo.lines())
        assertTrue(bagInfo.lines().none { it.startsWith("Entity:") || it.startsWith("Period:") })
        val v = PackageVerifier.verify(source(r.files))
        assertTrue(v is PackageVerification.Valid) { "expected Valid but was $v" }
        val counts = (v as PackageVerification.Valid).itemCounts
        PackageContentClass.entries.forEach {
            assertEquals(
                all.count { c ->
                    c.item.contentClass == it
                },
                counts[it.directory],
            )
        }

        val flipped =
            r.files.paths
                .associateWith { r.files.bytes(it)!! }
                .toMutableMap()
        val target = dataPaths(r.files).first()
        flipped[target]!![0] = (flipped.getValue(target)[0].toInt() xor 1).toByte()
        val bad =
            PackageVerifier.verify(
                object : PackageSource {
                    override fun paths(): Set<String> = flipped.keys

                    override fun read(path: String): ByteArray = flipped.getValue(path)
                },
            )
        assertTrue(bad is PackageVerification.Invalid) { "expected Invalid but was $bad" }
        assertTrue((bad as PackageVerification.Invalid).problems.any { it.code == DIGEST_MISMATCH })
    }

    @Test
    fun `INV-AC-13404 the entity period result verifies as valid`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed(), at, report, sig, pem))
        val v = PackageVerifier.verify(source(r.files))
        assertTrue(v is PackageVerification.Valid) { "expected Valid but was $v" }
        assertEquals(6, (v as PackageVerification.Valid).payloadFiles)
    }

    @Test
    fun `a duplicate class and name is refused`() {
        val dup = sixOf(t1, e1, p1, "a") + cand(t1, e1, p1, PackageContentClass.AUDIT_LOG, "a-audit-log.dat")
        val r = AuditPackAssembler.assemble(scope(), dup, at, report, sig, pem)
        assertEquals(AssemblyResult.Refused(AssemblyRefusal.DUPLICATE_ITEM, "a-audit-log.dat"), r)
    }

    @Test
    fun `an empty candidate list gives a valid empty package`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), emptyList(), at, report, sig, pem))
        assertEquals(0, r.included)
        assertEquals(0, r.excluded)
        assertTrue(PackageVerifier.verify(source(r.files)) is PackageVerification.Valid)
    }

    @Test
    fun `INV-AC-24773 the assembler passes the report to the writer and refuses a bad one`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed(), at, report, sig, pem))
        assertEquals(report, String(r.files.bytes("verification-report.txt")!!, Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            AuditPackAssembler.assemble(scope(), mixed(), at, null, sig, pem)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuditPackAssembler.assemble(PackageScope.WholeTenant(t1), mixed(), at, report, null, null)
        }
        val w =
            assembled(AuditPackAssembler.assemble(PackageScope.WholeTenant(t1), mixed(), at, null, null, null))
        assertEquals(null, w.files.bytes("verification-report.txt"))
    }

    @Test
    fun `INV-AC-27739 the assembler passes the signature and key to the writer`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed(), at, report, sig, pem))
        org.junit.jupiter.api.Assertions
            .assertArrayEquals(sig, r.files.bytes("verification-report.p7s"))
        assertEquals("$pem\n", String(r.files.bytes("verification-key.pem")!!, Charsets.UTF_8))
        assertTrue(PackageVerifier.verify(source(r.files)) is PackageVerification.Valid)
        assertThrows(IllegalArgumentException::class.java) {
            AuditPackAssembler.assemble(PackageScope.WholeTenant(t1), mixed(), at, null, sig, pem)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuditPackAssembler.assemble(scope(), mixed(), at, report, sig, null)
        }
    }

    @Test
    fun `INV-AC-27779 a signed assembled package is valid`() {
        val r = assembled(AuditPackAssembler.assemble(scope(), mixed(), at, report, sig, pem))
        val written =
            AuditPackWriter.write(
                scope(),
                mixed().filter { it.entity == e1 && it.period == p1 }.map { it.item },
                at,
                report,
                sig,
                pem,
            )
        assertEquals(written.paths, r.files.paths)
        written.paths.forEach { assertArrayEquals(written.bytes(it), r.files.bytes(it), it) }
        val v = PackageVerifier.verify(source(r.files))
        assertTrue(v is PackageVerification.Valid) { "expected Valid but was $v" }
    }
}
