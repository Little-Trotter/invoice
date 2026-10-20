package de.littletrotter.application

import de.littletrotter.application.dto.InvoiceCursor
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InvoiceCursorTest {

    private fun dto(id: Long?, date: LocalDate?) = InvoiceOverviewDto(
        id = id,
        invoiceNumber = "DS-2025-%04d".format(id ?: 0),
        typeCode = null,
        sellerName = null,
        sellerCity = null,
        recipientName = null,
        grossAmount = Money.ZERO,
        paymentLabel = null,
        freeText = null,
        itemsCount = 0,
        invoiceDate = date,
        status = InvoiceStatus.DRAFT,
        validationStatus = ValidationStatus.UNAVAILABLE,
    )

    @Test
    fun `from returns null for empty page`() {
        assertNull(InvoiceCursor.from(emptyList()))
    }

    @Test
    fun `from returns null when last element has null date`() {
        val page = listOf(dto(1L, null))
        assertNull(InvoiceCursor.from(page))
    }

    @Test
    fun `from returns null when last element has null id`() {
        val page = listOf(dto(null, LocalDate.of(2025, 1, 1)))
        assertNull(InvoiceCursor.from(page))
    }

    @Test
    fun `from captures last element of multi-element page`() {
        val page = listOf(
            dto(10L, LocalDate.of(2025, 5, 1)),
            dto(7L,  LocalDate.of(2025, 4, 1)),
            dto(3L,  LocalDate.of(2025, 3, 1)),
        )
        val cursor = InvoiceCursor.from(page)!!
        assertEquals(LocalDate.of(2025, 3, 1), cursor.lastDate)
        assertEquals(3L, cursor.lastId)
    }

    @Test
    fun `from captures single-element page correctly`() {
        val page = listOf(dto(42L, LocalDate.of(2024, 12, 31)))
        val cursor = InvoiceCursor.from(page)!!
        assertEquals(LocalDate.of(2024, 12, 31), cursor.lastDate)
        assertEquals(42L, cursor.lastId)
    }

    @Test
    fun `cursor equality and copy work as expected`() {
        val date = LocalDate.of(2025, 6, 15)
        val c1 = InvoiceCursor(date, 99L)
        val c2 = InvoiceCursor(date, 99L)
        assertEquals(c1, c2)
        assertEquals(c1, c1.copy())
    }
}
