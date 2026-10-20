package de.littletrotter.application.invoice

import  de.littletrotter.fixture.anInvoice
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.fixture.anInvoice
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The deployment-level [LlmAnalysisFeatureConfig] guard on the entry point that starts
 * the whole LLM analysis path. Complements [InvoiceLlmAnalysisEventHandlerTest], which covers the
 * pre-existing behavior with the flag left at its enabled default (constructed via the two-arg
 * overload there).
 */
class InvoiceLlmAnalysisEventHandlerFeatureFlagTest {

    private val invoiceRepository = mock<InvoiceRepository>()

    @Test
    fun `flag off - onInvoiceFinalized returns silently without touching the repository`() {
        val handler = InvoiceLlmAnalysisEventHandler(
            invoiceRepository,
            LlmAnalysisFeatureConfig(enabled = false),
        )

        assertDoesNotThrow { handler.onInvoiceFinalized(InvoiceFinalized(1L, "RE-2026-0001")) }

        // Skipped, not merely tolerant of failure: the repository is not even asked.
        verify(invoiceRepository, never()).findById(any())
    }

    @Test
    fun `flag on - onInvoiceFinalized still queues an analysis as before`() {
        val invoice = anInvoice(id = 2L)
        whenever(invoiceRepository.findById(2L)).thenReturn(invoice)

        val handler = InvoiceLlmAnalysisEventHandler(
            invoiceRepository,
            LlmAnalysisFeatureConfig(enabled = true),
        )

        handler.onInvoiceFinalized(InvoiceFinalized(2L, "RE-2026-0002"))

        assertEquals(1, invoice.llmAnalysisList.size)
    }
}
