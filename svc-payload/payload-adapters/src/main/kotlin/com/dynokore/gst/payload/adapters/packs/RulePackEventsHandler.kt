package com.dynokore.gst.payload.adapters.packs

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Called once a `schematron` pack version is activated in the replica, with its contents JSON (INV-REQ-776). */
fun interface SchematronActivation {
    fun activated(
        packVersion: String,
        contentsJson: String,
    )
}

/**
 * The consumer of `RulePackStaged` and `RulePackActivated` for payload-svc (event-carried state transfer,
 * INV-AC-23578).
 * One instance handles exactly one event type ([expectedType], exact string equality); any other type is a
 * [ContractViolation] before the payload is read. The type of every staged pack is recorded by identifier; only an
 * MDE pack is stored. An activation of a pack of another type is a no-op; an unknown identifier or an unstaged version
 * throws [IllegalStateException] (inbox retry); nothing is invented.
 */
class RulePackEventsHandler(
    private val expectedType: String,
    private val replica: JooqMdePackReplica,
    private val rules: JooqRulePackReplica,
    private val onSchematronActivated: SchematronActivation = SchematronActivation { _, _ -> },
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != expectedType) throw ContractViolation("expected $expectedType, was ${event.type}")
        val data = parse(event.dataJson)
        return if (expectedType == STAGED_TYPE) staged(data) else activated(data)
    }

    private fun staged(data: JsonNode): HandleOutcome {
        val identifier = text(data, "packIdentifier")
        val packType = text(data, "packType")
        val version = text(data, "version")
        val contents = contentsOf(data)
        val log = LoggerFactory.getLogger(RulePackEventsHandler::class.java)
        val recorded = replica.recordIdentity(identifier, packType)
        if (recorded == IdentityResult.CONFLICT) {
            log.error("staged pack {} came with another type than the recorded one; nothing changes", identifier)
            throw ContractViolation("a pack identifier is already recorded with another type")
        }
        if (packType in RULE_PACK_TYPES) {
            return stageRulePack(packType, identifier, version, json.writeValueAsString(contents))
        }
        return if (packType ==
            MDE_PACK_TYPE
        ) {
            stageMde(identifier, version, json.writeValueAsString(contents))
        } else {
            HandleOutcome.IGNORED
        }
    }

    private fun stageMde(
        identifier: String,
        version: String,
        contents: String,
    ): HandleOutcome {
        val log = LoggerFactory.getLogger(RulePackEventsHandler::class.java)
        return when (replica.stage(identifier, version, contents)) {
            StageResult.STORED -> {
                HandleOutcome.APPLIED
            }

            StageResult.UNCHANGED -> {
                HandleOutcome.IGNORED
            }

            StageResult.CONFLICT -> {
                log.error(
                    "staged MDE pack {} version {} came with other contents; the stored one is kept",
                    identifier,
                    version,
                )
                throw ContractViolation("an MDE pack version is already staged with other contents")
            }
        }
    }

    private fun stageRulePack(
        packType: String,
        identifier: String,
        version: String,
        contents: String,
    ): HandleOutcome =
        when (rules.stage(packType, identifier, version, contents)) {
            StageResult.STORED -> {
                HandleOutcome.APPLIED
            }

            StageResult.UNCHANGED -> {
                HandleOutcome.IGNORED
            }

            StageResult.CONFLICT -> {
                LoggerFactory
                    .getLogger(RulePackEventsHandler::class.java)
                    .error(
                        "staged {} pack {} version {} came with other contents; the stored one is kept",
                        packType,
                        identifier,
                        version,
                    )
                throw ContractViolation("a $packType pack version is already staged with other contents")
            }
        }

    private fun contentsOf(data: JsonNode): JsonNode =
        data.get("artefact")?.get("contents")?.takeUnless {
            it.isNull || it.isMissingNode || (it.isString && it.asString().isBlank()) ||
                ((it.isObject || it.isArray) && it.isEmpty)
        } ?: throw ContractViolation("a staged pack carries no artefact.contents")

    private fun activated(data: JsonNode): HandleOutcome {
        val identifier = text(data, "packIdentifier")
        val version = text(data, "version")
        val packType = checkNotNull(replica.typeOf(identifier)) { "the pack identifier is not staged yet" }
        return when {
            packType in RULE_PACK_TYPES -> {
                val changed = rules.activate(packType, identifier, version)
                if (changed && packType == SCHEMATRON_PACK_TYPE) {
                    // a throw rolls the activation back with the inbox transaction; the inbox retries
                    onSchematronActivated.activated(
                        version,
                        checkNotNull(
                            rules.contentsOf(packType, identifier, version),
                        ) { "the activated pack has no contents" },
                    )
                }
                if (changed) HandleOutcome.APPLIED else HandleOutcome.IGNORED
            }

            packType == MDE_PACK_TYPE -> {
                if (replica.activate(identifier, version)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
            }

            else -> {
                LoggerFactory
                    .getLogger(RulePackEventsHandler::class.java)
                    .info("activation of pack {} of another type ignored", identifier)
                HandleOutcome.IGNORED
            }
        }
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("the payload member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }.also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }

    companion object {
        const val MDE_PACK_TYPE = "MDE"
        const val SCHEMATRON_PACK_TYPE = "schematron"
        const val SEVERITY_OVERRIDE_PACK_TYPE = "severity-override"
        private val RULE_PACK_TYPES = setOf(SCHEMATRON_PACK_TYPE, SEVERITY_OVERRIDE_PACK_TYPE)
        const val STAGED_TYPE = "com.dynokore.gst.platform.RulePackStaged.v1"
        const val ACTIVATED_TYPE = "com.dynokore.gst.platform.RulePackActivated.v1"
        const val STAGED_TOPIC = "gst.platform.rule-pack-staged.v1"
        const val ACTIVATED_TOPIC = "gst.platform.rule-pack-activated.v1"
    }
}
