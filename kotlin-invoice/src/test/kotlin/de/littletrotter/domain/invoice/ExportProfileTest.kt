package de.littletrotter.domain.invoice

import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent

/** F4/R8 — [resolveExportProfileId] must never disagree between the finalization gate and the
 *  document job publisher about which profile an invoice would export as; both read this one
 *  function. Exercises every reachable combination of a present/blank/absent VAT id on each side. */
class ExportProfileTest {

    @Test
    fun `no vat id on either side resolves to EN16931`() {
        assertEquals("EN16931", resolveExportProfileId(sellerVatId = null, buyerVatId = null))
    }

    @Test
    fun `blank vat ids on both sides are treated the same as absent`() {
        assertEquals("EN16931", resolveExportProfileId(sellerVatId = "  ", buyerVatId = ""))
    }

    @Test
    fun `a seller vat id alone already selects XRechnung`() {
        assertEquals("XRechnung", resolveExportProfileId(sellerVatId = "DE123456789", buyerVatId = null))
    }

    @Test
    fun `a buyer vat id alone already selects XRechnung`() {
        assertEquals("XRechnung", resolveExportProfileId(sellerVatId = null, buyerVatId = "FR12345678901"))
    }

    @Test
    fun `both sides carrying a vat id also selects XRechnung`() {
        assertEquals("XRechnung", resolveExportProfileId(sellerVatId = "DE123456789", buyerVatId = "FR12345678901"))
    }

    @Test
    fun `a blank seller vat id does not mask a real buyer vat id`() {
        assertEquals("XRechnung", resolveExportProfileId(sellerVatId = " ", buyerVatId = "FR12345678901"))
    }

    @Test
    fun `a blank buyer vat id does not mask a real seller vat id`() {
        assertEquals("XRechnung", resolveExportProfileId(sellerVatId = "DE123456789", buyerVatId = " "))
    }

    // ─── EXTENDED escalation (PO "profile escalation, only when Skonto") ────────

    @Test
    fun `no vat id with extended features requested resolves to EXTENDED`() {
        assertEquals(
            "EXTENDED",
            resolveExportProfileId(sellerVatId = null, buyerVatId = null, extendedFeatures = true),
        )
    }

    @Test
    fun `a vat id wins over extended features - XRechnung is never escalated`() {
        assertEquals(
            "XRechnung",
            resolveExportProfileId(sellerVatId = "DE123456789", buyerVatId = null, extendedFeatures = true),
        )
    }

    private fun invoiceWithDiscount(
        typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
    ) = anInvoice(
        typeCode = typeCode,
        invoiceDate = LocalDate.of(2026, 9, 28),
        paymentTerm = aPaymentTerm {
            discountPercent = DiscountPercent(BigDecimal("2"))
            discountDays = 7
        },
        // one untaxed position makes the gross total equal to the given amount
        items = listOf(
            anInvoiceItem(
                positionId = "1",
                description = "Position",
                unitPriceNet = Money(BigDecimal("604.66")),
                taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal.ZERO) },
            ),
        ),
        recalculate = true,
    )

    @Test
    fun `requiresExtendedProfile is false for an invoice with a cash discount while the opt-in is off`() {
        assertFalse(invoiceWithDiscount().requiresExtendedProfile(extendedProfileEnabled = false))
    }

    @Test
    fun `requiresExtendedProfile is true for an invoice carrying a cash discount once opted in`() {
        assertTrue(invoiceWithDiscount().requiresExtendedProfile(extendedProfileEnabled = true))
    }

    @Test
    fun `requiresExtendedProfile is false without a cash discount even with the opt-in on`() {
        val invoice = anInvoice()
        assertFalse(invoice.requiresExtendedProfile(extendedProfileEnabled = true))
    }

    @Test
    fun `requiresExtendedProfile is false for a cancellation even if it once had a discount term and the opt-in is on`() {
        val invoice = invoiceWithDiscount(InvoiceTypeCode.CORRECTED_INVOICE)
        assertFalse(invoice.requiresExtendedProfile(extendedProfileEnabled = true))
    }
}
