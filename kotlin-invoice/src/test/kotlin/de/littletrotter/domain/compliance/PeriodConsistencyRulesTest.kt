package de.littletrotter.domain.compliance

import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem

/**
 * Covers every branch of [PeriodConsistencyRules] in both directions: for each rule there's a
 * case that triggers it, **and** one that narrowly doesn't (equality at the boundary). The
 * boundary cases are the actual content — that a period lying exactly on the header period is
 * valid is the statement an off-by-one in the comparison would otherwise silently break.
 */
class PeriodConsistencyRulesTest {

    private val jan1 = LocalDate.of(2026, 1, 1)
    private val jan15 = LocalDate.of(2026, 1, 15)
    private val jan31 = LocalDate.of(2026, 1, 31)
    private val feb1 = LocalDate.of(2026, 2, 1)
    private val dec31 = LocalDate.of(2025, 12, 31)

    private fun input(
        deliveryDate: LocalDate? = null,
        periodFrom: LocalDate? = null,
        periodTo: LocalDate? = null,
        lines: List<LinePeriodInput> = emptyList(),
    ) = InvoicePeriodInput(
        invoiceDate = LocalDate.of(2026, 2, 5),
        deliveryDate = deliveryDate,
        periodFrom = periodFrom,
        periodTo = periodTo,
        lines = lines,
    )

    private fun ruleIds(input: InvoicePeriodInput) = PeriodConsistencyRules.evaluate(input).map { it.ruleId }

    // ── BR-29 — BT-74 ≥ BT-73 ────────────────────────────────────────────────

    @Nested
    inner class HeaderPeriodOrder {

        @Test
        fun `BR-29 meldet einen Kopfzeitraum, dessen Ende vor dem Beginn liegt`() {
            val findings = PeriodConsistencyRules.evaluate(input(periodFrom = jan31, periodTo = jan1))
            assertEquals(listOf(PeriodConsistencyRules.BR_29), findings.map { it.ruleId })
            val finding = findings.single()
            assertEquals(ComplianceSeverity.ERROR, finding.severity)
            assertEquals(listOf(B2bTerm.BT_73, B2bTerm.BT_74), finding.businessTerms)
            // Header level: no line named.
            assertEquals(null, finding.positionId)
            assertEquals(listOf(jan31, jan1), finding.args)
        }

        @Test
        fun `BR-29 meldet nicht, wenn Beginn und Ende auf denselben Tag fallen`() {
            assertTrue(ruleIds(input(periodFrom = jan15, periodTo = jan15)).isEmpty())
        }

        @Test
        fun `BR-29 meldet nicht bei ordentlicher Reihenfolge`() {
            assertTrue(ruleIds(input(periodFrom = jan1, periodTo = jan31)).isEmpty())
        }

        @Test
        fun `BR-29 braucht beide Daten - ein einseitig offener Zeitraum wird nicht gemeldet`() {
            assertTrue(ruleIds(input(periodFrom = jan31)).isEmpty())
            assertTrue(ruleIds(input(periodTo = jan1)).isEmpty())
        }
    }

    // ── LT-PER-03 — BT-72 within BT-73/BT-74 ──────────────────────────────

    @Nested
    inner class DeliveryDateInsideHeaderPeriod {

        @Test
        fun `ein Lieferdatum vor dem Beginn des Leistungszeitraums wird gemeldet`() {
            val findings = PeriodConsistencyRules.evaluate(
                input(deliveryDate = dec31, periodFrom = jan1, periodTo = jan31),
            )
            assertEquals(listOf(PeriodConsistencyRules.DELIVERY_DATE_OUTSIDE_INVOICE_PERIOD), findings.map { it.ruleId })
            assertEquals(listOf(B2bTerm.BT_72, B2bTerm.BT_73, B2bTerm.BT_74), findings.single().businessTerms)
        }

        @Test
        fun `ein Lieferdatum nach dem Ende des Leistungszeitraums wird gemeldet`() {
            assertEquals(
                listOf(PeriodConsistencyRules.DELIVERY_DATE_OUTSIDE_INVOICE_PERIOD),
                ruleIds(input(deliveryDate = feb1, periodFrom = jan1, periodTo = jan31)),
            )
        }

        @Test
        fun `ein Lieferdatum genau auf dem Beginn oder dem Ende ist gueltig`() {
            assertTrue(ruleIds(input(deliveryDate = jan1, periodFrom = jan1, periodTo = jan31)).isEmpty())
            assertTrue(ruleIds(input(deliveryDate = jan31, periodFrom = jan1, periodTo = jan31)).isEmpty())
        }

        @Test
        fun `ein einseitig offener Kopfzeitraum prueft nur die vorhandene Seite`() {
            // Only BT-73: everything from 1.1. onward is valid, everything before isn't.
            assertTrue(ruleIds(input(deliveryDate = jan31, periodFrom = jan1)).isEmpty())
            assertEquals(
                listOf(PeriodConsistencyRules.DELIVERY_DATE_OUTSIDE_INVOICE_PERIOD),
                ruleIds(input(deliveryDate = dec31, periodFrom = jan1)),
            )
            // Only BT-74: everything up to 31.1. is valid, everything after isn't.
            assertTrue(ruleIds(input(deliveryDate = dec31, periodTo = jan31)).isEmpty())
            assertEquals(
                listOf(PeriodConsistencyRules.DELIVERY_DATE_OUTSIDE_INVOICE_PERIOD),
                ruleIds(input(deliveryDate = feb1, periodTo = jan31)),
            )
        }

        @Test
        fun `ohne Kopfzeitraum ist jedes Lieferdatum gueltig`() {
            assertTrue(ruleIds(input(deliveryDate = feb1)).isEmpty())
        }

        @Test
        fun `ohne Lieferdatum entfaellt die Pruefung`() {
            assertTrue(ruleIds(input(periodFrom = jan1, periodTo = jan31)).isEmpty())
        }
    }

    // ── BR-30 — BT-135 ≥ BT-134 ──────────────────────────────────────────────

    @Nested
    inner class LinePeriodOrder {

        @Test
        fun `BR-30 meldet eine Position, deren Zeitraum-Ende vor dem Beginn liegt`() {
            val findings = PeriodConsistencyRules.evaluate(
                input(
                    periodFrom = jan1,
                    periodTo = jan31,
                    lines = listOf(LinePeriodInput("POS-001", from = jan31, to = jan15)),
                ),
            )
            assertEquals(listOf(PeriodConsistencyRules.BR_30), findings.map { it.ruleId })
            val finding = findings.single()
            assertEquals("POS-001", finding.positionId)
            assertEquals(listOf(B2bTerm.BT_134, B2bTerm.BT_135), finding.businessTerms)
        }

        @Test
        fun `BR-30 meldet nicht, wenn Beginn und Ende auf denselben Tag fallen`() {
            assertTrue(
                ruleIds(
                    input(
                        periodFrom = jan1,
                        periodTo = jan31,
                        lines = listOf(LinePeriodInput("POS-001", from = jan15, to = jan15)),
                    ),
                ).isEmpty(),
            )
        }

        @Test
        fun `BR-30 greift auch ohne Kopfzeitraum - die Reihenfolge ist von ihm unabhaengig`() {
            val ids = ruleIds(input(lines = listOf(LinePeriodInput("POS-001", from = jan31, to = jan1))))
            assertTrue(PeriodConsistencyRules.BR_30 in ids)
            // …plus the warning that a header period is missing.
            assertTrue(PeriodConsistencyRules.LINE_PERIOD_WITHOUT_INVOICE_PERIOD in ids)
        }
    }

    // ── LT-PER-01/02 — line period within header period ─────────────────────

    @Nested
    inner class LinePeriodInsideHeaderPeriod {

        @Test
        fun `eine Position, die vor dem Rechnungszeitraum beginnt, wird gemeldet`() {
            val findings = PeriodConsistencyRules.evaluate(
                input(
                    periodFrom = jan1,
                    periodTo = jan31,
                    lines = listOf(LinePeriodInput("POS-001", from = dec31, to = jan15)),
                ),
            )
            assertEquals(listOf(PeriodConsistencyRules.LINE_PERIOD_STARTS_BEFORE_INVOICE_PERIOD), findings.map { it.ruleId })
            assertEquals(listOf(B2bTerm.BT_73, B2bTerm.BT_134), findings.single().businessTerms)
            assertEquals("POS-001", findings.single().positionId)
        }

        @Test
        fun `eine Position, die nach dem Rechnungszeitraum endet, wird gemeldet`() {
            assertEquals(
                listOf(PeriodConsistencyRules.LINE_PERIOD_ENDS_AFTER_INVOICE_PERIOD),
                ruleIds(
                    input(
                        periodFrom = jan1,
                        periodTo = jan31,
                        lines = listOf(LinePeriodInput("POS-001", from = jan15, to = feb1)),
                    ),
                ),
            )
        }

        @Test
        fun `eine Position, die den Kopfzeitraum beidseitig ueberschreitet, erzeugt beide Befunde`() {
            assertEquals(
                listOf(
                    PeriodConsistencyRules.LINE_PERIOD_STARTS_BEFORE_INVOICE_PERIOD,
                    PeriodConsistencyRules.LINE_PERIOD_ENDS_AFTER_INVOICE_PERIOD,
                ),
                ruleIds(
                    input(
                        periodFrom = jan1,
                        periodTo = jan31,
                        lines = listOf(LinePeriodInput("POS-001", from = dec31, to = feb1)),
                    ),
                ),
            )
        }

        @Test
        fun `eine Position, die den Kopfzeitraum exakt ausfuellt, ist gueltig`() {
            assertTrue(
                ruleIds(
                    input(
                        periodFrom = jan1,
                        periodTo = jan31,
                        lines = listOf(LinePeriodInput("POS-001", from = jan1, to = jan31)),
                    ),
                ).isEmpty(),
            )
        }

        @Test
        fun `nur BT-134 gesetzt - geprueft wird nur gegen BT-73`() {
            assertEquals(
                listOf(PeriodConsistencyRules.LINE_PERIOD_STARTS_BEFORE_INVOICE_PERIOD),
                ruleIds(
                    input(
                        periodFrom = jan1,
                        periodTo = jan31,
                        lines = listOf(LinePeriodInput("POS-001", from = dec31, to = null)),
                    ),
                ),
            )
        }

        @Test
        fun `nur BT-135 gesetzt - geprueft wird nur gegen BT-74`() {
            assertEquals(
                listOf(PeriodConsistencyRules.LINE_PERIOD_ENDS_AFTER_INVOICE_PERIOD),
                ruleIds(
                    input(
                        periodFrom = jan1,
                        periodTo = jan31,
                        lines = listOf(LinePeriodInput("POS-001", from = null, to = feb1)),
                    ),
                ),
            )
        }

        @Test
        fun `ein einseitig offener Kopfzeitraum prueft nur die vorhandene Seite`() {
            // Header with only BT-73: the line end can't be too late.
            assertTrue(
                ruleIds(input(periodFrom = jan1, lines = listOf(LinePeriodInput("POS-001", jan15, feb1)))).isEmpty(),
            )
            // Header with only BT-74: the line start can't be too early.
            assertTrue(
                ruleIds(input(periodTo = jan31, lines = listOf(LinePeriodInput("POS-001", dec31, jan15)))).isEmpty(),
            )
        }

        @Test
        fun `eine Position ohne eigenen Zeitraum erzeugt keinen Befund`() {
            assertTrue(
                ruleIds(
                    input(
                        periodFrom = jan1,
                        periodTo = jan31,
                        lines = listOf(LinePeriodInput("POS-001", from = null, to = null)),
                    ),
                ).isEmpty(),
            )
        }
    }

    // ── LT-PER-04 — BG-26 without BG-14 ─────────────────────────────────────────

    @Nested
    inner class LinePeriodWithoutHeaderPeriod {

        @Test
        fun `eine Position mit Zeitraum auf einer Rechnung ohne Zeitraum wird als Warnung gemeldet`() {
            val findings = PeriodConsistencyRules.evaluate(
                input(lines = listOf(LinePeriodInput("POS-001", from = jan1, to = jan31))),
            )
            val finding = findings.single()
            assertEquals(PeriodConsistencyRules.LINE_PERIOD_WITHOUT_INVOICE_PERIOD, finding.ruleId)
            assertEquals(ComplianceSeverity.WARNING, finding.severity)
            assertFalse(finding.isBlocking)
            assertFalse(findings.hasBlocking())
            assertTrue(findings.blocking().isEmpty())
        }

        @Test
        fun `ein halber Kopfzeitraum genuegt - dann faellt die Warnung weg`() {
            assertTrue(
                ruleIds(input(periodFrom = jan1, lines = listOf(LinePeriodInput("POS-001", jan15, jan31)))).isEmpty(),
            )
            assertTrue(
                ruleIds(input(periodTo = jan31, lines = listOf(LinePeriodInput("POS-001", jan1, jan15)))).isEmpty(),
            )
        }
    }

    // ── Interaction across multiple lines ────────────────────────────────

    @Test
    fun `jede Position wird einzeln bewertet und benennt ihre eigene Positions-Id`() {
        val findings = PeriodConsistencyRules.evaluate(
            input(
                periodFrom = jan1,
                periodTo = jan31,
                lines = listOf(
                    LinePeriodInput("POS-001", from = jan1, to = jan15),   // gültig
                    LinePeriodInput("POS-002", from = dec31, to = jan15),  // zu früh
                    LinePeriodInput("POS-003", from = jan15, to = feb1),   // zu spät
                ),
            ),
        )
        assertEquals(
            listOf("POS-002" to PeriodConsistencyRules.LINE_PERIOD_STARTS_BEFORE_INVOICE_PERIOD,
                   "POS-003" to PeriodConsistencyRules.LINE_PERIOD_ENDS_AFTER_INVOICE_PERIOD),
            findings.map { it.positionId to it.ruleId },
        )
        assertTrue(findings.hasBlocking())
        assertEquals(2, findings.blocking().size)
    }

    @Test
    fun `eine Rechnung ohne jede Datumsangabe erzeugt keinen Befund`() {
        assertTrue(PeriodConsistencyRules.evaluate(input()).isEmpty())
    }

    // ── Entity adapter ──────────────────────────────────────────────

    @Nested
    inner class InvoiceAdapter {

        private fun item(positionId: String, from: LocalDate?, to: LocalDate?) = anInvoiceItem(
            positionId = positionId,
            description = "Leistung",
            servicePeriodFrom = from,
            servicePeriodTo = to,
        )

        @Test
        fun `periodInput uebernimmt Kopf- und Positionsdaten unveraendert`() {
            val invoice = anInvoice(
                invoiceDate = feb1,
                deliveryDate = jan31,
                servicePeriodFrom = jan1,
                servicePeriodTo = jan31,
                items = listOf(item("POS-001", jan1, jan15), item("POS-002", null, null)),
            )

            val input = invoice.periodInput()
            assertEquals(feb1, input.invoiceDate)
            assertEquals(jan31, input.deliveryDate)
            assertEquals(jan1, input.periodFrom)
            assertEquals(jan31, input.periodTo)
            assertEquals(
                listOf(
                    LinePeriodInput("POS-001", jan1, jan15),
                    LinePeriodInput("POS-002", null, null),
                ),
                input.lines,
            )
            assertTrue(invoice.evaluatePeriodConsistency().isEmpty())
        }

        @Test
        fun `periodInput leitet BT-72 nie aus BT-74 ab`() {
            // The export falls back from BT-72 to BT-74; this check must not do that, or the
            // delivery date would always lie within the header period by construction, and
            // LT-PER-03 would be a rule that can never fire.
            val invoice = anInvoice(servicePeriodFrom = jan1, servicePeriodTo = jan31)
            assertEquals(null, invoice.periodInput().deliveryDate)
        }

        @Test
        fun `evaluatePeriodConsistency meldet den Positionszeitraum ausserhalb des Kopfzeitraums`() {
            val invoice = anInvoice(
                servicePeriodFrom = jan1,
                servicePeriodTo = jan31,
                items = listOf(item("POS-001", dec31, feb1)),
            )
            assertEquals(
                listOf(
                    PeriodConsistencyRules.LINE_PERIOD_STARTS_BEFORE_INVOICE_PERIOD,
                    PeriodConsistencyRules.LINE_PERIOD_ENDS_AFTER_INVOICE_PERIOD,
                ),
                invoice.evaluatePeriodConsistency().map { it.ruleId },
            )
        }

        @Test
        fun `hasPeriod-Kurzformen entsprechen der Vorhandenheit von BG-14 und BG-26`() {
            assertFalse(input().hasPeriod)
            assertTrue(input(periodFrom = jan1).hasPeriod)
            assertTrue(input(periodTo = jan31).hasPeriod)
            assertFalse(LinePeriodInput("POS-001", null, null).hasPeriod)
            assertTrue(LinePeriodInput("POS-001", jan1, null).hasPeriod)
            assertTrue(LinePeriodInput("POS-001", null, jan31).hasPeriod)
        }
    }

    // ── Data class equality and hashing ────────────────────────────────

    @Nested
    inner class InvoicePeriodInputDataClass {

        @Test
        fun `two InvoicePeriodInput instances with identical fields are equal`() {
            val input1 = InvoicePeriodInput(
                invoiceDate = jan1,
                deliveryDate = jan15,
                periodFrom = jan1,
                periodTo = jan31,
                lines = listOf(LinePeriodInput("POS-001", jan1, jan31)),
            )
            val input2 = InvoicePeriodInput(
                invoiceDate = jan1,
                deliveryDate = jan15,
                periodFrom = jan1,
                periodTo = jan31,
                lines = listOf(LinePeriodInput("POS-001", jan1, jan31)),
            )
            assertEquals(input1, input2)
            assertEquals(input1.hashCode(), input2.hashCode())
        }

        @Test
        fun `InvoicePeriodInput with differing invoiceDate is not equal`() {
            val input1 = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = null, periodFrom = null, periodTo = null)
            val input2 = InvoicePeriodInput(invoiceDate = jan15, deliveryDate = null, periodFrom = null, periodTo = null)
            assertNotEquals(input1, input2)
        }

        @Test
        fun `InvoicePeriodInput with differing deliveryDate is not equal`() {
            val input1 = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = jan1, periodFrom = null, periodTo = null)
            val input2 = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = jan15, periodFrom = null, periodTo = null)
            assertNotEquals(input1, input2)
        }

        @Test
        fun `InvoicePeriodInput with differing periodFrom is not equal`() {
            val input1 = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = null, periodFrom = jan1, periodTo = null)
            val input2 = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = null, periodFrom = jan15, periodTo = null)
            assertNotEquals(input1, input2)
        }

        @Test
        fun `InvoicePeriodInput with differing periodTo is not equal`() {
            val input1 = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = null, periodFrom = null, periodTo = jan31)
            val input2 = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = null, periodFrom = null, periodTo = jan1)
            assertNotEquals(input1, input2)
        }

        @Test
        fun `InvoicePeriodInput with differing lines is not equal`() {
            val input1 = InvoicePeriodInput(
                invoiceDate = jan1,
                deliveryDate = null,
                periodFrom = null,
                periodTo = null,
                lines = listOf(LinePeriodInput("POS-001", jan1, null)),
            )
            val input2 = InvoicePeriodInput(
                invoiceDate = jan1,
                deliveryDate = null,
                periodFrom = null,
                periodTo = null,
                lines = listOf(LinePeriodInput("POS-002", jan1, null)),
            )
            assertNotEquals(input1, input2)
        }

        @Test
        fun `InvoicePeriodInput is not equal to null`() {
            val input = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = null, periodFrom = null, periodTo = null)
            assertNotEquals(input, null)
        }

        @Test
        fun `InvoicePeriodInput is not equal to a different type`() {
            val input = InvoicePeriodInput(invoiceDate = jan1, deliveryDate = null, periodFrom = null, periodTo = null)
            assertNotEquals(input, "not an InvoicePeriodInput")
        }

        @Test
        fun `InvoicePeriodInput copy with modified field creates new instance`() {
            val input1 = InvoicePeriodInput(
                invoiceDate = jan1,
                deliveryDate = jan15,
                periodFrom = jan1,
                periodTo = jan31,
                lines = listOf(LinePeriodInput("POS-001", jan1, jan31)),
            )
            val input2 = input1.copy(deliveryDate = feb1)
            assertEquals(jan1, input2.invoiceDate)
            assertEquals(feb1, input2.deliveryDate)
            assertEquals(jan1, input2.periodFrom)
            assertEquals(jan31, input2.periodTo)
            assertNotEquals(input1, input2)
        }
    }

    @Nested
    inner class LinePeriodInputDataClass {

        @Test
        fun `two LinePeriodInput instances with identical fields are equal`() {
            val line1 = LinePeriodInput("POS-001", jan1, jan31)
            val line2 = LinePeriodInput("POS-001", jan1, jan31)
            assertEquals(line1, line2)
            assertEquals(line1.hashCode(), line2.hashCode())
        }

        @Test
        fun `LinePeriodInput with differing positionId is not equal`() {
            val line1 = LinePeriodInput("POS-001", jan1, jan31)
            val line2 = LinePeriodInput("POS-002", jan1, jan31)
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LinePeriodInput with differing from date is not equal`() {
            val line1 = LinePeriodInput("POS-001", jan1, jan31)
            val line2 = LinePeriodInput("POS-001", jan15, jan31)
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LinePeriodInput with differing to date is not equal`() {
            val line1 = LinePeriodInput("POS-001", jan1, jan31)
            val line2 = LinePeriodInput("POS-001", jan1, feb1)
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LinePeriodInput is not equal to null`() {
            val line = LinePeriodInput("POS-001", jan1, jan31)
            assertNotEquals(line, null)
        }

        @Test
        fun `LinePeriodInput is not equal to a different type`() {
            val line = LinePeriodInput("POS-001", jan1, jan31)
            assertNotEquals(line, "not a LinePeriodInput")
        }

        @Test
        fun `LinePeriodInput copy with modified field creates new instance`() {
            val line1 = LinePeriodInput("POS-001", jan1, jan31)
            val line2 = line1.copy(to = feb1)
            assertEquals("POS-001", line2.positionId)
            assertEquals(jan1, line2.from)
            assertEquals(feb1, line2.to)
            assertNotEquals(line1, line2)
        }

        @Test
        fun `LinePeriodInput component functions work correctly`() {
            val line = LinePeriodInput("POS-001", jan1, jan31)
            val (posId, from, to) = line
            assertEquals("POS-001", posId)
            assertEquals(jan1, from)
            assertEquals(jan31, to)
        }
    }
}
