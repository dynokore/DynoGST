package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.renditionevidence.PageReply
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.asm.ClassReader
import org.springframework.asm.ClassVisitor
import org.springframework.asm.Handle
import org.springframework.asm.MethodVisitor
import org.springframework.asm.Opcodes
import org.springframework.mock.env.MockEnvironment
import org.springframework.util.LinkedMultiValueMap
import java.io.File
import java.net.URLDecoder
import java.util.jar.JarFile

/**
 * INV-AC-28736: every query value console-bff sends through a generated owner client reaches the owner
 * percent-encoded (RFC 3986), equal to the given value and never expanded as a URI template.
 */
class GeneratedQueryEncodingTest {
    private val stub = PageStub()

    @AfterEach
    fun stop() = stub.stop()

    /** Makes the call and gives back the one request the stub received. */
    private fun send(
        operation: String,
        cursor: String?,
    ): PageRequest {
        val call = CALLS.getValue(operation)
        stub.answer(call.status, call.body)
        CurrentEndUserToken.callWith("Bearer u-1") { call.make(stub.url, cursor) }
        assertEquals(1, stub.seen.size, "$operation $cursor: requests received")
        return stub.seen.single()
    }

    /** The raw query as sent: names and values still encoded. */
    private fun rawQuery(request: PageRequest): List<Pair<String, String>> =
        request.rawQuery.split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }

    @ParameterizedTest(name = "INV-AC-28736 {0} sends the cursor [{1}] encoded and unchanged")
    @MethodSource("hostileCalls")
    fun cursorReachesOwnerUnchanged(
        operation: String,
        cursor: String,
    ) {
        val request = send(operation, cursor)
        val query = rawQuery(request)
        assertEquals(listOf("cursor", "limit"), query.map { it.first }.sorted(), request.rawQuery)
        val values = query.toMap()
        // java.net.URLDecoder reads an unencoded '+' as a space
        assertEquals(cursor, URLDecoder.decode(values.getValue("cursor"), Charsets.UTF_8), request.rawQuery)
        assertEquals("25", URLDecoder.decode(values.getValue("limit"), Charsets.UTF_8), request.rawQuery)
        assertTrue(PERCENT_ENCODED.matches(values.getValue("cursor")), request.rawQuery)
        assertEquals(CALLS.getValue(operation).path, request.rawPath)
        assertEquals(send(operation, null).rawPath, request.rawPath)
    }

    @ParameterizedTest(name = "INV-AC-28736 {0} without a cursor sends no cursor parameter")
    @MethodSource("operations")
    fun noCursorNoParameter(operation: String) {
        val request = send(operation, null)
        assertEquals(listOf("limit" to "25"), rawQuery(request))
        assertEquals(CALLS.getValue(operation).path, request.rawPath)
    }

    @Test
    @DisplayName(
        "INV-AC-28736 every value of every query parameter is encoded, and none takes the place of a path variable",
    )
    fun everyValueOfEveryParameter() {
        stub.answer(200, null)
        val query =
            LinkedMultiValueMap<String, String>().apply {
                addAll("cursor", listOf("{entityRef}", "a+b"))
                addAll("entityRef", listOf("{cursor} é", "x&limit=500", ""))
            }
        GeneratedQueryValues
            .restClient(TestEdges.builder(), stub.url)
            .get()
            .uri("/probe/{cursor}/{entityRef}") { builder ->
                builder.queryParams(query).build(mapOf("cursor" to "p1", "entityRef" to "p2"))
            }.retrieve()
            .toBodilessEntity()
        val request = stub.seen.single()
        assertEquals("/probe/p1/p2", request.rawPath)
        assertEquals(
            query.flatMap { (name, values) -> values.map { name to it } },
            request.query,
            request.rawQuery,
        )
        assertTrue(rawQuery(request).all { PERCENT_ENCODED.matches(it.second) }, request.rawQuery)
    }

    @Test
    @DisplayName("INV-AC-28736 a path variable with a reserved query variable name is refused and nothing is sent")
    fun reservedVariableName() {
        stub.answer(200, null)
        val client = GeneratedQueryValues.restClient(TestEdges.builder(), stub.url)
        assertThrows(IllegalArgumentException::class.java) {
            client
                .get()
                .uri("/probe/{gstQueryValue0}") { builder ->
                    builder
                        .queryParams(LinkedMultiValueMap(mapOf("cursor" to listOf("c"))))
                        .build(mapOf("gstQueryValue0" to "p"))
                }.retrieve()
                .toBodilessEntity()
        }
        assertEquals(0, stub.seen.size)
    }

    @Test
    @DisplayName(
        "INV-AC-28736 the generated Api classes constructed in the main sources are exactly the listed ones",
    )
    fun generatedClientsAreListed() {
        val sources = File("src/main/kotlin").walk().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "the scan found no .kt file under src/main/kotlin")
        // a listed file name names one file only, so a pair cannot stand for a second file of the same name
        for (name in GENERATED_CLIENTS.keys.map { it.first }.toSet()) {
            assertEquals(1, sources.count { it.name == name }, "files named $name")
        }
        assertEquals(GENERATED_CLIENTS.keys, apiConstructions())
    }

    @Test
    @DisplayName("INV-AC-28736 each listed query-sending operation has a regression case")
    fun listedQueryOperationsAreTested() {
        assertEquals(CALLS.keys, GENERATED_CLIENTS.values.flatten().toSet())
    }

    /**
     * The pairs (source file name, class) of every constructor call of a class whose simple name ends in `Api`, read
     * from the compiled main classes: an import alias, a typealias, a qualified name, a constructor reference and the
     * super call of a subclass all compile to the class's `<init>`, and comments and string literals do not.
     */
    private fun apiConstructions(): Set<Pair<String, String>> {
        val location =
            File(
                CanonicalEdgeClient::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val classes = classBytes(location)
        assertTrue(classes.isNotEmpty(), "the scan found no compiled main class in $location")
        val found = mutableSetOf<Pair<String, String>>()
        for (bytes in classes) {
            ClassReader(bytes).accept(ConstructionScan(found), ClassReader.SKIP_FRAMES)
        }
        return found
    }

    /** Every class of the main output, which Gradle gives to the tests as a jar or as a directory. */
    private fun classBytes(location: File): List<ByteArray> =
        if (location.isDirectory) {
            location
                .walk()
                .filter { it.isFile && it.extension == "class" }
                .map { it.readBytes() }
                .toList()
        } else {
            JarFile(location).use { jar ->
                jar
                    .entries()
                    .asSequence()
                    .filter { it.name.endsWith(".class") }
                    .map { entry -> jar.getInputStream(entry).use { it.readBytes() } }
                    .toList()
            }
        }

    private class ConstructionScan(
        private val found: MutableSet<Pair<String, String>>,
    ) : ClassVisitor(Opcodes.ASM9) {
        private var className = ""
        private var source: String? = null

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            className = name
        }

        override fun visitSource(
            source: String?,
            debug: String?,
        ) {
            this.source = source
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor =
            object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) = record(owner, name)

                override fun visitInvokeDynamicInsn(
                    name: String,
                    descriptor: String,
                    bootstrapMethodHandle: Handle,
                    vararg bootstrapMethodArguments: Any,
                ) = bootstrapMethodArguments.filterIsInstance<Handle>().forEach { record(it.owner, it.name) }
            }

        private fun record(
            owner: String,
            method: String,
        ) {
            if (method == "<init>" && owner.endsWith("Api") && owner != className) {
                // a class with no SourceFile attribute is listed under its own name, so it fails the set
                found += (source ?: className) to owner.removePrefix(CLIENTS_PACKAGE).replace('/', '.')
            }
        }
    }

    private class QueryCall(
        val path: String,
        val status: Int,
        val body: String?,
        val make: (url: String, cursor: String?) -> Unit,
    )

    companion object {
        private const val CLIENTS_PACKAGE = "com/dynokore/gst/consoleBff/adapters/clients/"
        private const val EMPTY_PAGE = """{"items":[]}"""

        /** RFC 3986 section 2: only unreserved characters and percent-encoded octets. */
        private val PERCENT_ENCODED = Regex("(?:[A-Za-z0-9._~-]|%[0-9A-F]{2})*")

        private val CURSORS =
            listOf("a+b", "a b", "x&limit=500", "a=b#c", "%41", "é", "{entityRef}", "{unknown}")

        private fun environment(
            property: String,
            url: String,
        ) = MockEnvironment().withProperty(property, url)

        /** One regression case for each operation that sends a query, by operation id. */
        private val CALLS: Map<String, QueryCall> =
            mapOf(
                "listPreparedReassignments" to
                    // the stub answers 404, which the generated client throws on after the request was sent
                    QueryCall("/internal/v1/canonical/entities/E1/prepared-reassignments", 404, null) { url, cursor ->
                        val environment = environment(CanonicalEdgeClient.URL_PROPERTY, url)
                        runCatching {
                            CanonicalEdgeClient(TestEdges.builder(), environment)
                                .listPreparedReassignments("E1", 25, cursor)
                        }
                    },
                "listRenditionAttestations" to
                    QueryCall(
                        "/internal/v1/tenancy/entities/E1/rendition-attestations",
                        200,
                        EMPTY_PAGE,
                    ) { url, cursor ->
                        val reply =
                            AttestationReadClient(
                                TestEdges.builder(),
                                TestEdges.passThrough,
                                TestEdges.clock,
                                environment(OwningService.TENANCY.urlProperty, url),
                            ).page("E1", 25, cursor)
                        assertTrue(reply is PageReply.Ok, "$reply")
                    },
                "listRenditionSampleChecks" to
                    QueryCall(
                        "/internal/v1/evidence/entities/E1/rendition-sample-checks",
                        200,
                        EMPTY_PAGE,
                    ) { url, cursor ->
                        val reply =
                            SampleCheckReadClient(
                                TestEdges.builder(),
                                TestEdges.passThrough,
                                TestEdges.clock,
                                environment(OwningService.EVIDENCE.urlProperty, url),
                            ).page("E1", 25, cursor)
                        assertTrue(reply is PageReply.Ok, "$reply")
                    },
            )

        private val NO_QUERY = emptyList<String>()

        /**
         * Every generated `Api` class the main sources construct: (file name, class) to the query-sending operations
         * the file calls, each a key of [CALLS]; an empty list states that the file calls none. A task that adds a
         * generated client edits this list.
         */
        private val GENERATED_CLIENTS: Map<Pair<String, String>, List<String>> =
            mapOf(
                // reads only the path of openOperatorSession, which sends no query
                ("AccessOperatorSessionClient.kt" to "access.api.ElevationApi") to NO_QUERY,
                ("AttestationReadClient.kt" to "tenancy.api.PeriodsApi") to listOf("listRenditionAttestations"),
                ("CanonicalEdgeClient.kt" to "canonical.api.DocumentsApi") to NO_QUERY,
                ("CanonicalEdgeClient.kt" to "canonical.api.ReassignmentsApi") to listOf("listPreparedReassignments"),
                ("CounterpartyCorrectionClient.kt" to "masterdata.api.PrivacyApi") to NO_QUERY,
                ("EvidenceAnnotationClient.kt" to "evidence.api.PrivacyApi") to NO_QUERY,
                ("MasterdataBulkClient.kt" to "masterdata.api.RemediationApi") to NO_QUERY,
                ("PayloadOverrideClient.kt" to "payload.api.QueriesApi") to NO_QUERY,
                ("PayloadOverrideClient.kt" to "payload.api.ValidationApi") to NO_QUERY,
                ("PayloadSampleMetaClient.kt" to "payload.api.RenditionApi") to NO_QUERY,
                ("PayloadSampleRecordClient.kt" to "payload.api.RenditionApi") to NO_QUERY,
                ("PrivacyCaseClient.kt" to "evidence.api.PrivacyApi") to NO_QUERY,
                ("SampleCheckReadClient.kt" to "evidence.api.VerificationApi") to listOf("listRenditionSampleChecks"),
                ("SubmissionBoundaryClient.kt" to "submission.api.TransactionsApi") to NO_QUERY,
                ("SubmissionOverrideClient.kt" to "submission.api.ReadinessApi") to NO_QUERY,
            )

        @JvmStatic
        fun operations(): List<String> = CALLS.keys.toList()

        @JvmStatic
        fun hostileCalls(): List<Arguments> =
            operations().flatMap { operation -> CURSORS.map { Arguments.of(operation, it) } }
    }
}
