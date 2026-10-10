package de.littletrotter.infrastructure.dev

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.ValidationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** [DevInvoiceTerms] as the parameter object of [DevDataCatalogue.invoice]: the defaults and
 *  every explicit term land on the fixture invoice exactly as the former named arguments did. */
class DevInvoiceTermsTest {

    private val date = LocalDate.of(2025, 3, 1)
    private val sender = DevDataCatalogue.sender()
    private val recipient = DevDataCatalogue.recipient("DEVEDGE-K1", "Kunde", Country.DE, null)

    private fun serviceItem() = DevDataCatalogue.item(
        DevDataCatalogue.POS_001, InvoiceItemCategory.SERVICE, "Leistung",
        DevDataCatalogue.ItemAmounts("1", InvoiceItemUnit.FLAT_RATE, "100.00", DevDataCatalogue.VAT_STANDARD_RATE),
    )

    @Test
    fun `defaults describe an ordinary never-validated commercial invoice`() {
        val terms = DevInvoiceTerms()

        assertNull(terms.dueDate)
        assertFalse(terms.reverseCharge)
        assertNull(terms.freeText)
        assertEquals(InvoiceTypeCode.COMMERCIAL_INVOICE, terms.typeCode)
        assertNull(terms.originalInvoiceNumber)
        assertEquals(14, terms.paymentTermsDays)
        assertEquals(ValidationStatus.NOT_VALIDATED, terms.validationStatus)
    }

    @Test
    fun `invoice without terms carries the defaults`() {
        val invoice = DevDataCatalogue.invoice(InvoiceStatus.DRAFT, DevDataCatalogue.DevInvoiceParties(sender, recipient), date, items = listOf(serviceItem()))

        assertEquals(date, invoice.invoiceDate)
        assertNull(invoice.dueDate)
        assertEquals(14, invoice.paymentTermsDays)
        assertNull(invoice.freeText)
        assertEquals(InvoiceTypeCode.COMMERCIAL_INVOICE, invoice.typeCode)
        assertNull(invoice.originalInvoiceNumber)
        assertEquals(ValidationStatus.NOT_VALIDATED, invoice.validationStatus)
        assertFalse(invoice.reverseCharge)
    }

    @Test
    fun `boundary - storno-shaped terms land on every invoice field`() {
        val terms = DevInvoiceTerms(
            dueDate = null,
            typeCode = InvoiceTypeCode.CORRECTED_INVOICE,
            originalInvoiceNumber = "DEVEDGE-2025-00001",
            paymentTermsDays = 0,
            freeText = "Storno",
            validationStatus = ValidationStatus.INVALID,
        )

        val invoice = DevDataCatalogue.invoice(InvoiceStatus.CANCELLED, DevDataCatalogue.DevInvoiceParties(sender, recipient), date, listOf(serviceItem()), terms)

        assertNull(invoice.dueDate)
        assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, invoice.typeCode)
        assertEquals("DEVEDGE-2025-00001", invoice.originalInvoiceNumber)
        assertEquals(0, invoice.paymentTermsDays)
        assertEquals("Storno", invoice.freeText)
        assertEquals(ValidationStatus.INVALID, invoice.validationStatus)
    }

    @Test
    fun `boundary - reverse charge tags only the first item's tax category`() {
        val first = serviceItem()
        val second = serviceItem()

        val invoice = DevDataCatalogue.invoice(
            InvoiceStatus.SENT, DevDataCatalogue.DevInvoiceParties(sender, recipient), date, listOf(first, second),
            DevInvoiceTerms(dueDate = date.plusDays(30), reverseCharge = true),
        )

        assertTrue(invoice.reverseCharge)
        assertEquals(date.plusDays(30), invoice.dueDate)
        assertEquals("AE", first.taxCategory)
        assertNull(second.taxCategory)
    }

    @Test
    fun `boundary - reverse charge without items sets the flag and touches no item`() {
        val invoice = DevDataCatalogue.invoice(
            InvoiceStatus.VOIDED, DevDataCatalogue.DevInvoiceParties(sender, recipient), date, emptyList(), DevInvoiceTerms(reverseCharge = true),
        )

        assertTrue(invoice.reverseCharge)
        assertTrue(invoice.itemList.isEmpty())
    }
}
