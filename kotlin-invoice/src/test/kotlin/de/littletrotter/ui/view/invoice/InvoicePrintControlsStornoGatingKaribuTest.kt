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
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
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
 * ST-8 rule table — "generate e-invoice / print job": `yes` for SENT and for both `CANCELLED`
 * documents (the cancelled original and the storno document alike), `no` for `VOIDED`. Pins the
 * exact code this asymmetry lives in ([InvoicePrintControls]'s `buildPrintButton`): it must read
 * [InvoiceStatus.VOIDED] specifically via [de.littletrotter.domain.exception.GenerationBlockReason],
 * never [de.littletrotter.domain.invoice.InvoiceStateMachine.isTerminal] (which is also `true` for
 * `CANCELLED` and would wrongly disable the button there too). The server-side half of the same
 * rule ([GenerationJobCreationService.createJob]) has its own pin in
 * `GenerationJobCreationServiceTest`. Relocated from the former monolithic
 * `InvoiceExportPanel` into [InvoicePrintControls], which now owns the print button.
 */
class InvoicePrintControlsStornoGatingKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun invoice(status: InvoiceStatus, typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE): InvoiceDetailDto =
        InvoiceDetailDto(
            id = 1L, invoiceNumber = "INV-1", status = status.ordinal, invoiceDate = null,
            netAmount = null, vatAmount = null, grossAmount = null,
            sender = PartyDto(id = 2L, name = "Seller"),
            recipient = PartyDto(id = 3L, name = "Buyer", customerNumber = "KD-1"),
            items = emptyList(), latestGenerationJob = null,
            typeCode = typeCode.ordinal,
        )

    private fun buildControls(current: InvoiceDetailDto): InvoicePrintControls {
        val blockReason = when (current.statusEnum()) {
            InvoiceStatus.VOIDED -> de.littletrotter.domain.exception.GenerationBlockReason.VOIDED
            else -> null
        }
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.generationBlockReason(any())).thenReturn(blockReason)
        val controls = InvoicePrintControls(
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            recipientQueryPort = mock<RecipientQueryPort>(),
            generationJobCreationService = mock<GenerationJobCreationService>(),
            documentJobPublisher = mock<DocumentJobPublisher>(),
            llmCheckAvailability = LlmCheckAvailability(),
        )
        controls.render(current)
        return controls
    }

    private fun printButton(controls: InvoicePrintControls): Button =
        flatten(controls).filterIsInstance<Button>().single { it.text == UiTranslations.tr("invoiceDetail.jobs.print") }

    @Test
    fun `print job button stays enabled for a CANCELLED original`() {
        val controls = buildControls(invoice(InvoiceStatus.CANCELLED))

        val button = printButton(controls)

        assertTrue(button.isEnabled, "a cancelled invoice was really sent -- its e-invoice can still be generated")
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.print.tooltip"), button.element.getAttribute("title"))
    }

    @Test
    fun `print job button stays enabled for the CANCELLED storno document`() {
        val controls = buildControls(invoice(InvoiceStatus.CANCELLED, typeCode = InvoiceTypeCode.CORRECTED_INVOICE))

        val button = printButton(controls)

        assertTrue(button.isEnabled, "the storno document itself is also CANCELLED and just as eligible")
    }

    @Test
    fun `print job button is disabled with the blockedVoided tooltip for a VOIDED invoice`() {
        val controls = buildControls(invoice(InvoiceStatus.VOIDED))

        val button = printButton(controls)

        assertFalse(button.isEnabled, "a voided invoice was never sent -- sinnlos (PO) to place a job for it")
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.print.blockedVoided"), button.element.getAttribute("title"))
    }

    @Test
    fun `print job button stays enabled for a plain SENT invoice`() {
        val controls = buildControls(invoice(InvoiceStatus.SENT))

        assertTrue(printButton(controls).isEnabled)
    }
}
