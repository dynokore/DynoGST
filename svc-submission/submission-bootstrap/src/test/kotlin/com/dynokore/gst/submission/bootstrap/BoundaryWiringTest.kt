package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.boundary.BoundaryVersionSource
import com.dynokore.gst.submission.application.boundary.CheckSubmissionBoundary
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ClassPathResource
import java.nio.file.Path

/** The wiring of checkSubmissionBoundary with the shipped application.yaml (INV-REQ-1005). */
class BoundaryWiringTest {
    private val key = "inv.boundary-check.max-documents"

    @Test
    fun `INV-AC-26429 with the shipped yaml one use case and one source exist and the cap equals 5000`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            (context.startupFailure == null) shouldBe true
            context.getBeansOfType(CheckSubmissionBoundary::class.java).size shouldBe 1
            context.getBeansOfType(BoundaryVersionSource::class.java).size shouldBe 1
            context.getBean(BoundaryProperties::class.java).maxDocuments shouldBe 5000
            context.getBeansOfType(ReplayBySelection::class.java).size shouldBe 1
            context.getBeansOfType(ReplaySingleVersion::class.java).size shouldBe 1
        }
    }

    private fun shippedExcept(vararg dropped: String): Map<String, Any> {
        val source = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val names = (source as EnumerablePropertySource<*>).propertyNames
        return names.filter { it !in dropped }.associateWith { source.getProperty(it)!! }
    }

    private fun failureOf(values: Map<String, Any>): String? {
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly::class.java)
                .withInitializer { it.environment.propertySources.addFirst(MapPropertySource("shipped", values)) }
        var failure: Throwable? = null
        runner.run { failure = it.startupFailure }
        return generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }.takeIf { failure != null }
    }

    @Test
    fun `INV-AC-26429 without the key the start fails`() {
        shippedExcept(key)[key] shouldBe null
        // with no key under the prefix at all the binder cannot build the properties (no value, no default in code)
        failureOf(shippedExcept(key)).orEmpty() shouldContain "Failed to bind properties under 'inv.boundary-check'"
    }

    @Test
    fun `INV-AC-26429 with the value 0 the start fails and with the shipped value it starts`(
        @TempDir dir: Path,
    ) {
        failureOf(shippedExcept(key) + (key to 0)).orEmpty() shouldContain
            "inv.boundary-check.max-documents must be greater than 0"
        SubmissionContext.offline(dir).withPropertyValues("$key=0").run { context ->
            generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() } shouldContain
                "inv.boundary-check.max-documents must be greater than 0"
        }
        failureOf(shippedExcept()) shouldBe null
        MapConfigurationPropertySource(shippedExcept()).let {
            Binder(it).bindOrCreate("inv.boundary-check", Bindable.of(BoundaryProperties::class.java)).maxDocuments shouldBe 5000
        }
    }

    /** Only the binding of `inv.boundary-check`, so a missing key can be tested without the rest of the service. */
    @EnableConfigurationProperties(BoundaryProperties::class)
    class PropertiesOnly
}
