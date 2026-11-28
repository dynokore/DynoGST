package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/** The edge to canonical-svc runs under its modelled policy (INV-REQ-1204), not the library default. */
class CanonicalEdgePolicyTest {
    @Test
    fun `INV-AC-29581 the canonical edge has the modelled timeout and one retry`(
        @TempDir dir: Path,
    ) {
        IngestionContext
            .offline(dir)
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            .run { context ->
                val edge = context.getBean(ResilienceProperties::class.java).edges["ingestion-svc->canonical-svc"]

                (edge != null) shouldBe true
                edge?.timeout shouldBe Duration.ofSeconds(2)
                edge?.retry?.maxAttempts shouldBe 2
            }
    }
}
