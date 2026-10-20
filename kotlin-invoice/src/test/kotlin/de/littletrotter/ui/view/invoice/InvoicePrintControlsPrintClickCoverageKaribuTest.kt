package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.notification.Notification
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.SchedulePrintJobUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Closes [InvoicePrintControls] branch gaps around the print button's click
 * listener that no existing test reaches: the `newJob.id` fallback in the
 * success toast, the [InvoiceValidationException] fork (with and without [InvoiceDetailState
 * .onValidationFailure] wired), the generic-error fork with and without an exception message, and
 * the `recipientBillingCountry` / `additionalVatRegistrationCountries` resolution when the sender
 * or recipient is entirely absent from the DTO. Relocated from the former monolithic
 * `InvoiceExportPanel`; the two outcome callbacks now go through a bound [InvoiceDetailState]
 * ([InvoicePrintControls.bind]) instead of constructor lambdas.
 */
class InvoicePrintControlsPrintClickCoverageKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun printButton(controls: InvoicePrintControls): Button =
        flatten(controls).filterIsInstance<Button>().single { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.jobs.print") }

    /** Both toast idioms this panel uses: [UiNotifications.success]/[Notification.show] set a
     *  plain "text" element property (no child Span); the richer [UiNotifications.error] wraps
     *  its message in an actual child [com.vaadin.flow.component.html.Span] instead — same dual
     *  check as [ReceiptDialogActionsKaribuTest]'s `assertNotificationShown`. */
    private fun notificationTexts(): List<String> {
        MockVaadin.clientRoundtrip()
        val found = mutableListOf<Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(com.vaadin.flow.component.UI.getCurrent().element)
        val plain = found.filterIsInstance<Notification>().mapNotNull { de.littletrotter.ui.toastText(it) }
        val spans = found.filterIsInstance<com.vaadin.flow.component.html.Span>().mapNotNull { it.text }
        return plain + spans
    }

    private fun buildControls(
        schedulePrintJobUseCase: SchedulePrintJobUseCase = mock(),
    ): InvoicePrintControls = InvoicePrintControls(
        invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>(),
        schedulePrintJobUseCase = schedulePrintJobUseCase,
        llmCheckAvailability = LlmCheckAvailability(),
    )

    // ── success toast: newJob.id null vs. present ───────────────────────────────────────────────

    @Test
    fun `a successful print job with no id yet shows the n-a placeholder in the toast`() {
        val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any()))
            .thenReturn(null)
        val controls = buildControls(schedulePrintJobUseCase = schedulePrintJobUseCase)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(
            notificationTexts().any { it == de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.jobs.print.scheduled", "n/a") },
            "expected the n/a placeholder toast, got: ${notificationTexts()}",
        )
    }

    @Test
    fun `a successful print job with an id shows it in the toast`() {
        val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any()))
            .thenReturn(42L)
        val controls = buildControls(schedulePrintJobUseCase = schedulePrintJobUseCase)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(
            notificationTexts().any { it == de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.jobs.print.scheduled", "42") },
            "expected the real-id toast, got: ${notificationTexts()}",
        )
    }

    // ── InvoiceValidationException: with and without the onValidationFailure callback ──────────

    @Test
    fun `a validation failure with a wired callback invokes it and shows the error toast`() {
        val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
        val ex = InvoiceValidationException(InvoiceValidationError.NO_ITEMS)
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any())).thenThrow(ex)
        var captured: InvoiceValidationException? = null
        val controls = buildControls(schedulePrintJobUseCase = schedulePrintJobUseCase)
        val state = InvoiceDetailState()
        state.onValidationFailure { captured = it }
        controls.bind(state)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertEquals(ex, captured, "the callback must receive the exact validation exception")
    }

    @Test
    fun `a validation failure without a wired callback does not crash and shows the resolved i18n message`() {
        val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any()))
            .thenThrow(InvoiceValidationException(InvoiceValidationError.NO_ITEMS, message = "no items"))
        val controls = buildControls(schedulePrintJobUseCase = schedulePrintJobUseCase)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(
            notificationTexts().any { it == de.littletrotter.ui.i18n.UiTranslations.tr("domain.validation.noItems") },
            "expected the resolved i18n message as the toast, got: ${notificationTexts()}",
        )
    }

    // ── generic exception: with and without a message ───────────────────────────────────────────

    @Test
    fun `a generic failure with a message shows that message and never calls the validation callback`() {
        val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any()))
            .thenAnswer { throw RuntimeException("db down") }
        var callbackInvoked = false
        val controls = buildControls(schedulePrintJobUseCase = schedulePrintJobUseCase)
        val state = InvoiceDetailState()
        state.onValidationFailure { callbackInvoked = true }
        controls.bind(state)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(notificationTexts().any { it == de.littletrotter.ui.i18n.UiTranslations.tr("message.error.generic") })
        assertTrue(notificationTexts().none { it.contains("db down") }, "an unexpected exception's own text never reaches the toast")
        assertTrue(!callbackInvoked, "a non-validation exception must never reach onValidationFailure")
    }

    @Test
    fun `a generic failure without a message falls back to the generic error label`() {
        val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any()))
            .thenAnswer { throw RuntimeException() }
        val controls = buildControls(schedulePrintJobUseCase = schedulePrintJobUseCase)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(notificationTexts().any { it == de.littletrotter.ui.i18n.UiTranslations.tr("message.error.generic") })
    }

    // ── onPrintScheduled: present / absent on a successful print ────────────────────────────────

    @Test
    fun `a successful print with a wired onPrintScheduled callback invokes it`() {
        val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any()))
            .thenReturn(9L)
        var invoked = false
        val controls = buildControls(schedulePrintJobUseCase = schedulePrintJobUseCase)
        val state = InvoiceDetailState()
        state.onPrintScheduled { invoked = true }
        controls.bind(state)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(invoked)
    }

    private fun plainInvoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 2L, name = "Seller"),
        recipient = PartyDto(id = 3L, name = "Buyer", customerNumber = "KD-1"),
        items = emptyList(), latestGenerationJob = null,
    )
}
