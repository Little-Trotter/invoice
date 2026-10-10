package de.littletrotter.application.invoice

import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
import de.littletrotter.domain.repository.InvoiceRepository
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
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
    private val llmAnalysisRepository = mock<InvoiceLlmAnalysisRepository>()

    @Test
    fun `flag off - onInvoiceFinalized returns silently without touching any repository`() {
        val handler = InvoiceLlmAnalysisEventHandler(
            invoiceRepository,
            llmAnalysisRepository,
            LlmAnalysisFeatureConfig(enabled = false),
        )

        assertDoesNotThrow { handler.onInvoiceFinalized(InvoiceFinalized(1L, "RE-2026-0001")) }

        // Skipped, not merely tolerant of failure: neither repository is even asked.
        verify(invoiceRepository, never()).findById(any())
        verify(llmAnalysisRepository, never()).findByInvoiceId(any())
        verify(llmAnalysisRepository, never()).save(any())
    }

    @Test
    fun `flag on - onInvoiceFinalized still queues an analysis as before`() {
        whenever(llmAnalysisRepository.findByInvoiceId(2L)).thenReturn(emptyList())
        whenever(invoiceRepository.findById(2L)).thenReturn(Invoice().apply { id = 2L })
        whenever(llmAnalysisRepository.save(any())).thenAnswer { it.arguments[0] }

        val handler = InvoiceLlmAnalysisEventHandler(
            invoiceRepository,
            llmAnalysisRepository,
            LlmAnalysisFeatureConfig(enabled = true),
        )

        handler.onInvoiceFinalized(InvoiceFinalized(2L, "RE-2026-0002"))

        verify(llmAnalysisRepository).save(any())
    }
}
