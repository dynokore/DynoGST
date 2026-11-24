package com.dynokore.gst.notification.adapters.delivery

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Test double of an HTTPS API (INV-REQ-817): the JDK HttpsServer on a loopback port with a self-signed certificate made
 * at run time by the JDK keytool in a temporary directory. [answer] sets the status of the next calls; [redirectTo]
 * answers 302 to that path; [delayMs] holds the answer back. [clientContext] trusts only this server's certificate.
 * [untrustedClientContext] trusts a different certificate, so the server's certificate fails verification.
 */
class FakeHttpsApiServer : AutoCloseable {
    /** One request the double received. */
    data class Received(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    @Volatile var answer: Int = 202

    @Volatile var redirectTo: String? = null

    @Volatile var delayMs: Long = 0

    val requests: MutableList<Received> = CopyOnWriteArrayList()

    private val dir = Files.createTempDirectory("fake-https-api").toFile()
    private val server: HttpsServer

    val clientContext: SSLContext
    val untrustedClientContext: SSLContext
    val port: Int

    init {
        val own = certificate("server")
        val other = certificate("other")
        val serverContext = SSLContext.getInstance("TLS")
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also { it.init(own, PASSWORD) }
        serverContext.init(kmf.keyManagers, null, null)
        clientContext = trusting(own)
        untrustedClientContext = trusting(other)
        server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(serverContext)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            requests += Received(ex.requestMethod, ex.requestURI.path, headers, body)
            if (delayMs > 0) Thread.sleep(delayMs)
            val target = redirectTo
            if (target != null) ex.responseHeaders.add("Location", target)
            val code = if (target != null) REDIRECT else answer
            runCatching { ex.sendResponseHeaders(code, -1) }
            ex.close()
        }
        server.start()
        port = server.address.port
    }

    private fun certificate(alias: String): KeyStore {
        val file = File(dir, "$alias.p12")
        val keytool = File(File(System.getProperty("java.home"), "bin"), "keytool").path
        val process =
            ProcessBuilder(
                keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", file.path, "-storetype", "PKCS12",
                "-storepass", String(PASSWORD), "-keypass", String(PASSWORD),
            ).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }
        return KeyStore.getInstance("PKCS12").also { ks -> file.inputStream().use { ks.load(it, PASSWORD) } }
    }

    private fun trusting(keys: KeyStore): SSLContext {
        val anchors = KeyStore.getInstance("PKCS12").also { it.load(null, null) }
        keys.aliases().toList().forEach { anchors.setCertificateEntry(it, keys.getCertificate(it)) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(anchors) }
        return SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, null) }
    }

    fun url(path: String = "/v1/send"): String = "https://localhost:$port$path"

    override fun close() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private companion object {
        val PASSWORD = "changeit".toCharArray()
        const val REDIRECT = 302
        const val KEYTOOL_SECONDS = 60L
    }
}
