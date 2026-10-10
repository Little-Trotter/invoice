package de.littletrotter.ui.view.invoice

import de.littletrotter.shared.catalogue.InvoiceItemUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

/**
 * [LineAdvisor] is purely advisory: every case here only asserts which [LineAdvice]
 * codes fire, never anything that could block a save. Both checks are deliberately conservative
 * (Definition of Done: "prefer misses over noise") — the negative cases below are exactly the
 * "looks similar but must not fire" cases the patterns are guarded against.
 */
class LineAdvisorTest {

    private fun quantityCodes(description: String, unit: InvoiceItemUnit, quantity: String) =
        LineAdvisor.advise(description, unit, BigDecimal(quantity)).map { it.code }

    private fun descriptionCodes(description: String) =
        // unit/quantity irrelevant to the BT-153 checks: HOUR/2 never matches the single-unit-line
        // condition of check #3.
        LineAdvisor.advise(description, InvoiceItemUnit.HOUR, BigDecimal("2")).map { it.code }

    // ── #2 covered: BT153_BARE_CODE — pure digits or a short letter+number article-style code ──

    @ParameterizedTest(name = "bare code fires: \"{0}\"")
    @ValueSource(strings = ["4711", "12345", "XY-4711", "ABC1234", "K99"])
    fun `covered - bare digits or short article-style codes trigger BT153_BARE_CODE`(description: String) {
        assertEquals(listOf(LineAdvisor.BT153_BARE_CODE), descriptionCodes(description))
    }

    // ── #2 covered: BT153_REFERENCE_ONLY — the whole description is only a reference clause ──

    @ParameterizedTest(name = "reference-only fires: \"{0}\"")
    @ValueSource(
        strings = [
            "Wartung lt. Wartungsvertrag XYZ12345",
            "lt. Angebot 4711",
            "gemäß Auftrag 123",
            "siehe Anlage 2",
            "Leistungen gemäß Vertrag 998",
            "Leistung lt. Rahmenvertrag 42",
        ],
    )
    fun `covered - a bare reference clause triggers BT153_REFERENCE_ONLY`(description: String) {
        assertEquals(listOf(LineAdvisor.BT153_REFERENCE_ONLY), descriptionCodes(description))
    }

    // ── #2 uncovered on purpose: real descriptions that merely mention a reference elsewhere ──

    @ParameterizedTest(name = "no BT-153 advice for: \"{0}\"")
    @ValueSource(
        strings = [
            // the spec's own corrected example — must never fire
            "Wartung der Heizungsanlage (lt. Wartungsvertrag XYZ12345)",
            "Softwareentwicklung Feature X, Zeitraum 03.-07.03.2025",
            "Beratung zur Sanierung laut Absprache am Telefon",
            "Hydraulikpumpe Typ X",
            "Musterstraße 12345",
        ],
    )
    fun `uncovered on purpose - real descriptions with more than a bare reference do not fire`(description: String) {
        assertTrue(descriptionCodes(description).none { it == LineAdvisor.BT153_BARE_CODE || it == LineAdvisor.BT153_REFERENCE_ONLY })
    }

    @ParameterizedTest(name = "blank description \"{0}\" fires nothing")
    @ValueSource(strings = ["", "   "])
    fun `uncovered on purpose - a blank description fires no BT-153 advice`(description: String) {
        assertTrue(descriptionCodes(description).isEmpty())
    }

    // ── #3 covered: QUANTITY_UNIT_MISMATCH — "30 h" (etc.) billed as 1 Stück/Pauschale ──

    @ParameterizedTest(name = "quantity/unit mismatch fires: \"{0}\"")
    @ValueSource(
        strings = [
            "30 h Beratung",
            "10 Std. Wartung",
            "10 Std Wartung",
            "40 Stunden Programmierung",
            "120 km Anfahrt",
            "50 kg Material",
            "2 t Schüttgut",
            "5 qm Bodenbelag",
        ],
    )
    fun `covered - a quantity+unit expression in the text while billed 1 Stueck fires QUANTITY_UNIT_MISMATCH`(description: String) {
        assertEquals(listOf(LineAdvisor.QUANTITY_UNIT_MISMATCH), quantityCodes(description, InvoiceItemUnit.PIECE, "1"))
    }

    @org.junit.jupiter.api.Test
    fun `covered - the same text billed 1 Pauschale also fires QUANTITY_UNIT_MISMATCH`() {
        assertEquals(listOf(LineAdvisor.QUANTITY_UNIT_MISMATCH), quantityCodes("30 h Beratung", InvoiceItemUnit.FLAT_RATE, "1"))
    }

    // ── #3 uncovered on purpose: the quantity/unit already matches, or the line isn't a 1-off ──

    @org.junit.jupiter.api.Test
    fun `uncovered on purpose - the same text billed in HOUR unit does not fire (already the right unit)`() {
        assertTrue(quantityCodes("30 h Beratung", InvoiceItemUnit.HOUR, "30").none { it == LineAdvisor.QUANTITY_UNIT_MISMATCH })
    }

    @org.junit.jupiter.api.Test
    fun `uncovered on purpose - 1 Stueck with a quantity of 2 does not fire (not a single-unit line)`() {
        assertTrue(quantityCodes("30 h Beratung", InvoiceItemUnit.PIECE, "2").none { it == LineAdvisor.QUANTITY_UNIT_MISMATCH })
    }

    @org.junit.jupiter.api.Test
    fun `uncovered on purpose - 1 Stueck with no quantity+unit wording does not fire`() {
        assertTrue(quantityCodes("Beratung Projekt Alpha", InvoiceItemUnit.PIECE, "1").isEmpty())
    }

    @org.junit.jupiter.api.Test
    fun `uncovered on purpose - a plain number without a recognised unit does not fire (avoids false positives like invoice or order numbers)`() {
        assertTrue(quantityCodes("Bestellung 30 Stk. Schrauben", InvoiceItemUnit.PIECE, "1").none { it == LineAdvisor.QUANTITY_UNIT_MISMATCH })
    }

    // known miss (task's own regex, used verbatim): the superscript "²" in "m²" is not a Java
    // regex word character, so the pattern's trailing \b never finds a boundary right after it
    // when "m²" is followed by whitespace or end-of-string — "m²" as a unit token effectively
    // never matches. A miss, not noise, so left as-is rather than reworking the given pattern.
    @org.junit.jupiter.api.Test
    fun `uncovered on purpose - known miss - "m²" as a unit token never matches the task's own regex`() {
        assertTrue(quantityCodes("5 m² Bodenbelag", InvoiceItemUnit.PIECE, "1").none { it == LineAdvisor.QUANTITY_UNIT_MISMATCH })
    }
}
