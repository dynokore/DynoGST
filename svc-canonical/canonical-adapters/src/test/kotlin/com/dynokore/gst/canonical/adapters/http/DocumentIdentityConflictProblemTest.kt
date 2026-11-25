package com.dynokore.gst.canonical.adapters.http

import com.dynokore.gst.canonical.application.document.DocumentIdentityConflict
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** INV-AC-27932 (INV-T-27939): the 409 problem of the SUB-007 refusal. */
class DocumentIdentityConflictProblemTest {
    private val correlationId = "req-sub007-mapper"

    /** The seven identity values, distinctive enough that none occurs in the catalogue text of the answer. */
    private val identityValues =
        listOf(
            "7f1c2a9e-5b3d-4c8a-9e21-0a6b4d8f3c17",
            "c4e8b2d6-19a7-4f53-8b0c-7d2e5a91f6b4",
            "OUTBOUND-DIRECTION-MARK",
            "DOCTYPE-380931",
            "ISSUER-KEY-ZQ77",
            "DOCNO-XK-558812",
            "FISCALYEAR-209913",
        )

    /** The cause carries the identity the way the database's unique-violation detail does. */
    private fun conflict() =
        DocumentIdentityConflict(
            IllegalStateException("Key (document_identity_key)=(${identityValues.joinToString()}) already exists."),
        )

    private fun render(conflict: DocumentIdentityConflict = conflict()) =
        DocumentIdentityConflictProblem.render(conflict, correlationId)

    @Test
    fun `INV-AC-27932 the refusal answers 409 as application problem json`() {
        val response = render()

        response.status shouldBe 409
        response.headers["Content-Type"] shouldBe "application/problem+json"
    }

    @Test
    fun `INV-AC-27932 the body is the API-20 envelope of SUB-007 and holds no other member`() {
        val body = JsonMapper.builder().build().readTree(render().body)

        body.propertyNames().toSet() shouldBe
            setOf(
                "type",
                "title",
                "status",
                "correlationId",
                "code",
                "severity",
                "disposition",
                "remediationOwner",
            )
        body.get("type").stringValue() shouldBe "https://errors.invoice.internal/SUB-007"
        body.get("title").stringValue() shouldBe "Same commercial document under two Doc UUIDs"
        body.get("status").isInt shouldBe true
        body.get("status").intValue() shouldBe 409
        body.get("code").stringValue() shouldBe "SUB-007"
        body.get("severity").stringValue() shouldBe "BLOCK"
        body.get("disposition").stringValue() shouldBe "TERMINAL"
        body.get("remediationOwner").stringValue() shouldBe "Platform"
        body.get("correlationId").stringValue() shouldBe correlationId
    }

    @Test
    fun `INV-AC-27932 the body holds none of the seven identity values of the conflict`() {
        val text = render().body

        identityValues.forEach { text shouldNotContain it }
    }

    @Test
    fun `INV-AC-27932 a conflict whose code is not SUB-007 is refused`() {
        listOf("SUB-008", "sub-007", "SUB-0070", " SUB-007", "SUB-007 OR SUB-008", "").forEach { code ->
            shouldThrow<IllegalArgumentException> { render(conflictWithCode(code)) }
        }
    }

    /** The class fixes its code to SUB-007 and has no way to be built with another, so the test writes the field. */
    private fun conflictWithCode(code: String): DocumentIdentityConflict {
        val conflict = DocumentIdentityConflict()
        DocumentIdentityConflict::class.java
            .getDeclaredField("code")
            .apply { isAccessible = true }
            .set(conflict, code)
        conflict.code shouldBe code
        return conflict
    }
}
