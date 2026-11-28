package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.http.ChainPageCursor
import com.dynokore.gst.evidence.application.lookup.ChainCursor
import com.dynokore.gst.evidence.application.lookup.ChainItemKind
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ChainPageCursorTest {
    @TempDir
    lateinit var mount: Path

    private val k1 = "k".repeat(32)
    private val k2 = "o".repeat(32)
    private val k3 = "z".repeat(32)
    private val current = "evidence.chain-cursor-key"
    private val previous = "evidence.chain-cursor-key-previous"
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val otherTenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val at = Instant.parse("2026-11-10T02:00:00Z")
    private val last = ChainCursor(Instant.parse("2026-03-20T03:10:00.123456Z"), ChainItemKind.ACKNOWLEDGEMENT, 7)
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private fun put(
        name: String,
        value: String,
    ) {
        Files.writeString(mount.resolve(name), value)
    }

    private fun cursors() = ChainPageCursor(MountedFileSecretStore(mount))

    private fun issued(now: Instant = at): String {
        put(current, k1)
        return cursors().issue(tenant, "U1", "A1", last, now)
    }

    private fun open(
        token: String,
        now: Instant = at.plusSeconds(60),
    ) = cursors().open(token, tenant, "U1", "A1", now)

    private fun payloadFields(token: String) =
        String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8).split('\n')

    private fun withPayload(
        token: String,
        fields: List<String>,
    ): String {
        val parts = token.split('.')
        return "${parts[0]}.${encoder.encodeToString(fields.joinToString("\n").toByteArray())}.${parts[2]}"
    }

    @Test
    fun `INV-AC-29278 an unchanged cursor opens to its position and holds no tenant, user ID or key`() {
        val token = issued()
        open(token) shouldBe last
        token.split('.').size shouldBe 3
        token.startsWith("chain-v1.") shouldBe true
        val readable = token + payloadFields(token).joinToString()
        readable shouldNotContain "U1"
        readable shouldNotContain tenant.value.toString()
        readable shouldNotContain k1
    }

    @Test
    fun `INV-AC-29278 another user, tenant or Acknowledgement ID is refused`() {
        val token = issued()
        cursors().open(token, tenant, "U2", "A1", at) shouldBe null
        cursors().open(token, otherTenant, "U1", "A1", at) shouldBe null
        cursors().open(token, tenant, "U1", "A2", at) shouldBe null
        cursors().open(token, tenant, "U1", "a1", at) shouldBe null
        // length-prefixed fields: a value cannot move into its neighbour
        cursors().open(token, tenant, "U1A", "1", at) shouldBe null
        cursors().open(token, tenant, "U", "1A1", at) shouldBe null
    }

    @Test
    fun `INV-AC-29278 every altered token and every second spelling of a signed value is refused`() {
        val token = issued()
        val (version, payload, tag) = token.split('.')
        val fields = payloadFields(token)
        val (appliedAt, kind, seq) = fields
        val issuedAt = fields.last()
        val flipped = (if (tag[0] == 'A') "B" else "A") + tag.drop(1)
        val altered =
            mapOf(
                "tag" to "$version.$payload.$flipped",
                "short tag" to "$version.$payload.${tag.dropLast(2)}",
                "padded tag" to "$token=",
                "no tag" to "$version.$payload.",
                "extra part" to "$token.x",
                "no layout" to "$payload.$tag",
                "other layout" to "v1.$payload.$tag",
                "upper-case layout" to "CHAIN-V1.$payload.$tag",
                "empty" to "",
                "junk" to "not-a-cursor",
                "instant" to withPayload(token, listOf("2026-03-20T03:10:00.123457Z", kind, seq, issuedAt)),
                "kind" to withPayload(token, listOf(appliedAt, "PAYLOAD", seq, issuedAt)),
                "seq" to withPayload(token, listOf(appliedAt, kind, "8", issuedAt)),
                "seq respelled" to withPayload(token, listOf(appliedAt, kind, "07", issuedAt)),
                "instant respelled" to
                    withPayload(token, listOf("2026-03-20T03:10:00.123456000Z", kind, seq, issuedAt)),
                "issue time respelled" to withPayload(token, listOf(appliedAt, kind, seq, "+$issuedAt")),
                "extra field" to withPayload(token, listOf(appliedAt, kind, seq, issuedAt, "")),
                "missing field" to withPayload(token, listOf(appliedAt, kind, seq)),
            )
        altered.forEach { (name, forged) -> withClue(name) { open(forged) shouldBe null } }
        open(token) shouldBe last
    }

    @Test
    fun `INV-AC-29278 a cursor is valid up to the 24th hour included, then refused, and never before its issue`() {
        val token = issued()
        open(token, at) shouldBe last
        open(token, at.plus(Duration.ofHours(24))) shouldBe last
        open(token, at.plus(Duration.ofHours(24)).plusMillis(1)) shouldBe null
        open(token, at.plus(Duration.ofDays(30))) shouldBe null
        open(token, at.minusMillis(1)) shouldBe null
    }

    @Test
    fun `INV-AC-29278 the issue time is signed, so an expired cursor cannot be renewed`() {
        val token = issued()
        val (appliedAt, kind, seq) = payloadFields(token)
        val now = at.plus(Duration.ofHours(30))
        open(withPayload(token, listOf(appliedAt, kind, seq, now.toEpochMilli().toString())), now) shouldBe null
        val shifted = at.plusMillis(1).toEpochMilli().toString()
        open(withPayload(token, listOf(appliedAt, kind, seq, shifted))) shouldBe null
        open(token, now) shouldBe null
    }

    @Test
    fun `INV-AC-29278 K1 verifies while it is the previous key and no longer once it is neither`() {
        val token = issued()
        put(current, k2)
        open(token) shouldBe null
        put(previous, k1)
        open(token) shouldBe last
        put(previous, k3)
        open(token) shouldBe null
        Files.delete(mount.resolve(previous))
        open(token) shouldBe null
    }

    @Test
    fun `INV-AC-29278 the previous key never signs`() {
        put(current, k2)
        put(previous, k1)
        val fresh = cursors().issue(tenant, "U1", "A1", last, at)
        Files.delete(mount.resolve(previous))
        open(fresh) shouldBe last
        put(current, k3)
        put(previous, k1)
        open(fresh) shouldBe null
    }

    @Test
    fun `INV-AC-29278 the key is read at every use, never cached`() {
        put(current, k1)
        val cursors = cursors()
        val token = cursors.issue(tenant, "U1", "A1", last, at)
        put(current, k2)
        cursors.open(token, tenant, "U1", "A1", at) shouldBe null
    }

    @Test
    fun `INV-AC-29278 a missing, blank or short current key fails to issue and to open, previous key or not`() {
        val token = issued()
        put(previous, k1)
        listOf(null, "", " ".repeat(40), "k".repeat(31)).forEach { bad ->
            if (bad == null) Files.delete(mount.resolve(current)) else put(current, bad)
            withClue("current key '$bad'") {
                shouldThrow<IllegalStateException> { cursors().issue(tenant, "U1", "A1", last, at) }
                shouldThrow<IllegalStateException> { open(token) }
                shouldThrow<IllegalStateException> { open("not-a-cursor") }
            }
        }
    }
}
