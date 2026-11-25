package com.dynokore.gst.gatewayauthz.check

import com.dynokore.gst.gatewayauthz.GatewayAuthzApplication
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import com.dynokore.gst.gatewayauthz.revocation.RevocationList
import com.dynokore.gst.gatewayauthz.token.TestTokens
import io.envoyproxy.envoy.service.auth.v3.AttributeContext
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc
import io.envoyproxy.envoy.service.auth.v3.CheckRequest
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.MultiThreadIoEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollIoHandler
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * The permissions of the extAuth socket and of its directory (INV-AC-28810), on the application as deployed. Only the
 * two injected-failure cases replace the [PermissionChange]; the others run with the production default.
 */
class ExtAuthzSocketPermissionsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `INV-AC-28810 absent socket directory is created 0770 and the socket is 0660`() {
        assertOpenedToTheGroupOnly(dir.resolve("shared"))
    }

    @Test
    fun `INV-AC-28810 a 0777 socket directory becomes 0770`() {
        assertOpenedToTheGroupOnly(directory("shared", "rwxrwxrwx"))
    }

    @Test
    fun `INV-AC-28810 a socket directory that is a file fails the start`() {
        val parent = Files.createFile(dir.resolve("shared"))
        val socket = parent.resolve(SOCKET)

        val error = startFailure(socket)

        error.shouldName(parent)
        Files.exists(socket).shouldBeFalse()
        Files.isRegularFile(parent).shouldBeTrue()
    }

    @Test
    fun `INV-AC-28810 a socket path two missing levels deep fails the start`() {
        val parent = dir.resolve("missing").resolve("shared")
        val socket = parent.resolve(SOCKET)

        val error = startFailure(socket)

        error.shouldName(parent)
        Files.exists(socket).shouldBeFalse()
        Files.exists(parent.parent).shouldBeFalse()
    }

    @Test
    fun `INV-AC-28810 a failing directory permission change fails the start`() {
        val parent = directory("shared", "rwxrwxrwx")
        val socket = parent.resolve(SOCKET)

        val error = startFailure(socket, failingFor(parent))

        error.shouldName(parent)
        Files.exists(socket).shouldBeFalse()
    }

    @Test
    fun `INV-AC-28810 a failing socket permission change fails the start`() {
        val socket = directory("shared", "rwxrwx---").resolve(SOCKET)

        val error = startFailure(socket, failingFor(socket))

        error.shouldName(socket)
    }

    private fun assertOpenedToTheGroupOnly(parent: Path) {
        val socket = parent.resolve(SOCKET)
        val tokens = TestTokens()
        val context = start(socket)
        val group = MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory())
        val channel =
            NettyChannelBuilder
                .forAddress(DomainSocketAddress(socket.toString()))
                .channelType(EpollDomainSocketChannel::class.java)
                .eventLoopGroup(group)
                .usePlaintext()
                .build()
        try {
            context.getBean(KeyMaterialCache::class.java).apply {
                keysRefreshed(listOf(tokens.verificationKey))
                revocationsRefreshed(RevocationList.EMPTY, fromSnapshot = true, readStartedAt = Instant.now())
            }
            readiness(context.environment.getProperty("local.management.port")) shouldBe 200
            permissions(parent) shouldBe "rwxrwx---"
            permissions(socket) shouldBe "rw-rw----"

            val bearer = tokens.mint(Instant.now(), lifetime = Duration.ofHours(1))
            AuthorizationGrpc
                .newBlockingStub(channel)
                .check(checkRequest(bearer))
                .status.code shouldBe 0
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            group.shutdownGracefully()
            context.close()
        }
    }

    private fun directory(
        name: String,
        mode: String,
    ): Path {
        val created = Files.createDirectory(dir.resolve(name))
        // Set after creation, so the umask does not narrow the mode of the precondition.
        Files.setPosixFilePermissions(created, PosixFilePermissions.fromString(mode))
        permissions(created) shouldBe mode
        return created
    }

    private fun permissions(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    /** Fails for [target] with an error that names no path, and delegates to the production operation otherwise. */
    private fun failingFor(target: Path) =
        PermissionChange { path, permissions ->
            if (path == target) throw IOException("injected failure")
            Files.setPosixFilePermissions(path, permissions)
        }

    private fun startFailure(
        socket: Path,
        replacement: PermissionChange? = null,
    ): Throwable = shouldThrow<Exception> { start(socket, replacement).close() }

    /** A message in the cause chain names [path] itself; a longer path under it (the socket) does not count. */
    private fun Throwable.shouldName(path: Path) {
        val messages = generateSequence(this) { it.cause }.map { it.message.orEmpty() }.toList()
        val named = Regex(Regex.escape(path.toAbsolutePath().toString()) + "(?![\\w./-])")
        check(messages.any(named::containsMatchIn)) { "No message names $path: $messages" }
    }

    private fun start(
        socket: Path,
        replacement: PermissionChange? = null,
    ): ConfigurableApplicationContext {
        val builder = SpringApplicationBuilder(GatewayAuthzApplication::class.java)
        if (replacement != null) {
            builder.initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> {
                    (it as GenericApplicationContext)
                        .registerBean(PermissionChange::class.java, Supplier { replacement })
                },
            )
        }
        return builder.run(
            "--server.port=0",
            "--management.server.port=0",
            "--spring.grpc.server.netty.domain-socket-path=$socket",
            "--gateway-authz.token.issuer=${TestTokens.ISSUER}",
            "--gateway-authz.token.audience=${TestTokens.AUDIENCE}",
        )
    }

    private fun readiness(port: String?): Int {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/health/ready")).build()
        return HttpClient.newHttpClient().send(request, BodyHandlers.discarding()).statusCode()
    }

    private fun checkRequest(bearer: String): CheckRequest {
        val http =
            AttributeContext.HttpRequest
                .newBuilder()
                .setPath("/v1/invoices")
                .setMethod("POST")
                .putHeaders("authorization", "Bearer $bearer")
        return CheckRequest
            .newBuilder()
            .setAttributes(
                AttributeContext
                    .newBuilder()
                    .setRequest(AttributeContext.Request.newBuilder().setHttp(http))
                    .putContextExtensions("route-class", "machine-api")
                    .putContextExtensions("required-scope", "ingest"),
            ).build()
    }

    private companion object {
        const val SOCKET = "ext-authz.sock"
    }
}
