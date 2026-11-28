package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.certification.CertificationHistoryPosition
import com.dynokore.gst.tenancy.application.period.PagePosition
import com.dynokore.gst.tenancy.application.rendition.RenditionHistoryPosition
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/** The cursor of listSandboxCertifications (API-18): opaque, bound to entity and user, signed, valid 24 hours. */
class SandboxCertificationCursorTest {
    @TempDir
    lateinit var mount: Path

    // made per run: no key text lives in the source
    private val key = randomKey()
    private val now = Instant.parse("2026-11-28T02:00:00Z")
    private val last = CertificationHistoryPosition(Instant.parse("2026-11-05T01:02:03.123456Z"), 7L)

    private fun randomKey() = UUID.randomUUID().toString() + UUID.randomUUID()

    private fun put(
        value: String,
        name: String = SandboxCertificationCursor.CURRENT_SECRET,
    ) {
        val file = mount.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
    }

    private fun cursors() = SandboxCertificationCursor(MountedFileSecretStore(mount))

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

    private fun refusalOf(block: () -> Unit): ApiRefusal? =
        try {
            block()
            null
        } catch (e: ApiRefusal) {
            e
        }

    private fun refused(block: () -> Unit) {
        val refusal = refusalOf(block)
        refusal?.code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        refusal?.detail shouldBe SandboxCertificationCursor.REFUSED
    }

    @Test
    fun `INV-AC-29133 a cursor opens to the position it was issued for and shows neither key nor user nor entity`() {
        val token = issued()
        open(token) shouldBe last
        val shown = token + String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8)
        shown shouldNotContain key
        shown shouldNotContain "USR-1"
        shown shouldNotContain "ENT-1"
    }

    @Test
    fun `INV-AC-29133 the layout prefix is its own among the cursors of the service`() {
        issued().substringBefore('.') shouldBe "sc1"
        listOf(PeriodListCursor.LAYOUT, PackDeltaCursor.LAYOUT, RenditionAttestationCursor.LAYOUT).forEach {
            SandboxCertificationCursor.LAYOUT shouldNotBe it
        }
    }

    @Test
    fun `INV-AC-29133 a token of another list is refused, also under this layout prefix`() {
        put(key)
        val secrets = MountedFileSecretStore(mount)
        val period =
            PeriodListCursor(secrets).issue("ENT-1", "USR-1", PagePosition(LocalDate.parse("2026-06-01"), "P1"), now)
        val rendition =
            RenditionAttestationCursor(secrets)
                .issue("ENT-1", "USR-1", RenditionHistoryPosition(last.recordedAt, last.sequence), now)
        listOf(period, rendition).forEach { foreign ->
            refused { open(foreign) }
            refused { open("sc1." + foreign.substringAfter('.')) }
        }
        refused { open(issued().replaceFirst("sc1.", "SC1.")) }
        refused { open(issued().replaceFirst("sc1.", "ra1.")) }
    }

    @Test
    fun `INV-AC-29133 a token for another entity or another user is refused`() {
        val token = issued()
        refused { open(token, entity = "ENT-2") }
        refused { open(token, entity = "ENT-1 ") }
        refused { open(token, user = "USR-2") }
        refused { open(token, user = "usr-1") }
    }

    @Test
    fun `INV-AC-29133 a tampered tag and a re-encoded position are refused`() {
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
    fun `INV-AC-29133 a token is valid for 24 hours and refused afterwards or before its issue`() {
        val token = issued()
        open(token, at = now.plus(Duration.ofHours(24))) shouldBe last
        refused { open(token, at = now.plus(Duration.ofHours(24)).plusMillis(1)) }
        refused { open(issued(at = now.plusSeconds(60))) }
    }

    @Test
    fun `INV-AC-29133 empty and malformed tokens are refused`() {
        put(key)
        listOf("", ".", "sc1", "sc1..", "sc1.a.b", "sc1.a.b.c", "x", "*").forEach { refused { open(it) } }
    }

    @Test
    fun `INV-AC-29133 the key is read at every use and the previous key only verifies`() {
        val old = issued()
        val cursors = cursors()
        put(randomKey())
        refused { cursors.open(old, "ENT-1", "USR-1", now) }
        put(key, SandboxCertificationCursor.PREVIOUS_SECRET)
        cursors.open(old, "ENT-1", "USR-1", now) shouldBe last
        val next = cursors.issue("ENT-1", "USR-1", last, now)
        Files.delete(mount.resolve(SandboxCertificationCursor.PREVIOUS_SECRET))
        cursors.open(next, "ENT-1", "USR-1", now) shouldBe last
    }

    @Test
    fun `INV-AC-29133 without a usable current key nothing is issued unsigned`() {
        listOf(null, "", " ".repeat(40), "short").forEach { value ->
            value?.let { put(it) }
            refusalOf { cursors().issue("ENT-1", "USR-1", last, now) }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
        }
    }
}
