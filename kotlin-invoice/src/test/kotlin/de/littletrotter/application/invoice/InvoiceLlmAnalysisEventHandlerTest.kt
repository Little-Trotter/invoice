package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.recordLlmAnalysis
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `InvoiceLlmAnalysisEventHandler.onInvoiceFinalized` against a fake repository: the handler
 * runs asynchronously after finalization, so it must never let an exception propagate outward
 * (class KDoc: "failures are logged but do not affect the finalization transaction"). The
 * `analysisModels` list itself depends on `System.getenv("MLX_MODEL"/"MLX_MODELS")` and cannot
 * be pinned to a specific value without a test seam -- the tests here therefore deliberately
 * check only that *some* model name is used consistently, not which one exactly.
 */
class InvoiceLlmAnalysisEventHandlerTest {

    private val invoiceRepository = mock<InvoiceRepository>()
    private val handler = InvoiceLlmAnalysisEventHandler(invoiceRepository)

    private fun invoice(id: Long): Invoice = anInvoice(id = id).also { whenever(invoiceRepository.findById(id)).thenReturn(it) }

    @Test
    fun `onInvoiceFinalized legt eine neue PENDING-Analyse an wenn noch keine fuer das Modell existiert`() {
        val invoice = invoice(1L)

        handler.onInvoiceFinalized(InvoiceFinalized(1L, "RE-2026-0001"))

        assertEquals(LlmAnalysisStatus.PENDING, invoice.llmAnalysisList.single().status)
    }

    @Test
    fun `onInvoiceFinalized ueberspringt ein Modell dessen Analyse bereits abgeschlossen (nicht PENDING) ist`() {
        val invoice = invoice(2L)
        // The model name of the already-completed analysis must match the actually configured
        // one for the skip branch to trigger -- we therefore read it out of the handler's own
        // behavior instead of guessing it (see the class KDoc on analysisModels).
        val done = invoice.recordLlmAnalysis(configuredModelName())
        done.edit { status = LlmAnalysisStatus.COMPLETED }

        handler.onInvoiceFinalized(InvoiceFinalized(2L, "RE-2026-0002"))

        assertSame(done, invoice.llmAnalysisList.single())
        assertEquals(LlmAnalysisStatus.COMPLETED, done.status)
    }

    @Test
    fun `onInvoiceFinalized aktualisiert eine bestehende PENDING-Analyse statt eine zweite anzulegen`() {
        val invoice = invoice(3L)
        val existing = invoice.recordLlmAnalysis(configuredModelName())

        handler.onInvoiceFinalized(InvoiceFinalized(3L, "RE-2026-0003"))

        assertSame(existing, invoice.llmAnalysisList.single(), "eine noch offene PENDING-Analyse wird wiederverwendet, nicht dupliziert")
        assertEquals(LlmAnalysisStatus.PENDING, existing.status)
    }

    @Test
    fun `onInvoiceFinalized ueberspringt ein Modell still wenn die Rechnung nicht mehr gefunden wird`() {
        whenever(invoiceRepository.findById(4L)).thenReturn(null)

        assertDoesNotThrow { handler.onInvoiceFinalized(InvoiceFinalized(4L, "RE-2026-0004")) }
    }

    @Test
    fun `onInvoiceFinalized faengt jede Ausnahme ab statt sie an den asynchronen Aufrufer durchzureichen`() {
        // Class KDoc: "Runs in a separate thread – failures are logged but do not affect the
        // finalization transaction." A repository failure must therefore never escape the
        // method.
        whenever(invoiceRepository.findById(5L)).thenThrow(RuntimeException("DB down"))

        assertDoesNotThrow { handler.onInvoiceFinalized(InvoiceFinalized(5L, "RE-2026-0005")) }
    }

    // ── The deployment-level llmAnalysisFeatureConfig switch ────────────────────────

    @Test
    fun `onInvoiceFinalized skips silently and creates nothing when the deployment-level feature is disabled`() {
        val disabledHandler = InvoiceLlmAnalysisEventHandler(invoiceRepository, LlmAnalysisFeatureConfig(enabled = false))

        disabledHandler.onInvoiceFinalized(InvoiceFinalized(7L, "RE-2026-0007"))

        verify(invoiceRepository, never()).findById(any())
    }

    @Test
    fun `onInvoiceFinalized proceeds normally when the deployment-level feature is explicitly enabled`() {
        val enabledHandler = InvoiceLlmAnalysisEventHandler(invoiceRepository, LlmAnalysisFeatureConfig(enabled = true))
        val invoice = invoice(8L)

        enabledHandler.onInvoiceFinalized(InvoiceFinalized(8L, "RE-2026-0008"))

        assertEquals(LlmAnalysisStatus.PENDING, invoice.llmAnalysisList.single().status)
    }

    /** Reads the actually configured model name via the class's own behavior (instead of
     *  guessing it): an invoice without analyses ends up with exactly one, whose `modelName`
     *  is the configured value. Runs over its own, isolated fakes. */
    private fun configuredModelName(): String {
        val probeInvoiceRepository = mock<InvoiceRepository>()
        val probe = anInvoice(id = -1L)
        whenever(probeInvoiceRepository.findById(-1L)).thenReturn(probe)

        InvoiceLlmAnalysisEventHandler(probeInvoiceRepository).onInvoiceFinalized(InvoiceFinalized(-1L, "PROBE"))
        return probe.llmAnalysisList.single().modelName
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
