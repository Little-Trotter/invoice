package de.littletrotter.application.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * COV — [extractResultRef]/[derivePdfObjectKey]/[deriveXmlObjectKey] had no direct unit test
 * before this AP, reachable before only incidentally through [PrintJobOverviewQueryServiceImpl]'s
 * row projection. Mirrors [de.littletrotter.ui.view.invoice.parseGenerationJobResultRef]'s own
 * regex/fallback contract 1:1 (see this file's own KDoc on why the duplicate exists), so the same
 * scenarios apply here.
 */
class GenerationJobResultRefsTest {

    @Test
    fun `extractResultRef returns null for a null resultRefs string`() {
        assertNull(extractResultRef(null, "objectKey"))
    }

    @Test
    fun `extractResultRef returns null for a blank resultRefs string`() {
        assertNull(extractResultRef("   ", "objectKey"))
    }

    @Test
    fun `extractResultRef finds the first matching key among several candidates`() {
        val refs = """{"xmlObjectKey":"invoices/RE-1.xml","otherKey":"ignored"}"""
        assertEquals("invoices/RE-1.xml", extractResultRef(refs, "zugferdObjectKey", "xmlObjectKey", "xrechnungObjectKey"))
    }

    @Test
    fun `extractResultRef prefers the earlier vararg key when both are present`() {
        val refs = """{"zugferdObjectKey":"invoices/RE-1.xml","xmlObjectKey":"invoices/RE-1-legacy.xml"}"""
        assertEquals("invoices/RE-1.xml", extractResultRef(refs, "zugferdObjectKey", "xmlObjectKey"))
    }

    @Test
    fun `extractResultRef returns null when none of the keys are present`() {
        val refs = """{"unrelatedKey":"value"}"""
        assertNull(extractResultRef(refs, "objectKey", "xmlObjectKey"))
    }

    @Test
    fun `extractResultRef treats a regex-meaningful key literally via escaping`() {
        val refs = """{"a.b":"literal-dot-key"}"""
        assertEquals("literal-dot-key", extractResultRef(refs, "a.b"))
        assertNull(extractResultRef(refs, "axb"), "the escaped dot must not act as a regex wildcard")
    }

    @Test
    fun `derivePdfObjectKey reads the objectKey entry`() {
        assertEquals("invoices/RE-1.pdf", derivePdfObjectKey("""{"objectKey":"invoices/RE-1.pdf"}"""))
    }

    @Test
    fun `derivePdfObjectKey returns null when resultRefs carries no objectKey`() {
        assertNull(derivePdfObjectKey("""{"xmlObjectKey":"invoices/RE-1.xml"}"""))
        assertNull(derivePdfObjectKey(null))
    }

    @Test
    fun `deriveXmlObjectKey reads the zugferdObjectKey entry`() {
        assertEquals("invoices/RE-1.xml", deriveXmlObjectKey("""{"zugferdObjectKey":"invoices/RE-1.xml"}"""))
    }

    @Test
    fun `deriveXmlObjectKey returns null when resultRefs is null or blank`() {
        assertNull(deriveXmlObjectKey(null))
        assertNull(deriveXmlObjectKey("   "))
    }

    @Test
    fun `deriveXmlObjectKey returns null when zugferdObjectKey is absent`() {
        assertNull(deriveXmlObjectKey("""{"unrelated":"x"}"""))
    }

    @Test
    fun `N12 - deriveXmlObjectKey returns null for an empty zugferdObjectKey -- no pdf-key derivation, no XML button`() {
        // Coordinator review (2026-09-11): the empty-string case is the rejected-Factur-X-engine
        // outcome (PdfExportServiceImpl.completeRejectedFacturXExport) -- there is genuinely no
        // XML to show. Deriving one from the pdf key would render a button for an object that was
        // never written; there must be no fallback here at all.
        assertNull(deriveXmlObjectKey("""{"zugferdObjectKey":""}"""))
    }

    @Test
    fun `N12 - deriveXmlObjectKey no longer recognizes the removed legacy key names -- the worker never wrote them`() {
        // xmlObjectKey/xrechnungObjectKey/xml were dead fallback key names (JobConsumer
        // .publishCompletedResult in kotlin-xpdf never wrote any of them); removed as dead code,
        // together with the .pdf -> .xml derivation fallback (see the test above).
        assertNull(deriveXmlObjectKey("""{"xmlObjectKey":"x.xml"}"""))
        assertNull(deriveXmlObjectKey("""{"xrechnungObjectKey":"x.xml"}"""))
        assertNull(deriveXmlObjectKey("""{"xml":"plain.xml"}"""))
    }
}
