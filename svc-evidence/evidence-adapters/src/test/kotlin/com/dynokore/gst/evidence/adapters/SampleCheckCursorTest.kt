package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.http.SampleCheckCursor
import com.dynokore.gst.evidence.application.artefact.SampleCheckPosition
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64

class SampleCheckCursorTest {
    @TempDir
    lateinit var mount: Path

    private val key = "k".repeat(32)
    private val other = "o".repeat(32)
    private val at = Instant.parse("2026-11-10T02:00:00Z")
    private val last =
        SampleCheckPosition(Instant.parse("2026-11-01T00:00:00.123456Z"), "ref\nwith newline", "a".repeat(64))

    private fun put(
        name: String,
        value: String,
    ) = Files.writeString(mount.resolve(name), value)

    private fun cursor() = SampleCheckCursor(MountedFileSecretStore(mount))

    private fun issued(
        user: String = "USR-1",
        entity: String = "E1",
        now: Instant = at,
    ): String {
        put(SampleCheckCursor.CURRENT_SECRET, key)
        return cursor().issue(entity, user, last, now)
    }

    @Test
    fun `INV-AC-27602 a token opens to its position for the same entity and user and shows neither`() {
        val token = issued()
        cursor().open(token, "E1", "USR-1", at.plusSeconds(60)) shouldBe last
        token shouldNotContain "USR-1"
        token.split('.').size shouldBe 3
        token.startsWith("v1.") shouldBe true
    }

    @Test
    fun `INV-AC-27602 a tampered tag or payload, another entity or user, another layout and junk are refused alike`() {
        val token = issued()
        val parts = token.split('.')
        val flipped = parts[2].let { (if (it[0] == 'A') "B" else "A") + it.drop(1) }
        val payloadOther = Base64.getUrlEncoder().withoutPadding().encodeToString("x".toByteArray())
        val refused =
            listOf(
                token to ("E1" to "USR-2"),
                token to ("E2" to "USR-1"),
                "${parts[0]}.${parts[1]}.$flipped" to ("E1" to "USR-1"),
                "${parts[0]}.$payloadOther.${parts[2]}" to ("E1" to "USR-1"),
                "v2.${parts[1]}.${parts[2]}" to ("E1" to "USR-1"),
                "${parts[1]}.${parts[2]}" to ("E1" to "USR-1"),
                "" to ("E1" to "USR-1"),
                "$token.x" to ("E1" to "USR-1"),
            )
        refused.forEach { (t, who) -> cursor().open(t, who.first, who.second, at.plusSeconds(1)) shouldBe null }
    }

    @Test
    fun `INV-AC-27602 a token is valid for 24 hours and refused after, and one issued in the future is refused`() {
        val token = issued()
        cursor().open(token, "E1", "USR-1", at.plus(Duration.ofHours(24))) shouldBe last
        cursor().open(token, "E1", "USR-1", at.plus(Duration.ofHours(24)).plusMillis(1)) shouldBe null
        cursor().open(token, "E1", "USR-1", at.minusSeconds(1)) shouldBe null
    }

    @Test
    fun `INV-AC-27602 a cursor with a rewritten issue time is refused like a bad tag`() {
        val token = issued()
        val parts = token.split('.')
        val fields = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8).split('\n', limit = 4)
        val later = (at.plus(Duration.ofHours(30))).toEpochMilli().toString()
        val rewritten = listOf(fields[0], later, fields[2], fields[3]).joinToString("\n")
        val forged =
            "${parts[0]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(rewritten.toByteArray())}.${parts[2]}"
        val now = at.plus(Duration.ofHours(30))
        cursor().open(token, "E1", "USR-1", now) shouldBe null
        cursor().open(forged, "E1", "USR-1", now) shouldBe null
    }

    @Test
    fun `INV-AC-27602 an expired cursor with its issue time rewritten to now, or moved by one millisecond, is refused`() {
        val now = at.plus(Duration.ofHours(24)).plusSeconds(1)
        val token = issued()
        val parts = token.split('.')
        val fields = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8).split('\n', limit = 4)

        fun withIssue(issue: Instant): String {
            val body = listOf(fields[0], issue.toEpochMilli().toString(), fields[2], fields[3]).joinToString("\n")
            return "${parts[0]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())}.${parts[2]}"
        }
        cursor().open(token, "E1", "USR-1", now) shouldBe null
        cursor().open(withIssue(now), "E1", "USR-1", now) shouldBe null
        cursor().open(withIssue(at.plusMillis(1)), "E1", "USR-1", at.plusSeconds(60)) shouldBe null
        cursor().open(withIssue(at.minusMillis(1)), "E1", "USR-1", at.plusSeconds(60)) shouldBe null
        cursor().open(token, "E1", "USR-1", at.plusSeconds(60)) shouldBe last
    }

    @Test
    fun `INV-AC-27602 the previous key verifies but never signs, and a rotated current key refuses old tokens`() {
        val token = issued()
        put(SampleCheckCursor.CURRENT_SECRET, other)
        cursor().open(token, "E1", "USR-1", at) shouldBe null
        put(SampleCheckCursor.PREVIOUS_SECRET, key)
        cursor().open(token, "E1", "USR-1", at) shouldBe last
        val fresh = cursor().issue("E1", "USR-1", last, at)
        put(SampleCheckCursor.CURRENT_SECRET, "z".repeat(32))
        put(SampleCheckCursor.PREVIOUS_SECRET, key)
        cursor().open(fresh, "E1", "USR-1", at) shouldBe null
    }

    @Test
    fun `INV-AC-27602 a missing or short current key is a failure and never an unsigned token`() {
        shouldThrow<IllegalStateException> { cursor().issue("E1", "USR-1", last, at) }
        put(SampleCheckCursor.CURRENT_SECRET, "short")
        shouldThrow<IllegalStateException> { cursor().issue("E1", "USR-1", last, at) }
        shouldThrow<IllegalStateException> { cursor().open("v1.a.b", "E1", "USR-1", at) }
        put(SampleCheckCursor.CURRENT_SECRET, " ".repeat(40))
        shouldThrow<IllegalStateException> { cursor().issue("E1", "USR-1", last, at) }
    }
}
