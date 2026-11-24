package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Path

/** INV-AC-27141: the evidence-svc URL of correctPersonalData is required: not blank, not an unresolved placeholder. */
class PrivacyUrlsRequiredTest {
    private val property = "console.evidence-svc-url"

    @Test
    fun `INV-AC-27141 the context starts with the evidence URL set`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { (it.startupFailure == null) shouldBe true }
    }

    @Test
    fun `INV-AC-27141 the context fails when the evidence URL is blank or an unresolved placeholder`(
        @TempDir dir: Path,
    ) {
        for (value in listOf("", "   ", "\${CONSOLE_UNSET_IN_TEST}", "http://\${HOST}")) {
            ConsoleContext
                .offline(dir, "$property=$value")
                .run { (it.startupFailure != null) shouldBe true }
        }
    }

    @Test
    fun `INV-AC-27141 the context fails when the evidence URL is absent from the configuration`(
        @TempDir dir: Path,
    ) {
        // application.yaml holds only the unresolved placeholder: with no environment value the start fails.
        val others = ConsoleContext.overrides(dir).filterNot { it.startsWith("$property=") }
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ConsoleBffApplication::class.java)
            .withPropertyValues(*others.toTypedArray())
            .run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-27141 application yaml on the classpath gives the property no default`() {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/application.yaml")) { "application.yaml is missing" }
                .use { String(it.readBytes()) }
        val lines = text.lines().map { it.substringBefore(" #").trim() }
        lines.count { it == "evidence-svc-url: \${CONSOLE_EVIDENCE_SVC_URL}" } shouldBe 1
    }
}
