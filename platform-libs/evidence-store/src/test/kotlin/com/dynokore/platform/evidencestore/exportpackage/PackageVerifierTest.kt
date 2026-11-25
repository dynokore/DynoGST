package com.dynokore.platform.evidencestore.exportpackage

import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.BAD_DECLARATION
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.CLASS_COUNT_MISMATCH
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.DIGEST_MISMATCH
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.DUPLICATE_MANIFEST_ENTRY
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.LISTED_FILE_MISSING
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.MALFORMED_MANIFEST_LINE
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.MANIFEST_PATH_NOT_PAYLOAD
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.MISSING_BAG_INFO_FIELD
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.OXUM_MISMATCH
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.PAYLOAD_FILE_NOT_LISTED
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.SOURCE_READ_FAILED
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.TAG_DIGEST_MISMATCH
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.UNKNOWN_CONTENT_DIRECTORY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

@Suppress("LargeClass") // one test per acceptance-criterion case of INV-AC-13402, each on its own package copy
class PackageVerifierTest {
    private val baseTags = listOf("bagit.txt", "bag-info.txt", "README.txt", "manifest-sha256.txt")
    private val reportPath = "verification-report.txt"
    private val reportBytes = "Result: PASSED\n".toByteArray()
    private val sigPath = "verification-report.p7s"
    private val keyPath = "verification-key.pem"
    private val dirs =
        listOf(
            "transmitted-xml",
            "acknowledgements",
            "decision-traces",
            "reconciliation-work-papers",
            "source-document-references",
            "audit-log",
        )

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T) { "expected ${T::class.simpleName} but was $value" }
        return value as T
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    /** Builds a correct package in memory with JDK code only; [payload] maps a `data/...` path to bytes. */
    private fun bag(
        payload: Map<String, ByteArray>,
        counts: Map<String, Int> = dirs.associateWith { d -> payload.keys.count { it.startsWith("data/$d/") } },
        octets: Long = payload.values.sumOf { it.size }.toLong(),
        manifestExtra: List<String> = emptyList(),
        omitLabel: String? = null,
        kind: String = "AUDIT_PACK",
        signing: Boolean = true,
    ): MutableMap<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        files["bagit.txt"] = "BagIt-Version: 1.0\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
        val info =
            buildList {
                add("Package-Kind: $kind")
                add("Tenant: 00000000-0000-0000-0000-000000000001")
                add("Generated-At: 2026-10-30T00:00:00Z")
                add("Payload-Oxum: $octets.${payload.size}")
                dirs.forEach { add("Item-Count-$it: ${counts.getValue(it)}") }
            }.filterNot { omitLabel != null && it.startsWith("$omitLabel:") }
        files["bag-info.txt"] = (info.joinToString("\n") + "\n").toByteArray()
        files["README.txt"] = "Check with sha256sum -c manifest-sha256.txt\n".toByteArray()
        files.putAll(payload)
        val lines = payload.toSortedMap().map { (p, b) -> "${sha(b)}  $p" } + manifestExtra
        files["manifest-sha256.txt"] = (lines.joinToString("") { it + "\n" }).toByteArray()
        if (signing) {
            files[reportPath] = reportBytes
            files[sigPath] = byteArrayOf(0x30, 3, 1, 2, 3)
            files[keyPath] = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n".toByteArray()
        }
        retag(files)
        return files
    }

    private fun retag(files: MutableMap<String, ByteArray>) {
        val tags = baseTags + listOf(reportPath, sigPath, keyPath).filter { it in files }
        tagManifest(files, tags)
    }

    private fun tagManifest(
        files: MutableMap<String, ByteArray>,
        tags: List<String>,
    ) {
        files["tagmanifest-sha256.txt"] = tags.joinToString("") { "${sha(files.getValue(it))}  $it\n" }.toByteArray()
    }

    private fun full(): Map<String, ByteArray> =
        dirs.associate {
            "data/$it/item-1.bin" to
                "content of $it".toByteArray()
        }

    private fun source(
        files: Map<String, ByteArray>,
        failing: String? = null,
    ) = object : PackageSource {
        override fun paths(): Set<String> = files.keys.toSet()

        override fun read(path: String): ByteArray {
            check(path != failing) { "disk error" }
            return files.getValue(path)
        }
    }

    private fun verify(
        files: Map<String, ByteArray>,
        failing: String? = null,
    ) = PackageVerifier.verify(source(files, failing))

    private fun problems(v: PackageVerification) = assertIs<PackageVerification.Invalid>(v).problems

    private fun single(
        v: PackageVerification,
        code: PackageProblemCode,
        path: String?,
    ) = assertEquals(listOf(PackageProblem(code, path)), problems(v))

    private fun editManifest(
        files: MutableMap<String, ByteArray>,
        edit: (List<String>) -> List<String>,
    ) {
        files["manifest-sha256.txt"] =
            (
                edit(
                    String(files.getValue("manifest-sha256.txt")).trimEnd('\n').split("\n"),
                ).joinToString("\n") + "\n"
            ).toByteArray()
        retag(files)
    }

    @Test
    fun `INV-AC-13402 correct package with six items is valid with counts of one`() {
        val v = assertIs<PackageVerification.Valid>(verify(bag(full())))
        assertEquals(dirs.associateWith { 1 }, v.itemCounts)
        assertEquals(6, v.payloadFiles)
        assertEquals(full().values.sumOf { it.size }.toLong(), v.payloadOctets)
    }

    @Test
    fun `INV-AC-13402 correct package with no item is valid with six zero counts`() {
        val v = assertIs<PackageVerification.Valid>(verify(bag(emptyMap())))
        assertEquals(dirs.associateWith { 0 }, v.itemCounts)
        assertEquals(0, v.payloadFiles)
    }

    @Test
    fun `INV-AC-13402 item counts come from the files not from bag-info`() {
        val payload = full() + ("data/audit-log/item-2.bin" to byteArrayOf(1))
        val v = assertIs<PackageVerification.Valid>(verify(bag(payload)))
        assertEquals(2, v.itemCounts.getValue("audit-log"))
    }

    @Test
    fun `INV-AC-13402 flipped payload byte is a digest mismatch`() {
        val f = bag(full())
        val p = "data/audit-log/item-1.bin"
        f[p] = f.getValue(p).also { it[0] = (it[0].toInt() xor 1).toByte() }
        single(verify(f), DIGEST_MISMATCH, p)
    }

    @Test
    fun `INV-AC-13402 deleted payload file is a missing listed file`() {
        val payload = full()
        val gone = "data/acknowledgements/item-1.bin"
        val f = bag(payload - gone, manifestExtra = listOf("${sha(payload.getValue(gone))}  $gone"))
        single(verify(f), LISTED_FILE_MISSING, gone)
    }

    @Test
    fun `INV-AC-13402 extra file under transmitted-xml is not listed`() {
        val extra = "data/transmitted-xml/extra.xml"
        val f = bag(full() + (extra to byteArrayOf(5)))
        editManifest(f) { lines -> lines.filterNot { it.endsWith(extra) } }
        single(verify(f), PAYLOAD_FILE_NOT_LISTED, extra)
    }

    @Test
    fun `INV-AC-13402 edited bag-info is a tag digest mismatch`() {
        val f = bag(full())
        f["bag-info.txt"] = String(f.getValue("bag-info.txt")).replace("AUDIT_PACK", "OFFBOARDING").toByteArray()
        single(verify(f), TAG_DIGEST_MISMATCH, "bag-info.txt")
    }

    @Test
    fun `INV-AC-13402 declaration with version 0_97 is bad`() {
        val f = bag(full())
        f["bagit.txt"] = "BagIt-Version: 0.97\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
        retag(f)
        single(verify(f), BAD_DECLARATION, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 declaration with a byte order mark is bad`() {
        val f = bag(full())
        f["bagit.txt"] = "\uFEFFBagIt-Version: 1.0\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
        retag(f)
        single(verify(f), BAD_DECLARATION, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 declaration with a third line is bad`() {
        val f = bag(full())
        f["bagit.txt"] = "BagIt-Version: 1.0\nTag-File-Character-Encoding: UTF-8\nX: y\n".toByteArray()
        retag(f)
        single(verify(f), BAD_DECLARATION, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 removed Item-Count-audit-log label is a missing field`() {
        val f = bag(full(), omitLabel = "Item-Count-audit-log")
        single(verify(f), MISSING_BAG_INFO_FIELD, "Item-Count-audit-log")
    }

    @Test
    fun `INV-AC-13402 wrong octet sum is an oxum mismatch`() {
        val payload = full()
        val f = bag(payload, octets = payload.values.sumOf { it.size }.toLong() + 1)
        single(verify(f), OXUM_MISMATCH, "Payload-Oxum")
    }

    @Test
    fun `INV-AC-13402 wrong class count is a count mismatch`() {
        val f = bag(full(), counts = dirs.associateWith { 1 } + ("decision-traces" to 2))
        single(verify(f), CLASS_COUNT_MISMATCH, "Item-Count-decision-traces")
    }

    @Test
    fun `INV-AC-13402 file under data other is an unknown content directory`() {
        val payload = full() + ("data/other/x.bin" to byteArrayOf(7))
        val f = bag(payload)
        single(verify(f), UNKNOWN_CONTENT_DIRECTORY, "data/other/x.bin")
    }

    @Test
    fun `INV-AC-13402 manifest paths outside data are not payload paths`() {
        for (bad in listOf("../x", "/etc/x", "x/data/y", "data/../x", "data/./x", "data\\x")) {
            val f = bag(full(), manifestExtra = listOf("${"0".repeat(64)}  $bad"))
            single(verify(f), MANIFEST_PATH_NOT_PAYLOAD, bad)
        }
    }

    @Test
    fun `INV-AC-13402 duplicate manifest line is reported`() {
        val f = bag(full())
        editManifest(f) { it + it.first() }
        single(verify(f), DUPLICATE_MANIFEST_ENTRY, "data/acknowledgements/item-1.bin")
    }

    @Test
    fun `INV-AC-13402 short checksum is a malformed line`() {
        val f = bag(full(), manifestExtra = listOf("${"a".repeat(63)}  data/audit-log/short.bin"))
        single(verify(f), MALFORMED_MANIFEST_LINE, "manifest-sha256.txt")
    }

    @Test
    fun `INV-AC-13402 upper-case checksum of a correct file is not a problem`() {
        val f = bag(full())
        editManifest(f) { lines -> lines.map { it.substring(0, 64).uppercase() + it.substring(64) } }
        assertIs<PackageVerification.Valid>(verify(f))
    }

    @Test
    fun `INV-AC-13402 source that throws on one file is a read failure and not an exception`() {
        val p = "data/audit-log/item-1.bin"
        single(verify(bag(full()), failing = p), SOURCE_READ_FAILED, p)
    }

    @Test
    fun `INV-AC-13402 source that throws on listing is a read failure`() {
        val v =
            PackageVerifier.verify(
                object : PackageSource {
                    override fun paths(): Set<String> = error("gone")

                    override fun read(path: String): ByteArray = error("gone")
                },
            )
        single(v, SOURCE_READ_FAILED, null)
    }

    @Test
    fun `INV-AC-13402 two changes report both codes in code order`() {
        val f = bag(full())
        val p = "data/audit-log/item-1.bin"
        f[p] = f.getValue(p).also { it[0] = (it[0].toInt() xor 1).toByte() }
        f["bagit.txt"] = "BagIt-Version: 0.97\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
        retag(f)
        assertEquals(
            listOf(PackageProblem(BAD_DECLARATION, "bagit.txt"), PackageProblem(DIGEST_MISMATCH, p)),
            problems(verify(f)),
        )
    }

    @Test
    fun `manifest path with percent-encoded line feed decodes to a real line feed in the name`() {
        val f = bag(mapOf("data/audit-log/a\nb.bin" to byteArrayOf(1, 2)))
        f["manifest-sha256.txt"] = "${sha(byteArrayOf(1, 2))}  data/audit-log/a%0Ab.bin\n".toByteArray()
        retag(f)
        assertIs<PackageVerification.Valid>(verify(f))
    }

    @Test
    fun `a missing declaration, manifest or tag manifest is reported`() {
        val f = bag(full())
        f.remove("bagit.txt")
        f.remove("manifest-sha256.txt")
        f.remove("tagmanifest-sha256.txt")
        val codes = problems(verify(f)).map { it.code }
        assertTrue(PackageProblemCode.MISSING_DECLARATION in codes)
        assertTrue(PackageProblemCode.MISSING_PAYLOAD_MANIFEST in codes)
        assertTrue(PackageProblemCode.MISSING_TAG_MANIFEST in codes)
        assertTrue(PAYLOAD_FILE_NOT_LISTED in codes)
    }

    @Test
    fun `a tag file missing from the tag manifest is reported`() {
        val f = bag(full())
        f["tagmanifest-sha256.txt"] =
            String(f.getValue("tagmanifest-sha256.txt"))
                .lines()
                .filterNot {
                    it.endsWith("README.txt")
                }.joinToString("\n")
                .toByteArray()
        single(verify(f), PackageProblemCode.TAG_FILE_NOT_LISTED, "README.txt")
    }

    private fun withEol(
        eol: String,
        vararg names: String,
    ): MutableMap<String, ByteArray> {
        val f = bag(full())
        for (n in names.filter { it != "tagmanifest-sha256.txt" }) {
            f[n] = String(f.getValue(n)).replace("\n", eol).toByteArray()
        }
        retag(f)
        if ("tagmanifest-sha256.txt" in names) {
            f["tagmanifest-sha256.txt"] = String(f.getValue("tagmanifest-sha256.txt")).replace("\n", eol).toByteArray()
        }
        return f
    }

    @Test
    fun `INV-AC-13402 CRLF and CR line ends are valid`() {
        for (eol in listOf("\r\n", "\r")) {
            assertIs<PackageVerification.Valid>(
                verify(withEol(eol, "manifest-sha256.txt", "tagmanifest-sha256.txt")),
            )
        }
        assertIs<PackageVerification.Valid>(verify(withEol("\r\n", "bagit.txt")))
        assertIs<PackageVerification.Valid>(verify(withEol("\r\n", "bag-info.txt")))
    }

    @Test
    fun `INV-AC-13402 manifest with an invalid UTF-8 byte is malformed`() {
        val f = bag(full())
        f["manifest-sha256.txt"] = f.getValue("manifest-sha256.txt") + byteArrayOf(0xC3.toByte(), 0x28, 0x0A)
        retag(f)
        assertTrue(PackageProblem(MALFORMED_MANIFEST_LINE, "manifest-sha256.txt") in problems(verify(f)))
        val g = bag(full())
        g["manifest-sha256.txt"] = "\u00e9".toByteArray(Charsets.ISO_8859_1) + g.getValue("manifest-sha256.txt")
        retag(g)
        assertEquals(
            PackageProblem(MALFORMED_MANIFEST_LINE, "manifest-sha256.txt"),
            problems(verify(g)).first(),
        )
    }

    @Test
    fun `INV-AC-13402 tag manifest with an invalid UTF-8 byte is malformed`() {
        val f = bag(full())
        f["tagmanifest-sha256.txt"] = byteArrayOf(0xFF.toByte()) + f.getValue("tagmanifest-sha256.txt")
        assertTrue(PackageProblem(MALFORMED_MANIFEST_LINE, "tagmanifest-sha256.txt") in problems(verify(f)))
    }

    @Test
    fun `INV-AC-13402 declaration that is not UTF-8 is bad`() {
        val f = bag(full())
        f["bagit.txt"] = byteArrayOf(0xFF.toByte()) + f.getValue("bagit.txt")
        retag(f)
        single(verify(f), BAD_DECLARATION, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 CR in the middle of a manifest line splits it and is malformed`() {
        val f = bag(full())
        editManifest(f) { lines -> listOf(lines.first().replaceFirst("  ", "  data/x\ry")) + lines.drop(1) }
        assertTrue(PackageProblem(MALFORMED_MANIFEST_LINE, "manifest-sha256.txt") in problems(verify(f)))
    }

    @Test
    fun `INV-AC-13402 duplicate line in the tag manifest is reported with the tag file path`() {
        val f = bag(full())
        val tm = String(f.getValue("tagmanifest-sha256.txt"))
        f["tagmanifest-sha256.txt"] = (tm + tm.lines().first() + "\n").toByteArray()
        single(verify(f), DUPLICATE_MANIFEST_ENTRY, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 duplicate manifest path with a wrong first checksum is not trusted`() {
        val f = bag(full())
        editManifest(f) { lines -> listOf("0".repeat(64) + lines.first().substring(64)) + lines }
        single(verify(f), DUPLICATE_MANIFEST_ENTRY, "data/acknowledgements/item-1.bin")
    }

    @Test
    fun `INV-AC-13402 paths in two letter cases listed once each are not a duplicate`() {
        val payload = full() + ("data/audit-log/A.bin" to byteArrayOf(1)) + ("data/audit-log/a.bin" to byteArrayOf(2))
        assertIs<PackageVerification.Valid>(verify(bag(payload)))
    }

    @Test
    fun `INV-AC-13402 one to six files in the six directories give exactly those counts`() {
        val payload = linkedMapOf<String, ByteArray>()
        dirs.forEachIndexed { i, d -> repeat(i + 1) { n -> payload["data/$d/f$n.bin"] = ByteArray(n + i + 1) { 3 } } }
        val v = assertIs<PackageVerification.Valid>(verify(bag(payload)))
        assertEquals(dirs.mapIndexed { i, d -> d to i + 1 }.toMap(), v.itemCounts)
        assertEquals(21, v.payloadFiles)
        assertEquals(payload.values.sumOf { it.size }.toLong(), v.payloadOctets)
    }

    @Test
    fun `INV-AC-13402 wrong octet sum with a throwing read reports only the read failure`() {
        val payload = full()
        val p = "data/audit-log/item-1.bin"
        val f = bag(payload, octets = payload.values.sumOf { it.size }.toLong() + 5)
        single(verify(f, failing = p), SOURCE_READ_FAILED, p)
    }

    @Test
    @Suppress("LongMethod") // the table of one package per problem code
    fun `INV-AC-13402 every problem code carries the path of the defect`() {
        val ok = full()
        val p = "data/audit-log/item-1.bin"
        val cases = mutableListOf<Triple<PackageProblemCode, String?, Map<String, ByteArray>>>()
        cases += Triple(PackageProblemCode.MISSING_DECLARATION, "bagit.txt", bag(ok).also { it.remove("bagit.txt") })
        cases +=
            Triple(
                PackageProblemCode.MISSING_PAYLOAD_MANIFEST,
                "manifest-sha256.txt",
                bag(emptyMap()).also { it.remove("manifest-sha256.txt") },
            )
        cases +=
            Triple(
                PackageProblemCode.MISSING_TAG_MANIFEST,
                "tagmanifest-sha256.txt",
                bag(ok).also { it.remove("tagmanifest-sha256.txt") },
            )
        cases +=
            Triple(
                PackageProblemCode.TAG_FILE_NOT_LISTED,
                "README.txt",
                bag(ok).also { f ->
                    f["tagmanifest-sha256.txt"] =
                        String(f.getValue("tagmanifest-sha256.txt"))
                            .lines()
                            .filterNot { it.endsWith("README.txt") }
                            .joinToString("\n")
                            .toByteArray()
                },
            )
        cases +=
            Triple(
                PackageProblemCode.SOURCE_READ_FAILED,
                p,
                bag(ok),
            )
        cases +=
            Triple(
                PackageProblemCode.BAD_DECLARATION,
                "bagit.txt",
                bag(ok).also {
                    it["bagit.txt"] = "BagIt-Version: 0.97\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
                    retag(it)
                },
            )
        cases +=
            Triple(
                PackageProblemCode.MALFORMED_MANIFEST_LINE,
                "manifest-sha256.txt",
                bag(ok, manifestExtra = listOf("abc  data/audit-log/x")),
            )
        cases +=
            Triple(
                PackageProblemCode.MANIFEST_PATH_NOT_PAYLOAD,
                "../x",
                bag(ok, manifestExtra = listOf("${"0".repeat(64)}  ../x")),
            )
        cases +=
            Triple(
                PackageProblemCode.DUPLICATE_MANIFEST_ENTRY,
                "data/acknowledgements/item-1.bin",
                bag(ok).also { f -> editManifest(f) { it + it.first() } },
            )
        cases +=
            Triple(
                PackageProblemCode.LISTED_FILE_MISSING,
                "data/audit-log/gone.bin",
                bag(ok, manifestExtra = listOf("${"0".repeat(64)}  data/audit-log/gone.bin")),
            )
        cases +=
            Triple(
                PackageProblemCode.PAYLOAD_FILE_NOT_LISTED,
                "data/audit-log/extra.bin",
                bag(ok + ("data/audit-log/extra.bin" to byteArrayOf(1))).also { f ->
                    editManifest(f) { l -> l.filterNot { it.endsWith("extra.bin") } }
                },
            )
        cases +=
            Triple(
                PackageProblemCode.DIGEST_MISMATCH,
                p,
                bag(ok).also { it[p] = byteArrayOf(9) },
            )
        cases +=
            Triple(
                PackageProblemCode.TAG_DIGEST_MISMATCH,
                "README.txt",
                bag(ok).also { it["README.txt"] = byteArrayOf(9) },
            )
        cases +=
            Triple(
                PackageProblemCode.MISSING_BAG_INFO_FIELD,
                "Tenant",
                bag(ok, omitLabel = "Tenant"),
            )
        cases += Triple(PackageProblemCode.OXUM_MISMATCH, "Payload-Oxum", bag(ok, octets = 1))
        cases +=
            Triple(
                PackageProblemCode.CLASS_COUNT_MISMATCH,
                "Item-Count-audit-log",
                bag(ok, counts = dirs.associateWith { 1 } + ("audit-log" to 3)),
            )
        cases +=
            Triple(
                PackageProblemCode.UNKNOWN_CONTENT_DIRECTORY,
                "data/zzz/x.bin",
                bag(ok + ("data/zzz/x.bin" to byteArrayOf(1))),
            )
        cases +=
            Triple(PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE, "verification-key.pem", lonePem(bag(ok)))
        assertEquals(PackageProblemCode.entries.toSet(), cases.map { it.first }.toSet())
        for ((code, path, files) in cases) {
            val failing = if (code == PackageProblemCode.SOURCE_READ_FAILED) p else null
            assertTrue(PackageProblem(code, path) in problems(verify(files, failing))) { "$code $path" }
        }
    }

    /** A bag with the report and the signature, listed, and no key file. */
    private fun lonePem(f: MutableMap<String, ByteArray>): MutableMap<String, ByteArray> {
        f.remove(keyPath)
        retag(f)
        return f
    }

    private fun withReport(): MutableMap<String, ByteArray> = bag(full())

    @Test
    fun `INV-AC-24773 a bag with the report listed and intact is valid`() {
        assertIs<PackageVerification.Valid>(verify(withReport()))
    }

    @Test
    fun `INV-AC-24773 one changed byte of the report is one tag digest mismatch`() {
        val f = withReport()
        f[reportPath] = f.getValue(reportPath).also { it[0] = (it[0].toInt() xor 1).toByte() }
        single(verify(f), TAG_DIGEST_MISMATCH, reportPath)
    }

    @Test
    fun `INV-AC-24773 the report present with its tag line removed is not listed`() {
        val f = withReport()
        f["tagmanifest-sha256.txt"] =
            String(f.getValue("tagmanifest-sha256.txt"))
                .lines()
                .filterNot { it.endsWith(reportPath) || it.isEmpty() }
                .joinToString("") { it + "\n" }
                .toByteArray()
        single(verify(f), PackageProblemCode.TAG_FILE_NOT_LISTED, reportPath)
    }

    @Test
    fun `INV-AC-24773 the tag line present and the report removed is a tag digest mismatch`() {
        val f = withReport()
        f.remove(reportPath)
        single(verify(f), TAG_DIGEST_MISMATCH, reportPath)
    }

    @Test
    fun `INV-AC-24773 a bag with neither the report nor its line is valid`() {
        assertIs<PackageVerification.Valid>(verify(bag(full(), kind = "OFFBOARDING", signing = false)))
    }

    @Test
    fun `INV-AC-24773 a changed file with a look-alike name is not checked as the report`() {
        val f = bag(full())
        f["Verification-Report.txt"] = byteArrayOf(1, 2, 3)
        assertIs<PackageVerification.Valid>(verify(f))
    }

    private fun signed(
        withSig: Boolean = true,
        withKey: Boolean = true,
        listSig: Boolean = withSig,
        listKey: Boolean = withKey,
        withRep: Boolean = true,
        kind: String = "AUDIT_PACK",
    ): MutableMap<String, ByteArray> {
        val f = bag(full(), kind = kind)
        val tags =
            baseTags +
                (if (withRep) listOf(reportPath) else emptyList()) +
                (if (listSig) listOf(sigPath) else emptyList()) +
                (if (listKey) listOf(keyPath) else emptyList())
        tagManifest(f, tags)
        if (!withRep) f.remove(reportPath)
        if (!withSig) f.remove(sigPath)
        if (!withKey) f.remove(keyPath)
        return f
    }

    @Test
    fun `INV-AC-27739 the verifier accepts a signed pack`() {
        assertIs<PackageVerification.Valid>(verify(signed()))
    }

    @Test
    fun `INV-AC-27739 an unlisted p7s is TAG_FILE_NOT_LISTED`() {
        single(verify(signed(listSig = false)), PackageProblemCode.TAG_FILE_NOT_LISTED, sigPath)
        single(verify(signed(listKey = false)), PackageProblemCode.TAG_FILE_NOT_LISTED, keyPath)
    }

    @Test
    fun `INV-AC-27739 an altered key file is TAG_DIGEST_MISMATCH`() {
        val f = signed()
        f[keyPath] = f.getValue(keyPath).also { it[0] = (it[0] + 1).toByte() }
        single(verify(f), TAG_DIGEST_MISMATCH, keyPath)
        val g = signed()
        g[sigPath] = g.getValue(sigPath).also { it[4] = (it[4] + 1).toByte() }
        single(verify(g), TAG_DIGEST_MISMATCH, sigPath)
        // OFFBOARDING: an AUDIT_PACK bag would also report the two removed files as REPORT_SIGNATURE_INCOMPLETE.
        val h = signed(kind = "OFFBOARDING")
        h.remove(sigPath)
        h.remove(keyPath)
        assertEquals(
            setOf(PackageProblem(TAG_DIGEST_MISMATCH, sigPath), PackageProblem(TAG_DIGEST_MISMATCH, keyPath)),
            problems(verify(h)).toSet(),
        )
    }

    @Test
    fun `INV-AC-27739 a lone key file is REPORT_SIGNATURE_INCOMPLETE`() {
        single(verify(signed(withSig = false)), PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE, sigPath)
        single(verify(signed(withKey = false)), PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE, keyPath)
        val noReport = problems(verify(signed(withRep = false)))
        assertTrue(noReport.all { it.code == PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE })
        assertEquals(setOf(sigPath, keyPath), noReport.map { it.path }.toSet())
        assertEquals(
            PackageProblemCode.SOURCE_READ_FAILED.ordinal + 1,
            PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE.ordinal,
        )
        assertEquals(
            PackageProblemCode.entries.size - 1,
            PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE.ordinal,
        )
        assertEquals(16, PackageProblemCode.SOURCE_READ_FAILED.ordinal)
        assertEquals(10, PackageProblemCode.TAG_FILE_NOT_LISTED.ordinal)
    }

    @Test
    fun `INV-AC-27739 a file named verification-report p7s bak or data verification-report p7s is not a tag file`() {
        val f = signed()
        f["$sigPath.bak"] = byteArrayOf(9)
        f["data/$sigPath"] = byteArrayOf(9)
        val p = problems(verify(f)).map { it.path }
        assertTrue("$sigPath.bak" !in p && sigPath !in p && keyPath !in p)
        val g = bag(full())
        g["$sigPath.bak"] = byteArrayOf(1)
        g["$keyPath.bak"] = byteArrayOf(1)
        assertIs<PackageVerification.Valid>(verify(g))
        val h = bag(full())
        h["x-$sigPath"] = byteArrayOf(1)
        h["x-$keyPath"] = byteArrayOf(1)
        assertIs<PackageVerification.Valid>(verify(h))
    }

    private fun missing(path: String) = PackageProblem(PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE, path)

    /** A bag of [kind] with no report and neither signing file. */
    private fun unsigned(kind: String) = bag(full(), kind = kind, signing = false)

    /** A bag of [kind] with the report listed and neither signing file. */
    private fun reportOnly(kind: String) = signed(withSig = false, withKey = false, kind = kind)

    @Test
    fun `INV-AC-27779 an AUDIT_PACK bag without the p7s is REPORT_SIGNATURE_INCOMPLETE with the p7s path`() {
        single(verify(signed(withSig = false)), PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE, sigPath)
    }

    @Test
    fun `INV-AC-27779 an AUDIT_PACK bag without the key is REPORT_SIGNATURE_INCOMPLETE with the key path`() {
        single(verify(signed(withKey = false)), PackageProblemCode.REPORT_SIGNATURE_INCOMPLETE, keyPath)
    }

    @Test
    fun `INV-AC-27779 an AUDIT_PACK bag without both files reports one problem per path`() {
        val expected = listOf(missing(keyPath), missing(sigPath))
        assertEquals(expected, problems(verify(unsigned("AUDIT_PACK"))))
        assertEquals(expected, problems(verify(reportOnly("AUDIT_PACK"))))
        // The first Package-Kind label decides, after the trim.
        assertEquals(expected, problems(verify(unsigned("  AUDIT_PACK  \nPackage-Kind: OFFBOARDING"))))
        assertIs<PackageVerification.Valid>(verify(unsigned("OFFBOARDING\nPackage-Kind: AUDIT_PACK")))
        // The requirement comes from bag-info.txt, so it holds without a tag manifest too.
        val noTagManifest = unsigned("AUDIT_PACK").also { it.remove("tagmanifest-sha256.txt") }
        assertTrue(problems(verify(noTagManifest)).containsAll(expected))
    }

    @Test
    fun `INV-AC-27779 an OFFBOARDING bag without the files is valid`() {
        assertIs<PackageVerification.Valid>(verify(unsigned("OFFBOARDING")))
        assertIs<PackageVerification.Valid>(verify(reportOnly("OFFBOARDING")))
    }

    @Test
    fun `INV-AC-27779 a Package-Kind that only looks like AUDIT_PACK requires nothing`() {
        listOf("audit_pack", "Audit_Pack", "AUDIT_PACKS", "XAUDIT_PACK", "AUDIT_PACK,OFFBOARDING", "*", "")
            .forEach { kind ->
                listOf(unsigned(kind), reportOnly(kind)).forEach {
                    val v = verify(it)
                    assertTrue(v is PackageVerification.Valid) { "'$kind': $v" }
                }
            }
        // A look-alike label is no Package-Kind: the missing field is the only problem.
        val f = unsigned("AUDIT_PACK")
        f["bag-info.txt"] =
            String(f.getValue("bag-info.txt")).replace("Package-Kind:", "package-kind:").toByteArray()
        retag(f)
        single(verify(f), MISSING_BAG_INFO_FIELD, "Package-Kind")
        single(verify(bag(full(), omitLabel = "Package-Kind", signing = false)), MISSING_BAG_INFO_FIELD, "Package-Kind")
    }

    @Test
    fun `INV-AC-27779 a lone file is reported once`() {
        assertEquals(listOf(missing(sigPath)), problems(verify(signed(withSig = false))))
        assertEquals(listOf(missing(keyPath)), problems(verify(signed(withKey = false))))
        // Files present without the report, one of them also missing: still one problem for each path.
        val noReport = problems(verify(signed(withKey = false, withRep = false)))
        assertEquals(listOf(missing(keyPath), missing(sigPath)), noReport)
    }
}
