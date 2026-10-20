package de.littletrotter.domain.repository

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.fixture.PersistenceEmulation
import de.littletrotter.fixture.anInvoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [InvoiceRepository]'s default method bodies (`updateValidationStatus`, `saveAndFlush`,
 * `detachIfManaged`, `findByIdWithItemsForStatusChange`) are compatibility fallbacks for in-memory
 * test doubles: the real behaviour lives in `PanacheInvoiceRepository`. They are real code with
 * real branches, so they get a direct test here rather than relying on being exercised
 * incidentally through a fake that overrides them anyway.
 */
class InvoiceRepositoryDefaultsTest {

    /** Minimal [InvoiceRepository] implementing only the non-default members, so every default
     *  body under test runs as the interface's own implementation. */
    private fun repoWith(vararg invoices: Invoice): InvoiceRepository = object : InvoiceRepository {
        private val data = invoices.toList()
        override fun findById(id: Long) = data.find { it.id == id }
        override fun findByIdWithItems(id: Long) = data.find { it.id == id }
        override fun findByInvoiceNumber(number: String) = data.find { it.invoiceNumber.value == number }
        override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
        override fun save(invoice: Invoice): Invoice = invoice
        override fun forceVersionIncrement(invoice: Invoice) { PersistenceEmulation.assignVersion(invoice, (invoice.version ?: 0L) + 1L) }
        override fun findAllForRecalculation() = data
    }

    @Test
    fun `updateValidationStatus transitions an existing invoice and returns true`() {
        val target = anInvoice(id = 1, validationStatus = ValidationStatus.VALID)
        val repo = repoWith(target)

        val result = repo.updateValidationStatus(1, ValidationStatus.PENDING)

        assertTrue(result)
        assertEquals(ValidationStatus.PENDING, target.validationStatus)
    }

    @Test
    fun `updateValidationStatus returns false when the invoice does not exist`() {
        val repo = repoWith()

        assertFalse(repo.updateValidationStatus(404, ValidationStatus.PENDING))
    }

    @Test
    fun `happy path saveAndFlush delegates to save`() {
        val repo = repoWith()
        val toSave = anInvoice(id = 1)

        assertEquals(toSave, repo.saveAndFlush(toSave))
    }

    @Test
    fun `boundary findByIdWithItemsForStatusChange falls back to the plain read`() {
        val target = anInvoice(id = 1)

        assertEquals(target, repoWith(target).findByIdWithItemsForStatusChange(1))
    }

    @Test
    fun `boundary detachIfManaged is a no-op for every in-memory test double`() {
        val repo = repoWith()

        repo.detachIfManaged(anInvoice(id = 1))
    }
}
