package com.dynokore.gst.evidence.application.retention

import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.readText

class SendRetentionNoticesTest {
    private val tenant = "00000000-0000-0000-0000-000000000001"
    private val now: Instant = Instant.parse("2033-03-01T10:00:00Z")
    private val past: Instant = now.minusSeconds(1)

    private fun candidate(id: String) = NoticeCandidate(id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))

    private fun holder(
        email: String = "holder@example.test",
        days: Int? = null,
    ) = CustodyHolderReader.CustodyHolder("Hana Holder", email, "+6590000000", days)

    /** The fake of the candidate port keeps its contract: only ATTACHED or DETACHED partitions with no notice row. */
    private class Candidates(
        private val rows: List<Row>,
    ) : NoticeCandidates {
        data class Row(
            val candidate: NoticeCandidate,
            val state: String,
            val hasNotice: Boolean = false,
        )

        override fun pending(tenant: String) =
            rows.filter { it.state in setOf("ATTACHED", "DETACHED") && !it.hasNotice }.map { it.candidate }
    }

    private class Recorder(
        private val answer: (String) -> Boolean = { true },
    ) : RetentionNoticeRecorder {
        val calls = mutableListOf<Pair<String, RetentionNotice>>()

        override fun record(
            tenant: String,
            notice: RetentionNotice,
        ): Boolean {
            calls += tenant to notice
            return answer(notice.partitionId)
        }

        val partitions get() = calls.map { it.second.partitionId }
    }

    private class Setting(
        private val read: () -> Int,
    ) : NoticeWindowSetting {
        var calls = 0

        override fun days(tenant: String): Int {
            calls++
            return read()
        }
    }

    private val recorder = Recorder()

    @Suppress("LongParameterList") // one defaulted fake per port, so a case names only what it changes
    private fun useCase(
        ids: List<String> = listOf("p1"),
        holder: CustodyHolderReader.CustodyHolder? = holder(),
        setting: NoticeWindowSetting = Setting { 90 },
        ends: (String) -> Instant? = { past },
        recorder: RetentionNoticeRecorder = this.recorder,
        candidates: NoticeCandidates = Candidates(ids.map { Candidates.Row(candidate(it), "ATTACHED") }),
    ) = SendRetentionNotices(
        candidates = candidates,
        holders =
            object : CustodyHolderReader {
                override fun holder(tenant: String) = holder
            },
        window = setting,
        ends =
            object : RetentionEndReader {
                override fun retentionEnd(
                    tenant: String,
                    partitionId: String,
                ) = ends(partitionId)
            },
        recorder = recorder,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `INV-AC-26995 a due partition is recorded once with the exact notice`() {
        useCase().run(tenant) shouldBe NoticeRun(listOf("p1"), emptyList())
        recorder.calls shouldBe
            listOf(
                tenant to
                    RetentionNotice(
                        partitionId = "p1",
                        notifiedAt = now,
                        windowDays = 90,
                        recipients = listOf("holder@example.test"),
                        template = "retention-notice",
                        channel = "EMAIL",
                        templateParameters =
                            mapOf(
                                "periodStart" to "2026-01-01",
                                "periodEnd" to "2026-03-31",
                                "noticeWindowEndsAt" to "2033-05-30T10:00:00Z",
                            ),
                    ),
            )
    }

    @Test
    fun `INV-AC-26995 a longer stored window wins over the VP-66 days`() {
        useCase(holder = holder(days = 120), setting = Setting { 90 }).run(tenant)
        val notice = recorder.calls.single().second
        notice.windowDays shouldBe 120
        notice.templateParameters["noticeWindowEndsAt"] shouldBe "2033-06-29T10:00:00Z"
    }

    @Test
    fun `INV-AC-26995 a shorter stored window does not shorten the VP-66 days`() {
        useCase(holder = holder(days = 30), setting = Setting { 90 }).run(tenant)
        recorder.calls
            .single()
            .second.windowDays shouldBe 90
    }

    @Test
    fun `INV-AC-26995 a shortened acknowledged setting applies with no longer stored window`() {
        for (stored in listOf(null, 59)) {
            val rec = Recorder()
            useCase(holder = holder(days = stored), setting = Setting { 60 }, recorder = rec).run(tenant)
            rec.calls
                .single()
                .second.windowDays shouldBe 60
        }
    }

    @Test
    fun `INV-AC-26995 an end equal to the clock is due and one a second later is not touched`() {
        val ends = mapOf("equal" to now, "later" to now.plusSeconds(1), "before" to past)
        val setting = Setting { 90 }
        val run = useCase(ids = ends.keys.toList(), ends = { ends.getValue(it) }, setting = setting).run(tenant)
        run shouldBe NoticeRun(listOf("equal", "before"), emptyList())
        recorder.partitions shouldBe listOf("equal", "before")
        setting.calls shouldBe 2
    }

    @Test
    fun `INV-AC-26995 a tenant with no custody holder is skipped without an error`() {
        val untouched =
            object : NoticeCandidates {
                override fun pending(tenant: String): List<NoticeCandidate> = listOf(candidate("p1"))
            }
        useCase(holder = null, candidates = untouched, ends = { null }).run(tenant) shouldBe
            NoticeRun(emptyList(), emptyList())
        recorder.calls.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-26995 a blank email fails every due partition and calls neither setting nor recorder`() {
        for (email in listOf("", " ", "\t\n")) {
            val setting = Setting { 90 }
            val ends = mapOf("p1" to past, "notDue" to now.plusSeconds(1), "p2" to now)
            val run =
                useCase(
                    ids = ends.keys.toList(),
                    holder = holder(email = email),
                    setting = setting,
                    ends = { ends.getValue(it) },
                ).run(tenant)
            run shouldBe NoticeRun(emptyList(), listOf("p1", "p2"))
            setting.calls shouldBe 0
        }
        recorder.calls.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-26995 a retention end that cannot be read fails that partition and the others are recorded`() {
        val run = useCase(ids = listOf("p1", "p2", "p3"), ends = { if (it == "p2") null else past }).run(tenant)
        run shouldBe NoticeRun(listOf("p1", "p3"), listOf("p2"))
        recorder.partitions shouldBe listOf("p1", "p3")
    }

    @Test
    fun `INV-AC-26995 a throwing retention end read fails that partition and the others are recorded`() {
        val run =
            useCase(ids = listOf("p1", "p2", "p3"), ends = { if (it == "p1") error("read failed") else past })
                .run(tenant)
        run shouldBe NoticeRun(listOf("p2", "p3"), listOf("p1"))
        recorder.partitions shouldBe listOf("p2", "p3")
    }

    @Test
    fun `INV-AC-26995 a failed read of the setting builds no message and the others are recorded`() {
        var reads = 0
        val setting = Setting { if (++reads == 2) error("setting unreadable") else 90 }
        val run = useCase(ids = listOf("p1", "p2", "p3"), holder = holder(days = 200), setting = setting).run(tenant)
        run shouldBe NoticeRun(listOf("p1", "p3"), listOf("p2"))
        recorder.partitions shouldBe listOf("p1", "p3")
    }

    @Test
    fun `INV-AC-26995 a setting below one day is a failure even with a longer stored window`() {
        for (days in listOf(0, -1)) {
            useCase(holder = holder(days = 200), setting = Setting { days }).run(tenant) shouldBe
                NoticeRun(emptyList(), listOf("p1"))
        }
        recorder.calls.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-26995 a throwing recorder propagates out of the run and no later partition is recorded`() {
        val failure = IllegalStateException("write failed")
        val rec = Recorder { if (it == "p2") throw failure else true }
        val thrown =
            shouldThrow<IllegalStateException> { useCase(ids = listOf("p1", "p2", "p3"), recorder = rec).run(tenant) }
        thrown shouldBeSameInstanceAs failure
        rec.partitions shouldBe listOf("p1", "p2")
    }

    @Test
    fun `INV-AC-26995 a false from the recorder is counted in neither list`() {
        val rec = Recorder { it != "p2" }
        useCase(ids = listOf("p1", "p2", "p3"), recorder = rec).run(tenant) shouldBe
            NoticeRun(listOf("p1", "p3"), emptyList())
        rec.partitions shouldBe listOf("p1", "p2", "p3")
    }

    @Test
    fun `INV-AC-26995 the recipients are only the holder email`() {
        useCase().run(tenant)
        val notice = recorder.calls.single().second
        notice.recipients shouldBe listOf("holder@example.test")
        val message = notice.templateParameters.toString()
        message shouldNotContain "Hana"
        message shouldNotContain "+6590000000"
    }

    @Test
    fun `INV-AC-26995 no text form of the holder or the notice shows contact values`() {
        useCase().run(tenant)
        for (text in listOf(
            holder().toString(),
            recorder.calls
                .single()
                .second
                .toString(),
        )) {
            text shouldNotContain "Hana"
            text shouldNotContain "holder@example.test"
            text shouldNotContain "+6590000000"
        }
    }

    @Test
    fun `INV-AC-26995 dropped, archived and already noticed partitions are not offered and not touched`() {
        val states = listOf("ATTACHED", "DETACHED", "DROPPED", "ARCHIVED")
        val rows =
            states.map { Candidates.Row(candidate(it), it) } +
                Candidates.Row(candidate("noticed"), "ATTACHED", hasNotice = true)
        useCase(candidates = Candidates(rows)).run(tenant) shouldBe
            NoticeRun(listOf("ATTACHED", "DETACHED"), emptyList())
        recorder.partitions shouldBe listOf("ATTACHED", "DETACHED")
    }

    @Test
    fun `INV-AC-26995 the job definition carries the given schedule and times`() {
        val schedule = JobSchedule("0 15 3 * * *", ZoneOffset.UTC)
        val definition = RetentionNoticeJob.definition(schedule, Duration.ofMinutes(7), Duration.ofMinutes(41))
        definition.name shouldBe "evidence-retention-notice"
        definition.ownerService shouldBe "evidence-svc"
        definition.entityScope shouldBe JobEntityScope.ENTITY
        definition.criticality shouldBe JobCriticality.STANDARD
        definition.schedule shouldBe schedule
        definition.tolerance shouldBe Duration.ofMinutes(7)
        definition.lockAtMostFor shouldBe Duration.ofMinutes(41)
    }

    /**
     * Deny by default over the raw text, so nothing hides in a comment or a string: every word that holds a digit must
     * be exactly 0 or 1, and no `of...` factory of a duration or period may be named, called or imported.
     */
    @Test
    fun `INV-AC-26995 the notice files write no number and build no duration or period`() {
        var root: Path? = Path.of("").toAbsolutePath()
        while (root != null && !Files.exists(root.resolve("ac-keys.tsv"))) root = root.parent
        val dir =
            requireNotNull(root) { "repository root not found" }
                .resolve("evidence-application/src/main/kotlin/com/dynokore/gst/evidence/application/retention")
        val word = Regex("""\w*\d\w*""")
        val factory = Regex("""\bof[A-Z]\w*|\bof\s*\(|::\s*of\b|\bjava\.time\.(Duration|Period)(\.|\s+as\b)""")
        val findings =
            listOf("RetentionNoticeJob.kt", "SendRetentionNotices.kt", "RetentionNoticePorts.kt").flatMap { file ->
                val text = dir.resolve(file).readText()
                val numbers = word.findAll(text).map { it.value }.filterNot { it == "0" || it == "1" }
                (numbers + factory.findAll(text).map { it.value }).map { "$file: $it" }.toList()
            }
        findings.shouldBeEmpty()
    }
}
