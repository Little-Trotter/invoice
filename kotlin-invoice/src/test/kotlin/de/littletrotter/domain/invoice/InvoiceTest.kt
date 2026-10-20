package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * An issued, cancelled or cancellation document is edited by nobody: every editing method of the
 * aggregate refuses it by itself, while the mail marker after issuing stays possible.
 */
class InvoiceTest {

    private val item = anInvoiceItem(positionId = "1")

    private fun issued(): Invoice = anInvoice(id = 3L, status = InvoiceStatus.ISSUED, items = listOf(item))

    private fun assertRefused(invoice: Invoice, edit: Invoice.() -> Unit) {
        assertThrows<InvoiceImmutableException> { invoice.edit() }
    }

    @Test
    fun `updateHeader on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) { updateHeader(headerChange()) }
    }

    @Test
    fun `assignParties on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) { assignParties(parties()) }
    }

    @Test
    fun `applyPartySnapshots on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) {
            applyPartySnapshots(InvoicePartySnapshot.forSeller(aSenderProfile()), InvoicePartySnapshot.forBuyer(aRecipient()))
        }
    }

    @Test
    fun `decideReverseCharge on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) { decideReverseCharge(true) }
    }

    @Test
    fun `linkToQuote on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) { linkToQuote(UUID.randomUUID()) }
    }

    @Test
    fun `addItem on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) { addItem(anInvoiceItem(positionId = "2")) }
        assertEquals(1, invoice.itemList.size)
    }

    @Test
    fun `removeItem on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) { removeItem(item) }
        assertEquals(1, invoice.itemList.size)
    }

    @Test
    fun `recalculateTotals on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) { recalculateTotals() }
    }

    @Test
    fun `beginEdit on an issued invoice throws`() {
        val invoice = issued()
        assertRefused(invoice) { beginEdit(null) }
    }

    @Test
    fun `a cancellation document is refused although its status is not issued`() {
        val invoice = anInvoice(id = 4L, typeCode = InvoiceTypeCode.CORRECTED_INVOICE)
        assertRefused(invoice) { addItem(anInvoiceItem(positionId = "2")) }
    }

    @Test
    fun `beginEdit rejects a stale version and accepts the current one`() {
        val invoice = anInvoice(id = 5L, version = 2L)
        invoice.beginEdit(2L)
        invoice.beginEdit(null)
        assertThrows<jakarta.persistence.OptimisticLockException> { invoice.beginEdit(1L) }
    }

    @Test
    fun `beginEdit skips the version check for a not yet persisted invoice`() {
        val invoice = anInvoice(id = 6L, version = null)
        assertDoesNotThrow { invoice.beginEdit(3L) }
    }

    @Test
    fun `recalculateTotalsWhileEditable leaves an issued invoice untouched`() {
        val invoice = issued()
        val before = invoice.totals
        invoice.recalculateTotalsWhileEditable()
        assertEquals(before, invoice.totals)
    }

    @Test
    fun `addInitialItems is only possible before the invoice is persisted`() {
        val persisted = anInvoice(id = 6L)
        assertThrows<IllegalStateException> { persisted.addInitialItems(listOf(anInvoiceItem(positionId = "1"))) }
        val fresh = Invoice.withInitialStatus(InvoiceStatus.ISSUED)
        fresh.addInitialItems(listOf(anInvoiceItem(positionId = "1", taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) })))
        assertEquals(1, fresh.itemList.size)
    }

    @Test
    fun `the mail marker after issuing stays possible`() {
        val invoice = issued()
        invoice.recordMailSent(OffsetDateTime.now())
        assertEquals(true, invoice.mailSent)
    }
}
