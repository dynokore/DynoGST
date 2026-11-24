package com.dynokore.gst.notification.adapters.delivery

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * A test double of an SMTP server on a local socket (JDK sockets only). It speaks the dialogue with STARTTLS and
 * AUTH PLAIN; its certificate is a self-signed test certificate for `localhost` that only [clientSocketFactory]
 * trusts. Behaviour is configured per instance.
 */
class FakeSmtpServer(
    private val offerStartTls: Boolean = true,
    private val greeting: String = "220 localhost ESMTP",
    private val authReply: String = "235 2.7.0 accepted",
    private val rcptReply: String = "250 2.1.5 ok",
    private val user: String = "relay-user",
    private val password: String = "relay-password",
) : Closeable {
    /** One accepted message: its envelope recipients and the DATA text. */
    class Received(
        val rcpt: List<String>,
        val data: String,
    ) {
        fun header(name: String): String? =
            data
                .substringBefore("\r\n\r\n")
                .lines()
                .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
    }

    val messages = CopyOnWriteArrayList<Received>()
    private val mailCommands = AtomicInteger()
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    val mailCommandCount: Int get() = mailCommands.get()

    private val keys: KeyStore =
        KeyStore.getInstance("PKCS12").also {
            it.load(ByteArrayInputStream(Base64.getDecoder().decode(KEYSTORE)), STORE_PASSWORD)
        }
    private val serverFactory: SSLSocketFactory =
        SSLContext
            .getInstance("TLS")
            .also { ctx ->
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keys, STORE_PASSWORD)
                ctx.init(kmf.keyManagers, null, null)
            }.socketFactory

    /** The client side factory that trusts this double's certificate and nothing else. */
    val clientSocketFactory: SSLSocketFactory =
        SSLContext
            .getInstance("TLS")
            .also { ctx ->
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(keys)
                ctx.init(null, tmf.trustManagers, null)
            }.socketFactory

    private val acceptor =
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread { runCatching { serve(socket) } }.apply { isDaemon = true }.start()
            }
        }.apply {
            isDaemon = true
            start()
        }

    private class Channel(
        val socket: Socket,
    ) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val writer: Writer = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)

        fun say(line: String) {
            writer.write(line + "\r\n")
            writer.flush()
        }
    }

    private fun serve(plain: Socket) {
        val session = Dialogue(plain)
        session.ch.say(greeting)
        var open = greeting.startsWith("220")
        while (open) {
            val line = session.ch.reader.readLine()
            open = line != null && session.handle(line)
        }
    }

    /** One client connection; every verb handler answers true to keep the connection open. */
    private inner class Dialogue(
        private val plain: Socket,
    ) {
        var ch = Channel(plain)
        private var tls = false
        private val rcpts = mutableListOf<String>()
        private val verbs: Map<String, (String) -> Boolean> =
            mapOf(
                "EHLO" to { hello() },
                "HELO" to { hello() },
                "STARTTLS" to { startTls() },
                "AUTH" to ::auth,
                "MAIL" to { mail() },
                "RCPT" to ::rcpt,
                "DATA" to { data() },
                "RSET" to { ok() },
                "NOOP" to { ok() },
                "QUIT" to { ch.say("221 bye").let { false } },
            )

        fun handle(line: String): Boolean = verbs[line.substringBefore(' ').uppercase()]?.invoke(line) ?: unknown()

        private fun unknown(): Boolean = ch.say("502 not implemented").let { true }

        private fun ok(): Boolean = ch.say("250 ok").let { true }

        private fun hello(): Boolean {
            val caps = mutableListOf("localhost")
            if (offerStartTls && !tls) caps += "STARTTLS"
            if (tls) caps += "AUTH PLAIN"
            caps.forEachIndexed { i, c -> ch.say((if (i < caps.lastIndex) "250-" else "250 ") + c) }
            return true
        }

        private fun startTls(): Boolean {
            ch.say("220 2.0.0 ready")
            val ssl = serverFactory.createSocket(plain, null, plain.port, true) as SSLSocket
            ssl.useClientMode = false
            ssl.startHandshake()
            ch = Channel(ssl)
            tls = true
            return true
        }

        private fun auth(line: String): Boolean {
            var initial = line.substringAfter("PLAIN", "").trim()
            if (initial.isEmpty()) {
                ch.say("334 ")
                initial = ch.reader.readLine() ?: return false
            }
            val parts = String(Base64.getDecoder().decode(initial), Charsets.UTF_8).split('\u0000')
            val accepted = parts.size == 3 && parts[1] == user && parts[2] == password
            ch.say(if (accepted) authReply else "535 5.7.8 authentication failed")
            return true
        }

        private fun mail(): Boolean {
            mailCommands.incrementAndGet()
            rcpts.clear()
            ch.say("250 2.1.0 ok")
            return true
        }

        private fun rcpt(line: String): Boolean {
            if (rcptReply.startsWith("250")) rcpts += line.substringAfter('<').substringBefore('>')
            ch.say(rcptReply)
            return !rcptReply.startsWith("421")
        }

        private fun data(): Boolean {
            ch.say("354 go ahead")
            val text = StringBuilder()
            while (true) {
                val l = ch.reader.readLine() ?: return false
                if (l == ".") break
                text.append(if (l.startsWith("..")) l.substring(1) else l).append("\r\n")
            }
            messages += Received(rcpts.toList(), text.toString())
            ch.say("250 2.0.0 queued")
            return true
        }
    }

    override fun close() {
        server.close()
        acceptor.interrupt()
    }

    companion object {
        private val STORE_PASSWORD = "changeit".toCharArray()
        private const val KEYSTORE =
            "MIIKCgIBAzCCCbQGCSqGSIb3DQEHAaCCCaUEggmhMIIJnTCCBaQGCSqGSIb3DQEHAaCCBZUEggWRMIIFjTCCBYkGCyqGSIb3DQEM" +
                "CgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFMR0Wn5Ue7jsbQ/58ukgYyk9ZHeoAgInEAIBIDAMBggq" +
                "hkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQWB0w7D2vz2rmMNMPz2103gSCBNBqInUvw8aTNjR84qmxxqAfMWdpjAt6mUIHVOUyeJfO" +
                "uxR36r4RFWlNsLMCfXLOHpDVX+zKqwNsvufT5IfI5MdHAYRjiqcWzCA5Oh47QXfiTlvKrIRPuHwHXra0L2WSJp+LbItc3bmf5dph" +
                "gpy6VoR/2SAV8ByS1UX6iLRKWJKLXaB0yMuHT53iQLEigiKgdSEWtEp+XyAleGcp39Dh6TPztFzjBQqmjM+D7+T0jhHgH7WDh3/+" +
                "cg2NTVcy72+ncnH0b7unWfYsGpisGvHLS+J7XrfR3GJ+TnjhL/o7fT9kOeIaqF8fk+F+Fb6MMYPwLkfBUISswTz1upsuWN+1kdch" +
                "yuH3oNS5E7P4LLOcVOwbAELRNZ4X/uBymXL6vVZO38BiNIo2DqN9z0veqWJhR7Z6TvO3dozS+J8KfwduIynNDbKeSEQg4G+oHFVu" +
                "LSZg5QqKLpFcAkZzKz3T+MHEVPmRtfXAQyPhFe9a45RUEyc7RA7a6jY53alOFuAKczog0mDblJG7peXVLarrnoCw3s82hXrj5Q4I" +
                "0Z4S0mKAyUDieFlukPdqzDHOEQ0iJB6RrJm6SRmMVWR0Xh856MAQA4I9e7jI2wPayJmgHSWKy9xudM7Ky51B/bPu5lRUJ85/lSEj" +
                "+LF29/vex4WQMtYFyhREfPpFKrAO/hh0aHPrdb4h/RRBcpI+gjhSaBsjNKcJYAEQRoveOe9TkL1UOFA8HzmlQc5XSFsonB27exOF" +
                "qb5eQtsfL61AMiktJW5Ws1Qj/9iige/vBv+Q3G96tf6/9JWiue08KaNFapLiLZ9yEWGaHI7r3uWcWjdaY35PcQnWvofTfgKqWtDg" +
                "xyzrBpGCg0vfGpYsunFLArLNWCkMi0ow096maUoOf18wKCzQsV5freQXBZgPefsJZwoI8Z4eHvtR3l5uF+AqPCzCKvbqmonyAGcT" +
                "srOvHiVgMdoJzCDSe+R63w+wILZbXZTLHIIhrF8cuEXm7m3WbQ5mK4deCRjiFslv/yLntTe3gp5557SLvb0HnyLZfaRUKz/NG2bA" +
                "2erGpG9wDx1c72G2Q6U9P2Cq8xDqhp3kfI0CpObq8uSiC8/ODAmOwcgEreWaFXg3PHYePXrbkOqzVh61McaxoxZIwhjrl9lxeERL" +
                "f0b7J/i6tO3OPLceDb+D+ClnS2eu0t4DFYR4/pL3w13LGtPL1lAYECRKaz0XFzImNwgvN8YZGIno5FRrQDK+UukfEfKWT9wxETIJ" +
                "pVb30D5x4wj3XJVphkO9eMo5/lwGc446dH0wordy3ecWmTq7cUoSvPAwxbG8HkDuziOQfKpqV/Tet/HkwMQpJQeFYZz5ScTNMz9S" +
                "IBygLU2Q69I8sNg75WLxfWAHvqGEM6370XZDLxufpKnZYBh08PuUiiCXsVJPuPqtLPi5GhxRzLsmDoUC4F5doCChaeRFphts7+N0" +
                "F+oFTcVYDKrjZceU3HWK7If96h9I8tzyKJ+Xvd8f7PDCYvZ0ELQHhPoI0XhiA7eQWcmsk0EPMWcurLcwbTdqiDNxCGiZuPDYuwb+" +
                "JEJJXst5YUiJceUGm76oSqQmeZUDUu7l3SIPxchkc9oMVE2Qbzoq4CiYoeozoO/YoAIa/ElneTuvMlxFEnzOWIZgvV+nZOfZA1JU" +
                "oTE2MBEGCSqGSIb3DQEJFDEEHgIAdDAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzk1NDUwNjUyNzkzMIID8QYJKoZIhvcNAQcGoIID" +
                "4jCCA94CAQAwggPXBgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFNnFswsf+b6B4iUm8fFJYD9dlO6Z" +
                "AgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQJW40tcRAMZzbx7C5OLerbYCCA2DXCcA0YAMpjvv+tAdP5HJKIwMV" +
                "H8u7fa1EzPjGKjoWwANBtBq/ftW5KUd4bte+U1gC7o2wliqCzREFW9dk+rJyjiCWeic5USEnVIIO0wFr0sfVnbqep2YgG0DGvqH2" +
                "EXyJ2JIYIccVXZ/CJ0hKWUlB4+vw7hZbycyS1aVnBRx6Qqw4CHCe5+iAqjYd7ZcFZ90JLdXQ++pptop9syZm5LdOrMmeH4HYhiCn" +
                "rm+eWUs/yTREjGmjnH/bs+CBDpHrAwcHtLDeGxFMD+maIMP1ziXB3UdISpFbtR4H0EadQj/ehHFLRZK8Gt233dEVOqS1YIl7hZex" +
                "iJdsU6l5tt0aWAmKnJZGaXqerv5b1vKCyZo6Ot5REympTff5RL4oDr2Gylqvl3xtjecHAVNMg7t5fR7IVXqfX+4Hu3FON6ZPlYat" +
                "JnYx4xOACGMP6LiBCIcDpz3LyeVPLEVTB7cQMwP5PtfGzKkKJs9wobEgLlo95ZzMy3hy6oK2nZ8RFhobeBaeN/lUiODoM6jGDu0l" +
                "tra06m7OKWDnznw9QQ0AD/jFVBvUcThvAzkW2kJULDir7r59D4WDVS9ocXOdchEGCz7iBVrZtmthAWn6S31Hx+ZBtyZoooZCc9AP" +
                "ot+bnWUAB8t9keBYJ8tHVZ+mlY+Ul4oLzOm4EaYRW2IjQr31T/1JHv1QtJvEl76JMv4t74K62wtweCsAjClM2C+PrmNSVSj1Lc+u" +
                "lh8DHoSAUs1REFXKvOu6ycNrzZqDZ2PLE8aXGMg630++ApDH/QTswMJJqpYoBaNsIDvvYN/cJQ+9hjngCQmHxbwwtj7O7cWlwvo1" +
                "imjCkjGzAuN/F6bJxt8uZBCmnoPN7eQnbK9tIKmCMW/F8x3iUqdpV5VBOljRTn8IiNTijOUpmBQxvTXtXFlzrQkusOrEO95IoFus" +
                "BED2eunPQGe2jJzAXZoaesgRDRAj+RMAxfwKo2YYoKP4ys/DTDm8O1CyZw1VTUnrIerkKszV5Ut6uN8eJ6X2yc6AKSQQ9YaeKfDd" +
                "ROYhnjtlK1KuGB/Py+Hed8aVWbqanb3mvTpG5SHbaHn5UukW/3MoXd+ntiPE5oKLLtD6dmgf7sBmLMCyagKBUhusrVoCtOuD6HbF" +
                "t9ogDFUSPtj8Ps8nvxb/qy8KNmcwTTAxMA0GCWCGSAFlAwQCAQUABCAzdVBSVIkkt2jIHf7Eglk8Dk/g6pfZqBAFv/PKgUp2hgQU" +
                "rKV+7Bh1eTQjq6A/ucEi5NP4tTMCAicQ"

        /** A port nothing listens on: bound once, then closed. */
        fun closedPort(): Int = ServerSocket(0).use { it.localPort }
    }
}
