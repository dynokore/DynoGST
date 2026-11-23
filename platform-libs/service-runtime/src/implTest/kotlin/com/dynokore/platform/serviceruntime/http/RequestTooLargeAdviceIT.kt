package com.dynokore.platform.serviceruntime.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/** A bean with a no-argument constructor: the module has no Jackson Kotlin module. */
class TooLargeBody {
    @field:Size(min = 1, max = 3)
    var items: List<String> = emptyList()

    @field:NotBlank
    var name: String = ""

    @field:Size(max = 3)
    var code: String = ""
}

/** The controller of INV-AC-27818. */
@RestController
class TooLargeProbeController {
    @PostMapping("/probe/listed")
    fun listed(
        @Valid @RequestBody body: TooLargeBody,
    ) = "listed:${body.items.size}"

    @PostMapping("/probe/listed-too")
    fun listedToo(
        @Valid @RequestBody body: TooLargeBody,
    ) = "listed-too:${body.items.size}"

    @PostMapping("/probe/unlisted")
    fun unlisted(
        @Valid @RequestBody body: TooLargeBody,
    ) = "unlisted:${body.items.size}"

    @PostMapping("/probe/method")
    fun method(
        @RequestBody @Size(max = 3) items: List<String>,
    ) = "method:${items.size}"
}

@Configuration
@ImportAutoConfiguration(
    TomcatServletWebServerAutoConfiguration::class,
    DispatcherServletAutoConfiguration::class,
    WebMvcAutoConfiguration::class,
    HttpMessageConvertersAutoConfiguration::class,
    JacksonAutoConfiguration::class,
    BindingFailureAutoConfiguration::class,
)
class TooLargeProbeApplication

/** A service's own advice for the validation exception. */
@RestControllerAdvice
class ServiceOwnValidationAdvice {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(): ResponseEntity<String> = ResponseEntity.status(422).body("service-own")
}

/** INV-T-27821 (INV-AC-27818): real HTTP requests to a servlet application with the service-runtime advice. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestTooLargeAdviceIT {
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val running = mutableListOf<ConfigurableApplicationContext>()
    private val dirs = mutableListOf<File>()

    private val list =
        "operationId\tmethod\tpath\nlistedOp\tPOST\t/probe/listed\nmethodOp\tPOST\t/probe/method\n"

    /** Starts the application; [resource] is the content of the list resource on the classpath, null for none. */
    private fun start(
        resource: String?,
        vararg sources: Class<*>,
    ): Int {
        val dir = Files.createTempDirectory("rtl").toFile().also(dirs::add)
        if (resource != null) {
            File(dir, "META-INF/gst").mkdirs()
            File(dir, "META-INF/gst/request-too-large-operations.tsv").writeText(resource)
        }
        val loader = URLClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
        return SpringApplicationBuilder(*sources)
            .web(WebApplicationType.SERVLET)
            .resourceLoader(DefaultResourceLoader(loader))
            .run("--server.port=0", "--spring.main.banner-mode=off")
            .also(running::add)
            .let { checkNotNull((it as WebServerApplicationContext).webServer).port }
    }

    private val app = arrayOf(TooLargeProbeApplication::class.java, TooLargeProbeController::class.java)
    private val listedPort by lazy { start(list, *app) }
    private val noListPort by lazy { start(null, *app) }
    private val ownPort by lazy { start(list, *app, ServiceOwnValidationAdvice::class.java) }

    @AfterAll
    fun stop() {
        running.forEach(ConfigurableApplicationContext::close)
        dirs.forEach { it.deleteRecursively() }
    }

    private fun post(
        port: Int,
        path: String,
        body: String,
        method: String = "POST",
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun json(
        items: Int,
        name: String = "giraffe",
        code: String = "abc",
    ) = """{"items":[${(1..items).joinToString(",") { "\"zebra$it\"" }}],"name":"$name","code":"$code"}"""

    private fun refused(
        r: HttpResponse<String>,
        status: Int,
        code: String,
    ) {
        r.statusCode() shouldBe status
        r.headers().firstValue("Content-Type").get() shouldBe Problems.CONTENT_TYPE
        val n = mapper.readTree(r.body())
        n.get("code").asString() shouldBe code
        n.get("type").asString() shouldBe "https://errors.invoice.internal/$code"
        n.get("correlationId").asString() shouldNotBe ""
        r.headers().firstValue("Retry-After").isPresent shouldBe false
        listOf("zebra", "giraffe", "abcd").forEach { r.body() shouldNotContain it }
    }

    @Test
    fun `INV-AC-27818 (a) listed with 4 items is 413 SRT-014 without Retry-After`() {
        refused(post(listedPort, "/probe/listed", json(4)), 413, "SRT-014")
    }

    @Test
    fun `INV-AC-27818 (b) listed with 3 items is the controller's own answer`() {
        val r = post(listedPort, "/probe/listed", json(3))
        r.statusCode() shouldBe 200
        r.body() shouldBe "listed:3"
    }

    @Test
    fun `INV-AC-27818 (c) listed with 0 items is 400 SRT-001`() {
        refused(post(listedPort, "/probe/listed", json(0)), 400, "SRT-001")
    }

    @Test
    fun `INV-AC-27818 (d) listed with 4 items and a blank name is 400 SRT-001`() {
        refused(post(listedPort, "/probe/listed", json(4, name = " ")), 400, "SRT-001")
    }

    @Test
    fun `INV-AC-27818 (e) unlisted with 4 items is 400 SRT-001`() {
        refused(post(listedPort, "/probe/unlisted", json(4)), 400, "SRT-001")
    }

    @Test
    fun `INV-AC-27818 (f) a listed path with another method is not 413`() {
        val r = post(listedPort, "/probe/listed", json(4), method = "PUT")
        (r.statusCode() != 413) shouldBe true
        r.body() shouldNotContain "SRT-014"
    }

    @Test
    fun `INV-AC-27818 (g) a look-alike path is 400 SRT-001`() {
        refused(post(listedPort, "/probe/listed-too", json(4)), 400, "SRT-001")
    }

    @Test
    fun `INV-AC-27818 (h) listed with a 4 character Size max string is 400 SRT-001`() {
        refused(post(listedPort, "/probe/listed", json(2, code = "abcd")), 400, "SRT-001")
    }

    @Test
    fun `INV-AC-27818 (i) without the list resource the case (a) request is 400 SRT-001`() {
        refused(post(noListPort, "/probe/listed", json(4)), 400, "SRT-001")
    }

    @Test
    fun `INV-AC-27818 (j) a malformed list line stops the application context`() {
        val failure =
            shouldThrow<Exception> {
                start("operationId\tmethod\tpath\nlistedOp\tPOST\n", *app)
            }
        generateSequence<Throwable>(failure) { it.cause }
            .map { it.message.orEmpty() }
            .any { "a line needs exactly three non-empty TAB-separated cells" in it } shouldBe true
    }

    @Test
    fun `INV-AC-27818 (k) a service's own advice for MethodArgumentNotValidException wins`() {
        val r = post(ownPort, "/probe/listed", json(4))
        r.statusCode() shouldBe 422
        r.body() shouldBe "service-own"
    }

    @Test
    fun `INV-AC-27818 (l) a method-level Size max on the body list of a listed operation is 413`() {
        refused(post(listedPort, "/probe/method", """["zebra1","zebra2","zebra3","zebra4"]"""), 413, "SRT-014")
    }
}

/** INV-T-27821 (INV-AC-27818): the advice registration is guarded by the presence of bean validation. */
class RequestTooLargeAutoConfigurationTest {
    private val runner =
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BindingFailureAutoConfiguration::class.java))

    @Test
    fun `INV-AC-27818 (m) without bean validation the context starts with no RequestTooLargeAdvice`() {
        runner.withClassLoader(FilteredClassLoader(jakarta.validation.ConstraintViolation::class.java)).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(RequestTooLargeAdvice::class.java).size shouldBe 0
            context.getBeansOfType(BindingFailureAdvice::class.java).size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-27818 (m) with bean validation the context holds exactly one RequestTooLargeAdvice`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(RequestTooLargeAdvice::class.java).size shouldBe 1
        }
    }
}
