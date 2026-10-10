package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
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
import java.util.concurrent.atomic.AtomicLong

/**
 * [PanacheInvoiceLlmAnalysisRepository] against a real Testcontainers Postgres. The
 * risk item this class exists to prove: saving an analysis for the same invoice+model twice
 * (load-modify-save, i.e. the caller's own upsert pattern) updates the existing row instead of
 * inserting a duplicate.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheInvoiceLlmAnalysisRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: InvoiceLlmAnalysisRepository

    private companion object {
        val invoiceSequence = AtomicLong(System.currentTimeMillis())
    }

    private fun invoice(): Invoice =
        Invoice().apply {
            invoiceNumber = InvoiceNumber("COV09-2031-01-02-${invoiceSequence.incrementAndGet()}")
        }.also { em.persist(it) }

    private fun rowCount(invoiceId: Long?): Long =
        em.createQuery("SELECT COUNT(a) FROM InvoiceLlmAnalysis a WHERE a.invoice.id = :id", Long::class.javaObjectType)
            .setParameter("id", invoiceId)
            .singleResult

    @Test
    @TestTransaction
    fun `happy path findByInvoiceId and findByInvoiceIdAndModelName resolve saved analyses`() {
        val inv = invoice()
        val mistral = InvoiceLlmAnalysis().apply {
            this.invoice = inv
            this.modelName = "mistral"
            this.rating = 4
        }
        val llama = InvoiceLlmAnalysis().apply {
            this.invoice = inv
            this.modelName = "llama"
            this.rating = 2
        }
        repository.save(mistral)
        repository.save(llama)
        em.flush()

        val all = repository.findByInvoiceId(inv.id!!)
        assertEquals(setOf("mistral", "llama"), all.map { it.modelName }.toSet())

        assertEquals(4, repository.findByInvoiceIdAndModelName(inv.id!!, "mistral")?.rating)
        assertNull(repository.findByInvoiceIdAndModelName(inv.id!!, "unknown-model"))
    }

    @Test
    @TestTransaction
    fun `boundary saving an analysis twice for the same invoice-and-model upserts, never duplicates`() {
        val inv = invoice()
        val analysis = InvoiceLlmAnalysis().apply {
            this.invoice = inv
            this.modelName = "mistral"
            this.rating = 3
            this.summary = "first pass"
        }
        val saved = repository.save(analysis)
        em.flush()
        assertEquals(1L, rowCount(inv.id))

        val existing = requireNotNull(repository.findByInvoiceIdAndModelName(inv.id!!, "mistral"))
        existing.rating = 5
        existing.summary = "second pass"
        val updated = repository.save(existing)
        em.flush()

        assertEquals(saved.id, updated.id)
        assertEquals(1L, rowCount(inv.id), "the second save must update the existing row, not add one")
        val reloaded = requireNotNull(repository.findByInvoiceIdAndModelName(inv.id!!, "mistral"))
        assertEquals(5, reloaded.rating)
        assertEquals("second pass", reloaded.summary)
    }

    @Test
    @TestTransaction
    fun `boundary an invoice without any analysis yields an empty list`() {
        val inv = invoice()
        em.flush()

        assertTrue(repository.findByInvoiceId(inv.id!!).isEmpty())
        assertNotNull(inv.id)
    }

    @Test
    @TestTransaction
    fun `boundary findByInvoiceIdAndModelName returns null for a model never analyzed on this invoice`() {
        val inv = invoice()
        repository.save(InvoiceLlmAnalysis().apply { this.invoice = inv; this.modelName = "mistral" })
        em.flush()

        assertNull(repository.findByInvoiceIdAndModelName(inv.id!!, "unknown-model"))
    }

    @Test
    @TestTransaction
    fun `boundary the same model name on two different invoices stays independent`() {
        val invA = invoice()
        val invB = invoice()
        val analysisA = repository.save(InvoiceLlmAnalysis().apply { this.invoice = invA; this.modelName = "mistral"; this.rating = 1 })
        val analysisB = repository.save(InvoiceLlmAnalysis().apply { this.invoice = invB; this.modelName = "mistral"; this.rating = 5 })
        em.flush()

        assertEquals(analysisA.id, repository.findByInvoiceIdAndModelName(invA.id!!, "mistral")?.id)
        assertEquals(analysisB.id, repository.findByInvoiceIdAndModelName(invB.id!!, "mistral")?.id)
        assertEquals(1, repository.findByInvoiceIdAndModelName(invA.id!!, "mistral")?.rating)
        assertEquals(5, repository.findByInvoiceIdAndModelName(invB.id!!, "mistral")?.rating)
    }
}
