package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.nio.file.Path
import java.time.Duration

/**
 * INV-AC-29586: the tenancy-svc URL is a required setting with no default (twelve-factor), and the edge to
 * tenancy-svc has its own timeout and bounded retry (INV-REQ-1204) instead of the registry's default policy.
 */
class TenancyEdgeWiringTest {
    @Test
    fun `INV-AC-29586 application yaml binds the tenancy url to INGESTION_TENANCY_SVC_URL with no default`() {
        // the loader resolves no placeholder: the value is the file's own text
        val values =
            YamlPropertySourceLoader()
                .load("application", ClassPathResource("application.yaml"))
                .mapNotNull { it.getProperty("ingestion.tenancy-svc-url") }

        values.map { it.toString() } shouldBe listOf("\${INGESTION_TENANCY_SVC_URL}")
    }

    @Test
    fun `INV-AC-29586 the tenancy edge has the modelled timeout and one retry`(
        @TempDir dir: Path,
    ) {
        IngestionContext
            .offline(dir)
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            .run { context ->
                val edge = context.getBean(ResilienceProperties::class.java).edges["ingestion-svc->tenancy-svc"]

                edge?.timeout shouldBe Duration.ofSeconds(2)
                edge?.retry?.maxAttempts shouldBe 2
            }
    }
}
