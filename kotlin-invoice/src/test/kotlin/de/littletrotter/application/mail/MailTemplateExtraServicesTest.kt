package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailBccTestResult
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.outbound.MailTemplateBccTestPort
import de.littletrotter.application.mail.port.outbound.MailTemplateResetPort
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

@DisplayName("ResetMailTemplateService / SendMailTemplateBccTestService")
class MailTemplateExtraServicesTest {

    private val kind = MailTemplateKind.INVOICE_DISPATCH
    private val language = MailTemplateLanguage.DE
    private val template = MailTemplate(1, kind, language, 3, "s", "b", null, true, Instant.EPOCH)
    private val bccResult = MailBccTestResult("bcc@example.com", "<m>", Instant.EPOCH)

    private fun <T : Any> instance(bean: T?): Instance<T> {
        val instance = mock<Instance<T>>()
        whenever(instance.isResolvable).thenReturn(bean != null)
        if (bean != null) whenever(instance.get()).thenReturn(bean)
        return instance
    }

    @Test
    fun `reset is delegated to the component port`() {
        val port = mock<MailTemplateResetPort>()
        whenever(port.reset(kind, language)).thenReturn(template)

        assertSame(template, ResetMailTemplateService(instance(port)).reset(kind, language))
    }

    @Test
    fun `reset without the component fails readably`() {
        val e = assertThrows(MailDispatchException::class.java) {
            ResetMailTemplateService(instance<MailTemplateResetPort>(null)).reset(kind, language)
        }
        assertEquals("The email component is not part of this instance", e.message)
    }

    @Test
    fun `bcc test is delegated with the address to try`() {
        val port = mock<MailTemplateBccTestPort>()
        whenever(port.sendBccTest(kind, language, "bcc@example.com")).thenReturn(bccResult)

        assertSame(bccResult, SendMailTemplateBccTestService(instance(port)).sendBccTest(kind, language, "bcc@example.com"))
    }

    @Test
    fun `bcc test without the component fails readably`() {
        val e = assertThrows(MailDispatchException::class.java) {
            SendMailTemplateBccTestService(instance<MailTemplateBccTestPort>(null)).sendBccTest(kind, language, "x@example.com")
        }
        assertEquals("The email component is not part of this instance", e.message)
    }
}
