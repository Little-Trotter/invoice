package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * [PanacheInvoiceDocumentRepository] against a real Testcontainers Postgres.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheInvoiceDocumentRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: InvoiceDocumentRepository

    private companion object {
        val invoiceSequence = AtomicLong(System.currentTimeMillis())
    }

    private fun invoice(): Invoice =
        Invoice().apply {
            invoiceNumber = InvoiceNumber("COV09-2031-01-01-${invoiceSequence.incrementAndGet()}")
        }.also { em.persist(it) }

    private fun document(invoice: Invoice, objectKey: String, generatedAt: OffsetDateTime?): InvoiceDocument =
        InvoiceDocument().apply {
            this.invoice = invoice
            this.shortName = "COV09 Document"
            this.documentType = "PDF"
            this.objectKey = objectKey
            this.fileName = "invoice.pdf"
            this.mimeType = "application/pdf"
            this.sha256Checksum = "c".repeat(64)
            this.generatedAt = generatedAt
        }.also { em.persist(it) }

    @Test
    @TestTransaction
    fun `happy path findByInvoiceId pages newest-first and save-findById round-trips`() {
        val inv = invoice()
        val now = OffsetDateTime.now()
        val oldest = document(inv, "zzcov9-1", now.minusHours(2))
        val middle = document(inv, "zzcov9-2", now.minusHours(1))
        val newest = document(inv, "zzcov9-3", now)
        em.flush()

        val firstPage = repository.findByInvoiceId(inv.id!!, pageIndex = 0, pageSize = 2)
        val secondPage = repository.findByInvoiceId(inv.id!!, pageIndex = 1, pageSize = 2)

        assertEquals(listOf(newest.id, middle.id), firstPage.map { it.id })
        assertEquals(listOf(oldest.id), secondPage.map { it.id })
        assertEquals(newest.id, repository.findById(newest.id!!)?.id)
    }

    @Test
    @TestTransaction
    fun `boundary findByInvoiceId returns an empty list for an invoice without documents`() {
        val inv = invoice()
        em.flush()

        assertTrue(repository.findByInvoiceId(inv.id!!, pageIndex = 0, pageSize = 10).isEmpty())
    }

    @Test
    @TestTransaction
    fun `boundary paging beyond the available rows returns an empty page`() {
        val inv = invoice()
        document(inv, "zzcov9-beyond-1", OffsetDateTime.now())
        em.flush()

        assertTrue(repository.findByInvoiceId(inv.id!!, pageIndex = 5, pageSize = 2).isEmpty())
    }

    @Test
    @TestTransaction
    fun `boundary findByObjectKey returns null when no document has that key`() {
        val inv = invoice()
        document(inv, "zzcov9-present", OffsetDateTime.now())
        em.flush()

        assertNull(repository.findByObjectKey("zzcov9-missing-${inv.id}"))
    }

    @Test
    @TestTransaction
    fun `happy path findByObjectKey resolves the most recently generated match`() {
        val inv = invoice()
        val key = "zzcov9-objectkey-${inv.id}"
        val older = document(inv, key, OffsetDateTime.now().minusHours(1))
        val newer = document(inv, key, OffsetDateTime.now())
        em.flush()

        val found = repository.findByObjectKey(key)

        assertNotNull(found)
        assertEquals(newer.id, found?.id)
        assertTrue(older.id != newer.id)
    }

    @Test
    @TestTransaction
    fun `happy path save persists a new document and merges an existing one`() {
        val inv = invoice()
        val doc = InvoiceDocument().apply {
            this.invoice = inv
            this.shortName = "COV09 Saved"
            this.documentType = "PDF"
            this.objectKey = "zzcov9-saved"
            this.fileName = "invoice.pdf"
            this.mimeType = "application/pdf"
            this.sha256Checksum = "d".repeat(64)
        }

        val saved = repository.save(doc)
        assertNotNull(saved.id)

        saved.fileName = "renamed.pdf"
        val merged = repository.save(saved)

        assertEquals("renamed.pdf", repository.findById(merged.id!!)?.fileName)
    }
}
