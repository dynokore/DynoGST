package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.Filter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

private val CORRECTION_TENANT = TenantId(UUID.randomUUID())
private const val OLD_NAME = "Old Legal Name Pte Ltd"
private const val NEW_NAME = "New Legal Name Pte Ltd"
private val CASE_ID: UUID = UUID.fromString("3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a7d9c11")

/**
 * INV-AC-27116 (INV-T-27119, INV-REQ-886) at the HTTP boundary of `correctCounterpartyData`: the real context over real
 * PostgreSQL, the identity and idempotency filters of service-runtime, the permission decided per request through
 * the permission port of the replica fixture, and the audit entries captured as they append.
 */
class CorrectCounterpartyDataHttpTest {
    private val resolver =
        EndUserPrincipalResolver { header ->
            val entities = setOf(EntityRef("E-001"))
            when (header) {
                "Bearer T-OP" -> EndUserPrincipal(OPERATOR, CORRECTION_TENANT, entities)
                "Bearer T-NOKEY" -> EndUserPrincipal("IDN-NOKEY", CORRECTION_TENANT, entities)
                "Bearer T-LOOK" -> EndUserPrincipal(TestPermissions.LOOKALIKE_PRIVACY, CORRECTION_TENANT, entities)
                else -> null
            }
        }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture
            .runner()
            .withBean(EndUserPrincipalResolver::class.java, { resolver })
            .withPropertyValues("service-runtime.idempotency.schema=masterdata")
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    private class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
        val party = unique("CTP-COR")
        private val mapper = JsonMapper()
        private val mvc =
            MockMvcBuilders
                .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
                .also { it.addFilters<DefaultMockMvcBuilder>(*filters()) }
                .build()

        init {
            seed(party)
        }

        private fun filters(): Array<Filter> =
            context
                .getBeansOfType(FilterRegistrationBean::class.java)
                .filterKeys { it == "endUserPrincipalBindingFilter" || it == "entityRefScopeFilter" }
                .values
                .sortedBy { it.order }
                .map { it.filter as Filter }
                .toTypedArray()

        fun seed(ref: String) {
            val tenants = context.getBean(TenantContext::class.java)
            val change =
                CounterpartyChange(
                    identifiers = emptyList(),
                    legalName = OLD_NAME,
                    address = PostalAddress(line1 = "1 Old Road", line2 = "Unit 2", postalCode = "111111"),
                    arcsGstChargeAgreed = null,
                    approvedSchemes = null,
                )
            tenants.bind(CORRECTION_TENANT, OPERATOR) {
                context
                    .getBean(RecordCounterparty::class.java)
                    .handle(RecordCommand("E-001", PartyRef(ref), change, "EVD-1", WriteOrigin.OPERATOR))
            }
        }

        fun body(
            entity: String? = "E-001",
            case: String? = CASE_ID.toString(),
            field: String? = "legalName",
            value: String? = NEW_NAME,
            extra: String? = null,
        ): String =
            listOfNotNull(
                entity?.let { "\"entityRef\":\"$it\"" },
                case?.let { "\"caseId\":\"$it\"" },
                field?.let { "\"field\":\"$it\"" },
                value?.let { "\"correctedValue\":\"$it\"" },
                "\"reason\":\"The data subject asked\"",
                extra,
            ).joinToString(",", "{", "}")

        fun correct(
            body: String,
            token: String? = "T-OP",
            key: UUID = UUID.randomUUID(),
            party: String = this.party,
        ): Pair<Int, String> {
            val request =
                MockMvcRequestBuilders
                    .post("/internal/v1/masterdata/counterparties/$party/corrections")
                    .header("Idempotency-Key", key.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            if (token != null) request.header("Authorization", "Bearer $token")
            val response = mvc.perform(request).andReturn().response
            return response.status to response.contentAsString
        }

        /** The versions of the history of [party], as (version, legalName, line1, line2, postalCode). */
        fun history(party: String = this.party): List<List<String?>> {
            val response =
                mvc
                    .perform(
                        MockMvcRequestBuilders
                            .get("/internal/v1/masterdata/counterparties/$party/history")
                            .header("Authorization", "Bearer T-OP"),
                    ).andReturn()
                    .response
            check(response.status == 200) { response.contentAsString }
            val items = mapper.readTree(response.contentAsString)["items"]
            return (0 until items.size()).map { index ->
                val item = items[index]
                val held = item["counterparty"]
                val address = held["address"]
                listOf(
                    item["version"].asString(),
                    held["legalName"]?.asString(),
                    address?.get("addressLine1")?.asString(),
                    address?.get("addressLine2")?.asString(),
                    address?.get("postalCode")?.asString(),
                )
            }
        }

        fun versionOf(answer: String): Int = mapper.readTree(answer)["version"].asInt()

        fun audits() = captured.envelopes.filter { it.type.contains("Audit") && CASE_ID.toString() in it.dataJson }
    }

    @Test
    fun `INV-AC-27116 a valid call answers 200 with the new version and the history shows the corrected field`() =
        run { env ->
            val (status, answer) = env.correct(env.body())
            assertThat(status).describedAs(answer).isEqualTo(200)
            assertThat(env.versionOf(answer)).isEqualTo(2)
            assertThat(JsonMapper().readTree(answer)["counterparty"]["legalName"].asString()).isEqualTo(NEW_NAME)
            assertThat(env.history())
                .containsExactly(
                    listOf("1", OLD_NAME, "1 Old Road", "Unit 2", "111111"),
                    listOf("2", NEW_NAME, "1 Old Road", "Unit 2", "111111"),
                )
        }

    @Test
    fun `INV-AC-27116 each correctable field maps to its own address part`() =
        run { env ->
            listOf("addressLine1" to 2, "addressLine2" to 3, "postalCode" to 4).forEach { (field, version) ->
                val (status, answer) = env.correct(env.body(field = field, value = "V-$field"))
                assertThat(status).describedAs(answer).isEqualTo(200)
                assertThat(env.versionOf(answer)).isEqualTo(version)
            }
            assertThat(env.history().last())
                .isEqualTo(listOf("4", OLD_NAME, "V-addressLine1", "V-addressLine2", "V-postalCode"))
        }

    @Test
    fun `INV-AC-27116 an unknown counterparty answers 404 SRT-005`() =
        run { env ->
            val (status, answer) = env.correct(env.body(), party = unique("NOPE"))
            assertThat(status).describedAs(answer).isEqualTo(404)
            assertThat(answer).contains("SRT-005")
        }

    @Test
    fun `INV-AC-27116 an entity outside the caller's set answers 403 SRT-004`() =
        run { env ->
            listOf("E-002", "e-001").forEach { entity ->
                val (status, answer) = env.correct(env.body(entity = entity))
                assertThat(status).describedAs("$entity: $answer").isEqualTo(403)
                assertThat(answer).contains("SRT-004")
            }
            assertThat(env.history()).hasSize(1)
        }

    @Test
    fun `INV-AC-27116 a caller without privacy-request handle or with a look-alike key is refused 403`() =
        run { env ->
            listOf("T-NOKEY", "T-LOOK").forEach { token ->
                val (status, answer) = env.correct(env.body(), token = token)
                assertThat(status).describedAs("$token: $answer").isEqualTo(403)
            }
            assertThat(env.correct(env.body(), token = null).first).isEqualTo(401)
            assertThat(env.history()).hasSize(1)
            assertThat(env.audits()).isEmpty()
        }

    @Test
    fun `INV-AC-27116 an unknown field value or a missing caseId answers 400 SRT-001`() =
        run { env ->
            val bodies =
                listOf(
                    env.body(field = "legalname"),
                    env.body(field = "ADDRESS_LINE_1"),
                    env.body(case = null),
                )
            bodies.forEach { body ->
                val (status, answer) = env.correct(body)
                assertThat(status).describedAs("$body: $answer").isEqualTo(400)
                assertThat(answer).contains("SRT-001")
            }
            assertThat(env.history()).hasSize(1)
            assertThat(env.audits()).isEmpty()
        }

    @Test
    fun `INV-AC-27116 a value the use case refuses answers 400 SRT-001 and appends none`() =
        run { env ->
            val (status, answer) = env.correct(env.body(field = "postalCode", value = "1".repeat(21)))
            assertThat(status).describedAs(answer).isEqualTo(400)
            assertThat(answer).contains("SRT-001")
            assertThat(env.history()).hasSize(1)
            assertThat(env.audits()).isEmpty()
        }

    @Test
    fun `INV-AC-27116 one audit entry per applied correction holds user ID, case id and field only`() =
        run { env ->
            env.correct(env.body())
            env.correct(env.body(case = null))
            env.correct(env.body(), token = "T-NOKEY")
            val entries = env.audits()
            assertThat(entries).hasSize(1)
            val json = entries.single().dataJson
            assertThat(json).contains(OPERATOR, CASE_ID.toString(), "LEGAL_NAME")
            assertThat(json).doesNotContain(NEW_NAME, OLD_NAME, "security.officer", "entity.admin")
        }
}
