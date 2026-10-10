package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.assertj.core.api.Assertions.assertThat
import java.time.LocalDate

/**
 * Tests for DocumentKeyRules, which define the storage key structure for invoices.
 */
class DocumentKeyRulesTest {

    // ── buildDocumentKey ───────────────────────────────────────────────────────

    @Test
    fun `buildDocumentKey creates year-based path from invoice date`() {
        val date = LocalDate.of(2026, 8, 27)
        val key = DocumentKeyRules.buildDocumentKey(date, "invoice_12345.pdf")
        assertEquals("documents/2026/invoice_12345.pdf", key)
    }

    @Test
    fun `buildDocumentKey uses invoice date year, not current year`() {
        val pastDate = LocalDate.of(2020, 1, 1)
        val key = DocumentKeyRules.buildDocumentKey(pastDate, "old-invoice.pdf")
        assertEquals("documents/2020/old-invoice.pdf", key)
    }

    @Test
    fun `buildDocumentKey preserves filename exactly`() {
        val date = LocalDate.of(2025, 12, 31)
        val filename = "invoice-2025_final_v3.xml"
        val key = DocumentKeyRules.buildDocumentKey(date, filename)
        assertEquals("documents/2025/invoice-2025_final_v3.xml", key)
    }

    @Test
    fun `buildDocumentKey handles filenames with multiple dots`() {
        val date = LocalDate.of(2026, 8, 27)
        val key = DocumentKeyRules.buildDocumentKey(date, "export.2026-08-27.final.pdf")
        assertEquals("documents/2026/export.2026-08-27.final.pdf", key)
    }

    // ── yearPrefix ─────────────────────────────────────────────────────────────

    @Test
    fun `yearPrefix creates correct prefix for year`() {
        assertEquals("documents/2026/", DocumentKeyRules.yearPrefix(2026))
    }

    @Test
    fun `yearPrefix works for any year`() {
        assertEquals("documents/1999/", DocumentKeyRules.yearPrefix(1999))
        assertEquals("documents/2099/", DocumentKeyRules.yearPrefix(2099))
    }

    // ── deriveExportKeys ───────────────────────────────────────────────────────

    @Test
    fun `deriveExportKeys creates four keys with same base name but different extensions`() {
        val keys = DocumentKeyRules.deriveExportKeys("INV-2026-00001")

        assertEquals("invoices/INV-2026-00001.pdf", keys.pdfObjectKey)
        assertEquals("invoices/INV-2026-00001.xml", keys.xmlObjectKey)
        assertEquals("invoices/INV-2026-00001-llm-report.md", keys.llmReportKey)
        assertEquals("invoices/INV-2026-00001.tar.gz", keys.archiveObjectKey)
    }

    // ── archiveObjectKey ──────────────────────────────────────────────────

    @Test
    fun `RS-9 - deriveExportKeys archiveObjectKey shares the pdf-xml base name`() {
        val keys = DocumentKeyRules.deriveExportKeys("2026/INV/00001")

        assertEquals("invoices/2026-INV-00001.tar.gz", keys.archiveObjectKey)
    }

    @Test
    fun `RS-9 - deriveExportKeys archiveObjectKey for a draft (blank number) uses the invoiceId fallback too`() {
        val keys = DocumentKeyRules.deriveExportKeys("", invoiceId = 42L)

        assertEquals("invoices/draft-42.tar.gz", keys.archiveObjectKey)
    }

    @Test
    fun `deriveExportKeys replaces slashes in invoice number`() {
        val keys = DocumentKeyRules.deriveExportKeys("2026/INV/00001")

        assertEquals("invoices/2026-INV-00001.pdf", keys.pdfObjectKey)
        assertEquals("invoices/2026-INV-00001.xml", keys.xmlObjectKey)
        assertEquals("invoices/2026-INV-00001-llm-report.md", keys.llmReportKey)
    }

    @Test
    fun `deriveExportKeys handles invoice numbers with no slashes`() {
        val keys = DocumentKeyRules.deriveExportKeys("INV20260827001")

        assertEquals("invoices/INV20260827001.pdf", keys.pdfObjectKey)
        assertEquals("invoices/INV20260827001.xml", keys.xmlObjectKey)
        assertEquals("invoices/INV20260827001-llm-report.md", keys.llmReportKey)
    }

    @Test
    fun `deriveExportKeys handles invoice numbers with multiple slashes`() {
        val keys = DocumentKeyRules.deriveExportKeys("DE/2026/123/45")

        assertEquals("invoices/DE-2026-123-45.pdf", keys.pdfObjectKey)
        assertEquals("invoices/DE-2026-123-45.xml", keys.xmlObjectKey)
        assertEquals("invoices/DE-2026-123-45-llm-report.md", keys.llmReportKey)
    }

    @Test
    fun `deriveExportKeys all three keys share the same base name`() {
        val keys = DocumentKeyRules.deriveExportKeys("INV-2026/00001")

        val baseName = "invoices/INV-2026-00001"
        assertThat(keys.pdfObjectKey).startsWith(baseName)
        assertThat(keys.xmlObjectKey).startsWith(baseName)
        assertThat(keys.llmReportKey).startsWith(baseName)
    }

    @Test
    fun `deriveExportKeys LLM report key has distinctive suffix`() {
        val keys = DocumentKeyRules.deriveExportKeys("INV-2026-00001")

        assertTrue(keys.llmReportKey.endsWith("-llm-report.md"))
        assertThat(keys.llmReportKey).contains("llm-report")
    }

    // ── deriveExportKeys, blank/unassigned invoice number (draft-stage exports, P-a) ─────────

    @Test
    fun `deriveExportKeys with blank invoice number derives base name from invoiceId`() {
        val keys = DocumentKeyRules.deriveExportKeys("", invoiceId = 42L)

        assertEquals("invoices/draft-42.pdf", keys.pdfObjectKey)
        assertEquals("invoices/draft-42.xml", keys.xmlObjectKey)
        assertEquals("invoices/draft-42-llm-report.md", keys.llmReportKey)
    }

    @Test
    fun `deriveExportKeys with blank invoice number never collides across different invoiceIds`() {
        val keysA = DocumentKeyRules.deriveExportKeys("", invoiceId = 1L)
        val keysB = DocumentKeyRules.deriveExportKeys("", invoiceId = 2L)

        assertThat(keysA.pdfObjectKey).isNotEqualTo(keysB.pdfObjectKey)
        assertThat(keysA.xmlObjectKey).isNotEqualTo(keysB.xmlObjectKey)
        assertThat(keysA.llmReportKey).isNotEqualTo(keysB.llmReportKey)
    }

    @Test
    fun `deriveExportKeys with blank invoice number and null invoiceId throws`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            DocumentKeyRules.deriveExportKeys("")
        }
    }

    // ── deriveJobExportKeys (unconditional per-attempt keys, never overwrite) ──────────────

    private fun ExportKeys.all() = listOf(pdfObjectKey, xmlObjectKey, llmReportKey, archiveObjectKey)

    @Test
    fun `issued export always gets the per-job per-attempt suffix - even the first job`() {
        val keys = DocumentKeyRules.deriveJobExportKeys("RE/1", 1L, "10", draft = false, attempt = "a1")
        assertEquals("invoices/RE-1-10-a1.pdf", keys.pdfObjectKey)
        assertEquals("invoices/RE-1-10-a1.xml", keys.xmlObjectKey)
        assertEquals("invoices/RE-1-10-a1.tar.gz", keys.archiveObjectKey)
        assertEquals("invoices/RE-1-10-a1-llm-report.md", keys.llmReportKey)
        assertThat(keys.all()).doesNotContainAnyElementsOf(DocumentKeyRules.deriveExportKeys("RE/1", 1L).all())
    }

    @Test
    fun `two jobs of an issued invoice never share a key - race scenario`() {
        val first = DocumentKeyRules.deriveJobExportKeys("RE-1", 1L, "10", draft = false)
        val second = DocumentKeyRules.deriveJobExportKeys("RE-1", 1L, "11", draft = false)
        val all = first.all() + second.all()
        assertEquals(all.size, all.toSet().size, "every job writes into its own objects")
    }

    @Test
    fun `redelivery of the same job id never rewrites the first attempt's objects`() {
        val first = DocumentKeyRules.deriveJobExportKeys("RE-1", 1L, "10", draft = false)
        val redelivered = DocumentKeyRules.deriveJobExportKeys("RE-1", 1L, "10", draft = false)
        assertThat(redelivered.all()).doesNotContainAnyElementsOf(first.all())
    }

    @Test
    fun `a draft never uses the issued base name even when its number is already assigned`() {
        val keys = DocumentKeyRules.deriveJobExportKeys("RE-1", 7L, "11", draft = true, attempt = "a1")
        assertEquals("invoices/draft/7-11-a1.pdf", keys.pdfObjectKey)
        keys.all().forEach { assertTrue(it.startsWith("invoices/draft/"), it) }
        assertThat(keys.all()).noneMatch { it.startsWith("invoices/RE-1") }
    }

    @Test
    fun `a draft with blank number is keyed by invoice id and a repeat draft job never overwrites`() {
        val a = DocumentKeyRules.deriveJobExportKeys("", 7L, "11", draft = true)
        val b = DocumentKeyRules.deriveJobExportKeys("", 7L, "12", draft = true)
        assertTrue(a.pdfObjectKey.startsWith("invoices/draft/7-11-"))
        assertThat(b.all()).doesNotContainAnyElementsOf(a.all())
    }

    @Test
    fun `issued export without invoice number falls back to the id-based base name, still suffixed`() {
        val keys = DocumentKeyRules.deriveJobExportKeys("", 1L, "10", draft = false, attempt = "a1")
        assertEquals("invoices/draft-1-10-a1.pdf", keys.pdfObjectKey)
    }

    @Test
    fun `job id and attempt are sanitized for use inside a key`() {
        val keys = DocumentKeyRules.deriveJobExportKeys("RE-1", 1L, "a/b c", draft = false, attempt = "x.y")
        assertEquals("invoices/RE-1-a_b_c-x_y.pdf", keys.pdfObjectKey)
    }

    @Test
    fun `a blank job id is rejected`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            DocumentKeyRules.deriveJobExportKeys("INV-1", 1L, " ", draft = false)
        }
    }

    @Test
    fun `isIssuedJobPdfKey matches exactly this invoice's per-attempt PDFs`() {
        val own = DocumentKeyRules.deriveJobExportKeys("RE-1", 1L, "10", draft = false)
        val longerNumber = DocumentKeyRules.deriveJobExportKeys("RE-1-5", 2L, "77", draft = false)
        val draft = DocumentKeyRules.deriveJobExportKeys("RE-1", 1L, "11", draft = true)

        assertTrue(DocumentKeyRules.isIssuedJobPdfKey(own.pdfObjectKey, "RE-1", 1L))
        assertTrue(longerNumber.pdfObjectKey.startsWith(DocumentKeyRules.issuedJobExportPrefix("RE-1", 1L)))
        assertEquals(false, DocumentKeyRules.isIssuedJobPdfKey(longerNumber.pdfObjectKey, "RE-1", 1L))
        assertEquals(false, DocumentKeyRules.isIssuedJobPdfKey(own.xmlObjectKey, "RE-1", 1L))
        assertEquals(false, DocumentKeyRules.isIssuedJobPdfKey(draft.pdfObjectKey, "RE-1", 1L))
    }
}
