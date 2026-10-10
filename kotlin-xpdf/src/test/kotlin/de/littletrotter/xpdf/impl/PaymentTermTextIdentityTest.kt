package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.xpdf.impl.mustang.MustangExportModelMapper
import de.littletrotter.xpdf.impl.mustang.MustangTransactionBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PO decision 1 (06.09.2026) — "one text for PDF and BT-20, byte-identical" for the payment
 * term's own name. This test is the assertion the decision explicitly asks for: for the same
 * [InvoiceDto], the printed payment line ([InvoiceReportContextBuilder.buildPaymentDueText], read
 * off `buildContext`'s `paymentDueText` key) and BT-20
 * ([de.littletrotter.xpdf.impl.mustang.MustangTransactionBuilder.getPaymentTermDescription]) name
 * the exact same term, across every shape: a chosen payment term (with and without an English
 * name), the no-payment-term-at-all fallback, and the storno null case. PO decision of
 * 28.09.2026: when a due date is also known, the PDF card additionally names it beside the term
 * (BT-20 already carries due date and term as separate BT fields, so it needs no such addition) —
 * the printed text then only starts with, rather than equals, the wire's term description.
 */
class PaymentTermTextIdentityTest {

    private fun paymentDueText(invoice: InvoiceDto): String? =
        InvoiceReportContextBuilder().buildContext(invoice).invoice.paymentDueText

    private fun bt20(invoice: InvoiceDto): String? =
        MustangTransactionBuilder(documentStore = null).build(MustangExportModelMapper.toModel(invoice)).paymentTermDescription

    @Test
    fun `a chosen payment term's German name opens the printed due-date sentence and is identical to the wire term`() {
        val invoice = InvoiceDto(
            meta = InvoiceMetaDto(documentLanguage = "de"),
            dates = InvoiceDatesDto(paymentTermName = "30 Tage netto", dueDate = "2026-09-30", paymentTermsDays = 30),
        )
        assertEquals("30 Tage netto · fällig am 30.09.2026", paymentDueText(invoice))
        assertEquals("30 Tage netto", bt20(invoice))
        assertTrue(paymentDueText(invoice)!!.startsWith(bt20(invoice) as String))
    }

    @Test
    fun `a chosen payment term's English name opens the printed due-date sentence and is identical to the wire term`() {
        val invoice = InvoiceDto(
            meta = InvoiceMetaDto(documentLanguage = "en"),
            dates = InvoiceDatesDto(paymentTermName = "Net 30 days", dueDate = "2026-09-30", paymentTermsDays = 30),
        )
        assertEquals("Net 30 days · due on 30 Sep 2026", paymentDueText(invoice))
        assertEquals("Net 30 days", bt20(invoice))
        assertTrue(paymentDueText(invoice)!!.startsWith(bt20(invoice) as String))
    }

    @Test
    fun `the no-payment-term-at-all fallback sentence is identical on both outputs`() {
        val invoice = InvoiceDto(
            meta = InvoiceMetaDto(documentLanguage = "de"),
            dates = InvoiceDatesDto(paymentTermName = null, dueDate = null, paymentTermsDays = 14),
        )
        assertEquals("Zahlbar innerhalb 14 Tagen netto", paymentDueText(invoice))
        assertEquals(paymentDueText(invoice), bt20(invoice))
    }

    @Test
    fun `the storno null case is identical (null) on both outputs, unaffected by IN-4`() {
        val invoice = InvoiceDto(
            storno = true,
            meta = InvoiceMetaDto(documentLanguage = "de"),
            dates = InvoiceDatesDto(paymentTermName = null, dueDate = null, paymentTermsDays = 0),
        )
        assertNull(paymentDueText(invoice))
        // MustangExportModel has no explicit storno flag (PDF's own invoice.storno check is not
        // mirrored there — see MustangTransactionBuilder.getPaymentTermDescription's own KDoc);
        // it infers the same null result from paymentTermsDays == 0 && dueDate == null, the exact
        // shape InvoiceStornoService produces and only it produces.
        assertNull(bt20(invoice))
    }
}
