package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.http.CursorBinding
import com.dynokore.gst.canonical.application.exposure.ExposurePosition
import com.dynokore.gst.canonical.application.exposure.ExposureWindow
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-28718: the cursor of the listing (API-18) is bound, integrity-protected and lives 24 hours. */
class SignedIngestedDocumentCursorsTest {
    private val issuedAt = Instant.parse("2026-05-03T00:00:00Z")

    @TempDir
    lateinit var mount: Path

    /** Writes the mounted secret [name], or removes it when [value] is null. */
    private fun secret(
        name: String,
        value: String?,
    ) {
        val file = mount.resolve(name)
        if (value == null) {
            Files.deleteIfExists(file)
        } else {
            Files.createDirectories(file.parent)
            Files.writeString(file, value)
        }
    }

    @BeforeEach
    fun seed() = secret(CURRENT, "k".repeat(32))

    private val window =
        ExposureWindow("principal-1", Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-02T00:00:00Z"))
    private val binding = CursorBinding("tenant-1", "user-1", window)
    private val last = ExposurePosition("erp\nwith|separators", issuedAt.minusNanos(1000), UUID.randomUUID(), 7)

    private fun cursors(at: Instant = issuedAt) =
        SignedIngestedDocumentCursors(MountedFileSecretStore(mount), Clock.fixed(at, ZoneOffset.UTC))

    @Test
    fun `INV-AC-28718 a cursor gives back its position for its own binding for 24 hours and no longer`() {
        val token = cursors().issue(binding, last)
        cursors().open(token, binding) shouldBe last
        cursors(issuedAt.plus(DAY)).open(token, binding) shouldBe last
        cursors(issuedAt.plus(DAY).plusMillis(1)).open(token, binding).shouldBeNull()
        cursors(issuedAt.minusMillis(1)).open(token, binding).shouldBeNull()
    }

    @Test
    fun `INV-AC-28718 a cursor of another tenant, user or filter opens nothing`() {
        val token = cursors().issue(binding, last)
        listOf(
            binding.copy(tenant = "tenant-2"),
            binding.copy(userId = "user-2"),
            binding.copy(window = window.copy(principalRef = "principal-2")),
            binding.copy(window = window.copy(recordedFrom = window.recordedFrom.plusNanos(1))),
            binding.copy(window = window.copy(recordedTo = window.recordedTo.plusNanos(1))),
            // the same characters shifted between two fields
            binding.copy(tenant = "tenant-1u", userId = "ser-1"),
        ).forEach { cursors().open(token, it).shouldBeNull() }
    }

    @Test
    fun `INV-AC-28718 a rotated key keeps verifying as the previous key and signs nothing`() {
        val token = cursors().issue(binding, last)
        secret(PREVIOUS, "k".repeat(32))
        secret(CURRENT, "n".repeat(32))
        cursors().open(token, binding) shouldBe last
        secret(PREVIOUS, null)
        cursors().open(token, binding).shouldBeNull()
    }

    @Test
    fun `INV-AC-28718 without a usable current key no cursor is issued or opened`() {
        val token = cursors().issue(binding, last)
        listOf(null, "", " ".repeat(32), "k".repeat(31)).forEach { key ->
            secret(CURRENT, key)
            shouldThrowAny { cursors().issue(binding, last) }
            shouldThrowAny { cursors().open(token, binding) }
        }
    }

    private companion object {
        const val CURRENT = "canonical/ingested-documents-cursor-key"
        const val PREVIOUS = "canonical/ingested-documents-cursor-key-previous"
        val DAY: Duration = Duration.ofHours(24)
    }
}
