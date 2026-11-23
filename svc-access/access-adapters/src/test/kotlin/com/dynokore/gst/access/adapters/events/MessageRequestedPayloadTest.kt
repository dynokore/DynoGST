package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.link.MessageChannel
import com.dynokore.gst.access.application.link.MessageRequest
import com.dynokore.gst.access.application.link.OaepPasscodeSealer
import com.dynokore.gst.access.application.link.SealingKey
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import javax.crypto.BadPaddingException

/**
 * A test per published channel (INV-T-17453): both messages of one issue, as the real mapper appends them, validate
 * against the schema of `accessMessageRequested` in the pinned contract.
 */
class MessageRequestedPayloadTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val appended = mutableListOf<OutboundEvent>()
    private val writer =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                appended += events
                return emptyList()
            }
        }
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor() = "system:access-svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val messages = OutboxLinkMessages(writer, tenants, JsonMapper.builder().build())
    private val schema =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/access/schemas/message-requested-payload.schema.json",
            ),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
            .let { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it) }

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val sealer = OaepPasscodeSealer { SealingKey(SealingKey.keyIdOf(publicKey), publicKey) }

    private fun open(
        sealed: String,
        label: String,
    ): String {
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            keyPair.private as RSAPrivateKey,
            javax.crypto.spec.OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                java.security.spec.MGF1ParameterSpec.SHA256,
                javax.crypto.spec.PSource
                    .PSpecified(label.toByteArray()),
            ),
        )
        return String(
            cipher.doFinal(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(sealed.split(".")[2]),
            ),
        )
    }

    private fun issue() =
        messages.filedUnder(EntityRef("ENT-1")) {
            messages.request(MessageRequest("signed-link", "a@example.sg", MessageChannel.EMAIL, "LNK-1", null))
            messages.request(
                MessageRequest("signed-link-passcode", "a@example.sg", MessageChannel.EMAIL, null, SEALED),
            )
        }

    @Test
    fun `both messages of one issue validate against the schema, one with linkReference and one with sealedPasscode`() {
        issue()
        assertEquals(2, appended.size)
        assertTrue(appended.all { it.type == OutboxLinkMessages.EVENT_TYPE })
        appended.forEach { assertTrue(schema.validate(it.dataJson, InputFormat.JSON).isEmpty(), it.dataJson) }
        assertTrue(appended[0].dataJson.contains("\"linkReference\":\"LNK-1\""))
        assertTrue(appended[1].dataJson.contains("\"sealedPasscode\":\"$SEALED\""))
    }

    @Test
    fun `a copy of the payload without template does not validate, so the check can fail`() {
        issue()
        val node = JsonMapper.builder().build().readTree(appended[0].dataJson) as tools.jackson.databind.node.ObjectNode
        node.remove("template")
        assertTrue(schema.validate(node.toString(), InputFormat.JSON).isNotEmpty())
    }

    @Test
    fun `INV-AC-26235 the SMS passcode payload carries fallbackRecipient and an SMS only issue has none`() {
        val sealed = sealer.seal("123456", MOBILE)
        messages.filedUnder(EntityRef("ENT-1")) {
            messages.request(MessageRequest("signed-link", EMAIL, MessageChannel.EMAIL, "LNK-1", null))
            messages.request(
                MessageRequest(
                    "signed-link-passcode",
                    MOBILE,
                    MessageChannel.SMS,
                    null,
                    sealed,
                    fallbackRecipient = EMAIL,
                ),
            )
            messages.request(MessageRequest("signed-link-passcode", MOBILE, MessageChannel.SMS, null, sealed))
        }
        appended.forEach { assertTrue(schema.validate(it.dataJson, InputFormat.JSON).isEmpty(), it.dataJson) }
        val mapper = JsonMapper.builder().build()
        val link = mapper.readTree(appended[0].dataJson)
        val sms = mapper.readTree(appended[1].dataJson)
        val smsOnly = mapper.readTree(appended[2].dataJson)
        assertFalse(link.has("fallbackRecipient"))
        assertEquals(EMAIL, sms.get("fallbackRecipient").asString())
        assertEquals(MOBILE, sms.get("recipients").get(0).asString())
        assertFalse(smsOnly.has("fallbackRecipient"))
        // the OAEP label that opens the sealed passcode is the mobile number, the seal form is unchanged
        assertEquals("123456", open(sms.get("sealedPasscode").asString(), MOBILE))
        assertThrows(BadPaddingException::class.java) { open(sms.get("sealedPasscode").asString(), EMAIL) }
    }

    private companion object {
        const val EMAIL = "a@example.sg"
        const val MOBILE = "+6591234567"

        /** The form of the contract since INV-T-17210: `v1.<16 hex>.<512 base64url characters>`. */
        val SEALED = "v1." + "0123456789abcdef" + "." + "A".repeat(512)
    }
}
