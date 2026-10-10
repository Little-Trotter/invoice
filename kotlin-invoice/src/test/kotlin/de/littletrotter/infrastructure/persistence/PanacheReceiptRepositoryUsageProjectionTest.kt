package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceNumber
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [PanacheReceiptRepository.invoiceNumberOf]'s `null`/"projected as something other than
 * [InvoiceNumber]" branches and `findReceiptUsageBatch`/`findReceiptUsage`'s null-`InvoiceStatus`
 * fallback have no real-Postgres path: `Invoice.invoiceNumber`/`.status` are NOT NULL columns
 * mapped through converters, so every row a real `PanacheReceiptRepositoryIT` fixture produces
 * already has a non-null, correctly-typed value. This drives both native projection queries
 * directly against a mocked [EntityManager], the same style as [PostgresDatabaseMaintenanceServiceTest].
 */
class PanacheReceiptRepositoryUsageProjectionTest {

    private fun repository(itemRows: List<Array<Any?>>, invoiceRows: List<Array<Any?>>): PanacheReceiptRepository {
        val itemQuery = mock<Query>()
        whenever(itemQuery.setParameter(any<String>(), any())).thenReturn(itemQuery)
        whenever(itemQuery.resultList).thenReturn(itemRows)

        val invoiceQuery = mock<Query>()
        whenever(invoiceQuery.setParameter(any<String>(), any())).thenReturn(invoiceQuery)
        whenever(invoiceQuery.resultList).thenReturn(invoiceRows)

        val em = mock<EntityManager>()
        whenever(em.createQuery(argThat<String> { contains("FROM InvoiceItem i JOIN i.receipts r") })).thenReturn(itemQuery)
        whenever(em.createQuery(argThat<String> { contains("FROM Invoice i JOIN i.receipts r") })).thenReturn(invoiceQuery)

        val repository = PanacheReceiptRepository()
        val field = BasePanacheRepository::class.java.getDeclaredField("em")
        field.isAccessible = true
        field.set(repository, em)
        return repository
    }

    @Test
    fun `boundary - findReceiptUsageBatch decodes a null invoice number and a null invoice status`() {
        // arr = [receiptId, invoiceId, invoiceNumber, positionId, itemId, status]
        val itemRow = arrayOf<Any?>(1L, 10L, null, "Pos-1", 100L, null)
        val repository = repository(itemRows = listOf(itemRow), invoiceRows = emptyList())

        val usage = repository.findReceiptUsageBatch(listOf(1L)).getValue(1L).single()

        assertEquals("", usage.invoiceNumber, "a null projection decodes to the empty string, not a crash")
        assertEquals(false, usage.invoiceReadOnly, "a null status is never read-only")
        assertEquals(false, usage.invoiceIssued, "a null status is never issued")
    }

    @Test
    fun `boundary - findReceiptUsageBatch decodes an invoice number projected as a raw non-InvoiceNumber type`() {
        // arr = [receiptId, invoiceId, invoiceNumber, status]
        val invoiceRow = arrayOf<Any?>(2L, 20L, "RE-2026-0099", null)
        val repository = repository(itemRows = emptyList(), invoiceRows = listOf(invoiceRow))

        val usage = repository.findReceiptUsageBatch(listOf(2L)).getValue(2L).single()

        assertEquals("RE-2026-0099", usage.invoiceNumber, "a raw String projection falls back to toString()")
        assertEquals(null, usage.positionId)
        assertEquals(null, usage.invoiceItemId)
    }

    @Test
    fun `boundary - findReceiptUsage decodes a null invoice number and a null invoice status for both link levels`() {
        // findReceiptUsage's two JPQL strings share the same "FROM InvoiceItem/Invoice i JOIN
        // i.receipts r" substrings as findReceiptUsageBatch's, so the same query stubs match.
        val itemRow = arrayOf<Any?>(10L, InvoiceNumber("RE-2026-0100"), "Pos-1", 100L, null)
        val invoiceRow = arrayOf<Any?>(20L, null, null)
        val repository = repository(itemRows = listOf(itemRow), invoiceRows = listOf(invoiceRow))

        val usage = repository.findReceiptUsage(99L)

        assertEquals(2, usage.size)
        val itemUsage = usage.first { it.positionId != null }
        assertEquals("RE-2026-0100", itemUsage.invoiceNumber)
        assertEquals(false, itemUsage.invoiceReadOnly)
        assertEquals(false, itemUsage.invoiceIssued)
        val invoiceUsage = usage.first { it.positionId == null }
        assertEquals("", invoiceUsage.invoiceNumber, "a null projection decodes to the empty string")
        assertEquals(false, invoiceUsage.invoiceReadOnly)
        assertEquals(false, invoiceUsage.invoiceIssued)
    }
}
