package de.littletrotter.shared.documents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [DocumentTexts] must behave exactly like kotlin-xpdf's `Labels`: two bundles
 * loaded once, a missing key/language throws rather than silently falling back to the other
 * language. [DocumentDates] pins the same two per-language date shapes the print image uses.
 */
class DocumentTextsTest {

    @Test
    fun `text resolves the German and English cancellation keys already shipped in both bundles`() {
        assertEquals("Grund: {0}", DocumentTexts.text("storno.reason", "de"))
        assertEquals("Reason: {0}", DocumentTexts.text("storno.reason", "en"))
    }

    @Test
    fun `format substitutes placeholders per language`() {
        assertEquals(
            "Stornierung der Rechnung RE-2026-0042 vom 15.08.2026.",
            DocumentTexts.format("storno.reference", "de", "RE-2026-0042", "15.08.2026"),
        )
        assertEquals(
            "Reversal of invoice RE-2026-0042 dated 15 Aug 2026.",
            DocumentTexts.format("storno.reference", "en", "RE-2026-0042", "15 Aug 2026"),
        )
    }

    @Test
    fun `format on the no-date variant takes only the invoice number`() {
        assertEquals(
            "Stornierung der Rechnung RE-2026-0042.",
            DocumentTexts.format("storno.reference.noDate", "de", "RE-2026-0042"),
        )
        assertEquals(
            "Reversal of invoice RE-2026-0042.",
            DocumentTexts.format("storno.reference.noDate", "en", "RE-2026-0042"),
        )
    }

    @Test
    fun `the reason is carried verbatim, never reformatted`() {
        assertEquals(
            "Grund: Kunde hat storniert — Doppelbuchung!",
            DocumentTexts.format("storno.reason", "de", "Kunde hat storniert — Doppelbuchung!"),
        )
    }

    @Test
    fun `text throws on a key missing from both bundles rather than falling back`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            DocumentTexts.text("no.such.key", "de")
        }
        assertEquals(
            "Missing document text 'no.such.key' for language 'de' — both " +
                "document-texts_*.properties must carry every key.",
            ex.message,
        )
    }

    @Test
    fun `text throws on an unknown language rather than defaulting to German`() {
        assertThrows(IllegalStateException::class.java) {
            DocumentTexts.text("storno.reason", "fr")
        }
    }

    @Test
    fun `DocumentDates formats German as dd MM yyyy and English as d MMM yyyy`() {
        val date = LocalDate.of(2026, 8, 15)
        assertEquals("15.08.2026", DocumentDates.format(date, "de"))
        assertEquals("15 Aug 2026", DocumentDates.format(date, "en"))
    }

    // load()'s own missing-classpath-resource guard: both real bundle files always ship
    // on this project's classpath, so only a bogus filename passed directly to the now-internal
    // load() can actually drive it.
    @Test
    fun `load throws when the named bundle file is missing from the classpath`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            DocumentTexts.load("does-not-exist-for-cov-23.properties")
        }
        assertEquals(
            "Document text bundle does-not-exist-for-cov-23.properties not found on classpath",
            ex.message,
        )
    }

    @Test
    fun `DocumentDates falls back to German formatting for an unrecognised language code`() {
        // Mirrors InvoiceReportContextBuilder.resolveRenderLanguage's own convention: anything
        // that is not exactly "en" renders German, never a third shape.
        val date = LocalDate.of(2026, 1, 5)
        assertEquals("05.01.2026", DocumentDates.format(date, "de-AT"))
    }
}
