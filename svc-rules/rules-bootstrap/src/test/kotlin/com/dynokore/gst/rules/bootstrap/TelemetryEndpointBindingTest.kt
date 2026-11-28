package com.dynokore.gst.rules.bootstrap

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import java.io.File
import java.util.function.Supplier

private const val PROPERTY = "gst.bindings.telemetry-export-otlp-endpoint"
private const val ADMITTED = "http://127.0.0.1:4318"

/** An `http` or `https` URL whose authority (no space, quote, backtick or slash in it) ends in port 4318. */
private val PORT_4318_URL = Regex("""https?://[^ "'`/]*:4318(?!\d)""", RegexOption.IGNORE_CASE)

private enum class Rule { NO_ENDPOINT, OTHER_ENDPOINT, PROPERTY_WITHOUT_ENDPOINT }

private data class Finding(
    val rule: Rule,
    val path: String = "",
    val url: String = "",
)

private fun endpoints(text: String): List<String> = PORT_4318_URL.findAll(text).map { it.value }.toList()

/** The findings for [files] (path to text). It fails closed: files with no endpoint at all are a finding. */
private fun findings(files: Map<String, String>): List<Finding> {
    val none = if (files.values.all { endpoints(it).isEmpty() }) listOf(Finding(Rule.NO_ENDPOINT)) else emptyList()
    return none +
        files.flatMap { (path, text) ->
            val urls = endpoints(text)
            val other = urls.filter { it != ADMITTED }.map { Finding(Rule.OTHER_ENDPOINT, path, it) }
            val unbound = PROPERTY in text && ADMITTED !in urls
            other + if (unbound) listOf(Finding(Rule.PROPERTY_WITHOUT_ENDPOINT, path)) else emptyList()
        }
}

private fun url(
    scheme: String,
    host: String,
    port: String = "4318",
): String = scheme + "://" + host + ":" + port

/**
 * INV-AC-29675: the `EgressGuard` admits exactly 127.0.0.1 and records the connects of the whole test JVM, so no test
 * context may export spans to a name or another address (`localhost` may resolve to `[::1]`). This file is scanned too:
 * it builds every refused look-alike by concatenation.
 */
class TelemetryEndpointBindingTest {
    private fun testFiles(): Map<String, String> {
        val root =
            File(
                checkNotNull(System.getProperty("gst.repo.root")) { "gst.repo.root is not set: run through Gradle" },
            )
        return File(root, "rules-bootstrap/src/test")
            .walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    @Test
    fun `INV-AC-29675 every port 4318 URL of the test files is the loopback address the egress guard admits`() {
        findings(testFiles()).filter { it.rule != Rule.PROPERTY_WITHOUT_ENDPOINT }.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-29675 every test file that names the telemetry endpoint property holds the loopback URL`() {
        findings(testFiles()).filter { it.rule == Rule.PROPERTY_WITHOUT_ENDPOINT }.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-29675 the shared test binding registers the loopback telemetry endpoint`() {
        val bound = mutableMapOf<String, Supplier<Any>>()
        PostgresRules.register(DynamicPropertyRegistry { name, value -> bound[name] = value })
        bound[PROPERTY]?.get() shouldBe ADMITTED
    }

    @Test
    fun `INV-AC-29675 look-alike endpoints, no endpoint and a property without the loopback URL are findings`() {
        val refused =
            listOf(
                url("http", "localhost"),
                url("http", "[::1]"),
                url("http", "127.0.0.2"),
                url("http", "127.0.0.1.example"),
                url("https", "127.0.0.1"),
                url("HTTP", "127.0.0.1"),
            )
        refused.forEach { endpoint ->
            findings(mapOf("a" to "x=$endpoint")) shouldContainExactly
                listOf(Finding(Rule.OTHER_ENDPOINT, "a", endpoint))
            // an admitted URL beside it, in the same file or in another one, does not hide it
            findings(mapOf("a" to "$ADMITTED\n$endpoint")) shouldContainExactly
                listOf(Finding(Rule.OTHER_ENDPOINT, "a", endpoint))
        }

        findings(emptyMap()) shouldContainExactly listOf(Finding(Rule.NO_ENDPOINT))
        findings(mapOf("a" to url("http", "127.0.0.1", "43180"))) shouldContainExactly
            listOf(Finding(Rule.NO_ENDPOINT))

        val noPort = "$PROPERTY=" + "http" + "://" + "localhost"
        findings(mapOf("a" to noPort)) shouldContainExactly
            listOf(Finding(Rule.NO_ENDPOINT), Finding(Rule.PROPERTY_WITHOUT_ENDPOINT, "a"))
        findings(mapOf("a" to noPort, "b" to ADMITTED)) shouldContainExactly
            listOf(Finding(Rule.PROPERTY_WITHOUT_ENDPOINT, "a"))

        findings(mapOf("a" to "$PROPERTY=$ADMITTED")).shouldBeEmpty()
    }
}
