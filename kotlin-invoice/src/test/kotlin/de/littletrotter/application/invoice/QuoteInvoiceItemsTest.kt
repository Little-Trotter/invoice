package de.littletrotter.application.invoice

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.country.Country
import de.littletrotter.ui.view.quote.quoteItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

class QuoteInvoiceItemsTest {

    private val itemMutation: InvoiceItemMutationService = mock()
    private val taxRates: TaxRateRepository = mock()
    private val b2bValues: B2bFieldValueService = mock()
    private val items = QuoteInvoiceItems(itemMutation, taxRates, b2bValues)
    private val date = LocalDate.of(2026, 10, 6)

    private fun rate(id: Long, percent: String, category: String = "S", country: Country = Country.DE, default: Boolean = false, active: Boolean = true) =
        TaxRate().edit {
            this.id = id
            rate = TaxRatePercent(BigDecimal(percent))
            this.category = category
            this.country = country
            isDefault = default
            isActive = active
            isSelectable = true
        }

    private fun saved(lineId: Long): Invoice {
        val invoice: Invoice = mock()
        val line = anInvoiceItem(id = lineId)
        whenever(invoice.itemList).thenReturn(listOf(anInvoiceItem(id = lineId - 1), line))
        return invoice
    }

    private fun givenSaved(lineId: Long) {
        val invoice = saved(lineId)
        whenever(itemMutation.addItem(eq(9L), any())).thenReturn(invoice)
    }

    private fun givenRates(vararg rates: TaxRate) {
        whenever(taxRates.findValidAt(date)).thenReturn(rates.toList())
    }

    @Test
    fun `a service position maps one to one and stores article number and customer reference per item`() {
        givenRates(rate(1, "19.00"))
        givenSaved(77)

        items.add(9L, quoteItem(1), date)

        val command = argumentCaptor<InvoiceItemCommand>()
        verify(itemMutation).addItem(eq(9L), command.capture())
        assertEquals(InvoiceItemCategory.SERVICE, command.firstValue.category)
        assertEquals(InvoiceItemUnit.HOUR, command.firstValue.unit)
        assertEquals("Beratung 1", command.firstValue.description)
        assertEquals("Kundenwerk", command.firstValue.buyerItemDescription)
        assertEquals(BigDecimal("2.50"), command.firstValue.quantity)
        assertEquals(1L, command.firstValue.taxRateId)
        verify(b2bValues).saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 77L, mapOf("BT-155" to "A-1", "BT-156" to "K-77"))
    }

    @Test
    fun `without identifiers no item field values are written`() {
        givenRates(rate(1, "19.00"))
        givenSaved(77)

        items.add(9L, quoteItem(1).copy(articleNumber = " ", customerReference = null), date)

        verify(b2bValues, never()).saveFieldValues(any(), any(), any())
    }

    @Test
    fun `category and unit are taken over one to one from the offer line`() {
        givenRates(rate(1, "19.00"))
        givenSaved(77)
        val lines = listOf(
            InvoiceItemCategory.PRODUCT to InvoiceItemUnit.PIECE,
            InvoiceItemCategory.UNIT_PRICE to InvoiceItemUnit.SQUARE_METER,
            InvoiceItemCategory.SERVICE to InvoiceItemUnit.FLAT_RATE,
        )

        lines.forEach { (category, unit) -> items.add(9L, quoteItem(1).copy(category = category, unit = unit), date) }

        val command = argumentCaptor<InvoiceItemCommand>()
        verify(itemMutation, times(lines.size)).addItem(eq(9L), command.capture())
        assertEquals(lines, command.allValues.map { it.category to it.unit })
    }

    @Test
    fun `a position without a matching tax rate is refused`() {
        givenRates(rate(1, "7.00"), rate(2, "19.00", category = "E"), rate(3, "19.00", active = false))

        val refusal = assertThrows(QuoteInvoiceRejectedException::class.java) { items.add(9L, quoteItem(2), date) }

        assertEquals("Für Position 2 gibt es keinen gültigen Steuersatz (S, 19 %).", refusal.reason)
    }

    @Test
    fun `among several matching rates the German default wins`() {
        givenRates(rate(1, "19.00", country = Country.AT), rate(2, "19.00", default = false), rate(3, "19.00", default = true))
        givenSaved(77)

        items.add(9L, quoteItem(1), date)

        val command = argumentCaptor<InvoiceItemCommand>()
        verify(itemMutation).addItem(eq(9L), command.capture())
        assertEquals(3L, command.firstValue.taxRateId)
    }
}
