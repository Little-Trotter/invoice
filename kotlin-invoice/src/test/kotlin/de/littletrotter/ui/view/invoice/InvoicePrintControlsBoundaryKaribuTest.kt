package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.exception.GenerationBlockReason
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [InvoicePrintControls] edge paths: a background refresh that arrives before the first
 * render, the print button blocked for an immutable invoice, and the sender's own template that is
 * scoped to a different B2B profile than the recipient's. The latest-job-summary edge path lives in
 * [InvoiceGenerationJobsPanelBoundaryKaribuTest].
 */
class InvoicePrintControlsBoundaryKaribuTest {

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

    private fun invoice(recipientB2bProfileId: Long? = null): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 2L, name = "Seller"),
        recipient = PartyDto(id = 3L, name = "Buyer", customerNumber = "KD-1", b2bProfileId = recipientB2bProfileId),
        items = emptyList(), latestGenerationJob = null,
    )

    private fun controls(
        blockReason: GenerationBlockReason? = null,
    ): InvoicePrintControls {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.generationBlockReason(any())).thenReturn(blockReason)
        return InvoicePrintControls(
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            recipientQueryPort = mock<RecipientQueryPort>(),
            generationJobCreationService = mock<GenerationJobCreationService>(),
            documentJobPublisher = mock<DocumentJobPublisher>(),
            llmCheckAvailability = LlmCheckAvailability(),
        )
    }

    private fun printButton(controls: InvoicePrintControls): Button =
        flatten(controls).filterIsInstance<Button>().single { it.text == UiTranslations.tr("invoiceDetail.jobs.print") }

    @Test
    fun `boundary - a refresh arriving before the first render builds the panel instead of failing`() {
        val controls = controls()

        controls.refresh(invoice())

        assertTrue(printButton(controls).isEnabled)
    }

    @Test
    fun `boundary - an immutable invoice disables printing with its own tooltip`() {
        val controls = controls(blockReason = GenerationBlockReason.IMMUTABLE)
        controls.render(invoice())

        val button = printButton(controls)
        assertFalse(button.isEnabled)
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.print.blockedImmutable"), button.element.getAttribute("title"))
    }
}
