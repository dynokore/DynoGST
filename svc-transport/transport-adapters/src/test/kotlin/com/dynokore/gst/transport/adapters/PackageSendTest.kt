package com.dynokore.gst.transport.adapters

import com.dynokore.gst.transport.adapters.ap.AccessPointRegistry
import com.dynokore.gst.transport.adapters.ap.ApEndpoints
import com.dynokore.gst.transport.adapters.ap.HttpAccessPointAdapter
import com.dynokore.gst.transport.adapters.ap.IrasEnvironment
import com.dynokore.gst.transport.adapters.ap.RegistryPackageSender
import com.dynokore.gst.transport.application.AccessPointPort
import com.dynokore.gst.transport.application.PackageMember
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.FailureReason
import com.dynokore.gst.transport.domain.TransportOutcome
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The package sender over the registry and the HTTP adapter against a recording HTTP server (INV-AC-29528). */
class PackageSendTest {
    private val servers = mutableListOf<HttpServer>()
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val mapper = JsonMapper.builder().build()
    private val adapter = AdapterId("primary")

    @AfterEach
    fun stop() = servers.forEach { it.stop(0) }

    private fun httpAdapter(
        status: Int = 202,
        body: String = """{"apSubmissionId":"AP-1"}""",
    ): HttpAccessPointAdapter {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        servers += server
        val endpoints =
            ApEndpoints(IrasEnvironment.SANDBOX, URI.create("http://127.0.0.1:${server.address.port}"), null)
        return HttpAccessPointAdapter(adapter, endpoints, "primary", Duration.ofSeconds(5))
    }

    private fun member(
        text: String,
        version: Int = 1,
    ) = PackageMember(DocRef(UUID.randomUUID(), version), text.toByteArray(), text.toByteArray().size.toLong())

    private val first = member("<Invoice>one</Invoice>")
    private val second = member("<Invoice>two é</Invoice>", version = 2)

    private fun sender(vararg ports: AccessPointPort) = RegistryPackageSender(AccessPointRegistry(ports.toList()))

    private fun expectedPackageId(
        ref: String,
        members: List<PackageMember>,
    ): String {
        val joined = members.joinToString(",") { "${it.doc.docUuid}-v${it.doc.version}" }
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.toByteArray()))
        return "$ref-${hash.substring(0, 32)}"
    }

    @Test
    fun `INV-AC-29528 a package of type 380 is one request with INVOICE entries in order and the hashed packageId`() {
        val result = sender(httpAdapter()).send(adapter, "PKG-1", "380", listOf(first, second))

        result shouldBe TransportOutcome.Accepted("AP-1")
        requests.size shouldBe 1
        requests.single().first shouldBe "/ap/v1/primary/submissions"
        val body = mapper.readTree(requests.single().second)
        body.get("packageId").asString() shouldBe expectedPackageId("PKG-1", listOf(first, second))
        val documents = body.get("documents").toList()
        documents.map { it.get("docUuid").asString() } shouldBe listOf(first, second).map { it.doc.docUuid.toString() }
        documents.map { it.get("version").asInt() } shouldBe listOf(1, 2)
        documents.map { it.get("documentType").asString() } shouldBe listOf("INVOICE", "INVOICE")
        documents.map { it.get("sizeBytes").asInt() } shouldBe listOf(first, second).map { it.payload.size }
        documents.map { it.get("payload").asString() } shouldBe
            listOf("<Invoice>one</Invoice>", "<Invoice>two é</Invoice>")
    }

    @Test
    fun `INV-AC-29528 a package of type 381 carries CREDIT_NOTE entries`() {
        sender(httpAdapter()).send(adapter, "PKG-2", "381", listOf(first, second))

        val documents = mapper.readTree(requests.single().second).get("documents").toList()
        documents.map { it.get("documentType").asString() } shouldBe listOf("CREDIT_NOTE", "CREDIT_NOTE")
    }

    @Test
    fun `INV-AC-29528 the packageId changes with the members and is not the packageRef alone`() {
        val port = httpAdapter()
        sender(port).send(adapter, "PKG-3", "380", listOf(first))
        sender(port).send(adapter, "PKG-3", "380", listOf(first, second))

        val ids = requests.map { mapper.readTree(it.second).get("packageId").asString() }
        ids[0] shouldBe expectedPackageId("PKG-3", listOf(first))
        ids[1] shouldBe expectedPackageId("PKG-3", listOf(first, second))
        (ids[0] == ids[1]) shouldBe false
        (ids[0] == "PKG-3") shouldBe false
    }

    @Test
    fun `INV-AC-29528 type 382 is refused with no request received`() {
        shouldThrow<IllegalArgumentException> { sender(httpAdapter()).send(adapter, "PKG-4", "382", listOf(first)) }
        requests.size shouldBe 0
    }

    @Test
    fun `INV-AC-29528 a 202 with a receipt is accepted, a 422 rejected with SUB-003 and a 500 transient`() {
        sender(httpAdapter(202)).send(adapter, "P", "380", listOf(first)) shouldBe TransportOutcome.Accepted("AP-1")
        val rejected = sender(httpAdapter(422, "{}")).send(adapter, "P", "380", listOf(first))
        (rejected as TransportOutcome.Rejected).reasons.map { it.code } shouldBe listOf(FailureReason.PACKAGE_REFUSED)
        FailureReason.PACKAGE_REFUSED shouldBe "SUB-003"
        sender(httpAdapter(500, "{}")).send(adapter, "P", "380", listOf(first)) shouldBe
            TransportOutcome.TransientFailure
    }

    @Test
    fun `INV-AC-29528 canSend is true with the capability, false without it and false for an unknown id`() {
        val plain =
            object : AccessPointPort {
                override val adapterId = AdapterId("plain")

                override fun submit(
                    doc: DocRef,
                    payload: String,
                ) = TransportOutcome.TransientFailure
            }
        val registry = sender(httpAdapter(), plain)

        registry.canSend(adapter) shouldBe true
        registry.canSend(AdapterId("plain")) shouldBe false
        registry.canSend(AdapterId("unknown")) shouldBe false
    }
}
