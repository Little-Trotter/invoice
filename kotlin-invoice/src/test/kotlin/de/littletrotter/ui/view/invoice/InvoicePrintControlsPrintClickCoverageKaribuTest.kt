package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.FacturXDeployment
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.GenerationJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Closes [InvoicePrintControls] branch gaps around the print button's click
 * listener that no existing test reaches: the visible-engine-selector branch of `chosenEngine`
 * (every existing print-click test keeps the selector hidden), the `newJob.id` fallback in the
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

    private fun engineSelector(controls: InvoicePrintControls): RadioButtonGroup<DocumentEngine> =
        flatten(controls).filterIsInstance<RadioButtonGroup<*>>().single().requireAs()

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

    /** Eligible via the seller's additional VAT registrations rather than the seller's own vatId,
     *  so the same fixture exercises `additionalVatRegistrationCountries`'s mapNotNull (line 202)
     *  together with the engine selector's visible branch. */
    private fun eligibleInvoiceViaAdditionalRegistration(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 2L, name = "Seller", vatId = null, additionalVatRegistrationCountries = listOf("FR", "XX")),
        recipient = PartyDto(id = 3L, name = "Buyer", customerNumber = "KD-1"),
        items = emptyList(), latestGenerationJob = null,
    )

    private fun buildControls(
        generationJobCreationService: GenerationJobCreationService = mock(),
        documentJobPublisher: DocumentJobPublisher = mock(),
        recipientQueryPort: RecipientQueryPort = mock(),
    ): InvoicePrintControls = InvoicePrintControls(
        invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>(),
        recipientQueryPort = recipientQueryPort,
        generationJobCreationService = generationJobCreationService,
        documentJobPublisher = documentJobPublisher,
        llmCheckAvailability = LlmCheckAvailability(),
        validationSettingsGate = validationSettingsGate(),
        facturXDeployment = FacturXDeployment(deployed = true),
    )

    // ── chosenEngine: the visible-selector branch ───────────────────────────────────────────────

    @Test
    fun `printing with a visible and chosen engine forwards it to createJob`() {
        val generationJobCreationService: GenerationJobCreationService = mock()
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(GenerationJob().apply { id = 5L; jobType = "PRINT_JOB" })
        val controls = buildControls(generationJobCreationService = generationJobCreationService)
        controls.render(eligibleInvoiceViaAdditionalRegistration())

        engineSelector(controls).value = DocumentEngine.FACTURX
        printButton(controls).click()

        verify(generationJobCreationService).createJob(
            any<Long>(), any(), any(), anyOrNull(), org.mockito.kotlin.eq(DocumentEngine.FACTURX), any(),
            org.mockito.kotlin.eq(de.littletrotter.domain.invoice.GenerationJobTrigger.MANUAL),
        )
    }

    // ── success toast: newJob.id null vs. present ───────────────────────────────────────────────

    @Test
    fun `a successful print job with no id yet shows the n-a placeholder in the toast`() {
        val generationJobCreationService: GenerationJobCreationService = mock()
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(GenerationJob().apply { id = null; jobType = "PRINT_JOB" })
        val controls = buildControls(generationJobCreationService = generationJobCreationService)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(
            notificationTexts().any { it == de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.jobs.print.scheduled", "n/a") },
            "expected the n/a placeholder toast, got: ${notificationTexts()}",
        )
    }

    @Test
    fun `a successful print job with an id shows it in the toast`() {
        val generationJobCreationService: GenerationJobCreationService = mock()
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(GenerationJob().apply { id = 42L; jobType = "PRINT_JOB" })
        val controls = buildControls(generationJobCreationService = generationJobCreationService)
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
        val generationJobCreationService: GenerationJobCreationService = mock()
        val ex = InvoiceValidationException(InvoiceValidationError.NO_ITEMS)
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenThrow(ex)
        var captured: InvoiceValidationException? = null
        val controls = buildControls(generationJobCreationService = generationJobCreationService)
        val state = InvoiceDetailState()
        state.onValidationFailure { captured = it }
        controls.bind(state)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertEquals(ex, captured, "the callback must receive the exact validation exception")
    }

    @Test
    fun `a validation failure without a wired callback does not crash and shows the resolved i18n message`() {
        val generationJobCreationService: GenerationJobCreationService = mock()
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenThrow(InvoiceValidationException(InvoiceValidationError.NO_ITEMS, message = "no items"))
        val controls = buildControls(generationJobCreationService = generationJobCreationService)
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
        val generationJobCreationService: GenerationJobCreationService = mock()
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenAnswer { throw RuntimeException("db down") }
        var callbackInvoked = false
        val controls = buildControls(generationJobCreationService = generationJobCreationService)
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
        val generationJobCreationService: GenerationJobCreationService = mock()
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenAnswer { throw RuntimeException() }
        val controls = buildControls(generationJobCreationService = generationJobCreationService)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(notificationTexts().any { it == de.littletrotter.ui.i18n.UiTranslations.tr("message.error.generic") })
    }

    // ── onPrintScheduled: present / absent on a successful print ────────────────────────────────

    @Test
    fun `a successful print with a wired onPrintScheduled callback invokes it`() {
        val generationJobCreationService: GenerationJobCreationService = mock()
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(GenerationJob().apply { id = 9L; jobType = "PRINT_JOB" })
        var invoked = false
        val controls = buildControls(generationJobCreationService = generationJobCreationService)
        val state = InvoiceDetailState()
        state.onPrintScheduled { invoked = true }
        controls.bind(state)
        controls.render(plainInvoice())

        printButton(controls).click()

        assertTrue(invoked)
    }

    // ── recipientBillingCountry: no recipient at all on the invoice ────────────────────────────

    @Test
    fun `rendering an invoice without a recipient never calls findBillingAddress`() {
        val recipientQueryPort: RecipientQueryPort = mock()
        val controls = buildControls(recipientQueryPort = recipientQueryPort)

        controls.render(
            InvoiceDetailDto(
                id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
                netAmount = null, vatAmount = null, grossAmount = null,
                sender = PartyDto(id = 2L, name = "Seller"),
                recipient = null, items = emptyList(), latestGenerationJob = null,
            ),
        )

        verify(recipientQueryPort, never()).findBillingAddress(any())
    }

    private fun plainInvoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 2L, name = "Seller"),
        recipient = PartyDto(id = 3L, name = "Buyer", customerNumber = "KD-1"),
        items = emptyList(), latestGenerationJob = null,
    )
}
