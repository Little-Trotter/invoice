package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.mail.MailComposeResult
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.InvoiceMailReadinessQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.toastText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The send button waits for the document of record; a pending compose is a hint, not an error. */
class InvoiceMailDocumentPendingKaribuTest {

    private val dispatch: InvoiceMailDispatchService = mock()
    private val readiness: InvoiceMailReadinessQueryPort = mock()
    private val host = Div()
    private val inReview = DetailViewTestHarness.dto(InvoiceStatus.IN_REVIEW)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        clearNotifications()
        whenever(dispatch.isAvailable()).thenReturn(true)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun hero() = testCockpitHero(mailDispatch = dispatch, mailReadiness = readiness)

    private fun sendButton(hero: InvoiceCockpitHero): Button =
        DetailViewTestHarness.flatten(hero).filterIsInstance<Button>()
            .single { it.text == UiTranslations.tr("invoiceDetail.mail.send") }

    private val hint get() = UiTranslations.tr("invoiceDetail.mail.documentPending")

    @Test
    fun `the send button is disabled with the hint while the document is pending`() {
        whenever(readiness.readiness(1L)).thenReturn(MailSendGate.JOB_IN_FLIGHT)
        val hero = hero().also { it.render(inReview); UI.getCurrent().add(it) }

        val button = sendButton(hero)
        assertFalse(button.isEnabled)
        assertEquals(hint, button.element.getAttribute("title"))
        assertEquals("Dokument wird erzeugt …", hint)
    }

    @Test
    fun `the button enables itself on the next refresh once the document is ready`() {
        whenever(readiness.readiness(1L)).thenReturn(MailSendGate.JOB_IN_FLIGHT)
        val hero = hero().also { it.render(inReview); UI.getCurrent().add(it) }
        assertFalse(sendButton(hero).isEnabled)

        whenever(readiness.readiness(1L)).thenReturn(MailSendGate.READY)
        hero.refresh(inReview)

        assertTrue(sendButton(hero).isEnabled)
    }

    @Test
    fun `no generation requested yet leaves the button enabled - the click starts it`() {
        whenever(readiness.readiness(1L)).thenReturn(MailSendGate.DOCUMENT_MISSING)
        val hero = hero().also { it.render(inReview); UI.getCurrent().add(it) }
        assertTrue(sendButton(hero).isEnabled)
    }

    @Test
    fun `without a readiness port the button stays enabled`() {
        val hero = testCockpitHero(mailDispatch = dispatch).also { it.render(inReview); UI.getCurrent().add(it) }
        assertTrue(sendButton(hero).isEnabled)
    }

    @Test
    fun `a DocumentPending result shows the hint once, no error, and asks the caller to retry`() {
        whenever(dispatch.compose(1L)).thenReturn(MailComposeResult.DocumentPending)
        val actions = InvoiceMailActions(dispatch, null, readiness)

        assertFalse(actions.openMailCompose(host, inReview))
        assertFalse(actions.openMailCompose(host, inReview))

        val toasts = getNotifications().map { toastText(it) }
        assertEquals(listOf(hint), toasts)
        verify(dispatch, times(2)).compose(1L)
    }

    @Test
    fun `a real failure after a pending hint still reports an error and ends the flow`() {
        whenever(dispatch.compose(any())).thenReturn(MailComposeResult.DocumentPending)
            .thenThrow(MailDispatchException("Recipient has no email address"))
        val actions = InvoiceMailActions(dispatch, null, readiness)

        assertFalse(actions.openMailCompose(host, inReview))
        assertTrue(actions.openMailCompose(host, inReview))

        assertEquals("Recipient has no email address", toastText(getNotifications().last()))
    }

    @Test
    fun `a failed generation reports the error once and ends the flow without a retry`() {
        whenever(dispatch.compose(1L)).thenThrow(
            MailDispatchException("Invoice RE-1 has no completed, checksummed document to attach — generate the e-invoice first"),
        )
        val actions = InvoiceMailActions(dispatch, null, readiness)

        assertTrue(actions.openMailCompose(host, inReview))

        val toasts = getNotifications().map { toastText(it) }
        assertEquals(1, toasts.size)
        assertTrue(toasts.single().contains("generate the e-invoice first"))
        verify(dispatch, times(1)).compose(1L)
    }
}
