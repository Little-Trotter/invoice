package de.littletrotter.application.invoice

import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
import de.littletrotter.domain.repository.InvoiceRepository
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `InvoiceLlmAnalysisEventHandler.onInvoiceFinalized` against fake repositories: the handler
 * runs asynchronously after finalization, so it must never let an exception propagate outward
 * (class KDoc: "failures are logged but do not affect the finalization transaction"). The
 * `analysisModels` list itself depends on `System.getenv("MLX_MODEL"/"MLX_MODELS")` and cannot
 * be pinned to a specific value without a test seam -- the tests here therefore deliberately
 * check only that *some* model name is used consistently, not which one exactly.
 */
class InvoiceLlmAnalysisEventHandlerTest {

    private val invoiceRepository = mock<InvoiceRepository>()
    private val llmAnalysisRepository = mock<InvoiceLlmAnalysisRepository>()
    private val handler = InvoiceLlmAnalysisEventHandler(invoiceRepository, llmAnalysisRepository)

    private fun invoice(id: Long) = Invoice().apply { this.id = id }

    @Test
    fun `onInvoiceFinalized legt eine neue PENDING-Analyse an wenn noch keine fuer das Modell existiert`() {
        whenever(llmAnalysisRepository.findByInvoiceId(1L)).thenReturn(emptyList())
        whenever(invoiceRepository.findById(1L)).thenReturn(invoice(1L))
        whenever(llmAnalysisRepository.save(any())).thenAnswer { it.arguments[0] }

        handler.onInvoiceFinalized(InvoiceFinalized(1L, "RE-2026-0001"))

        val captor = argumentCaptor<InvoiceLlmAnalysis>()
        verify(llmAnalysisRepository).save(captor.capture())
        assertEquals(LlmAnalysisStatus.PENDING, captor.firstValue.status)
    }

    @Test
    fun `onInvoiceFinalized ueberspringt ein Modell dessen Analyse bereits abgeschlossen (nicht PENDING) ist`() {
        val done = InvoiceLlmAnalysis().apply { modelName = "irrelevant-fuer-den-test"; status = LlmAnalysisStatus.COMPLETED }
        // The model name of the already-completed analysis must match the actually configured
        // one for the skip branch to trigger -- we therefore read it out of the instance
        // instead of guessing it (see the class KDoc on analysisModels).
        val configuredModel = configuredModelName()
        done.modelName = configuredModel
        whenever(llmAnalysisRepository.findByInvoiceId(2L)).thenReturn(listOf(done))

        handler.onInvoiceFinalized(InvoiceFinalized(2L, "RE-2026-0002"))

        verify(invoiceRepository, never()).findById(any())
        verify(llmAnalysisRepository, never()).save(any())
    }

    @Test
    fun `onInvoiceFinalized aktualisiert eine bestehende PENDING-Analyse statt eine zweite anzulegen`() {
        val existing = InvoiceLlmAnalysis().apply {
            modelName = configuredModelName()
            status = LlmAnalysisStatus.PENDING
        }
        whenever(llmAnalysisRepository.findByInvoiceId(3L)).thenReturn(listOf(existing))
        whenever(invoiceRepository.findById(3L)).thenReturn(invoice(3L))
        whenever(llmAnalysisRepository.save(any())).thenAnswer { it.arguments[0] }

        handler.onInvoiceFinalized(InvoiceFinalized(3L, "RE-2026-0003"))

        val captor = argumentCaptor<InvoiceLlmAnalysis>()
        verify(llmAnalysisRepository).save(captor.capture())
        assertSame(existing, captor.firstValue, "eine noch offene PENDING-Analyse wird wiederverwendet, nicht dupliziert")
    }

    @Test
    fun `onInvoiceFinalized ueberspringt ein Modell still wenn die Rechnung nicht mehr gefunden wird`() {
        whenever(llmAnalysisRepository.findByInvoiceId(4L)).thenReturn(emptyList())
        whenever(invoiceRepository.findById(4L)).thenReturn(null)

        assertDoesNotThrow { handler.onInvoiceFinalized(InvoiceFinalized(4L, "RE-2026-0004")) }

        verify(llmAnalysisRepository, never()).save(any())
    }

    @Test
    fun `onInvoiceFinalized faengt jede Ausnahme ab statt sie an den asynchronen Aufrufer durchzureichen`() {
        // Class KDoc: "Runs in a separate thread – failures are logged but do not affect the
        // finalization transaction." A repository failure must therefore never escape the
        // method.
        whenever(llmAnalysisRepository.findByInvoiceId(5L)).thenThrow(RuntimeException("DB down"))

        assertDoesNotThrow { handler.onInvoiceFinalized(InvoiceFinalized(5L, "RE-2026-0005")) }
    }

    @Test
    fun `onInvoiceFinalized faengt auch eine Ausnahme aus dem abschliessenden save ab`() {
        whenever(llmAnalysisRepository.findByInvoiceId(6L)).thenReturn(emptyList())
        whenever(invoiceRepository.findById(6L)).thenReturn(invoice(6L))
        whenever(llmAnalysisRepository.save(any())).thenThrow(RuntimeException("constraint violation"))

        assertDoesNotThrow { handler.onInvoiceFinalized(InvoiceFinalized(6L, "RE-2026-0006")) }
    }

    // ── The deployment-level llmAnalysisFeatureConfig switch ────────────────────────

    @Test
    fun `onInvoiceFinalized skips silently and creates nothing when the deployment-level feature is disabled`() {
        val disabledHandler = InvoiceLlmAnalysisEventHandler(
            invoiceRepository, llmAnalysisRepository, LlmAnalysisFeatureConfig(enabled = false),
        )

        disabledHandler.onInvoiceFinalized(InvoiceFinalized(7L, "RE-2026-0007"))

        verify(llmAnalysisRepository, never()).findByInvoiceId(any())
        verify(invoiceRepository, never()).findById(any())
        verify(llmAnalysisRepository, never()).save(any())
    }

    @Test
    fun `onInvoiceFinalized proceeds normally when the deployment-level feature is explicitly enabled`() {
        val enabledHandler = InvoiceLlmAnalysisEventHandler(
            invoiceRepository, llmAnalysisRepository, LlmAnalysisFeatureConfig(enabled = true),
        )
        whenever(llmAnalysisRepository.findByInvoiceId(8L)).thenReturn(emptyList())
        whenever(invoiceRepository.findById(8L)).thenReturn(invoice(8L))
        whenever(llmAnalysisRepository.save(any())).thenAnswer { it.arguments[0] }

        enabledHandler.onInvoiceFinalized(InvoiceFinalized(8L, "RE-2026-0008"))

        val captor = argumentCaptor<InvoiceLlmAnalysis>()
        verify(llmAnalysisRepository).save(captor.capture())
        assertEquals(LlmAnalysisStatus.PENDING, captor.firstValue.status)
    }

    /** Reads the actually configured model name via the class's own behavior (instead of
     *  guessing it): an empty analysis list results in exactly one `save()` call, whose
     *  `modelName` is the configured value. Runs over its own, isolated fakes -- otherwise the
     *  probe calls would count toward the `verify(...)` assertions of the actual test, which
     *  reuses the same repository mocks. */
    private fun configuredModelName(): String {
        val probeInvoiceId = -1L
        val probeInvoiceRepository = mock<InvoiceRepository>()
        val probeLlmAnalysisRepository = mock<InvoiceLlmAnalysisRepository>()
        val local = InvoiceLlmAnalysisEventHandler(probeInvoiceRepository, probeLlmAnalysisRepository)
        whenever(probeLlmAnalysisRepository.findByInvoiceId(probeInvoiceId)).thenReturn(emptyList())
        whenever(probeInvoiceRepository.findById(probeInvoiceId)).thenReturn(invoice(probeInvoiceId))
        val captor = argumentCaptor<InvoiceLlmAnalysis>()
        whenever(probeLlmAnalysisRepository.save(captor.capture())).thenAnswer { it.arguments[0] }

        local.onInvoiceFinalized(InvoiceFinalized(probeInvoiceId, "PROBE"))
        return captor.firstValue.modelName
    }
}

/**
 * Every branch of [resolveAnalysisModel], the precedence/parsing logic behind
 * [InvoiceLlmAnalysisEventHandler.analysisModels], exercised directly with explicit strings
 * instead of `System.getenv` (which cannot vary per test case).
 */
class ResolveAnalysisModelTest {

    @Test
    fun `MLX_MODEL wins outright when non-blank, MLX_MODELS is never even consulted`() {
        assertEquals("primary-model", resolveAnalysisModel("primary-model", "should-not-be-used"))
    }

    @Test
    fun `a blank MLX_MODEL falls back to MLX_MODELS`() {
        assertEquals("fallback-model", resolveAnalysisModel("   ", "fallback-model"))
    }

    @Test
    fun `a null MLX_MODEL falls back to MLX_MODELS`() {
        assertEquals("fallback-model", resolveAnalysisModel(null, "fallback-model"))
    }

    @Test
    fun `MLX_MODELS is split on comma and only the first entry is used, trimmed`() {
        assertEquals("first-model", resolveAnalysisModel(null, " first-model , second-model"))
    }

    @Test
    fun `a null MLX_MODEL and a null MLX_MODELS fall back to the hardcoded default`() {
        assertEquals("Qwen3.6-35B-A3B-8bit", resolveAnalysisModel(null, null))
    }

    @Test
    fun `both env values blank or absent fall back to the hardcoded default`() {
        assertEquals("Qwen3.6-35B-A3B-8bit", resolveAnalysisModel(null, null))
        assertEquals("Qwen3.6-35B-A3B-8bit", resolveAnalysisModel("", ""))
    }

    @Test
    fun `an MLX_MODELS first entry that is empty after trimming also falls back to the hardcoded default`() {
        // ",second-model" -> split gives ["", "second-model"] -> firstOrNull() is "" -> trim() is
        // still "" -> takeIf { isNotEmpty() } is null: the second entry is deliberately never
        // reached, matching the KDoc's "first entry wins" contract even when that entry is empty.
        assertEquals("Qwen3.6-35B-A3B-8bit", resolveAnalysisModel(null, ",second-model"))
    }
}
