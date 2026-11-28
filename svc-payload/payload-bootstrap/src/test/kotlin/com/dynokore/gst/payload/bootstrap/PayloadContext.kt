package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.application.build.LayerOneCheck
import com.dynokore.gst.payload.application.build.NOT_WIRED
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** The service composed as TPL-18 writes it, over a TPL-16 configuration (application.yaml plus these overrides). */
object PayloadContext {
    private fun secrets(mountRoot: Path) {
        Files.createDirectories(mountRoot.resolve("db"))
        Files.createDirectories(mountRoot.resolve("tenancy"))
        Files.writeString(mountRoot.resolve("db/payload_runtime"), PayloadDb.RUNTIME_PASSWORD)
        Files.writeString(mountRoot.resolve("tenancy/binding-key-current"), PayloadDb.BINDING_SECRET)
    }

    /**
     * No connection is opened at start: the pool is lazy (TPL-16). A null [timeZone] or [calendar] leaves that
     * property out (INV-AC-26579).
     */
    fun offline(
        mountRoot: Path,
        timeZone: String? = TIME_ZONE,
        calendar: String? = CALENDAR,
    ): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot, MASTERDATA_URL, timeZone, calendar)
    }

    /** As [offline] with the masterdata-svc base URL given (INV-AC-26694); null leaves the property out. */
    fun offlineWithMasterdataUrl(
        mountRoot: Path,
        masterdataUrl: String?,
    ): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot, masterdataUrl)
    }

    private const val MASTERDATA_URL = "http://localhost:9"

    /** Synthetic installation values: no real zone choice, calendar row or release code. */
    private const val TIME_ZONE = "UTC"

    internal val CALENDAR =
        listOf("PINT_SG", "SG_PEPPOL_BIS").joinToString(",", "{\"calendars\":[", "]}") { family ->
            """{"family":"$family","releaseOrder":["SYNTH-A"],""" +
                """"entries":[{"version":"SYNTH-A","acceptedFrom":"2000-01-01"}]}"""
        }

    /** The service over the Testcontainers database as a servlet context, for the tests that call the controllers. */
    fun web(mountRoot: Path): WebApplicationContextRunner {
        secrets(mountRoot)
        return WebApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PayloadApplication::class.java)
            .withPropertyValues(*properties(mountRoot).toTypedArray(), "spring.datasource.url=${PayloadDb.jdbcUrl}")
    }

    private fun properties(
        mountRoot: Path,
        masterdataUrl: String? = MASTERDATA_URL,
        timeZone: String? = TIME_ZONE,
        calendar: String? = CALENDAR,
    ): List<String> =
        listOfNotNull(
            "spring.datasource.username=payload_runtime",
            "service-runtime.workload-identity.audience=payload-svc",
            "gst.service=payload-svc",
            "gst.instance-id=payload-svc-0",
            "service-runtime.pool.profile=minimal",
            "service-runtime.modules.schema=payload",
            "service-runtime.idempotency.schema=payload",
            "service-runtime.datasource.credential=db/payload_runtime",
            "gst.bindings.secret-store.mount-root=$mountRoot",
            // the base URL of access-svc (INV-T-17184): the bean of the override wiring refuses to start without it
            "payload.access-svc-url=http://localhost:9",
            masterdataUrl?.let { "payload.masterdata-svc-url=$it" },
            timeZone?.let { "payload.build.time-zone=$it" },
            calendar?.let { "payload.acceptance-calendar=$it" },
            "gst.bindings.telemetry-export=bundled-otel",
            "gst.bindings.telemetry-export-otlp-endpoint=http://telemetry-export.invalid:4318",
        )

    private fun base(
        mountRoot: Path,
        masterdataUrl: String? = MASTERDATA_URL,
        timeZone: String? = TIME_ZONE,
        calendar: String? = CALENDAR,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            // the real application.yaml (TPL-16), then the overrides below
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PayloadApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/payload",
                *properties(mountRoot, masterdataUrl, timeZone, calendar).toTypedArray(),
            )

    /** Over the Testcontainers database; the login password and the binding key come from a mounted secret store. */
    fun online(
        mountRoot: Path,
        calendar: String? = CALENDAR,
    ): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot, calendar = calendar)
            .withPropertyValues("spring.datasource.url=${PayloadDb.jdbcUrl}")
            // These builds run no Schematron pack (none is active here), so the production layer-one check, which
            // fails closed without an active pack (INV-AC-27232), is replaced by the no-op check they ran with.
            .withBean(
                "layerOneCheckForBuildFixtures",
                LayerOneCheck::class.java,
                { NOT_WIRED },
                { it.isPrimary = true },
            )
    }
}
