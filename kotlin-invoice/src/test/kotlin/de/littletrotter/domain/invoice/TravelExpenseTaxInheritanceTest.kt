package de.littletrotter.domain.invoice

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Umsetzung §4 — a business trip ([InvoiceItemCategory.TRIP], replacing the deleted
 *  TRAVEL_EXPENSE/EXPENSE/PER_DIEM categories) never carries its own tax rate; it
 *  inherits the invoice's main-service item's rate. */
class TravelExpenseTaxInheritanceTest {

    private fun item(category: InvoiceItemCategory, rate: TaxRate? = null) =
        InvoiceItem().apply { this.category = category; taxRateRef = rate }

    @Test
    fun `R2-T14 - a reverse-charge main service item's rate is inherited by a trip`() {
        val ae = TaxRate().apply { code = "EU_B2B_RC_SERVICE"; category = "AE"; rate = TaxRatePercent(BigDecimal.ZERO) }
        val invoice = Invoice().apply {
            itemList.asMutableForTest().add(item(InvoiceItemCategory.SERVICE, ae))
            itemList.asMutableForTest().add(item(InvoiceItemCategory.TRIP))
        }
        assertEquals(ae, invoice.mainServiceTaxRate())
    }

    @Test
    fun `R2-T13 - a standard-rate main service item's rate is inherited by a trip`() {
        val standard = TaxRate().apply { code = "DE_STANDARD_19"; category = "S"; rate = TaxRatePercent(BigDecimal("19.00")) }
        val invoice = Invoice().apply {
            itemList.asMutableForTest().add(item(InvoiceItemCategory.PRODUCT, standard))
            itemList.asMutableForTest().add(item(InvoiceItemCategory.TRIP))
        }
        assertEquals(standard, invoice.mainServiceTaxRate())
    }

    @Test
    fun `R2-T15 - a trip is never the main service, even when it is the only item and carries a rate`() {
        val invoice = Invoice().apply {
            itemList.asMutableForTest().add(item(InvoiceItemCategory.TRIP, TaxRate().apply { rate = TaxRatePercent(BigDecimal("7.00")) }))
        }
        // No SERVICE/PRODUCT item exists — only the trip — so there is no main service to
        // inherit from, even though the trip item happens to already carry a tax rate.
        assertNull(invoice.mainServiceTaxRate())
    }

    @Test
    fun `mainServiceTaxRate is null when the invoice has no items at all`() {
        assertNull(Invoice().mainServiceTaxRate())
    }

    @Test
    fun `mainServiceTaxRate reads the first non-trip item even when it precedes trip items`() {
        val rate = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
        val invoice = Invoice().apply {
            itemList.asMutableForTest().add(item(InvoiceItemCategory.TRIP))
            itemList.asMutableForTest().add(item(InvoiceItemCategory.SERVICE, rate))
            itemList.asMutableForTest().add(item(InvoiceItemCategory.TRIP))
        }
        assertEquals(rate, invoice.mainServiceTaxRate())
    }
}
