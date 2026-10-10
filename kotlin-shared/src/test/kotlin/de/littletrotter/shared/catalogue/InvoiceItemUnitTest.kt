package de.littletrotter.shared.catalogue

import de.littletrotter.shared.b2b.codes.B2bCodeList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvoiceItemUnitTest {

    @Test
    fun `enum contains expected symbols and keys`() {
        val hour = InvoiceItemUnit.HOUR
        assertEquals("h", hour.symbol)
        assertEquals("unit.hour", hour.labelKey)

        val euro = InvoiceItemUnit.EURO
        assertEquals("€", euro.symbol)
    }

    @Test
    fun `all units have non-blank labelKey`() {
        InvoiceItemUnit.values().forEach { assertTrue(it.labelKey.isNotBlank()) }
    }

    // ─── Task F: BR-CL-23 (UN/ECE Rec 20 + Rec 21 unit codes) ──────────────────

    @Test
    fun `every unit has a UN-ECE code matching the expected uppercase alphanumeric pattern`() {
        val pattern = Regex("^[A-Z0-9]{2,3}\$")
        InvoiceItemUnit.entries.forEach {
            assertTrue(it.uneceCode.isNotBlank(), "${it.name} has a blank uneceCode")
            assertTrue(pattern.matches(it.uneceCode), "${it.name}.uneceCode '${it.uneceCode}' doesn't look like a UN/ECE Rec 20/21 code")
        }
    }

    // ─── Pinning against the official UN/ECE Rec 20+21 code list (B2bCodeList) ─────────────────
    //     (audit target: InvoiceItemUnit.uneceCode). The
    //     shape-only regex above catches typos in *new* values; this catches a code that merely
    //     *looks* right but is not actually an official UN/ECE unit — every hardcoded uneceCode
    //     literal must be a member, or export silently produces an invalid Factur-X unit code.

    @Test
    fun `every uneceCode is a member of the official UN-ECE Rec 20+21 list`() {
        InvoiceItemUnit.entries.forEach {
            assertTrue(
                B2bCodeList.UNIT_UNECE_REC20.contains(it.uneceCode),
                "${it.name}.uneceCode '${it.uneceCode}' is not a member of B2bCodeList.UNIT_UNECE_REC20 " +
                    "— either the codedb dropped it, or this literal was never valid",
            )
        }
    }

    @Test
    fun `flat rate approach and currency units use C62 not an unproven lump-sum code`() {
        assertEquals("C62", InvoiceItemUnit.FLAT_RATE.uneceCode)
        assertEquals("C62", InvoiceItemUnit.APPROACH.uneceCode)
        assertEquals("C62", InvoiceItemUnit.EURO.uneceCode)
    }

    @Test
    fun `original 14 units keep their historical ordinal position`() {
        // Task F only appends; it must never reorder these, since InvoiceItemUnitConverter
        // persists by ordinal. Breaking this test means existing invoice_item/article rows
        // would silently change meaning.
        val expectedOrder = listOf(
            InvoiceItemUnit.HOUR, InvoiceItemUnit.DAY, InvoiceItemUnit.WEEK, InvoiceItemUnit.MONTH,
            InvoiceItemUnit.PIECE, InvoiceItemUnit.METER, InvoiceItemUnit.SQUARE_METER, InvoiceItemUnit.CUBIC_METER,
            InvoiceItemUnit.KILOMETER, InvoiceItemUnit.LITER, InvoiceItemUnit.KILOGRAM, InvoiceItemUnit.FLAT_RATE,
            InvoiceItemUnit.APPROACH, InvoiceItemUnit.EURO,
        )
        expectedOrder.forEachIndexed { ordinal, unit ->
            assertEquals(ordinal, unit.ordinal, "${unit.name} moved from its original ordinal $ordinal")
        }
    }

    @Test
    fun `new units are appended after the original 14 without gaps`() {
        val newUnits = listOf(
            InvoiceItemUnit.GRAM, InvoiceItemUnit.LINEAR_METER, InvoiceItemUnit.HECTARE, InvoiceItemUnit.TONNE,
            InvoiceItemUnit.MILLILITER,
        )
        newUnits.forEachIndexed { i, unit ->
            assertEquals(14 + i, unit.ordinal, "${unit.name} is not contiguously appended after the original 14 units")
        }
    }

    // ─── germanLabel/englishLabel: the PDF's line-item table prints these texts verbatim; a
    // wrong branch here would be a visible error on the invoice. ──

    @Test
    fun `germanLabel liefert fuer jede Einheit die korrekte Singular- und Pluralform`() {
        val expected = mapOf(
            InvoiceItemUnit.HOUR to ("Stunde" to "Stunden"),
            InvoiceItemUnit.DAY to ("Tag" to "Tage"),
            InvoiceItemUnit.WEEK to ("Woche" to "Wochen"),
            InvoiceItemUnit.MONTH to ("Monat" to "Monate"),
            InvoiceItemUnit.PIECE to ("Stück" to "Stück"),
            InvoiceItemUnit.METER to ("Meter" to "Meter"),
            InvoiceItemUnit.SQUARE_METER to ("Quadratmeter" to "Quadratmeter"),
            InvoiceItemUnit.CUBIC_METER to ("Kubikmeter" to "Kubikmeter"),
            InvoiceItemUnit.KILOMETER to ("Kilometer" to "Kilometer"),
            InvoiceItemUnit.LITER to ("Liter" to "Liter"),
            InvoiceItemUnit.KILOGRAM to ("Kilogramm" to "Kilogramm"),
            InvoiceItemUnit.FLAT_RATE to ("Pauschale" to "Pauschalen"),
            InvoiceItemUnit.APPROACH to ("Anfahrt" to "Anfahrten"),
            InvoiceItemUnit.EURO to ("Euro" to "Euro"),
            InvoiceItemUnit.GRAM to ("Gramm" to "Gramm"),
            InvoiceItemUnit.LINEAR_METER to ("laufender Meter" to "laufende Meter"),
            InvoiceItemUnit.HECTARE to ("Hektar" to "Hektar"),
            InvoiceItemUnit.TONNE to ("Tonne" to "Tonnen"),
            InvoiceItemUnit.MILLILITER to ("Milliliter" to "Milliliter"),
        )
        assertEquals(InvoiceItemUnit.entries.toSet(), expected.keys, "jede Einheit muss hier eine erwartete deutsche Bezeichnung haben")
        expected.forEach { (unit, forms) ->
            val (singular, plural) = forms
            assertEquals(singular, unit.germanLabel(plural = false), "${unit.name}: falsche Singularform")
            assertEquals(plural, unit.germanLabel(plural = true), "${unit.name}: falsche Pluralform")
        }
    }

    @Test
    fun `englishLabel liefert fuer jede Einheit die korrekte Singular- und Pluralform`() {
        val expected = mapOf(
            InvoiceItemUnit.HOUR to ("hour" to "hours"),
            InvoiceItemUnit.DAY to ("day" to "days"),
            InvoiceItemUnit.WEEK to ("week" to "weeks"),
            InvoiceItemUnit.MONTH to ("month" to "months"),
            InvoiceItemUnit.PIECE to ("piece" to "pieces"),
            InvoiceItemUnit.METER to ("meter" to "meters"),
            InvoiceItemUnit.SQUARE_METER to ("square meter" to "square meters"),
            InvoiceItemUnit.CUBIC_METER to ("cubic meter" to "cubic meters"),
            InvoiceItemUnit.KILOMETER to ("kilometer" to "kilometers"),
            InvoiceItemUnit.LITER to ("liter" to "liters"),
            InvoiceItemUnit.KILOGRAM to ("kilogram" to "kilograms"),
            InvoiceItemUnit.FLAT_RATE to ("flat rate" to "flat rates"),
            InvoiceItemUnit.APPROACH to ("approach" to "approaches"),
            InvoiceItemUnit.EURO to ("Euro" to "Euro"),
            InvoiceItemUnit.GRAM to ("gram" to "grams"),
            InvoiceItemUnit.LINEAR_METER to ("linear meter" to "linear meters"),
            InvoiceItemUnit.HECTARE to ("hectare" to "hectares"),
            InvoiceItemUnit.TONNE to ("tonne" to "tonnes"),
            InvoiceItemUnit.MILLILITER to ("milliliter" to "milliliters"),
        )
        assertEquals(InvoiceItemUnit.entries.toSet(), expected.keys, "jede Einheit muss hier eine erwartete englische Bezeichnung haben")
        expected.forEach { (unit, forms) ->
            val (singular, plural) = forms
            assertEquals(singular, unit.englishLabel(plural = false), "${unit.name}: falsche Singularform")
            assertEquals(plural, unit.englishLabel(plural = true), "${unit.name}: falsche Pluralform")
        }
    }

    // ─── invoiceLabel: physical measures print their short symbol on the invoice regardless of
    // language/plural; time/count/currency units keep the existing word form. ──

    @Test
    fun `invoiceLabel liefert fuer jede physikalische Masseinheit das Symbol unabhaengig von Sprache und Numerus`() {
        val measureUnits = setOf(
            InvoiceItemUnit.METER, InvoiceItemUnit.SQUARE_METER, InvoiceItemUnit.CUBIC_METER,
            InvoiceItemUnit.KILOMETER, InvoiceItemUnit.LITER, InvoiceItemUnit.KILOGRAM,
            InvoiceItemUnit.GRAM, InvoiceItemUnit.LINEAR_METER, InvoiceItemUnit.HECTARE, InvoiceItemUnit.TONNE,
            InvoiceItemUnit.MILLILITER,
        )
        assertEquals(
            setOf(InvoiceItemUnitDomain.LENGTH, InvoiceItemUnitDomain.AREA, InvoiceItemUnitDomain.VOLUME, InvoiceItemUnitDomain.WEIGHT),
            measureUnits.map { it.domain }.toSet(),
            "measureUnits muss genau die vier physikalischen Domains abdecken",
        )
        assertEquals(
            measureUnits,
            InvoiceItemUnit.entries.filter { it.domain.printsSymbolOnInvoice }.toSet(),
            "measureUnits muss genau die Einheiten mit printsSymbolOnInvoice=true sein",
        )
        measureUnits.forEach { unit ->
            listOf(false, true).forEach { plural ->
                assertEquals(unit.symbol, unit.invoiceLabel(english = false, plural = plural), "${unit.name}: deutsches Symbol, plural=$plural")
                assertEquals(unit.symbol, unit.invoiceLabel(english = true, plural = plural), "${unit.name}: englisches Symbol, plural=$plural")
            }
        }
    }

    @Test
    fun `invoiceLabel behaelt fuer Zeit- Stueck- und Waehrungseinheiten die sprachabhaengige Wortform`() {
        val wordFormUnits = listOf(
            InvoiceItemUnit.HOUR, InvoiceItemUnit.DAY, InvoiceItemUnit.WEEK, InvoiceItemUnit.MONTH,
            InvoiceItemUnit.PIECE, InvoiceItemUnit.FLAT_RATE, InvoiceItemUnit.APPROACH, InvoiceItemUnit.EURO,
        )
        wordFormUnits.forEach { unit ->
            listOf(false, true).forEach { plural ->
                assertEquals(unit.germanLabel(plural), unit.invoiceLabel(english = false, plural = plural), "${unit.name}: deutsche Wortform, plural=$plural")
                assertEquals(unit.englishLabel(plural), unit.invoiceLabel(english = true, plural = plural), "${unit.name}: englische Wortform, plural=$plural")
            }
        }
    }
}
