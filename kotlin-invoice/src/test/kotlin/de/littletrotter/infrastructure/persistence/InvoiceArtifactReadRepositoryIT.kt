package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.recordLlmAnalysis
import de.littletrotter.domain.invoice.attachDocument
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.fixture.anInvoice
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * Documents and analyses are written through the [Invoice] root (cascade) and read back through
 * the document and analysis read repositories, against a real Postgres.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceArtifactReadRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var documents: InvoiceDocumentReadRepositoryImpl

    @Inject
    lateinit var analyses: InvoiceLlmAnalysisReadRepositoryImpl

    private companion object {
        val invoiceSequence = AtomicLong(System.currentTimeMillis())
    }

    private fun invoice(): Invoice =
        anInvoice(invoiceNumber = InvoiceNumber("COV09-2031-01-01-${invoiceSequence.incrementAndGet()}"))
            .also { em.persist(it) }

    @Test
    @TestTransaction
    fun `an attached document is found by object key and by id with its checksum`() {
        val invoice = invoice()
        val document = invoice.attachDocument("PDF", "zzcov9-key-${invoiceSequence.incrementAndGet()}", "application/pdf")
        document.recordGenerated("c".repeat(64), OffsetDateTime.now())
        em.flush()

        assertEquals("c".repeat(64), documents.findDocumentChecksum(document.objectKey))
        assertEquals("c".repeat(64), documents.findDocumentChecksumById(requireNotNull(document.id)))
    }

    @Test
    @TestTransaction
    fun `boundary - an unknown key or id yields null`() {
        assertNull(documents.findDocumentChecksum("zzcov9-unknown"))
        assertNull(documents.findDocumentChecksumById(-987654321L))
    }

    @Test
    @TestTransaction
    fun `a recorded analysis is reported with its model and status`() {
        val invoice = invoice()
        invoice.recordLlmAnalysis("zzcov9-model")
        em.flush()

        val report = analyses.findReports(requireNotNull(invoice.id)).single()
        assertEquals("zzcov9-model", report.modelName)
        assertEquals(LlmAnalysisStatus.PENDING, report.status)
        assertNotNull(report)
    }

    @Test
    @TestTransaction
    fun `boundary - an invoice without analyses has an empty report list`() {
        assertEquals(emptyList<Any>(), analyses.findReports(-987654321L))
    }
}
