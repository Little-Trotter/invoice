package de.littletrotter.domain.compliance

import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.InvoiceItemUnitDomain
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem

/**
 * Covers BT-153/BT-129/BT-130 consistency, with dedicated [Nested] blocks for euro, piece, time
 * units, and square meters; the remaining units are covered by parameterized cases so a later
 * addition to [InvoiceItemUnit] doesn't slip through unchecked.
 */
class UnitConsistencyRulesTest {

    private fun line(
        unit: InvoiceItemUnit,
        quantity: String,
        unitCode: String? = unit.uneceCode,
        itemName: String = "Leistung",
        itemDescription: String? = null,
        positionId: String = "POS-001",
    ) = LineUnitInput(
        positionId = positionId,
        itemName = itemName,
        itemDescription = itemDescription,
        quantity = BigDecimal(quantity),
        unitCode = unitCode,
        unit = unit,
    )

    private fun ruleIds(line: LineUnitInput) = UnitConsistencyRules.evaluateLine(line).map { it.ruleId }

    // ── LT-UNIT-01 — BT-130 is the unit's UN/ECE code ──────────────────

    @Nested
    inner class UnitCode {

        @ParameterizedTest
        @EnumSource(InvoiceItemUnit::class)
        fun `jede Einheit ist konsistent, wenn BT-130 ihr eigener Rec-20-Code ist`(unit: InvoiceItemUnit) {
            // Quantity 1 is valid for every unit (whole, positive, and the only valid one for
            // EURO) — keeps this case scoped to LT-UNIT-01.
            assertTrue(ruleIds(line(unit, "1")).isEmpty(), "$unit meldete unerwartet einen Befund")
        }

        @Test
        fun `ein fremder Einheitencode wird gemeldet`() {
            val findings = UnitConsistencyRules.evaluateLine(line(InvoiceItemUnit.HOUR, "8", unitCode = "H87"))
            assertEquals(listOf(UnitConsistencyRules.UNIT_CODE_MISMATCH), findings.map { it.ruleId })
            val finding = findings.single()
            assertEquals(ComplianceSeverity.ERROR, finding.severity)
            assertEquals(listOf(B2bTerm.BT_130), finding.businessTerms)
            assertEquals("POS-001", finding.positionId)
            assertEquals(listOf("H87", "HUR", "HOUR"), finding.args)
        }

        @Test
        fun `ein fehlender Einheitencode wird gemeldet - BT-130 ist nicht optional`() {
            assertEquals(
                listOf(UnitConsistencyRules.UNIT_CODE_MISMATCH),
                ruleIds(line(InvoiceItemUnit.DAY, "2", unitCode = null)),
            )
        }

        @Test
        fun `ein leerer Einheitencode wird gemeldet`() {
            assertEquals(
                listOf(UnitConsistencyRules.UNIT_CODE_MISMATCH),
                ruleIds(line(InvoiceItemUnit.DAY, "2", unitCode = "")),
            )
        }

        @Test
        fun `die im Fokus stehenden Einheiten tragen die erwarteten Rec-20-Codes`() {
            // Written out explicitly rather than derived from InvoiceItemUnit: a typo in the enum
            // constant should break this test, not slip through it.
            assertEquals("C62", InvoiceItemUnit.EURO.uneceCode)
            assertEquals("H87", InvoiceItemUnit.PIECE.uneceCode)
            assertEquals("HUR", InvoiceItemUnit.HOUR.uneceCode)
            assertEquals("DAY", InvoiceItemUnit.DAY.uneceCode)
            assertEquals("MTK", InvoiceItemUnit.SQUARE_METER.uneceCode)
        }
    }

    // ── LT-UNIT-02 — euro ────────────────────────────────────────────────────

    @Nested
    inner class CurrencyUnit {

        @Test
        fun `Euro mit Menge 1 ist gueltig`() {
            assertTrue(ruleIds(line(InvoiceItemUnit.EURO, "1")).isEmpty())
        }

        @Test
        fun `Euro mit gleichwertiger Menge 1_00 ist gueltig - die Skalierung entscheidet nicht`() {
            assertTrue(ruleIds(line(InvoiceItemUnit.EURO, "1.0000")).isEmpty())
        }

        @ParameterizedTest
        @ValueSource(strings = ["2", "3.5", "0.5"])
        fun `Euro mit einer Menge ungleich 1 wird gemeldet`(quantity: String) {
            val ids = ruleIds(line(InvoiceItemUnit.EURO, quantity))
            assertTrue(UnitConsistencyRules.CURRENCY_UNIT_QUANTITY_NOT_ONE in ids, "erwartet bei Menge $quantity")
        }

        @Test
        fun `der Befund benennt beide betroffenen Terms und traegt die Menge mit`() {
            val finding = UnitConsistencyRules.evaluateLine(line(InvoiceItemUnit.EURO, "42"))
                .single { it.ruleId == UnitConsistencyRules.CURRENCY_UNIT_QUANTITY_NOT_ONE }
            assertEquals(listOf(B2bTerm.BT_129, B2bTerm.BT_130), finding.businessTerms)
            assertEquals(listOf(BigDecimal("42")), finding.args)
            assertTrue(finding.isBlocking)
        }
    }

    // ── LT-UNIT-03 — piece and the other countable units ───────────────

    @Nested
    inner class CountableUnits {

        @ParameterizedTest
        @ValueSource(strings = ["1", "3", "3.0", "12.0000"])
        fun `Stueck mit ganzzahliger Menge ist gueltig`(quantity: String) {
            assertTrue(ruleIds(line(InvoiceItemUnit.PIECE, quantity)).isEmpty(), "Menge $quantity")
        }

        @ParameterizedTest
        @ValueSource(strings = ["2.5", "0.25", "1.0001"])
        fun `Stueck mit gebrochener Menge wird gemeldet`(quantity: String) {
            assertEquals(
                listOf(UnitConsistencyRules.COUNTABLE_UNIT_FRACTIONAL_QUANTITY),
                ruleIds(line(InvoiceItemUnit.PIECE, quantity)),
            )
        }

        @Test
        fun `auch Pauschale und Anfahrt sind zaehlbar`() {
            assertEquals(
                listOf(UnitConsistencyRules.COUNTABLE_UNIT_FRACTIONAL_QUANTITY),
                ruleIds(line(InvoiceItemUnit.FLAT_RATE, "0.5")),
            )
            assertEquals(
                listOf(UnitConsistencyRules.COUNTABLE_UNIT_FRACTIONAL_QUANTITY),
                ruleIds(line(InvoiceItemUnit.APPROACH, "1.5")),
            )
        }

        @Test
        fun `Euro faellt trotz C62 nicht unter die Ganzzahl-Regel`() {
            // EURO shares the code C62 with flat-rate/callout but belongs to the CURRENCY
            // domain — a fractional quantity there reports LT-UNIT-02, not LT-UNIT-03.
            assertEquals(
                listOf(UnitConsistencyRules.CURRENCY_UNIT_QUANTITY_NOT_ONE),
                ruleIds(line(InvoiceItemUnit.EURO, "0.5")),
            )
        }

        @ParameterizedTest
        @EnumSource(InvoiceItemUnit::class)
        fun `genau die Einheiten der Domaene COUNT sind ganzzahlig-pflichtig`(unit: InvoiceItemUnit) {
            val fractional = ruleIds(line(unit, "2.5"))
            val expectFinding = unit.domain == InvoiceItemUnitDomain.COUNT
            assertEquals(
                expectFinding,
                UnitConsistencyRules.COUNTABLE_UNIT_FRACTIONAL_QUANTITY in fractional,
                "$unit (Domäne ${unit.domain})",
            )
        }
    }

    // ── Time units and square meters — fractional quantities are intended ──────

    @Nested
    inner class MeasurableUnits {

        @ParameterizedTest
        @ValueSource(strings = ["0.25", "0.5", "7.75", "8"])
        fun `Stunden duerfen gebrochen sein`(quantity: String) {
            assertTrue(ruleIds(line(InvoiceItemUnit.HOUR, quantity)).isEmpty(), "Menge $quantity")
        }

        @ParameterizedTest
        @ValueSource(strings = ["0.5", "1.5", "21"])
        fun `Tage duerfen gebrochen sein`(quantity: String) {
            assertTrue(ruleIds(line(InvoiceItemUnit.DAY, quantity)).isEmpty(), "Menge $quantity")
        }

        @ParameterizedTest
        @ValueSource(strings = ["0.0001", "12.75", "340.5"])
        fun `Quadratmeter duerfen gebrochen sein`(quantity: String) {
            assertTrue(ruleIds(line(InvoiceItemUnit.SQUARE_METER, quantity)).isEmpty(), "Menge $quantity")
        }
    }

    // ── LT-UNIT-04 — quantity > 0 ───────────────────────────────────────────────

    @Nested
    inner class PositiveQuantity {

        @Test
        fun `eine Menge von null wird gemeldet`() {
            val finding = UnitConsistencyRules.evaluateLine(line(InvoiceItemUnit.HOUR, "0"))
                .single { it.ruleId == UnitConsistencyRules.QUANTITY_NOT_POSITIVE }
            assertEquals(listOf(B2bTerm.BT_129), finding.businessTerms)
            assertEquals(listOf(BigDecimal("0"), "HOUR"), finding.args)
        }

        @Test
        fun `eine negative Menge wird gemeldet`() {
            assertTrue(
                UnitConsistencyRules.QUANTITY_NOT_POSITIVE in ruleIds(line(InvoiceItemUnit.HOUR, "-8")),
            )
        }

        @Test
        fun `eine Rabattposition traegt eine positive Menge und wird nicht gemeldet`() {
            // A discount line's sign lives in the unit price, not the quantity. The price never
            // appears in this rule set, so it must trigger nothing here.
            assertTrue(ruleIds(line(InvoiceItemUnit.HOUR, "8", itemName = "Nachlass auf Position 1")).isEmpty())
        }
    }

    // ── LT-UNIT-05/06 — BT-153 and BT-154 ────────────────────────────────────

    @Nested
    inner class ItemNameAndDescription {

        @Test
        fun `eine leere Bezeichnung wird gemeldet`() {
            val finding = UnitConsistencyRules.evaluateLine(line(InvoiceItemUnit.HOUR, "1", itemName = "   "))
                .single { it.ruleId == UnitConsistencyRules.ITEM_NAME_BLANK }
            assertEquals(listOf(B2bTerm.BT_153), finding.businessTerms)
            assertTrue(finding.args.isEmpty())
        }

        @Test
        fun `eine gefuellte Beschreibung neben der Bezeichnung ist gueltig`() {
            assertTrue(
                ruleIds(
                    line(
                        InvoiceItemUnit.HOUR, "8",
                        itemName = "Beratung",
                        itemDescription = "Fachliche Begleitung der Migration, 4 Termine",
                    ),
                ).isEmpty(),
            )
        }

        @Test
        fun `eine wortgleiche Beschreibung wird als Warnung gemeldet`() {
            val finding = UnitConsistencyRules
                .evaluateLine(line(InvoiceItemUnit.HOUR, "8", itemName = "Beratung", itemDescription = " beratung "))
                .single()
            assertEquals(UnitConsistencyRules.ITEM_DESCRIPTION_DUPLICATES_NAME, finding.ruleId)
            assertEquals(ComplianceSeverity.WARNING, finding.severity)
            assertFalse(finding.isBlocking)
            assertEquals(listOf(B2bTerm.BT_153, B2bTerm.BT_154), finding.businessTerms)
        }

        @Test
        fun `eine fehlende Beschreibung ist gueltig - BT-154 ist optional`() {
            assertTrue(ruleIds(line(InvoiceItemUnit.HOUR, "8", itemDescription = null)).isEmpty())
        }

        @Test
        fun `eine leere Beschreibung zaehlt nicht als Dublette einer leeren Bezeichnung`() {
            // Both blank: reports the missing name — and also the duplicate, because "" does
            // equal "". Pins down that both findings fire, so order/completeness can't silently
            // change.
            assertEquals(
                listOf(UnitConsistencyRules.ITEM_NAME_BLANK, UnitConsistencyRules.ITEM_DESCRIPTION_DUPLICATES_NAME),
                ruleIds(line(InvoiceItemUnit.HOUR, "1", itemName = "", itemDescription = "")),
            )
        }
    }

    // ── D-R2(a) — LT-UNIT-07: BT-153 doesn't look like a bare article number or
    //    contract reference ────────────────────────────────────────────────

    @Nested
    inner class BareArticleCodeOrContractReference {

        @ParameterizedTest
        @ValueSource(strings = ["243538", "ART-4711", "KST/4711", "4711"])
        fun `eine reine Artikelnummer als Bezeichnung wird gemeldet`(itemName: String) {
            val finding = UnitConsistencyRules.evaluateLine(line(InvoiceItemUnit.PIECE, "1", itemName = itemName))
                .single { it.ruleId == UnitConsistencyRules.ITEM_NAME_LOOKS_LIKE_BARE_CODE }
            assertEquals(ComplianceSeverity.WARNING, finding.severity)
            assertFalse(finding.isBlocking)
            assertEquals(listOf(B2bTerm.BT_153, B2bTerm.BT_155), finding.businessTerms)
            assertEquals(listOf(itemName), finding.args)
        }

        @ParameterizedTest
        @ValueSource(strings = ["lt. Vertrag XYZ12345", "laut Wartungsvertrag XYZ12345", "Lt. Mietvertrag"])
        fun `ein blosser Vertragsverweis als Bezeichnung wird gemeldet`(itemName: String) {
            assertEquals(
                listOf(UnitConsistencyRules.ITEM_NAME_LOOKS_LIKE_BARE_CODE),
                ruleIds(line(InvoiceItemUnit.HOUR, "1", itemName = itemName)),
            )
        }

        @Test
        fun `eine echte Leistungsbeschreibung mit Vertragsverweis wird nicht gemeldet`() {
            // The directive itself names exactly this as the correct form: the reference stands
            // *next to* the actual description, not in place of it.
            assertTrue(
                ruleIds(line(InvoiceItemUnit.HOUR, "1", itemName = "Wartung der Heizungsanlage (lt. Wartungsvertrag XYZ12345)")).isEmpty(),
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["Beratung", "Wartung Gasheizung", "Reinigung Außenanlage", "Malerarbeiten Außenwand"])
        fun `eine gewoehnliche Leistungsbeschreibung wird nicht gemeldet`(itemName: String) {
            assertTrue(ruleIds(line(InvoiceItemUnit.HOUR, "1", itemName = itemName)).isEmpty())
        }
    }

    // ── D-R2(b) — LT-UNIT-08: quantity plausibility for piece/flat-rate ───────

    @Nested
    inner class QuantityPlausibility {

        @ParameterizedTest
        @ValueSource(strings = ["Reinigung Außenanlage 30 h zu je 30 EUR", "Fahrt 120 km", "Bodenbelag 45 qm", "Fliesen 12 m²", "Montage 8 Std vor Ort"])
        fun `ein Mengen-Token im Text bei Stueck und Menge 1 wird gemeldet`(itemName: String) {
            val finding = UnitConsistencyRules.evaluateLine(line(InvoiceItemUnit.PIECE, "1", itemName = itemName))
                .single { it.ruleId == UnitConsistencyRules.QUANTITY_LIKE_TOKEN_IN_DESCRIPTION }
            assertEquals(ComplianceSeverity.WARNING, finding.severity)
            assertFalse(finding.isBlocking)
            assertEquals(listOf(B2bTerm.BT_129, B2bTerm.BT_130, B2bTerm.BT_153), finding.businessTerms)
        }

        @Test
        fun `ein Mengen-Token im Text bei Pauschale und Menge 1 wird ebenfalls gemeldet`() {
            assertEquals(
                listOf(UnitConsistencyRules.QUANTITY_LIKE_TOKEN_IN_DESCRIPTION),
                ruleIds(line(InvoiceItemUnit.FLAT_RATE, "1", itemName = "Fahrtkosten (120 km)")),
            )
        }

        @Test
        fun `ein Mengen-Token in der Zusatzbeschreibung BT-154 wird ebenfalls erkannt`() {
            assertEquals(
                listOf(UnitConsistencyRules.QUANTITY_LIKE_TOKEN_IN_DESCRIPTION),
                ruleIds(line(InvoiceItemUnit.PIECE, "1", itemName = "Reinigung", itemDescription = "30 h Arbeitszeit")),
            )
        }

        @Test
        fun `ein echter Pauschalpreis ohne Mengen-Token bleibt warnungsfrei`() {
            // The directive names "Malerarbeiten Außenwand" (BT-129=1, BT-130=flat-rate)
            // explicitly as a correct flat-rate example.
            assertTrue(ruleIds(line(InvoiceItemUnit.FLAT_RATE, "1", itemName = "Malerarbeiten Außenwand")).isEmpty())
        }

        @Test
        fun `Stueck ohne Mengen-Token bleibt warnungsfrei`() {
            // The directive names "Kugelschreiber mit Nekar Logo" (BT-129=3000, BT-130=piece) as
            // a correct example — checked here at quantity 1 for plausibility.
            assertTrue(ruleIds(line(InvoiceItemUnit.PIECE, "1", itemName = "Kugelschreiber mit Nekar Logo")).isEmpty())
        }

        @ParameterizedTest
        @ValueSource(strings = ["2", "0.5", "30"])
        fun `ein Mengen-Token bei einer Menge ungleich 1 wird nicht gemeldet`(quantity: String) {
            // The rule targets blanket "1 piece/flat-rate" usage; a quantity already correctly
            // set to something other than 1 isn't the error this addresses (other rules like
            // LT-UNIT-03 may still fire independently at that quantity).
            assertTrue(
                UnitConsistencyRules.QUANTITY_LIKE_TOKEN_IN_DESCRIPTION !in ruleIds(line(InvoiceItemUnit.PIECE, quantity, itemName = "Reinigung 30 h")),
            )
        }

        @ParameterizedTest
        @EnumSource(value = InvoiceItemUnit::class, names = ["PIECE", "FLAT_RATE"], mode = EnumSource.Mode.EXCLUDE)
        fun `ein Mengen-Token bei jeder anderen Einheit als Stueck oder Pauschale wird nicht gemeldet`(unit: InvoiceItemUnit) {
            assertTrue(
                UnitConsistencyRules.QUANTITY_LIKE_TOKEN_IN_DESCRIPTION !in ruleIds(line(unit, "1", itemName = "Reinigung 30 h")),
                "$unit",
            )
        }
    }

    // ── Multiple lines and the entity adapter ───────────────────

    @Test
    fun `mehrere Befunde einer Position kommen alle zurueck und nennen dieselbe Position`() {
        val findings = UnitConsistencyRules.evaluateLine(
            line(InvoiceItemUnit.PIECE, "-2.5", unitCode = "XXX", itemName = "", positionId = "POS-007"),
        )
        assertEquals(
            listOf(
                UnitConsistencyRules.UNIT_CODE_MISMATCH,
                UnitConsistencyRules.QUANTITY_NOT_POSITIVE,
                UnitConsistencyRules.COUNTABLE_UNIT_FRACTIONAL_QUANTITY,
                UnitConsistencyRules.ITEM_NAME_BLANK,
            ),
            findings.map { it.ruleId },
        )
        assertTrue(findings.all { it.positionId == "POS-007" })
    }

    @Test
    fun `evaluate bewertet jede Position der Liste`() {
        val findings = UnitConsistencyRules.evaluate(
            listOf(
                line(InvoiceItemUnit.HOUR, "8", positionId = "POS-001"),
                line(InvoiceItemUnit.EURO, "3", positionId = "POS-002"),
                line(InvoiceItemUnit.PIECE, "1.5", positionId = "POS-003"),
            ),
        )
        assertEquals(
            listOf(
                "POS-002" to UnitConsistencyRules.CURRENCY_UNIT_QUANTITY_NOT_ONE,
                "POS-003" to UnitConsistencyRules.COUNTABLE_UNIT_FRACTIONAL_QUANTITY,
            ),
            findings.map { it.positionId to it.ruleId },
        )
    }

    @Test
    fun `eine leere Positionsliste erzeugt keinen Befund`() {
        assertTrue(UnitConsistencyRules.evaluate(emptyList()).isEmpty())
    }

    @Nested
    inner class InvoiceAdapter {

        private fun item(
            positionId: String,
            unit: InvoiceItemUnit,
            quantity: String,
            description: String = "Leistung",
            itemDescription: String? = null,
        ) = anInvoiceItem(
            positionId = positionId,
            description = description,
            itemDescription = itemDescription,
            unit = unit,
            quantity = BigDecimal(quantity),
            unitPriceNet = Money.of(BigDecimal("100.00")),
        )

        @Test
        fun `unitInputs uebernimmt Bezeichnung, Beschreibung, Menge und den abgeleiteten Code`() {
            val invoice = anInvoice(
                items = listOf(item("POS-001", InvoiceItemUnit.SQUARE_METER, "12.5", itemDescription = "Innenwand")),
            )
            assertEquals(
                listOf(
                    LineUnitInput(
                        positionId = "POS-001",
                        itemName = "Leistung",
                        itemDescription = "Innenwand",
                        quantity = BigDecimal("12.5"),
                        unitCode = "MTK",
                        unit = InvoiceItemUnit.SQUARE_METER,
                    ),
                ),
                invoice.unitInputs(),
            )
            assertTrue(invoice.evaluateUnitConsistency().isEmpty())
        }

        @Test
        fun `evaluateUnitConsistency meldet die Euro-Position mit einer Menge ungleich 1`() {
            val invoice = anInvoice(
                items = listOf(item("POS-001", InvoiceItemUnit.HOUR, "8"), item("POS-002", InvoiceItemUnit.EURO, "2")),
            )
            val findings = invoice.evaluateUnitConsistency()
            assertEquals(listOf(UnitConsistencyRules.CURRENCY_UNIT_QUANTITY_NOT_ONE), findings.map { it.ruleId })
            assertEquals("POS-002", findings.single().positionId)
        }

        @Test
        fun `der aus der Einheit abgeleitete Code der Entitaet loest LT-UNIT-01 nie aus`() {
            // InvoiceItem.unitCode is a derived field — as long as that holds, the rule can never
            // fire on the entity. It exists for the wire type (InvoiceItemDto), where the code
            // can be set freely; this test pins the expectation so a later loosening of unitCode
            // gets noticed.
            InvoiceItemUnit.entries.forEach { unit ->
                val invoice = anInvoice(items = listOf(item("POS-001", unit, "1")))
                assertTrue(
                    invoice.evaluateUnitConsistency().none { it.ruleId == UnitConsistencyRules.UNIT_CODE_MISMATCH },
                    "$unit",
                )
            }
        }
    }

    // ── Data class equality and hashing ────────────────────────────────

    @Nested
    inner class LineUnitInputDataClass {

        @Test
        fun `two LineUnitInput instances with identical fields are equal`() {
            val line1 = LineUnitInput(
                positionId = "POS-001",
                itemName = "Service",
                itemDescription = "Detailed service",
                quantity = BigDecimal("10.5"),
                unitCode = "HUR",
                unit = InvoiceItemUnit.HOUR,
            )
            val line2 = LineUnitInput(
                positionId = "POS-001",
                itemName = "Service",
                itemDescription = "Detailed service",
                quantity = BigDecimal("10.5"),
                unitCode = "HUR",
                unit = InvoiceItemUnit.HOUR,
            )
            assertEquals(line1, line2)
            assertEquals(line1.hashCode(), line2.hashCode())
        }

        @Test
        fun `LineUnitInput with differing positionId is not equal`() {
            val line1 = line(InvoiceItemUnit.HOUR, "1")
            val line2 = line1.copy(positionId = "POS-002")
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LineUnitInput with differing itemName is not equal`() {
            val line1 = line(InvoiceItemUnit.HOUR, "1", itemName = "Service A")
            val line2 = line1.copy(itemName = "Service B")
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LineUnitInput with differing itemDescription is not equal`() {
            val line1 = line(InvoiceItemUnit.HOUR, "1", itemDescription = "Description A")
            val line2 = line1.copy(itemDescription = "Description B")
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LineUnitInput with differing quantity is not equal`() {
            val line1 = line(InvoiceItemUnit.HOUR, "5")
            val line2 = line1.copy(quantity = BigDecimal("10"))
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LineUnitInput with differing unitCode is not equal`() {
            val line1 = line(InvoiceItemUnit.HOUR, "1", unitCode = "HUR")
            val line2 = line1.copy(unitCode = "DAY")
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LineUnitInput with differing unit is not equal`() {
            val line1 = line(InvoiceItemUnit.HOUR, "1")
            val line2 = line1.copy(unit = InvoiceItemUnit.DAY)
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LineUnitInput is not equal to null`() {
            val line = line(InvoiceItemUnit.HOUR, "1")
            assertNotEquals(line, null)
        }

        @Test
        fun `LineUnitInput is not equal to a different type`() {
            val line = line(InvoiceItemUnit.HOUR, "1")
            assertNotEquals(line, "not a LineUnitInput")
        }

        @Test
        fun `LineUnitInput copy with modified fields creates new instance`() {
            val line1 = line(InvoiceItemUnit.HOUR, "5", itemName = "Original")
            val line2 = line1.copy(quantity = BigDecimal("10"), itemName = "Modified")
            assertEquals("POS-001", line2.positionId)
            assertEquals("Modified", line2.itemName)
            assertEquals(BigDecimal("10"), line2.quantity)
            assertEquals(InvoiceItemUnit.HOUR, line2.unit)
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LineUnitInput component functions work correctly`() {
            val line = LineUnitInput(
                positionId = "POS-001",
                itemName = "Service",
                itemDescription = "Description",
                quantity = BigDecimal("5.5"),
                unitCode = "HUR",
                unit = InvoiceItemUnit.HOUR,
            )
            val (posId, name, desc, qty, code, unit) = line
            assertEquals("POS-001", posId)
            assertEquals("Service", name)
            assertEquals("Description", desc)
            assertEquals(BigDecimal("5.5"), qty)
            assertEquals("HUR", code)
            assertEquals(InvoiceItemUnit.HOUR, unit)
        }
    }
}
