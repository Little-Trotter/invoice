package de.littletrotter.xpdf.impl

import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [buildItems]' TRIP-category branch: the trip's own row (position, title, quantity/unit
 * unchanged from a plain item, purpose/period subline, total line) plus, only when
 * `detailedOnInvoice` and actually populated, indented expense sub-rows and a per-diem summary
 * row.and README border case 11.
 */
class InvoiceReportRowBuildersTripTest {

    private fun tripExpense(kindLabelKey: String, description: String, quantity: String, unitLabel: String, netAmount: String) =
        TripExpenseRenderContext(
            kindLabelKey = kindLabelKey,
            description = description,
            quantity = quantity,
            unitLabel = unitLabel,
            netAmount = netAmount,
        )

    private fun tripItemContext(
        detailed: Boolean = false,
        expenses: List<TripExpenseRenderContext> = emptyList(),
        perDiemDays: Int = 0,
        perDiemTotal: String = "0,00",
        purpose: String? = "Workshop in Nürnberg",
        periodFrom: String? = "12.03.2026",
        periodTo: String? = "14.03.2026",
    ) = ItemRenderContext(
        position = "003",
        description = "Dienstreise Nürnberg",
        qty = "1",
        unitLabel = "Pausch",
        unitPrice = "450,00",
        lineTotal = "450,00",
        lineVat = "85,50",
        vatPercent = "19",
        category = InvoiceItemCategory.TRIP.code,
        itemDescription = purpose,
        servicePeriodFrom = periodFrom,
        servicePeriodTo = periodTo,
        tripDetailed = detailed,
        tripExpenses = expenses,
        tripPerDiemDays = perDiemDays,
        tripPerDiemTotal = perDiemTotal,
    )

    private fun invoiceContextWith(item: ItemRenderContext) = InvoiceRenderContext(items = listOf(item))

    // ── grouped (default, detailedOnInvoice = false) ──────────────────────────────

    @Test
    fun `grouped trip renders exactly one row`() {
        val rows = buildItems(invoiceContextWith(tripItemContext(detailed = false)), "de")
        assertEquals(1, rows.size)
    }

    @Test
    fun `grouped trip row keeps position, quantity and unit like a plain item`() {
        val row = buildItems(invoiceContextWith(tripItemContext(detailed = false)), "de").single()
        assertEquals("003", row.pos)
        assertTrue(row.qtyText.contains("Pausch"), "unit label missing: ${row.qtyText}")
        assertTrue(row.qtyText.contains("1"), "quantity missing: ${row.qtyText}")
    }

    @Test
    fun `grouped trip row's description carries the title and the purpose-period subline, never an amount`() {
        val row = buildItems(invoiceContextWith(tripItemContext(detailed = false)), "de").single()
        assertTrue(row.text.contains("Dienstreise Nürnberg"), "title missing: ${row.text}")
        assertTrue(row.text.contains("Zweck: Workshop in Nürnberg"), "purpose subline missing: ${row.text}")
        assertTrue(row.text.contains("Datum: 12.03.2026 – 14.03.2026"), "period subline missing: ${row.text}")
        assertFalse(row.text.contains("535,50"), "the position's gross must not appear in its description: ${row.text}")
        assertFalse(row.text.contains("Gesamt"), "no total line on a trip position: ${row.text}")
        assertFalse(row.text.contains("Netto") || row.text.contains("Brutto"), "must never say Netto/Brutto on a position subline: ${row.text}")
    }

    @Test
    fun `an English trip position carries no total line either`() {
        val row = buildItems(invoiceContextWith(tripItemContext(detailed = false)), "en").single()
        assertFalse(row.text.contains("Total"), "no total line expected: ${row.text}")
        assertFalse(row.text.contains("535,50"), "the position's gross must not appear in its description: ${row.text}")
    }

    @Test
    fun `no purpose and no period produce no subline at all`() {
        val row = buildItems(
            invoiceContextWith(tripItemContext(detailed = false, purpose = null, periodFrom = null, periodTo = null)),
            "de",
        ).single()
        assertFalse(row.text.contains("Zweck"), "no purpose captured, no Zweck line expected: ${row.text}")
        assertFalse(row.text.contains("Datum"), "no period captured, no Datum line expected: ${row.text}")
        assertFalse(row.text.contains("535,50"), "the position's gross must not appear in its description: ${row.text}")
    }

    @Test
    fun `a non-TRIP item never gets a purpose-period or total subline`() {
        val plainItem = ItemRenderContext(
            position = "1", description = "Consulting", qty = "1",
            itemDescription = "Some free text",
        )
        val row = buildItems(invoiceContextWith(plainItem), "de").single()
        assertFalse(row.text.contains("Zweck"), "no subline for a plain item: ${row.text}")
        assertFalse(row.text.contains("Gesamt"), "no total line for a plain item: ${row.text}")
    }

    // ── detailed ───────────────────────────────────────────────────────────────────

    @Test
    fun `detailed trip with expenses and per-diem days adds one indented sub-row per expense plus a per-diem summary row`() {
        val expenses = listOf(
            tripExpense("trip.kind.hotel", "Hotel XYZ", "2", "Nächte", "158,88"),
            tripExpense("trip.kind.transport", "Zugticket Berlin", "1", "Fahrt", "23,36"),
        )
        val rows = buildItems(invoiceContextWith(tripItemContext(detailed = true, expenses = expenses, perDiemDays = 2, perDiemTotal = "42,00")), "de")

        // main row + 2 expense sub-rows + 1 per-diem summary row
        assertEquals(4, rows.size)
    }

    @Test
    fun `sub-rows are numbered N-dot-1, N-dot-2 and so on after the trip's own position`() {
        val expenses = listOf(
            tripExpense("trip.kind.hotel", "Hotel XYZ", "2", "Nächte", "158,88"),
            tripExpense("trip.kind.transport", "Zugticket Berlin", "1", "Fahrt", "23,36"),
        )
        val rows = buildItems(invoiceContextWith(tripItemContext(detailed = true, expenses = expenses, perDiemDays = 1, perDiemTotal = "21,00")), "de")

        assertEquals("003", rows[0].pos)
        assertEquals("003.1", rows[1].pos)
        assertEquals("003.2", rows[2].pos)
        assertEquals("003.3", rows[3].pos)
    }

    @Test
    fun `an expense sub-row shows its kind, description, quantity-unit and net only — VAT columns blank`() {
        val expenses = listOf(tripExpense("trip.kind.hotel", "Hotel XYZ", "2", "Nächte", "158,88"))
        val row = buildItems(invoiceContextWith(tripItemContext(detailed = true, expenses = expenses)), "de")[1]

        assertTrue(row.text.contains("Unterkunft"), "kind label missing: ${row.text}")
        assertTrue(row.text.contains("Hotel XYZ"), "description missing: ${row.text}")
        assertTrue(row.qtyText.contains("2"), "quantity missing: ${row.qtyText}")
        assertTrue(row.qtyText.contains("Nächte"), "unit label missing: ${row.qtyText}")
        assertTrue(row.net.contains("158,88"), "net amount missing: ${row.net}")
        assertEquals("", row.price, "unit price column must stay blank on a sub-row")
        assertEquals("", row.vatAmt, "VAT amount column must stay blank on a sub-row")
        assertEquals("", row.vatPct, "VAT percent column must stay blank on a sub-row")
    }

    @Test
    fun `the per-diem summary row names the day count and the summed claimable amount`() {
        val rows = buildItems(invoiceContextWith(tripItemContext(detailed = true, perDiemDays = 3, perDiemTotal = "63,00")), "de")
        val summaryRow = rows.last()

        assertTrue(summaryRow.text.contains("Verpflegungspauschalen"), "per-diem label missing: ${summaryRow.text}")
        assertTrue(summaryRow.text.contains("3"), "day count missing: ${summaryRow.text}")
        assertTrue(summaryRow.net.contains("63,00"), "summed amount missing: ${summaryRow.net}")
    }

    @Test
    fun `README border case 11 — detailed but neither expenses nor per-diem days renders no sub-rows`() {
        val rows = buildItems(invoiceContextWith(tripItemContext(detailed = true, expenses = emptyList(), perDiemDays = 0)), "de")
        assertEquals(1, rows.size, "only the main row, no empty sub-rows/note")
    }

    @Test
    fun `README border case 7 — 100 expenses all get their own numbered sub-row`() {
        val expenses = (1..100).map { n -> tripExpense("trip.kind.other", "Posten $n", "1", "Stk", "1,00") }
        val rows = buildItems(invoiceContextWith(tripItemContext(detailed = true, expenses = expenses)), "de")

        // main row + 100 expense sub-rows, no per-diem summary row (perDiemDays = 0)
        assertEquals(101, rows.size)
        assertEquals("003.1", rows[1].pos)
        assertEquals("003.100", rows[100].pos)
    }

    /** The description cell's raw content: item text plus the trip's purpose-period subline. */
    private val ReportItemRow.text get() = "$description\n$tripSubline"

    private val ReportItemRow.qtyText get() = "$qtyPlain $unitLabel"
}
