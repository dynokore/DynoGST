package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.packs.ReplicaPack
import com.dynokore.gst.payload.application.build.DecisionInputFact
import com.dynokore.gst.payload.application.build.DecisionPackVersion
import com.dynokore.gst.payload.application.build.LayerOneCheck
import com.dynokore.gst.payload.application.build.LayerOneResult
import com.dynokore.gst.payload.application.build.PackVersionFact
import com.dynokore.gst.payload.application.build.RejectionDecision
import com.dynokore.gst.payload.domain.ClassifiedFinding
import com.dynokore.gst.payload.domain.FailedAssertion
import com.dynokore.gst.payload.domain.LayerOneSeverity
import com.dynokore.gst.payload.domain.PeppolFlag
import com.dynokore.gst.payload.domain.SchematronFailure
import com.dynokore.gst.payload.domain.SchematronValidator
import com.dynokore.gst.payload.domain.Severity
import com.dynokore.gst.payload.domain.SeverityEscalation
import tools.jackson.databind.json.JsonMapper

/** The active pack of a pack type (`schematron`, `severity-override`), or null when none is active. */
fun interface ActiveRulePacks {
    fun active(packType: String): ReplicaPack?
}

/**
 * The production layer-one check (INV-AC-27232, INV-REQ-470): runs the active Schematron pack on the built bytes,
 * classifies each failed assert (INV-Q-6786) and builds one decision per blocking finding. Fails closed: with no
 * active Schematron pack it raises [IllegalStateException], so the build is retried and nothing publishes unchecked.
 * `ruleId` and `assertionText` are never cut; `name` and `value` are cut only to the bounds of [DecisionInputFact].
 */
class SchematronLayerOneCheck(
    private val validator: SchematronValidator,
    private val rules: ActiveRulePacks,
) : LayerOneCheck {
    private val json = JsonMapper.builder().build()

    override fun check(
        bytes: ByteArray,
        specificationVersion: String,
    ): LayerOneResult {
        val schematron = checkNotNull(rules.active(SCHEMATRON)) { "no active Schematron pack: layer one cannot run" }
        val override = rules.active(SEVERITY_OVERRIDE)
        val escalations = override?.let { escalationsOf(it.contentsJson) }.orEmpty()
        val failed = validator.validate(schematron.version, bytes).failedAsserts

        val findings = ArrayList<ClassifiedFinding>()
        val decisions = ArrayList<RejectionDecision>()
        for (assert in failed) {
            val ruleId = assert.id ?: UNIDENTIFIED
            val finding =
                LayerOneSeverity.classify(
                    SchematronFailure(ruleId, flagOf(assert.role)),
                    specificationVersion,
                    escalations,
                )
            findings += finding
            if (finding.severity == Severity.BLOCK) {
                val escalated = finding.code == LayerOneSeverity.PAY_001
                decisions += decision(finding, assert, bytes, schematron, override.takeIf { escalated })
            }
        }
        val packs =
            listOf(PackVersionFact(SCHEMATRON, schematron.version)) +
                listOfNotNull(override?.let { PackVersionFact(SEVERITY_OVERRIDE, it.version) })
        return LayerOneResult(findings, decisions, packs)
    }

    private fun decision(
        finding: ClassifiedFinding,
        assert: FailedAssertion,
        bytes: ByteArray,
        schematron: ReplicaPack,
        override: ReplicaPack?,
    ): RejectionDecision {
        val used =
            listOfNotNull(schematron, override).map { DecisionPackVersion(it.packIdentifier, it.version) }
        val input =
            DecisionInputFact(
                cut(SvrlLocation.lastStepName(assert.location), DecisionInputFact.NAME_MAX),
                cut(SvrlLocation.valueOf(bytes, assert.location), DecisionInputFact.VALUE_MAX),
            )
        return RejectionDecision(checkNotNull(finding.code), finding.ruleId, used, listOf(input), assert.text)
    }

    private fun flagOf(role: String?): PeppolFlag =
        when (role) {
            "fatal" -> PeppolFlag.FATAL
            "warning" -> PeppolFlag.WARNING
            else -> PeppolFlag.FATAL
        }

    private fun escalationsOf(contents: String): List<SeverityEscalation> =
        json.readTree(contents).path("escalations").values().map { entry ->
            SeverityEscalation(
                entry.path("ruleId").asString(),
                entry
                    .path("specificationVersions")
                    .values()
                    .map { it.asString() }
                    .toSet(),
            )
        }

    /** At most [max] characters, never ending inside a surrogate pair; a text within the bound is unchanged. */
    private fun cut(
        text: String,
        max: Int,
    ): String {
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, end)
    }

    companion object {
        const val SCHEMATRON = "schematron"
        const val SEVERITY_OVERRIDE = "severity-override"
        const val UNIDENTIFIED = "unidentified"
    }
}
