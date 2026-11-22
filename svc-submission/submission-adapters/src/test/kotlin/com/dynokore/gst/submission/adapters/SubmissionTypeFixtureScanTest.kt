package com.dynokore.gst.submission.adapters

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source scan for INV-AC-27291 (INV-T-27293): each statement that inserts into submission.submission in the listed
 * fixture files names the submission_type column, so the later NOT NULL migration (INV-T-27288) finds them ready.
 * It is a text scan: an insert built by a helper in another file is not seen (none exists in the listed files; the
 * production writer JooqSubmissionStore is not a fixture).
 */
class SubmissionTypeFixtureScanTest {
    private val files =
        listOf(
            "AcknowledgementDeadlineMigrationTest.kt",
            "AttemptFailureClassMigrationTest.kt",
            "AttemptFailureClassRetrySuffixMigrationTest.kt",
            "AttemptOutcomeMigrationTest.kt",
            "DrainBacklogDbTest.kt",
            "DrainResidualSourceDbTest.kt",
            "HoldStoreDbTest.kt",
            "InFlightIndexMigrationTest.kt",
            "ObligationRowSourceDbTest.kt",
            "PrecedingStatesDbTest.kt",
            "QueuedAtMigrationTest.kt",
            "ReadinessEntrySourceDbTest.kt",
            "TenantTablesTest.kt",
            "UnsubmittedCountMeasurementDbTest.kt",
            "UnsubmittedDocumentSourceDbTest.kt",
            "WaitingTenantsMigrationTest.kt",
            "FailureCodesDbTest.kt",
            "ReplayInFlightProbeDbTest.kt",
            "ReplayVersionLookupDbTest.kt",
            "ScopeStoresDbTest.kt",
        )

    @Test
    fun `INV-AC-27291 the insert statements of the 20 listed fixture files each name submission_type`() {
        assertEquals(20, files.size)
        assertEquals(20, files.toSet().size)
        val root = repositoryRoot().resolve(DIR)
        val failures =
            files.flatMap { rel ->
                val path = root.resolve(rel)
                if (!Files.isRegularFile(path)) {
                    listOf("$rel: listed file is missing")
                } else {
                    scan(rel, Files.readString(path))
                }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `scan fails a listed file with zero insert sites`() {
        assertEquals(1, scan("F.kt", "class F { val x = 1 }").size)
    }

    @Test
    fun `scan fails a raw insert without the column and a misspelled column`() {
        val bare = "\"insert into submission.submission (tenant_id, entity_ref) \" + \"values (?, ?)\""
        val typo = "\"insert into submission.submission (tenant_id, submission_typ) \" + \"values (?, ?)\""
        val ok = "\"insert into submission.submission (tenant_id, submission_type) \" + \"values (?, 'TYPE_1B')\""
        assertEquals(1, scan("F.kt", bare).size)
        assertEquals(1, scan("F.kt", typo).size)
        assertEquals(0, scan("F.kt", ok).size)
    }

    @Test
    fun `scan covers jOOQ inserts through an alias and ignores the attempt table`() {
        val head = "private val s = Submission.SUBMISSION_\n"
        val bad = head + "dsl.insertInto(s)\n.set(s.STATE, \"X\")\n.execute()\n"
        val good = head + "dsl.insertInto(s)\n.set(s.SUBMISSION_TYPE, \"TYPE_1B\")\n.execute()\n"
        val attempt = "\"insert into submission.submission_attempt (a) values (1)\""
        assertEquals(1, scan("F.kt", bad).size)
        assertEquals(0, scan("F.kt", good).size)
        assertEquals(1, scan("F.kt", attempt).size)
    }

    private fun scan(
        name: String,
        text: String,
    ): List<String> {
        val sites = mutableListOf<Pair<Int, Boolean>>()
        RAW.findAll(text).forEach { m ->
            val values = text.indexOf("values", m.range.last, ignoreCase = true)
            val columns = if (values < 0) text.substring(m.range.first) else text.substring(m.range.first, values)
            sites += m.range.first to TYPE_RAW.containsMatchIn(columns)
        }
        val aliases =
            ALIAS
                .findAll(text)
                .map { it.groupValues[1] }
                .toSet() + "Submission.SUBMISSION_"
        aliases.forEach { alias ->
            Regex("""insertInto\(\s*${Regex.escape(alias)}\s*\)""").findAll(text).forEach { m ->
                val end = text.indexOf(".execute()", m.range.last)
                val body = if (end < 0) text.substring(m.range.first) else text.substring(m.range.first, end)
                sites += m.range.first to TYPE_JOOQ.containsMatchIn(body)
            }
        }
        if (sites.isEmpty()) return listOf("$name: listed file holds no insert site")
        return sites.filter { !it.second }.map { (offset, _) ->
            "$name:${text.substring(0, offset).count { it == '\n' } + 1}: insert into submission.submission " +
                "does not name submission_type"
        }
    }

    private fun repositoryRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.isRegularFile(dir.resolve("ac-keys.tsv"))) dir = dir.parent
        return requireNotNull(dir) { "repository root (ac-keys.tsv) not found" }
    }

    private companion object {
        const val DIR = "submission-adapters/src/test/kotlin/com/dynokore/gst/submission/adapters"
        val RAW = Regex("""insert\s+into\s+submission\.submission\s*\(""", RegexOption.IGNORE_CASE)
        val TYPE_RAW = Regex("""\bsubmission_type\b""", RegexOption.IGNORE_CASE)
        val TYPE_JOOQ = Regex("""\bSUBMISSION_TYPE\b""")
        val ALIAS = Regex("""\b(?:val|var)\s+(\w+)\s*=\s*Submission\.SUBMISSION_\b""")
    }
}
