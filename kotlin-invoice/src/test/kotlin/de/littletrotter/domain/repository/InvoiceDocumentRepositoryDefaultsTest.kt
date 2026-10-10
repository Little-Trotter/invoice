package de.littletrotter.domain.repository

import de.littletrotter.domain.invoice.InvoiceDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [InvoiceDocumentRepository.findByInvoiceId] declares default parameter values
 * (`pageIndex = 0`, `pageSize = 60`) without a body; Kotlin still compiles a synthetic `$default`
 * dispatcher into the interface for callers going through an interface-typed reference with
 * arguments omitted — covered the same way as [B2bProfileRepositoryDefaultsTest].
 */
class InvoiceDocumentRepositoryDefaultsTest {

    private class RecordingRepo : InvoiceDocumentRepository {
        var lastCall: Pair<Long, Pair<Int, Int>>? = null

        override fun findByInvoiceId(invoiceId: Long, pageIndex: Int, pageSize: Int): List<InvoiceDocument> {
            lastCall = invoiceId to (pageIndex to pageSize)
            return emptyList()
        }
        override fun findById(id: Long): InvoiceDocument? = null
        override fun save(doc: InvoiceDocument): InvoiceDocument = doc
        override fun findByObjectKey(objectKey: String): InvoiceDocument? = null
    }

    @Test
    fun `happy path findByInvoiceId with paging omitted uses the documented defaults`() {
        val repo = RecordingRepo()
        val ref: InvoiceDocumentRepository = repo

        ref.findByInvoiceId(42L)

        assertEquals(42L to (0 to 60), repo.lastCall)
    }

    @Test
    fun `boundary findByInvoiceId with explicit paging is forwarded verbatim`() {
        val repo = RecordingRepo()
        val ref: InvoiceDocumentRepository = repo

        ref.findByInvoiceId(42L, pageIndex = 2, pageSize = 10)

        assertEquals(42L to (2 to 10), repo.lastCall)
    }

    @Test
    fun `boundary findByInvoiceId with only pageIndex overridden keeps the pageSize default`() {
        val repo = RecordingRepo()
        val ref: InvoiceDocumentRepository = repo

        ref.findByInvoiceId(7L, pageIndex = 1)

        assertEquals(7L to (1 to 60), repo.lastCall)
    }

    @Test
    fun `boundary findByObjectKey is null when no row was ever persisted for the key`() {
        val repo = RecordingRepo()
        val ref: InvoiceDocumentRepository = repo

        assertEquals(null, ref.findByObjectKey("does-not-exist"))
    }
}
