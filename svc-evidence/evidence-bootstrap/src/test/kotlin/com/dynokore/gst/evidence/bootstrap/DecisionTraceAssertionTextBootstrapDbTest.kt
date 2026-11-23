package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.RetentionPartitionFixture
import com.dynokore.gst.evidence.adapters.http.LookupController
import com.dynokore.gst.evidence.adapters.http.LookupRefusal
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.application.lookup.DOC_VIEW_CONTENT
import com.dynokore.gst.evidence.contract.model.DecisionTraceView
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationContext
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-27156 over PostgreSQL and the TPL-18 context: a `PayloadRejected` event delivered through the real inbox
 * consumer of the payload topic (the handler of the registry entry of the `payloadRejectedConsumer` bean) after the
 * locator is returned by `getDecisionTrace` of the real controller with pack version, assertion text, input name and
 * input value. Malformed stored content answers SRT-010 (INTERNAL_ERROR) with no body, as INV-T-20778 defines.
 */
class DecisionTraceAssertionTextBootstrapDbTest {
    private val actor = "svc:evidence-svc"
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-0000000027e1")
    private val entity = "ENT-1"
    private val period = "2026-Q1"
    private val at = Instant.parse("2026-11-20T10:00:00Z")
    private val text = "Total tax amount must equal the sum of the tax category amounts"
    private val json = EnvelopeJson()

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private class Boundary(
        context: ApplicationContext,
        val tenant: TenantId,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)
        val log = Log()
        private val lookup = LookupConfiguration()

        private val noObjects: ObjectStore =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(ObjectStore::class.java),
            ) { _, _, _ -> error("no object is read by this handler") } as ObjectStore

        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(
                    "assertion-text-" + UUID.randomUUID(),
                    RecordConsumerConfiguration.PAYLOAD_REJECTED_TOPIC,
                    com.dynokore.gst.evidence.adapters.persistence.ChainWiring.SCHEMA,
                ),
                catalogue,
                log,
                scope,
                dsl,
                RecordConsumerConfiguration.REGISTRY
                    .single { it.bean == RecordConsumerConfiguration.PAYLOAD_REJECTED_BEAN }
                    .handler(
                        dsl,
                        Clock.systemUTC(),
                        noObjects,
                    ),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )

        private val request =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(HttpServletRequest::class.java),
            ) { _, method, args ->
                check(method.name == "getAttribute")
                EndUserPrincipal("user-42", tenant, emptySet()).takeIf {
                    args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE
                }
            } as HttpServletRequest

        val controller =
            LookupController(
                context.getBean(com.dynokore.gst.evidence.application.lookup.AcknowledgementChainLookup::class.java),
                context.getBean(com.dynokore.gst.evidence.application.lookup.ReverseLookup::class.java),
                lookup.decisionTraceLookup(replica(), dsl),
                context.getBean(com.dynokore.gst.evidence.application.lookup.TransmittedVersionLookup::class.java),
                object : ObjectProvider<HttpServletRequest> {
                    override fun getObject(): HttpServletRequest = request
                },
            )

        private fun replica(): AuthorityReplica {
            val clock = Clock.systemUTC()
            val roles =
                OperationRoleMap {
                    if (it == DOC_VIEW_CONTENT) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap()
                }
            val unavailable =
                ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
            val replica = AuthorityReplica(clock, roles, fallback = unavailable)
            val grant =
                RoleGrant(
                    "auditor",
                    GrantScope.TENANT,
                    tenant.value.toString(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null,
                )
            replica.accept(
                AuthorityChanged(
                    tenant,
                    "user-42",
                    1,
                    AuthorityRecord.RoleGrantRecord("g1", RoleGrantState.ACTIVE, grant),
                ),
            )
            replica.onSnapshotComplete(1)
            replica.observeHead(1)
            return replica
        }
    }

    private fun run(
        dir: Path,
        block: Boundary.() -> Unit,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        EvidenceContext.online(dir).run { context -> Boundary(context, tenant).block() }
    }

    private fun <T> Boundary.tx(body: () -> T): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    private fun Boundary.locate() =
        tx {
            JooqLocatorStore(dsl).insert(
                tenant,
                "L1",
                DocumentLocator(doc, 1, entity, period, LocalDate.of(2026, 1, 15), "trace/1", "INV-1"),
                at,
            )
            RetentionPartitionFixture.ensureRetentionPartition(dsl, "decision_trace", tenant.value, entity, period)
        }

    private fun Boundary.deliver(decision: String) {
        log.add(
            json.write(
                Envelope(
                    id = EventId("pr-" + UUID.randomUUID()),
                    source = "/gst/payload-svc",
                    type = "com.dynokore.gst.payload.PayloadRejected.v1",
                    subject = doc.toString(),
                    time = at,
                    tenant = tenant,
                    entity = EventEntityRef(entity),
                    docUuid = DocUuid(doc),
                    transactionDate = LocalDate.of(2026, 1, 15),
                    docSequence = null,
                    instanceId = "/gst/payload-svc-0",
                    correlation = CorrelationId("corr-1"),
                    causation = CausationId("cause-1"),
                    dataJson = """{"docUuid":"$doc","version":1,"decisions":[$decision]}""",
                ),
            ),
        )
        consumer.pollOnce().applied shouldBe 1
    }

    private fun decision(assertion: String?) =
        """{"code":"PAY-002","ruleId":"BR-CO-15",""" +
            """"packVersions":[{"packIdentifier":"schematron","version":"2026.1"}],""" +
            """"inputs":[{"name":"IBT-110","value":"7.00"}]""" +
            assertion?.let { ""","assertionText":"$it"""" }.orEmpty() + "}"

    private fun Boundary.trace(version: Int = 1): DecisionTraceView? =
        tx { controller.getDecisionTrace(doc, version) }.also { it.statusCode.value() shouldBe 200 }.body

    @Test
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // the name is fixed by the task text
    fun `INV-AC-27156 a PAY-002 entry delivered by PayloadRejected is returned by getDecisionTrace with pack version, assertion text, input name and input value`(
        @TempDir dir: Path,
    ) = run(dir) {
        locate()
        deliver(decision(text))
        val view = trace()!!
        view.decisions.size shouldBe 1
        val entry = view.decisions.single()
        entry.code shouldBe "PAY-002"
        entry.ruleId shouldBe "BR-CO-15"
        entry.packVersions[0].packIdentifier shouldBe "schematron"
        entry.packVersions[0].version shouldBe "2026.1"
        entry.inputs[0].name shouldBe "IBT-110"
        entry.inputs[0].value shouldBe "7.00"
        entry.assertionText shouldBe text
    }

    @Test
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // the name is fixed by the task text
    fun `INV-AC-27156 an entry without assertionText is returned without the member`(
        @TempDir dir: Path,
    ) = run(dir) {
        locate()
        deliver(decision(null))
        val entry = trace()!!.decisions.single()
        entry.code shouldBe "PAY-002"
        entry.assertionText shouldBe null
    }

    @Test
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // the name is fixed by the task text
    fun `INV-AC-27156 a stored assertionText that breaks the contract makes getDecisionTrace answer the Malformed outcome of INV-T-20778 and no decisions member`(
        @TempDir dir: Path,
    ) = run(dir) {
        locate()
        deliver(decision("x".repeat(501)))
        shouldThrow<LookupRefusal> { tx { controller.getDecisionTrace(doc, 1) } }.code shouldBe
            ClosedErrorCode.INTERNAL_ERROR
    }

    @Test
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // the name is fixed by the task text
    fun `INV-AC-27156 getDecisionTrace for version 2 of the same document is not found while version 1 holds the entry`(
        @TempDir dir: Path,
    ) = run(dir) {
        locate()
        deliver(decision(text))
        trace(1)!!.decisions.single().assertionText shouldBe text
        shouldThrow<LookupRefusal> { tx { controller.getDecisionTrace(doc, 2) } }.code shouldBe
            ClosedErrorCode.NOT_FOUND
    }
}
