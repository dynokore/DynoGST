package com.dynokore.gst.notification.adapters.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import jakarta.mail.NoSuchProviderException
import jakarta.mail.Session
import org.junit.jupiter.api.Test
import java.util.Properties

/** INV-REQ-817, dependency slice: Angus Mail provides the smtp transport; no socket is opened. */
class AngusMailClasspathTest {
    private val session: Session = Session.getInstance(Properties())

    @Test
    fun `INV-AC-27627 the smtp transport is the Angus Mail SMTPTransport`() {
        session.getTransport("smtp").javaClass.name shouldBe "org.eclipse.angus.mail.smtp.SMTPTransport"
    }

    @Test
    fun `INV-AC-27627 the transport smtpx is refused with NoSuchProviderException`() {
        shouldThrow<NoSuchProviderException> { session.getTransport("smtpx") }
    }
}
