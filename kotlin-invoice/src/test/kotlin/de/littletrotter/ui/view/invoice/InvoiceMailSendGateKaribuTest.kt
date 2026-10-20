package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.MultilingualTextsFeatureConfig
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.dto.MailComposeHandover
import de.littletrotter.application.dto.MailComposeResult
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.InvoiceMailReadinessQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.toastText
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ComposeRouteStub : Div()

/** The two explicit steps of the cockpit: "Festschreiben" in review, then the gated "E-Mail senden". */
class InvoiceMailSendGateKaribuTest {

    private val dispatch: InvoiceMailDispatchService = mock()
    private val readiness: InvoiceMailReadinessQueryPort = mock()
    private val host = Div()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        clearNotifications()
        whenever(dispatch.isAvailable()).thenReturn(true)
        whenever(readiness.readiness(any())).thenReturn(MailSendGate.READY)
        DirectPollTicks.install()
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun tr(key: String) = UiTranslations.tr(host, key)

    private fun dto(status: InvoiceStatus, mailSent: Boolean? = null) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "RE-1", status = status.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
        mailSent = mailSent,
    )

    private fun mailActions() = InvoiceMailActions(dispatch, null, readiness)

    private fun buttons(current: InvoiceDetailDto): List<Button> {
        val statusActions = InvoiceStatusActions(
            mock<ChangeInvoiceStatusUseCase>(),
            mock<GenerationJobQueryPort>(),
            MultilingualTextsFeatureConfig(false),
            mailActions(),
            FIXED_TIME_SOURCE,
        )
        return statusActions.buildButtons(host, current, false).filterIsInstance<Button>()
    }

    private fun sendButton(current: InvoiceDetailDto): Button? = mailActions().buildSendButton(host, current)

    /** The cockpit hero stays attached while its buttons are rebuilt: [host] stands in for it. */
    private fun attach(button: Component) {
        if (!host.isAttached) UI.getCurrent().add(host)
        host.add(button)
    }

    private fun currentPath(): String = UI.getCurrent().internals.activeViewLocation.path

    @Test
    fun `in review the finalize button is offered and the mail button is not`() {
        val texts = buttons(dto(InvoiceStatus.IN_REVIEW)).map { it.text }

        assertTrue(texts.contains("Festschreiben"), texts.toString())
        assertFalse(texts.contains(tr("invoiceDetail.mail.send")))
    }

    @Test
    fun `an issued invoice with a ready gate offers an enabled primary send button`() {
        val button = buttons(dto(InvoiceStatus.ISSUED)).single { it.text == tr("invoiceDetail.mail.send") }

        assertTrue(button.isEnabled)
        assertNull(button.element.getAttribute("title"))
    }

    @Test
    fun `a click shows the composing state during the call and navigates to the draft`() {
        RouteConfiguration.forSessionScope().setRoute("mail/compose/:draftId", ComposeRouteStub::class.java)
        val button = sendButton(dto(InvoiceStatus.ISSUED))!!
        attach(button)
        var textDuringCall: String? = null
        var enabledDuringCall: Boolean? = null
        whenever(dispatch.compose(1L)).thenAnswer {
            textDuringCall = button.text
            enabledDuringCall = button.isEnabled
            MailComposeResult.Ready(MailComposeHandover("d-7", "mail/compose/d-7"))
        }

        button._click()
        DirectPollTicks.awaitTicks()

        verify(dispatch).compose(1L)
        assertEquals(tr("invoiceDetail.mail.composing"), textDuringCall)
        assertEquals(false, enabledDuringCall)
        assertEquals("mail/compose/d-7", currentPath())
    }

    @Test
    fun `the click only sets the loading state, the outcome is applied afterwards`() {
        RouteConfiguration.forSessionScope().setRoute("mail/compose/:draftId", ComposeRouteStub::class.java)
        val button = sendButton(dto(InvoiceStatus.ISSUED))!!
        attach(button)
        whenever(dispatch.compose(1L)).thenReturn(MailComposeResult.Ready(MailComposeHandover("d-7", "mail/compose/d-7")))
        val pathBefore = currentPath()

        button._click()

        assertFalse(button.isEnabled)
        assertEquals(tr("invoiceDetail.mail.composing"), button.text)
        assertEquals(pathBefore, currentPath())
        assertTrue(button.isDisableOnClick)
        DirectPollTicks.awaitTicks()
        assertEquals("mail/compose/d-7", currentPath())
    }

    @Test
    fun `a failed handover restores label and enabled state and shows the error`() {
        val button = sendButton(dto(InvoiceStatus.ISSUED))!!
        attach(button)
        val label = button.text
        whenever(dispatch.compose(1L)).thenThrow(MailDispatchException("Kein Empfänger"))

        button._click()
        DirectPollTicks.awaitTicks()

        assertEquals(label, button.text)
        assertTrue(button.isEnabled)
        assertTrue(getNotifications().any { toastText(it).contains("Kein Empfänger") })
    }

    @Test
    fun `a cockpit rebuild during the handover renders the new button in the in-flight state`() {
        val actions = mailActions()
        val clicked = actions.buildSendButton(host, dto(InvoiceStatus.ISSUED))!!
        attach(clicked)
        whenever(dispatch.compose(1L)).thenReturn(MailComposeResult.Ready(MailComposeHandover("d-7", "mail/compose/d-7")))

        clicked._click()
        val rebuilt = actions.buildSendButton(host, dto(InvoiceStatus.ISSUED))!!

        assertFalse(rebuilt.isEnabled)
        assertEquals(tr("invoiceDetail.mail.composing"), rebuilt.text)
    }

    @Test
    fun `a handover that succeeds after the clicked button was replaced still opens the draft`() {
        RouteConfiguration.forSessionScope().setRoute("mail/compose/:draftId", ComposeRouteStub::class.java)
        val actions = mailActions()
        val clicked = actions.buildSendButton(host, dto(InvoiceStatus.ISSUED))!!
        attach(clicked)
        whenever(dispatch.compose(1L)).thenReturn(MailComposeResult.Ready(MailComposeHandover("d-7", "mail/compose/d-7")))

        clicked._click()
        clicked.removeFromParent()
        attach(actions.buildSendButton(host, dto(InvoiceStatus.ISSUED))!!)
        DirectPollTicks.awaitTicks()

        assertEquals("mail/compose/d-7", currentPath())
    }

    @Test
    fun `a handover that fails after the clicked button was replaced reports the error and restores the replacement`() {
        val actions = mailActions()
        val clicked = actions.buildSendButton(host, dto(InvoiceStatus.ISSUED))!!
        attach(clicked)
        whenever(dispatch.compose(1L)).thenThrow(MailDispatchException("Kein Empfänger"))

        clicked._click()
        clicked.removeFromParent()
        val rebuilt = actions.buildSendButton(host, dto(InvoiceStatus.ISSUED))!!
        attach(rebuilt)
        DirectPollTicks.awaitTicks()

        assertTrue(rebuilt.isEnabled)
        assertEquals(tr("invoiceDetail.mail.send"), rebuilt.text)
        assertTrue(getNotifications().any { toastText(it).contains("Kein Empfänger") })
    }

    @Test
    fun `a handover result arriving after the user left the view is dropped`() {
        val button = sendButton(dto(InvoiceStatus.ISSUED))!!
        attach(button)
        whenever(dispatch.compose(1L)).thenThrow(MailDispatchException("Kein Empfänger"))

        button._click()
        host.removeFromParent()
        DirectPollTicks.awaitTicks()

        assertEquals(tr("invoiceDetail.mail.composing"), button.text)
        assertFalse(button.isEnabled)
        assertTrue(getNotifications().isEmpty())
    }

    @ParameterizedTest
    @EnumSource(MailSendGate::class, names = ["READY"], mode = EnumSource.Mode.EXCLUDE)
    fun `every blocked gate keeps the button visible but disabled with its tooltip`(gate: MailSendGate) {
        whenever(readiness.readiness(any())).thenReturn(gate)

        val button = buttons(dto(InvoiceStatus.ISSUED)).single { it.text == tr("invoiceDetail.mail.send") }

        assertFalse(button.isEnabled)
        assertEquals(tr(sendBlockerTooltipKey(gate)!!), button.element.getAttribute("title"))
    }

    @Test
    fun `a running validation disables the button with its own tooltip`() {
        whenever(readiness.readiness(any())).thenReturn(MailSendGate.VALIDATION_PENDING)

        val button = buttons(dto(InvoiceStatus.ISSUED)).single { it.text == tr("invoiceDetail.mail.send") }

        assertFalse(button.isEnabled)
        assertEquals("Die E-Rechnung wird noch geprüft …", button.element.getAttribute("title"))
    }

    @Test
    fun `a recorded dispatch turns the button into a secondary resend`() {
        val button = sendButton(dto(InvoiceStatus.ISSUED, mailSent = true))!!

        assertEquals("Erneut senden", button.text)
        assertTrue(button.isEnabled)
        assertFalse(button.hasThemeName("primary"))
    }

    @Test
    fun `without the mail component there is no send button`() {
        whenever(dispatch.isAvailable()).thenReturn(false)

        assertNull(sendButton(dto(InvoiceStatus.ISSUED)))
        assertTrue(buttons(dto(InvoiceStatus.ISSUED)).none { it.text == tr("invoiceDetail.mail.send") })
    }

    @Test
    fun `a rejected handover reports the error and restores the button`() {
        whenever(dispatch.compose(1L)).thenThrow(MailDispatchException("x"))
        val button = sendButton(dto(InvoiceStatus.ISSUED))!!
        attach(button)

        button._click()
        DirectPollTicks.awaitTicks()
        MockVaadin.clientRoundtrip()

        assertEquals(1, getNotifications().size)
        assertTrue(button.isEnabled)
        assertEquals(tr("invoiceDetail.mail.send"), button.text)
    }

    @Test
    fun `an unexpected failure is reported generically and restores the button`() {
        whenever(dispatch.compose(1L)).thenThrow(IllegalStateException("boom"))
        val button = sendButton(dto(InvoiceStatus.ISSUED))!!
        attach(button)

        button._click()
        DirectPollTicks.awaitTicks()

        assertTrue(button.isEnabled)
        assertEquals(1, getNotifications().size)
    }
}

class SendBlockerTooltipKeyTest {

    @Test
    fun `the ready gate has no blocker text`() {
        assertNull(sendBlockerTooltipKey(MailSendGate.READY))
    }

    @Test
    fun `each blocked gate maps to its own tooltip key`() {
        val expected = mapOf(
            MailSendGate.JOB_IN_FLIGHT to "invoiceDetail.finalize.inProgress.tooltip",
            MailSendGate.NOT_ISSUED to "invoiceDetail.mail.gate.notIssued",
            MailSendGate.VALIDATION_PENDING to "invoiceDetail.mail.gate.validationPending",
            MailSendGate.NOT_VALID to "invoiceDetail.mail.gate.notValid",
            MailSendGate.DOCUMENT_MISSING to "invoiceDetail.mail.gate.documentMissing",
            MailSendGate.REPRODUCED to "document.reproduction.sendBlocked",
            MailSendGate.NO_RECIPIENT_EMAIL to "invoiceDetail.mail.gate.noRecipientEmail",
        )

        assertEquals(expected, MailSendGate.entries.filter { it != MailSendGate.READY }.associateWith { sendBlockerTooltipKey(it) })
    }
}
