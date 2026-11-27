package com.dynokore.platform.testcontainerspins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.utility.TestcontainersConfiguration
import java.io.File
import java.util.Properties

private const val TIMEOUT_NAME = "TESTCONTAINERS_RYUK_CONTAINER_TIMEOUT"
private const val DISABLED_NAME = "TESTCONTAINERS_RYUK_DISABLED"
private const val REQUIRED_ENTRY = "$TIMEOUT_NAME: \"120\""

// The whole name only: a longer name that starts or ends with it is another variable.
private val timeoutNameToken = Regex("""(?<![A-Za-z0-9_])$TIMEOUT_NAME(?![A-Za-z0-9_])""")
private val topLevelVariablesHeader = Regex("""variables:(\s+#.*)?""")

private fun indentOf(line: String) = line.length - line.trimStart(' ').length

private fun isBlankOrComment(line: String) = line.isBlank() || line.trimStart().startsWith("#")

/**
 * The reasons to refuse a CI file; empty means accepted. It reads lines, not YAML, and fails closed: only a whole-line
 * comment is exempt, and the one line that names the timeout must be exactly [REQUIRED_ENTRY], as a direct child of the
 * one top-level `variables:` block.
 */
private fun ryukWaitViolations(text: String): List<String> {
    val lines = text.lines()
    val violations = mutableListOf<String>()
    val code = lines.indices.filterNot { isBlankOrComment(lines[it]) }

    code.filter { DISABLED_NAME in lines[it] }.forEach { violations += "line ${it + 1} names $DISABLED_NAME" }

    val naming = code.filter { timeoutNameToken.containsMatchIn(lines[it]) }
    if (naming.size != 1) {
        violations += "$TIMEOUT_NAME must be named on exactly one line, found ${naming.size}"
    }

    val headers = code.filter { topLevelVariablesHeader.matches(lines[it]) }
    if (headers.size != 1) {
        violations += "expected exactly one top-level variables block, found ${headers.size}"
        return violations
    }
    val block = code.filter { it > headers.single() }.takeWhile { indentOf(lines[it]) > 0 }
    val childIndent = block.firstOrNull()?.let { indentOf(lines[it]) }
    val entries = block.filter { indentOf(lines[it]) == childIndent && lines[it].trim() == REQUIRED_ENTRY }
    if (entries.size != 1) {
        violations += "the top-level variables block must hold exactly one entry $REQUIRED_ENTRY, found ${entries.size}"
    }
    return violations
}

class RyukStartupWaitTest {
    private val rootDir =
        File(checkNotNull(System.getProperty("platformLibs.rootDir")) { "platformLibs.rootDir is not set" })

    @Test
    fun `INV-AC-29252 (a) the real CI file sets the bounded Ryuk start-up wait and nothing disables Ryuk`() {
        assertEquals(emptyList<String>(), ryukWaitViolations(rootDir.resolve(".gitlab-ci.yml").readText()))
        val properties =
            Properties().apply {
                rootDir
                    .resolve(
                        "testcontainers-pins/src/main/resources/testcontainers.properties",
                    ).inputStream()
                    .use(::load)
            }
        assertFalse(properties.containsKey("ryuk.disabled"), "testcontainers.properties must not set ryuk.disabled")
    }

    @Test
    fun `INV-AC-29252 (b) the check refuses each look-alike declaration`() {
        val top = "variables:\n  MAVEN_PUBLISH: \"true\"\n"
        val job = "unit-test:\n  variables:\n"
        val right = "$top  $REQUIRED_ENTRY\n"
        // The control: without it a function that refuses every text would pass this case.
        assertEquals(emptyList<String>(), ryukWaitViolations(right))

        val refused =
            mapOf(
                "name TESTCONTAINERS_RYUK_TIMEOUT" to "$top  TESTCONTAINERS_RYUK_TIMEOUT: \"120\"\n",
                "name with suffix _SECONDS" to "$top  ${TIMEOUT_NAME}_SECONDS: \"120\"\n",
                "value 30" to "$top  $TIMEOUT_NAME: \"30\"\n",
                "value 1200" to "$top  $TIMEOUT_NAME: \"1200\"\n",
                "entry only under a job" to "$top$job    $REQUIRED_ENTRY\n",
                "second declaration under a job" to "$right$job    $REQUIRED_ENTRY\n",
                "entry commented out" to "$top  # $REQUIRED_ENTRY\n",
                "Ryuk disabled" to "$right  $DISABLED_NAME: \"true\"\n",
                "empty text" to "",
                "no variables block" to "  $REQUIRED_ENTRY\n",
                "entry nested below a variable" to "$top  OTHER:\n    $REQUIRED_ENTRY\n",
                "entry after the block ended" to "${top}unit-test:\n  $REQUIRED_ENTRY\n",
                "second top-level variables block" to "${right}variables:\n  OTHER: \"1\"\n",
                "override in a script line" to "$right$job    A: b\n  script: export $TIMEOUT_NAME=30\n",
            )
        refused.forEach { (label, text) ->
            assertNotEquals(emptyList<String>(), ryukWaitViolations(text), "accepted: $label")
        }
    }

    // TestcontainersConfiguration is what the reaper start-up reads; no container is started.
    @Test
    fun `INV-AC-29252 (c) in CI Testcontainers resolves a Ryuk start-up wait of 120 seconds`() {
        assumeTrue(System.getenv("GITLAB_CI") == "true", "only a GitLab CI job has the pipeline variables")
        assertEquals(120, TestcontainersConfiguration.getInstance().ryukTimeout)
    }
}
