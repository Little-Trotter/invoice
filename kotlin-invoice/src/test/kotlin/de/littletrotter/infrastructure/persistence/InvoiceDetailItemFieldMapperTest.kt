package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvoiceDetailItemFieldMapperTest {

    private fun itemRow(id: Long, overrides: Map<String, Any?> = emptyMap()): MapTuple {
        val base = mapOf(
            "id" to id, "positionId" to "1", "description" to "d", "quantity" to BigDecimal("2"),
            "unit" to "H", "unitPriceNet" to BigDecimal("50"), "taxRate" to BigDecimal("19"),
            "taxRateRefId" to 1L, "netAmount" to BigDecimal("100"), "vatAmount" to BigDecimal("19"),
            "grossAmount" to BigDecimal("119"), "version" to 0L, "category" to "SERVICE", "sortOrder" to 0,
            "deliveryNoteReference" to "LS-1", "itemDescription" to null, "articleRefId" to null,
            "exemptionReasonText" to null, "taxLegalBasis" to null, "taxCategory" to "S",
            "tripDetailedOnInvoice" to false, "tripFixedNetPrice" to null,
            "servicePeriodFrom" to null, "servicePeriodTo" to null, "buyerItemDescription" to null,
        )
        return MapTuple(base + overrides)
    }

    @Test
    fun `mapAll assembles a plain SERVICE item with its receipts, no trip detail`() {
        val items = InvoiceDetailItemFieldMapper.mapAll(
            itemRows = listOf(itemRow(11L)),
            receiptRows = listOf(arrayOf(11L, 100L), arrayOf(11L, 101L)),
            travelRows = emptyList(),
            expenseRows = emptyList(),
        )

        val item = items.single()
        assertEquals(11L, item.id)
        assertEquals("1", item.positionId)
        assertEquals(2, item.receiptsCount)
        assertEquals(listOf(100L, 101L), item.receiptIds)
        assertNull(item.trip)
    }

    @Test
    fun `mapAll returns 0 receipts and an empty receiptIds list when no receipt row references the item`() {
        val items = InvoiceDetailItemFieldMapper.mapAll(
            itemRows = listOf(itemRow(11L)),
            receiptRows = emptyList(),
            travelRows = emptyList(),
            expenseRows = emptyList(),
        )

        val item = items.single()
        assertEquals(0, item.receiptsCount)
        assertEquals(emptyList<Long>(), item.receiptIds)
    }

    @Test
    fun `mapAll filters out a null receipt id from a left-joined item with no receipts`() {
        val items = InvoiceDetailItemFieldMapper.mapAll(
            itemRows = listOf(itemRow(11L)),
            receiptRows = listOf(arrayOf(11L, null)),
            travelRows = emptyList(),
            expenseRows = emptyList(),
        )

        val item = items.single()
        assertEquals(0, item.receiptsCount)
        assertEquals(emptyList<Long>(), item.receiptIds)
    }

    @Test
    fun `mapAll decodes every optional item column that the other fixtures leave at its default`() {
        val items = InvoiceDetailItemFieldMapper.mapAll(
            itemRows = listOf(
                itemRow(
                    15L,
                    mapOf(
                        "articleRefId" to 9L, "itemDescription" to "Langtext", "exemptionReasonText" to "Reverse-Charge",
                        "taxLegalBasis" to "§13b UStG", "buyerItemDescription" to "Kundenbezeichnung",
                        "servicePeriodFrom" to java.time.LocalDate.of(2026, 1, 1), "servicePeriodTo" to java.time.LocalDate.of(2026, 1, 31),
                        "deliveryNoteReference" to null,
                    ),
                ),
            ),
            receiptRows = emptyList(),
            travelRows = emptyList(),
            expenseRows = emptyList(),
        )

        val item = items.single()
        assertEquals(9L, item.articleRefId)
        assertEquals("Langtext", item.itemDescription)
        assertEquals("Reverse-Charge", item.exemptionReasonText)
        assertEquals("§13b UStG", item.taxLegalBasis)
        assertEquals("Kundenbezeichnung", item.buyerItemDescription)
        assertEquals(java.time.LocalDate.of(2026, 1, 1), item.servicePeriodFrom)
        assertEquals(java.time.LocalDate.of(2026, 1, 31), item.servicePeriodTo)
        assertNull(item.deliveryNoteReference)
    }

    @Test
    fun `mapAll decodes null-optional item columns without throwing`() {
        val items = InvoiceDetailItemFieldMapper.mapAll(
            itemRows = listOf(itemRow(12L, mapOf("unit" to null, "taxRateRefId" to null, "version" to null, "category" to null))),
            receiptRows = emptyList(),
            travelRows = emptyList(),
            expenseRows = emptyList(),
        )

        val item = items.single()
        assertNull(item.unit)
        assertNull(item.taxRateRefId)
        assertNull(item.version)
        assertNull(item.category)
        assertNull(item.trip, "a null category must never enter the TRIP branch")
    }

    @Test
    fun `mapAll builds a TripDto for a TRIP item from its batched travel and expense rows`() {
        val items = InvoiceDetailItemFieldMapper.mapAll(
            itemRows = listOf(
                itemRow(13L, mapOf("category" to "TRIP", "tripDetailedOnInvoice" to true, "tripFixedNetPrice" to BigDecimal("200"))),
            ),
            receiptRows = emptyList(),
            travelRows = listOf(
                arrayOf(
                    901L, 13L, java.time.LocalDate.of(2026, 3, 1), "DE", false, true, true, false,
                    BigDecimal("28.00"), 0L, Money(BigDecimal("28.00")), Money(BigDecimal("14.00")),
                    Money.ZERO, "Dienstreise", "Max Mustermann", 55L, "DE-Satz",
                ),
            ),
            expenseRows = listOf(
                arrayOf(
                    801L, 13L, "TRAIN", "Zugticket", java.time.LocalDate.of(2026, 3, 1), BigDecimal("1"),
                    "STK", Money(BigDecimal("50.00")), Money(BigDecimal("50.00")), 700L, 0, 0L, true,
                ),
            ),
        )

        val item = items.single()
        val trip = item.trip
        assertTrue(item.category == "TRIP")
        requireNotNull(trip)
        assertEquals(true, trip.detailedOnInvoice)
        assertEquals(Money(BigDecimal("200")), trip.fixedNetPrice)
        assertEquals(Money(BigDecimal("78.00")), trip.calculatedNet, "50.00 (expense) + 28.00 (per-diem day)")
        assertEquals(1, trip.expenses.size)
        assertEquals(1, trip.perDiemDays.size)
    }

    @Test
    fun `mapAll builds a TripDto whose calculatedNet handles a null per-diem calculatedAmount cell`() {
        val items = InvoiceDetailItemFieldMapper.mapAll(
            itemRows = listOf(itemRow(14L, mapOf("category" to "TRIP", "tripDetailedOnInvoice" to false, "tripFixedNetPrice" to null))),
            receiptRows = emptyList(),
            travelRows = listOf(
                arrayOf(
                    902L, 14L, java.time.LocalDate.of(2026, 3, 2), "DE", true, false, false, false,
                    null, null, Money(BigDecimal("28.00")), Money(BigDecimal("14.00")), Money.ZERO,
                    "Dienstreise", "Max Mustermann", null, null,
                ),
            ),
            expenseRows = listOf(
                arrayOf(
                    802L, 14L, "TAXI", "Taxifahrt", null, BigDecimal("1"), "STK",
                    Money(BigDecimal("20.00")), Money(BigDecimal("20.00")), null, 0, null, false,
                ),
            ),
        )

        val trip = items.single().trip
        requireNotNull(trip)
        assertEquals(false, trip.detailedOnInvoice)
        assertNull(trip.fixedNetPrice)
        assertEquals(Money(BigDecimal("20.00")), trip.calculatedNet, "only the 20.00 expense; the per-diem day's calculatedAmount cell is null")
        val travelDay = trip.perDiemDays.single()
        assertNull(travelDay.version, "a null travel-entry version cell must decode to null")
        val expense = trip.expenses.single()
        assertNull(expense.receiptId, "a null expense receiptId cell must decode to null")
        assertNull(expense.version, "a null expense version cell must decode to null")
        assertEquals(false, expense.rateProvided)
    }
}
