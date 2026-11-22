package com.dynokore.gst.submission.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * INV-AC-27292: every insert into `submission.submission` in the listed bootstrap fixtures names `submission_type`.
 *
 * It is a text scan. It cannot see an insert built by a helper in another file, an insert whose table is held in a
 * variable other than a `val x = Submission.SUBMISSION_` alias, or SQL assembled from fragments. No such helper exists
 * in the listed files at the time of writing; the list is the whole surface.
 */
class SubmissionTypeFixtureScanTest {
    @Test
    fun `INV-AC-27292 the insert statements of the 10 listed fixture files each name submission_type`() {
        val problems = LISTED.flatMap { path -> problemsOf(path, root.resolve(path)) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `INV-AC-27292 the scan itself fails closed on a missing file, no insert site and a missing or misspelt column`() {
        val dir = Files.createTempDirectory("type-scan")
        try {
            assertEquals(1, problemsOf("gone.kt", dir.resolve("gone.kt")).size)
            val none = dir.resolve("none.kt").also { Files.writeString(it, "class A\n") }
            assertEquals(1, problemsOf("none.kt", none).size)
            val raw = "insert into submission.submission (tenant_id) values (?)"
            assertEquals(1, problemsOf("r.kt", write(dir, "r.kt", "x(\"$raw\")")).size)
            val typo = "insert into submission.submission (tenant_id, submission_typ) values (?, 'X')"
            assertEquals(1, problemsOf("t.kt", write(dir, "t.kt", "x(\"$typo\")")).size)
            val ok = "insert into submission.submission (tenant_id, submission_type) values (?, 'TYPE_1B')"
            assertEquals(0, problemsOf("o.kt", write(dir, "o.kt", "x(\"$ok\")")).size)
            val aliased = "val s = Submission.SUBMISSION_\ndsl.insertInto(s)\n.set(s.TENANT_ID, t)\n.execute()"
            assertEquals(1, problemsOf("a.kt", write(dir, "a.kt", aliased)).size)
            val aliasedOk = aliased.replace(".execute()", ".set(s.SUBMISSION_TYPE, \"TYPE_1B\")\n.execute()")
            assertEquals(0, problemsOf("b.kt", write(dir, "b.kt", aliasedOk)).size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun write(
        dir: Path,
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text) }

    companion object {
        private const val BASE = "submission-bootstrap/src/test/kotlin/com/dynokore/gst/submission/bootstrap/"

        /** The listed fixture files, compared by equality; never a directory walk. */
        val LISTED: List<String> =
            listOf(
                "DocUuidIndependenceDbTest.kt",
                "InFlightWaitDbTest.kt",
                "JobRuntimeCompositionTest.kt",
                "NetSubmissionScenarioDbTest.kt",
                "OutcomeFixture.kt",
                "SubmissionBoundaryDbTest.kt",
                "SubmissionQueueDbTest.kt",
                "SubmissionWorkerDbTest.kt",
                "TenancyVerifiedAdaptersDbTest.kt",
                "ComplianceEvaluationJobTest.kt",
            ).map { BASE + it }

        private val root: Path =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.exists(it.resolve("settings.gradle.kts")) }

        private val RAW = Regex("""insert\s+into\s+submission\.submission(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
        private val ALIAS = Regex("""val\s+(\w+)\s*=\s*Submission\.SUBMISSION_\b""")
        private val BLOCK = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val LINE = Regex("""(?m)^\s*//.*$""")
        private val TYPE = Regex("""\bsubmission_type\b|\bSUBMISSION_TYPE\b""")

        /** Comments blanked, newlines kept so a line number stays true. */
        private fun code(text: String): String =
            LINE.replace(BLOCK.replace(text) { m -> m.value.filter { it == '\n' } }, "")

        fun problemsOf(
            label: String,
            file: Path,
        ): List<String> {
            if (!Files.isRegularFile(file)) return listOf("$label: file is missing")
            val text = code(Files.readString(file))
            val aliases = ALIAS.findAll(text).map { it.groupValues[1] }.toSet() + "Submission.SUBMISSION_"
            val sites =
                RAW.findAll(text).map { it.range.first to statementEnd(text, it.range.first, "values") } +
                    aliases.flatMap { alias ->
                        Regex("""insertInto\(\s*${Regex.escape(alias)}\s*\)""").findAll(text).map {
                            it.range.first to statementEnd(text, it.range.first, "execute(")
                        }
                    }
            val list = sites.toList()
            if (list.isEmpty()) return listOf("$label: no insert site into submission.submission")
            return list.mapNotNull { (start, end) ->
                if (TYPE.containsMatchIn(text.substring(start, end))) {
                    null
                } else {
                    "$label:${text.substring(0, start).count { it == '\n' } + 1}: insert does not name submission_type"
                }
            }
        }

        private fun statementEnd(
            text: String,
            from: Int,
            marker: String,
        ): Int = text.indexOf(marker, from).let { if (it < 0) text.length else it }
    }
}
