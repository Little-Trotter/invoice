package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.exception.GenerationBlockReason
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
 * UI gating and corrective-invoice label pair tests. Covers:
 * - Badge label resolution for ISSUED, cancelled original, type 384 (corrected invoice)
 * - Regression: type 380/381 as CANCELLED still show cancelledOriginal
 * - Button enablement/tooltips per generationBlockReason (null/VOIDED/IMMUTABLE)
 */
class RS3UiGatingLabelsKaribuTest {

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

    private fun invoiceDetail(
        status: InvoiceStatus,
        typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
    ): InvoiceDetailDto =
        InvoiceDetailDto(
            id = 1L, invoiceNumber = "INV-1", status = status.ordinal, invoiceDate = null,
            netAmount = null, vatAmount = null, grossAmount = null,
            sender = PartyDto(id = 2L, name = "Seller"),
            recipient = PartyDto(id = 3L, name = "Buyer", customerNumber = "KD-1"),
            items = emptyList(), latestGenerationJob = null,
            typeCode = typeCode.ordinal,
        )

    private fun buildPanel(current: InvoiceDetailDto, blockReason: GenerationBlockReason? = null): InvoicePrintControls {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>().apply {
            whenever(generationBlockReason(any())).thenReturn(blockReason)
        }
        val panel = InvoicePrintControls(
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            schedulePrintJobUseCase = mock(),
            llmCheckAvailability = LlmCheckAvailability(),
        )
        panel.render(current)
        return panel
    }

    private fun printButton(panel: InvoicePrintControls): Button =
        flatten(panel).filterIsInstance<Button>().single { it.text == UiTranslations.tr("invoiceDetail.jobs.print") }

    // ── Button enablement tests (via generationBlockReason) ─────────────────────────

    @Test
    fun `RS_3_print_button_is_enabled_when_generation_is_allowed_blockReason_null`() {
        val panel = buildPanel(invoiceDetail(InvoiceStatus.ISSUED), blockReason = null)
        val button = printButton(panel)
        assertTrue(button.isEnabled)
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.print.tooltip"), button.element.getAttribute("title"))
    }

    @Test
    fun `RS_3_print_button_is_disabled_with_blockedVoided_tooltip_when_blockReason_VOIDED`() {
        val panel = buildPanel(invoiceDetail(InvoiceStatus.VOIDED), blockReason = GenerationBlockReason.VOIDED)
        val button = printButton(panel)
        assertFalse(button.isEnabled)
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.print.blockedVoided"), button.element.getAttribute("title"))
    }

    @Test
    fun `RS_3_print_button_is_disabled_with_blockedImmutable_tooltip_when_blockReason_IMMUTABLE`() {
        val panel = buildPanel(
            invoiceDetail(InvoiceStatus.ISSUED),
            blockReason = GenerationBlockReason.IMMUTABLE,
        )
        val button = printButton(panel)
        assertFalse(button.isEnabled)
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.print.blockedImmutable"), button.element.getAttribute("title"))
    }

    @Test
    fun `RS_3_print_button_is_enabled_for_CANCELLED_original_when_blockReason_null`() {
        val panel = buildPanel(
            invoiceDetail(InvoiceStatus.CANCELLED, InvoiceTypeCode.COMMERCIAL_INVOICE),
            blockReason = null,
        )
        val button = printButton(panel)
        assertTrue(button.isEnabled, "cancelled original is still eligible for first-time generation")
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.print.tooltip"), button.element.getAttribute("title"))
    }

    @Test
    fun `RS_3_print_button_is_enabled_for_cancellation_document_type_384_when_blockReason_null`() {
        val panel = buildPanel(
            invoiceDetail(InvoiceStatus.CANCELLED, InvoiceTypeCode.CORRECTED_INVOICE),
            blockReason = null,
        )
        val button = printButton(panel)
        assertTrue(button.isEnabled, "storno document (384) is eligible for first-time generation")
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.print.tooltip"), button.element.getAttribute("title"))
    }

    // ── statusBadgeLabelKey — the shared resolution behind BOTH the overview grid's badge and
    //    the detail page's own two cockpit badges (an earlier sweep found the detail page still showed
    //    the generic "Storniert" for both halves of the cancellation pair, which is exactly the page
    //    where telling them apart matters most). Tested directly rather than through Karibu:
    //    it is a pure function, and this way the assertion covers every call site at once.

    @Test
    fun `RS_3_statusBadgeLabelKey_resolves_the_cancellation_document_by_type_code`() {
        assertEquals(
            "invoice.typeCode.correctedInvoice",
            statusBadgeLabelKey(InvoiceStatus.CANCELLED, InvoiceTypeCode.CORRECTED_INVOICE),
        )
    }

    @Test
    fun `RS_3_statusBadgeLabelKey_resolves_a_cancelled_original_distinctly`() {
        assertEquals(
            "invoice.status.cancelledOriginal",
            statusBadgeLabelKey(InvoiceStatus.CANCELLED, InvoiceTypeCode.COMMERCIAL_INVOICE),
        )
        assertEquals(
            "invoice.status.cancelledOriginal",
            statusBadgeLabelKey(InvoiceStatus.CANCELLED, InvoiceTypeCode.CREDIT_NOTE),
        )
    }

    @Test
    fun `RS_3_statusBadgeLabelKey_without_type_code_context_keeps_the_generic_cancelled_label`() {
        // The overview's status quick-filter chips label a STATUS, not a document — they have no
        // type code to resolve and must keep the plain "Storniert".
        assertEquals("invoice.status.cancelled", statusBadgeLabelKey(InvoiceStatus.CANCELLED, null))
    }

    @Test
    fun `RS_3_statusBadgeLabelKey_leaves_every_non_cancelled_status_on_its_own_label`() {
        InvoiceStatus.entries.filter { it != InvoiceStatus.CANCELLED }.forEach { status ->
            assertEquals(
                status.labelKey,
                statusBadgeLabelKey(status, InvoiceTypeCode.COMMERCIAL_INVOICE),
                "a type code must never change a non-CANCELLED status's own label, offender: $status",
            )
        }
    }
}
