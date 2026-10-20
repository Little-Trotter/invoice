package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class InvoiceOverviewSidebarTest {

    private fun item(category: InvoiceItemCategory, net: String) = ItemDto(
        id = 1L,
        positionId = "1",
        description = "",
        quantity = BigDecimal.ONE,
        unit = null,
        unitPriceNet = Money.ZERO,
        taxRate = null,
        taxRateRefId = null,
        netAmount = Money(BigDecimal(net)),
        vatAmount = null,
        grossAmount = null,
        category = category.name,
    )

    // ── Empty / no items ──────────────────────────────────────────────────────

    @Test
    fun `empty item list produces no summaries`() {
        val result = buildCategorySummaries(emptyList())
        assertTrue(result.isEmpty())
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

    @Test
    fun `SERVICE items appear in Leistungen group`() {
        val result = buildCategorySummaries(listOf(
            item(InvoiceItemCategory.SERVICE, "100.00"),
        ))
        assertEquals(1, result.size)
        assertEquals("invoiceDetail.summary.dashboard.services", result[0].labelKey)
        assertEquals(1, result[0].count)
    }

    @Test
    fun `PRODUCT items appear in goods group`() {
        val result = buildCategorySummaries(listOf(
            item(InvoiceItemCategory.PRODUCT, "50.00"),
        ))
        assertEquals(1, result.size)
        assertEquals("invoiceDetail.summary.dashboard.goods", result[0].labelKey)
    }

    // TRAVEL_EXPENSE/EXPENSE/PER_DIEM were merged into InvoiceItemCategory.TRIP: EXPENSE
    // no longer exists, so the "EXPENSE grouped with PRODUCT under goods" case this test used to
    // pin is gone with it (`every InvoiceItemCategory is covered by exactly one group` below
    // proves TRIP now maps solely to the travel group, not goods).

    @Test
    fun `TRIP items appear in Reisekosten group`() {
        val result = buildCategorySummaries(listOf(
            item(InvoiceItemCategory.TRIP, "28.00"),
        ))
        assertEquals(1, result.size)
        assertEquals("invoiceDetail.tab.trips", result[0].labelKey)
    }

    @Test
    fun `UNIT_PRICE items appear in Einheitspreis group`() {
        val result = buildCategorySummaries(listOf(
            item(InvoiceItemCategory.UNIT_PRICE, "75.00"),
        ))
        assertEquals(1, result.size)
        assertEquals("invoiceDetail.summary.dashboard.unitPrice", result[0].labelKey)
        assertEquals(1, result[0].count)
    }

    @Test
    fun `multiple TRIP items aggregate under the Reisekosten group`() {
        val result = buildCategorySummaries(listOf(
            item(InvoiceItemCategory.TRIP, "150.00"),
            item(InvoiceItemCategory.TRIP, "56.00"),
        ))
        assertEquals(1, result.size)
        assertEquals("invoiceDetail.tab.trips", result[0].labelKey)
        assertEquals(2, result[0].count)
        assertEquals(Money.of("206.00"), result[0].netTotal)
    }

    // ── Net total aggregation ─────────────────────────────────────────────────

    @Test
    fun `net totals are summed per group`() {
        val result = buildCategorySummaries(listOf(
            item(InvoiceItemCategory.SERVICE, "1000.00"),
            item(InvoiceItemCategory.SERVICE, "500.00"),
        ))
        assertEquals(1, result.size)
        assertEquals(Money.of("1500.00"), result[0].netTotal)
        assertEquals(2, result[0].count)
    }

    @Test
    fun `items with zero net amount are included and sum to zero`() {
        val result = buildCategorySummaries(listOf(
            item(InvoiceItemCategory.SERVICE, "0.00"),
            item(InvoiceItemCategory.SERVICE, "0.00"),
        ))
        assertEquals(1, result.size)
        assertEquals(2, result[0].count)
        assertEquals(Money.ZERO, result[0].netTotal)
    }

    // ── Groups without items are suppressed ───────────────────────────────────

    @Test
    fun `groups with no matching items are not returned`() {
        val result = buildCategorySummaries(listOf(
            item(InvoiceItemCategory.SERVICE, "100.00"),
        ))
        // Only Leistungen present; Waren and Reisekosten must be absent
        val labels = result.map { it.labelKey }
        assertTrue("invoiceDetail.summary.dashboard.services" in labels)
        assertTrue("invoiceDetail.summary.dashboard.goods" !in labels)
        assertTrue("invoiceDetail.tab.trips" !in labels)
    }

    // ── Full mixed invoice ────────────────────────────────────────────────────

    @Test
    fun `mixed invoice with all three categories returns three summaries in canonical order`() {
        val items = listOf(
            item(InvoiceItemCategory.TRIP,          "28.00"),
            item(InvoiceItemCategory.PRODUCT,       "200.00"),
            item(InvoiceItemCategory.SERVICE,        "1000.00"),
            item(InvoiceItemCategory.SERVICE,        "500.00"),
            item(InvoiceItemCategory.PRODUCT,        "50.00"),
        )
        val result = buildCategorySummaries(items)

        assertEquals(3, result.size)
        assertEquals(
            listOf(
                "invoiceDetail.summary.dashboard.services",
                "invoiceDetail.summary.dashboard.goods",
                "invoiceDetail.tab.trips",
            ),
            result.map { it.labelKey },
        )
        assertEquals(2, result[0].count)  // 2 SERVICE
        assertEquals(2, result[1].count)  // 2 PRODUCT
        assertEquals(1, result[2].count)  // 1 TRIP

        assertEquals(Money.of("1500.00"), result[0].netTotal)
        assertEquals(Money.of("250.00"), result[1].netTotal)
        assertEquals(Money.of("28.00"), result[2].netTotal)
    }

    // ── Icons are set ─────────────────────────────────────────────────────────

    @Test
    fun `each summary carries a non-blank icon`() {
        val items = listOf(
            item(InvoiceItemCategory.SERVICE,        "1.00"),
            item(InvoiceItemCategory.PRODUCT,       "1.00"),
            item(InvoiceItemCategory.TRIP,           "1.00"),
        )
        buildCategorySummaries(items).forEach { s ->
            assertTrue(s.icon.isNotBlank(), "Icon missing for group ${s.labelKey}")
        }
    }

    // ── INVOICE_CATEGORY_GROUPS contract ─────────────────────────────────────

    @Test
    fun `every InvoiceItemCategory is covered by exactly one group`() {
        val allCategories = InvoiceItemCategory.entries.toSet()
        val covered = INVOICE_CATEGORY_GROUPS.flatMap { (_, _, cats) -> cats }.toSet()
        assertEquals(allCategories, covered, "Every category must map to exactly one group")
    }

    @Test
    fun `groups have unique label keys`() {
        val keys = INVOICE_CATEGORY_GROUPS.map { (_, labelKey, _) -> labelKey }
        assertEquals(keys.size, keys.toSet().size, "Duplicate label keys in group definitions")
    }

    // ── resolvePaymentTerm ────────────────────────────────────────────────────

    private fun paymentTerm(name: String) = PaymentTermDto(namesByLanguageCode = mapOf("de" to name), fallbackLanguageCode = "de")

    @Test
    fun `invoice term is returned when set`() {
        val invoiceTerm = paymentTerm("30 Tage")
        val defaultTerm = paymentTerm("14 Tage")
        assertEquals(invoiceTerm, resolvePaymentTerm(invoiceTerm, defaultTerm))
    }

    @Test
    fun `default term is returned when invoice has no payment term`() {
        val defaultTerm = paymentTerm("14 Tage")
        assertEquals(defaultTerm, resolvePaymentTerm(null, defaultTerm))
    }

    @Test
    fun `null is returned when both invoice term and default are absent`() {
        assertNull(resolvePaymentTerm(null, null))
    }

    @Test
    fun `invoice term is preferred over default even when default exists`() {
        val invoiceTerm = paymentTerm("Sofort")
        val defaultTerm  = paymentTerm("30 Tage")
        val result = resolvePaymentTerm(invoiceTerm, defaultTerm)
        assertEquals("Sofort", result?.nameFor("de"))
    }

    // ── computeDueDate ────────────────────────────────────────────────────────

    @Test
    fun `due date is invoice date plus payment term days`() {
        val invoiceDate = LocalDate.of(2026, 4, 1)
        assertEquals(LocalDate.of(2026, 5, 1), computeDueDate(invoiceDate, 30))
    }

    @Test
    fun `due date with 14 days term`() {
        val invoiceDate = LocalDate.of(2026, 4, 1)
        assertEquals(LocalDate.of(2026, 4, 15), computeDueDate(invoiceDate, 14))
    }

    @Test
    fun `due date with a 0 day term equals the invoice date (payable immediately)`() {
        val invoiceDate = LocalDate.of(2026, 4, 5)
        assertEquals(invoiceDate, computeDueDate(invoiceDate, 0))
    }

    @Test
    fun `null invoice date returns null due date`() {
        assertNull(computeDueDate(null, 30))
    }

    @Test
    fun `due date respects month and year boundaries`() {
        val invoiceDate = LocalDate.of(2026, 12, 15)
        assertEquals(LocalDate.of(2027, 1, 14), computeDueDate(invoiceDate, 30))
    }

    @Test
    fun `due date respects leap year`() {
        val invoiceDate = LocalDate.of(2028, 2, 1)
        assertEquals(LocalDate.of(2028, 3, 2), computeDueDate(invoiceDate, 30))
    }
}
