package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * PO decision 4 — [composeTaxNote] over the testmatrix.md three-column shape (German
 * document / English with text / English without text -> German) crossed with
 * (German text present/blank) x (VATEX code / category only / neither). The golden rule this
 * package must not violate: every `de` case stays byte-identical to today's pre-existing
 * `ifBlank` fallback, so the `language == "de"` cases below pin exact strings, not just
 * non-blank-ness.
 */
class TaxNoteComposerTest {

    private val german = "Steuerschuldnerschaft aus § 3a Abs. 2 UStG."

    // ── German documents — must never move, regardless of code/category ───────────────────

    @Test
    fun `de - a present German text is returned unchanged, VATEX code ignored`() {
        assertEquals(german, composeTaxNote(german, TaxExemptionClassification("AE", "VATEX-EU-AE"), "de", separator = "\n"))
    }

    @Test
    fun `de - a blank German text falls back to the German standard text for the VATEX code`() {
        assertEquals(
            "Steuerschuldnerschaft des Leistungsempfängers (Reverse Charge).",
            composeTaxNote(null, TaxExemptionClassification("AE", "VATEX-EU-AE"), "de", separator = "\n"),
        )
    }

    @Test
    fun `de - a blank German text falls back to the German standard text for the category alone`() {
        assertEquals(
            "Nicht steuerbarer Umsatz (Leistungsort im Ausland).",
            composeTaxNote("   ", TaxExemptionClassification("O", null), "de", separator = "\n"),
        )
    }

    @Test
    fun `de - category S has no standard text - blank in, null out, never an empty string`() {
        assertNull(composeTaxNote(null, TaxExemptionClassification("S", null), "de", separator = "\n"))
        assertNull(composeTaxNote("", TaxExemptionClassification("S", null), "de", separator = "\n"))
    }

    @Test
    fun `de - an unknown code and category also resolve to nothing`() {
        assertNull(composeTaxNote(null, TaxExemptionClassification("Z", "VATEX-EU-G"), "de", separator = "\n"))
    }

    // ── English documents, text present — German original plus the English standard text ──

    @Test
    fun `en - VATEX code present wins over the category text`() {
        val result = composeTaxNote(german, TaxExemptionClassification("AE", "VATEX-EU-AE"), "en", separator = "\n")
        assertEquals(german + "\n" + "Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).", result)
    }

    @Test
    fun `en - unknown code falls back to the category text`() {
        val result = composeTaxNote(german, TaxExemptionClassification("AE", "VATEX-EU-UNKNOWN"), "en", separator = "\n")
        assertEquals(german + "\n" + "Reverse charge: the recipient of the service is liable for VAT.", result)
    }

    @Test
    fun `en - wire separator is used verbatim instead of a newline`() {
        val result = composeTaxNote(german, TaxExemptionClassification("AE", "VATEX-EU-AE"), "en", separator = " — ")
        assertEquals(german + " — " + "Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).", result)
    }

    @Test
    fun `en - category E resolves its own standard text`() {
        val result = composeTaxNote("Steuerbefreite Leistung — § 4 UStG.", TaxExemptionClassification("E", null), "en", separator = "\n")
        assertEquals("Steuerbefreite Leistung — § 4 UStG.\nTax exempt supply.", result)
    }

    @Test
    fun `en - category O resolves its own standard text`() {
        val result = composeTaxNote("Nicht steuerbarer Umsatz.", TaxExemptionClassification("O", null), "en", separator = "\n")
        assertEquals("Nicht steuerbarer Umsatz.\nNot subject to VAT (place of supply outside Germany).", result)
    }

    // ── English documents, text missing — the border case that is production behaviour ────

    @Test
    fun `en - a blank German text yields the English standard text alone, no dangling separator`() {
        val result = composeTaxNote(null, TaxExemptionClassification("AE", "VATEX-EU-AE"), "en", separator = "\n")
        assertEquals("Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).", result)
        assertEquals(false, result!!.startsWith("\n") || result.endsWith("\n"))
    }

    @Test
    fun `en - blank German text and blank-only whitespace both count as absent`() {
        assertEquals(
            composeTaxNote(null, TaxExemptionClassification("AE", "VATEX-EU-AE"), "en", separator = "\n"),
            composeTaxNote("   ", TaxExemptionClassification("AE", "VATEX-EU-AE"), "en", separator = "\n"),
        )
    }

    @Test
    fun `en - category S - neither text exists, result is null, never an empty parenthesis`() {
        assertNull(composeTaxNote(null, TaxExemptionClassification("S", null), "en", separator = "\n"))
        assertNull(composeTaxNote("", TaxExemptionClassification("S", null), "en", separator = "\n"))
    }

    @Test
    fun `en - no standard text for the code or category - the German text alone, no separator appended`() {
        val result = composeTaxNote(german, TaxExemptionClassification("Z", "VATEX-EU-G"), "en", separator = "\n")
        assertEquals(german, result)
    }

    @Test
    fun `en - both German text and standard text absent - null, not an empty string`() {
        assertNull(composeTaxNote(null, TaxExemptionClassification("Z", "VATEX-EU-G"), "en", separator = "\n"))
    }
}
