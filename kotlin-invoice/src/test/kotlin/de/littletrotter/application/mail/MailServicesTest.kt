package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateDraft
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTestToMeResult
import de.littletrotter.application.mail.port.outbound.MailDeliveryPort
import de.littletrotter.application.mail.port.outbound.MailTemplateWritePort
import de.littletrotter.domain.shared.InvoiceFormat
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@DisplayName("MailTemplateService / MailSendService")
class MailServicesTest {

    private val template = MailTemplate(1, MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 2, "s", "b", null, false, Instant.EPOCH)
    private val draft = MailTemplateDraft(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, "s", "b")
    private val command = MailSendCommand(
        MailTemplateKind.INVOICE_DISPATCH, InvoiceFormat.GERMAN, "k@example.com", "RE-1", "s", "b", emptyMap(), false, emptyList(),
    )

    private inline fun <reified T : Any> instance(port: T?): Instance<T> =
        mock<Instance<T>>().also {
            whenever(it.isResolvable).thenReturn(port != null)
            if (port != null) whenever(it.get()).thenReturn(port)
        }

    @Test
    fun `template changes are delegated to the component port`() {
        val port = mock<MailTemplateWritePort>()
        val testResult = MailTestToMeResult("a@example.com", "<m>", Instant.EPOCH)
        whenever(port.save(draft)).thenReturn(template)
        whenever(port.restoreVersion(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1)).thenReturn(template)
        whenever(port.sendTestToMe(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)).thenReturn(testResult)
        val service = MailTemplateService(instance(port))

        assertSame(template, service.save(draft))
        assertSame(template, service.restoreVersion(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1))
        assertSame(testResult, service.sendTestToMe(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE))
    }

    @Test
    fun `without the component every template change fails readably and touches nothing`() {
        val service = MailTemplateService(instance<MailTemplateWritePort>(null))

        listOf<() -> Any>(
            { service.save(draft) },
            { service.restoreVersion(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1) },
            { service.sendTestToMe(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE) },
        ).forEach { call ->
            val e = assertThrows(MailDispatchException::class.java) { call() }
            assertEquals("The email component is not part of this instance", e.message)
        }
    }

    @Test
    fun `send is delegated to the component port`() {
        val port = mock<MailDeliveryPort>()
        val outcome = MailSendOutcome(3, MailJournalStatus.QUEUED)
        whenever(port.send(command)).thenReturn(outcome)

        assertSame(outcome, MailSendService(instance(port)).send(command))
        verify(port).send(command)
    }

    @Test
    fun `without the component send fails readably`() {
        val e = assertThrows(MailDispatchException::class.java) { MailSendService(instance<MailDeliveryPort>(null)).send(command) }
        assertEquals("The email component is not part of this instance", e.message)
    }
}
