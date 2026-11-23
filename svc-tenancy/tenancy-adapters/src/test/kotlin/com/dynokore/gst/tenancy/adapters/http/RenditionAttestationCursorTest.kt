package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.period.PagePosition
import com.dynokore.gst.tenancy.application.rendition.RenditionHistoryPosition
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/** The cursor of listRenditionAttestations (API-18): opaque, bound to entity and user, signed, valid 24 hours. */
class RenditionAttestationCursorTest {
    @TempDir
    lateinit var mount: Path

    private val key = "k".repeat(32)
    private val now = Instant.parse("2026-11-20T02:00:00Z")
    private val last = RenditionHistoryPosition(Instant.parse("2026-11-05T01:02:03.123456Z"), 7L)

    private fun put(value: String) {
        val file = mount.resolve(RenditionAttestationCursor.CURRENT_SECRET)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
    }

    private fun cursors() = RenditionAttestationCursor(MountedFileSecretStore(mount))

    private fun issued(
        user: String = "USR-1",
        entity: String = "ENT-1",
        at: Instant = now,
    ): String {
        put(key)
        return cursors().issue(entity, user, last, at)
    }

    private fun open(
        token: String,
        user: String = "USR-1",
        entity: String = "ENT-1",
        at: Instant = now,
    ) = cursors().open(token, entity, user, at)

    private fun refused(block: () -> Unit) {
        val refusal =
            try {
                block()
                null
            } catch (e: ApiRefusal) {
                e
            }
        refusal?.code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        refusal?.detail shouldBe RenditionAttestationCursor.REFUSED
    }

    @Test
    fun `INV-AC-27599 a cursor opens to the position it was issued for and shows neither key nor user nor entity`() {
        val token = issued()
        open(token) shouldBe last
        token.startsWith("ra1.") shouldBe true
        token shouldNotContain key
        token shouldNotContain "USR-1"
        token shouldNotContain "ENT-1"
    }

    @Test
    fun `INV-AC-27599 a token of the period list cursor and of the pack delta cursor is refused`() {
        put(key)
        val position = PagePosition(LocalDate.parse("2026-06-01"), "P1")
        val period = PeriodListCursor(MountedFileSecretStore(mount)).issue("ENT-1", "USR-1", position, now)
        refused { open(period) }
        val pack = period.replaceFirst("v1.", PackDeltaCursor.LAYOUT + ".")
        refused { open(pack) }
        refused { open(issued().replaceFirst("ra1.", "v1.")) }
    }

    @Test
    fun `INV-AC-27599 a token for another entity or another user is refused`() {
        val token = issued()
        refused { open(token, entity = "ENT-2") }
        refused { open(token, user = "USR-2") }
    }

    @Test
    fun `INV-AC-27599 a tampered tag and a re-encoded position are refused`() {
        val token = issued()
        val tag = token.substringAfterLast('.')
        val changed = (if (tag.first() == 'A') 'B' else 'A') + tag.drop(1)
        refused { open(token.removeSuffix(tag) + changed) }
        val parts = token.split('.')
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8).substringBeforeLast('\n') + "\n8"
        val forged = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        refused { open("${parts[0]}.$forged.${parts[2]}") }
    }

    @Test
    fun `INV-AC-27599 an expired token is refused and one inside 24 hours is accepted`() {
        val token = issued()
        refused { open(token, at = now.plus(Duration.ofHours(24)).plusSeconds(1)) }
        open(token, at = now.plus(Duration.ofHours(24)).minusSeconds(1)) shouldBe last
        refused { open(issued(at = now.plusSeconds(60))) }
    }

    @Test
    fun `INV-AC-27599 empty and malformed tokens are refused`() {
        put(key)
        listOf("", ".", "ra1", "ra1..", "ra1.a.b", "ra1.a.b.c", "x").forEach { refused { open(it) } }
    }
}
