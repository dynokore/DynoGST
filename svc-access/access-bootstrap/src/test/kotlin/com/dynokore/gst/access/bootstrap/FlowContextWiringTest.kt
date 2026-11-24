package com.dynokore.gst.access.bootstrap

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.access.adapters.http.IssueFlowContextOperation
import com.dynokore.gst.access.application.flowcontext.FLOW_CONTEXT_LIFETIME_SECONDS
import com.dynokore.gst.access.application.flowcontext.FlowContextClaims
import com.dynokore.gst.access.application.flowcontext.FlowContextSigner
import com.dynokore.gst.access.application.flowcontext.FlowEntityKind
import com.dynokore.gst.access.application.flowcontext.FlowPrincipalKind
import com.dynokore.gst.access.application.flowcontext.IssueFlowContextUseCase
import com.dynokore.gst.access.bootstrap.served.ContractDocument
import com.dynokore.gst.access.contract.model.IssueFlowContextRequest
import com.dynokore.gst.access.contract.model.IssueFlowContextRequestEntity
import com.dynokore.gst.access.contract.model.VerificationKeysResponseKeysInner
import com.dynokore.platform.serviceruntime.identity.FlowContext
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * INV-AC-24990 (INV-T-24991): the wiring of `issueFlowContext`: the closed enum mapping, the lifetime ceiling, the
 * caller set read from the generated callers file and the fail-closed start-up.
 */
class FlowContextWiringTest {
    @Test
    fun `INV-AC-24990 the generated enums and the application enums have the same member names`() {
        assertThat(names(IssueFlowContextRequest.PrincipalKind.entries.map { it.name }))
            .isEqualTo(names(FlowPrincipalKind.entries.map { it.name }))
        assertThat(names(IssueFlowContextRequestEntity.Kind.entries.map { it.name }))
            .isEqualTo(names(FlowEntityKind.entries.map { it.name }))
        // the wire value of each generated member is its name (the text of the payload)
        assertThat(
            IssueFlowContextRequest.PrincipalKind.entries.map { it.value to it.name }.filter {
                it.first !=
                    it.second
            },
        ).isEmpty()
        assertThat(
            IssueFlowContextRequestEntity.Kind.entries
                .map { it.value to it.name }
                .filter { it.first != it.second },
        ).isEmpty()
        // look-alike: the same names with a sixth member fail the comparison
        assertThat(names(FlowPrincipalKind.entries.map { it.name } + "SERVICE_ACCOUNT"))
            .isNotEqualTo(names(IssueFlowContextRequest.PrincipalKind.entries.map { it.name }))
        assertThat(names(FlowEntityKind.entries.map { it.name } + "GROUP"))
            .isNotEqualTo(names(IssueFlowContextRequestEntity.Kind.entries.map { it.name }))
    }

    /** The findings of one mapping function of the controller: an `else`, a `valueOf` or a wrong branch count. */
    private fun mappingFindings(
        source: String,
        function: String,
        members: Int,
    ): List<String> {
        val start = source.indexOf("private fun $function(")
        if (start < 0) return listOf("$function is absent")
        val end = source.indexOf("\n\n", start).takeIf { it > 0 } ?: source.length
        val body = source.substring(start, end)
        val code = body.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        return buildList {
            if (!Regex("""=\s*\n?\s*when\s*\(""").containsMatchIn(code)) add("$function is no when expression")
            if (Regex("""\belse\b""").containsMatchIn(code)) add("$function has an else branch")
            if (code.contains("valueOf") || code.contains("entries.") || code.contains("values()")) {
                add("$function converts by name")
            }
            val branches = Regex("""->""").findAll(code).count()
            if (branches != members) add("$function has $branches branches, not $members")
        }
    }

    private val controllerSource: String by lazy {
        Files.readString(
            Path.of("..", "access-adapters").resolve(
                "src/main/kotlin/com/dynokore/gst/access/adapters/http/AuthoriseController.kt",
            ),
        )
    }

    @Test
    fun `INV-AC-24990 the controller maps each enum by one exhaustive when with no else and no valueOf`() {
        val principals = IssueFlowContextRequest.PrincipalKind.entries.size
        val entities = IssueFlowContextRequestEntity.Kind.entries.size
        assertThat(mappingFindings(controllerSource, "principalKindOf", principals)).isEmpty()
        assertThat(mappingFindings(controllerSource, "entityKindOf", entities)).isEmpty()
    }

    @Test
    fun `INV-AC-24990 the mapping check finds an else, a valueOf, a removed member and a missing function`() {
        val principals = IssueFlowContextRequest.PrincipalKind.entries.size
        val last = "IssueFlowContextRequest.PrincipalKind.SIGNATORY_LINK"

        fun findings(source: String) = mappingFindings(source, "principalKindOf", principals)
        assertThat(findings(controllerSource.replace("$last ->", "else ->")))
            .contains("principalKindOf has an else branch")
        val first = "IssueFlowContextRequest.PrincipalKind.HUMAN"
        val byName =
            controllerSource.replace(
                "when (kind) {\n        $first",
                "valueOf(kind.name) /* when (kind) {\n        $first",
            )
        assertThat(findings(byName)).isNotEmpty()
        assertThat(findings(controllerSource.replace("        $last -> FlowPrincipalKind.SIGNATORY_LINK\n", "")))
            .isNotEmpty()
        assertThat(findings(controllerSource.replace("principalKindOf(", "principalKindOf2(")))
            .contains("principalKindOf is absent")
    }

    private fun names(members: List<String>): Set<String> = members.toSet()

    @Test
    fun `INV-AC-24990 the lifetime constant equals the platform flow-context ceiling`() {
        assertThat(FLOW_CONTEXT_LIFETIME_SECONDS).isEqualTo(FlowContext.MAX_LIFETIME_SECONDS).isEqualTo(900L)
    }

    @Test
    fun `INV-AC-24990 the injected caller set equals the x-sync-edge callers of the contract document`() =
        withFlowEnv { env ->
            val operation = ContractDocument.read().single { it.operationId == "issueFlowContext" }
            val expected = operation.edgeCallers.toSet()
            assertThat(expected).isNotEmpty()
            val useCase = env.context.getBean(IssueFlowContextUseCase::class.java)
            val field =
                IssueFlowContextUseCase::class.java.getDeclaredField("allowedCallers").also {
                    it.isAccessible =
                        true
                }
            assertThat(field.get(useCase) as Set<*>).isEqualTo(expected)
            expected.forEach { service ->
                assertThat(env.call(env.body(), service).status).describedAs(service).isEqualTo(200)
            }
        }

    private fun resourceLines(): List<String> =
        FlowContextIssuanceTest::class.java.classLoader
            .getResources(FlowEntryCallers.RESOURCE)
            .toList()
            .flatMap { url ->
                url
                    .openStream()
                    .use { String(it.readAllBytes(), Charsets.UTF_8) }
                    .lines()
                    .filter { it.isNotEmpty() }
            }

    private fun startsWith(lines: List<String>): Throwable? {
        val dir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "flow-callers")
        try {
            val file = dir.resolve(FlowEntryCallers.RESOURCE)
            Files.createDirectories(file.parent)
            Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
            val hidden = FilteredClassLoader(ClassPathResource(FlowEntryCallers.RESOURCE))
            val loader = URLClassLoader(arrayOf(dir.toUri().toURL()), hidden)
            BoundaryFixture.deleteVerificationKeys()
            var failure: Throwable? = null
            BoundaryFixture
                .runner(mounted = BoundaryFixture.mount(TestSecrets.newSigningKey()))
                .withClassLoader(loader)
                .run { failure = it.startupFailure }
            return failure
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun rootMessage(failure: Throwable): String =
        generateSequence(failure) {
            it.cause
        }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `INV-AC-24990 the start-up fails closed when the line is any, missing, of another operation or ambiguous`() {
        val real = resourceLines()
        val mine = real.single { it.startsWith("POST\t/internal/v1/access/flow-contexts\t") }
        val others = real - mine
        assertThat(startsWith(real)).describedAs("the real resource starts").isNull()
        val any = startsWith(others + "POST\t/internal/v1/access/flow-contexts\tany")
        assertThat(any).describedAs("a line any").isNotNull()
        assertThat(rootMessage(any!!)).contains("open")
        val except = startsWith(others + "POST\t/internal/v1/access/flow-contexts\tany-except:submission-svc")
        assertThat(except).describedAs("a line any-except").isNotNull()
        val none = startsWith(others)
        assertThat(none).describedAs("no line for the operation").isNotNull()
        assertThat(rootMessage(none!!)).contains("no line")
        val anotherOperation = startsWith(listOf(others.first { it.startsWith("POST\t") }))
        assertThat(anotherOperation).describedAs("only the line of another operation").isNotNull()
        val differing = startsWith(others + mine + "POST\t/internal/v1/access/flow-contexts\tingestion-svc")
        assertThat(differing).describedAs("differing lines").isNotNull()
        val empty = startsWith(others + "POST\t/internal/v1/access/flow-contexts\t")
        assertThat(empty).describedAs("an empty caller field").isNotNull()
        val upper = startsWith(others + "POST\t/internal/v1/access/flow-contexts\tIngestion-Svc")
        assertThat(upper).describedAs("an upper-case caller").isNotNull()
    }

    @Test
    fun `INV-AC-24990 the parser of the callers line is exact for the path and the method`() {
        val path = "/internal/v1/access/flow-contexts"
        assertThat(
            FlowEntryCallers.of(listOf("POST\t$path\ta-svc,b-svc"), path),
        ).containsExactlyInAnyOrder("a-svc", "b-svc")
        listOf(
            listOf("GET\t$path\ta-svc"),
            listOf("POST\t$path/\ta-svc"),
            listOf("POST\t${path}x\ta-svc"),
            listOf("post\t$path\ta-svc"),
            listOf("POST\t$path\ta-svc,a-svc"),
            listOf("POST\t$path\ta-svc,"),
            listOf("POST\t$path\t*"),
            listOf("POST\t$path\ta-svc\textra"),
        ).forEach { lines ->
            assertThat(runCatching { FlowEntryCallers.of(lines, path) }.exceptionOrNull())
                .describedAs(lines.toString())
                .isNotNull()
        }
    }
}
