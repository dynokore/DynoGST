package com.dynokore.gst.submission.acceptance

import com.dynokore.gst.submission.adapters.events.JacksonAggregateWindowChangedDataReader
import com.dynokore.gst.submission.application.completeness.WINDOW_FINDING_CODE
import com.dynokore.gst.submission.application.completeness.WindowChangeState
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File

/**
 * The consumer side of `AggregateWindowChanged` against the schema of the pinned contract bundle (INV-AC-27725). The
 * bundle carries no examples, so the samples are written here from the schema.
 */
class AggregateWindowChangedContractTest {
    private val mapper = ObjectMapper()
    private val reader = JacksonAggregateWindowChangedDataReader()
    private val schemaText =
        File(
            checkNotNull(System.getProperty("gst.aggregation.schemas.dir")) {
                "system property gst.aggregation.schemas.dir is not set"
            },
            "aggregate-window-changed-payload.schema.json",
        ).readText()
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }.getSchema(schemaText)

    private val missed =
        """{"entityRef":"E-001","prescribedPeriod":"2026Q4","window":{"from":"2026-11-01","to":"2026-11-30"},
        |"state":"MISSED","findingCode":"$WINDOW_FINDING_CODE","stateVersion":2}
        """.trimMargin()

    private val spanUnknown =
        """{"entityRef":"E-001","prescribedPeriod":"UNKNOWN-SPAN",
        |"window":{"from":"2026-11-01","to":"2026-11-30"},"state":"MISSED","findingCode":"$WINDOW_FINDING_CODE",
        |"spanKnown":false,"stateVersion":1}
        """.trimMargin()

    private fun errors(payload: String): List<String> = schema.validate(mapper.readTree(payload)).map { it.toString() }

    private fun changed(
        sample: String,
        change: ObjectNode.() -> Unit,
    ): String = (mapper.readTree(sample) as ObjectNode).apply(change).toString()

    @Test
    @DisplayName("INV-AC-27725 the MISSED sample validates against the pinned schema and is read as MISSED")
    fun missedSample() {
        errors(missed).shouldBeEmpty()
        val read = reader.read(missed)
        read.state shouldBe WindowChangeState.MISSED
        read.spanKnown shouldBe true
    }

    @Test
    @DisplayName("INV-AC-27725 the span-unknown MISSED sample validates and is read with spanKnown false")
    fun spanUnknownSample() {
        errors(spanUnknown).shouldBeEmpty()
        val read = reader.read(spanUnknown)
        read.state shouldBe WindowChangeState.MISSED
        read.spanKnown shouldBe false
    }

    @Test
    @DisplayName("INV-AC-27725 state missed in lower case fails the pinned schema")
    fun lowerCaseState() {
        onlyErrorAt("/state", changed(missed) { put("state", "missed") })
    }

    @Test
    @DisplayName("INV-AC-27725 findingCode ing-027 in lower case fails the pinned schema")
    fun lowerCaseFindingCode() {
        onlyErrorAt("/findingCode", changed(missed) { put("findingCode", "ing-027") })
    }

    @Test
    @DisplayName("INV-AC-27725 spanKnown as the text false fails the pinned schema")
    fun textSpanKnown() {
        onlyErrorAt("/spanKnown", changed(spanUnknown) { put("spanKnown", "false") })
    }

    @Test
    @DisplayName("INV-AC-27725 the window finding code is the findingCode constant of the pinned schema, not PAY-014")
    fun findingCodeConstant() {
        val constant =
            mapper
                .readTree(schemaText)
                .get("properties")
                .get("findingCode")
                .get("const")
                .asString()
        WINDOW_FINDING_CODE shouldBe constant
        WINDOW_FINDING_CODE shouldNotBe "PAY-014"
    }

    private fun onlyErrorAt(
        pointer: String,
        payload: String,
    ) {
        val found = errors(payload)
        found shouldHaveSize 1
        found.single() shouldStartWith "$pointer:"
    }
}
