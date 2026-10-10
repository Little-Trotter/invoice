package de.littletrotter.application.invoice

import de.littletrotter.application.shared.AuditActionOverride
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import de.littletrotter.shared.GenerationJobResultRefs
import de.littletrotter.shared.time.ClockTimeSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class DocumentChecksumPersisterTest {

    private class InMemoryInvoiceDocumentRepository : InvoiceDocumentRepository {
        val saved = mutableListOf<InvoiceDocument>()
        var throwOnSave: Boolean = false
        private var nextId = 1L

        override fun findByInvoiceId(invoiceId: Long, pageIndex: Int, pageSize: Int): List<InvoiceDocument> =
            saved.filter { it.invoice?.id == invoiceId }

        override fun findById(id: Long): InvoiceDocument? = saved.find { it.id == id }

        override fun findByObjectKey(objectKey: String): InvoiceDocument? = saved.find { it.objectKey == objectKey }

        override fun save(doc: InvoiceDocument): InvoiceDocument {
            if (throwOnSave) error("simulated write failure")
            if (doc.id == null) {
                doc.id = nextId++
                saved.add(doc)
            }
            return doc
        }
    }

    private val repo = InMemoryInvoiceDocumentRepository()
    private val persister = DocumentChecksumPersister(repo, ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z")), mock())

    private fun invoice(id: Long) = Invoice().apply {
        this.id = id
        invoiceNumber = InvoiceNumber("RE-2026-01-${id.toString().padStart(5, '0')}")
    }

    @Test
    fun `persists PDF and XML rows pointing at the job keys`() {
        val invoice = invoice(800L)
        val refs = GenerationJobResultRefs(
            objectKey = "invoices/RE-2026-01-00800-7.pdf",
            zugferdObjectKey = "invoices/RE-2026-01-00800-7.xml",
            sha256 = "a".repeat(64),
            zugferdXmlSha256 = "b".repeat(64),
        )

        persister.persist(invoice, GenerationJobResultRefs.encode(refs))

        assertEquals(2, repo.saved.size)
        val pdf = repo.saved.single { it.documentType == "PDF" }
        assertEquals("invoices/RE-2026-01-00800-7.pdf", pdf.objectKey)
        assertEquals("a".repeat(64), pdf.sha256Checksum)
        assertEquals(DocumentGenerationStatus.COMPLETED, pdf.generationStatus)
        assertEquals(invoice, pdf.invoice)
        assertEquals("b".repeat(64), repo.saved.single { it.documentType == "XML" }.sha256Checksum)
    }

    @Test
    fun `two jobs of one invoice keep two rows with their own checksums`() {
        val invoice = invoice(801L)
        persister.persist(invoice, GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = "invoices/x-1.pdf", sha256 = "a".repeat(64))))
        persister.persist(invoice, GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = "invoices/x-2.pdf", sha256 = "c".repeat(64))))

        assertEquals(2, repo.saved.size)
        assertEquals("a".repeat(64), repo.findByObjectKey("invoices/x-1.pdf")!!.sha256Checksum)
        assertEquals("c".repeat(64), repo.findByObjectKey("invoices/x-2.pdf")!!.sha256Checksum)
    }

    @Test
    fun `persists an ARCHIVE row and skips it when the archive key is absent`() {
        val invoice = invoice(802L)
        persister.persist(
            invoice,
            GenerationJobResultRefs.encode(
                GenerationJobResultRefs(
                    objectKey = "invoices/a.pdf", sha256 = "a".repeat(64),
                    archiveObjectKey = "invoices/a.tar.gz", archiveSha256 = "c".repeat(64),
                ),
            ),
        )
        assertEquals("application/gzip", repo.saved.single { it.documentType == "ARCHIVE" }.mimeType)

        val draftRepo = InMemoryInvoiceDocumentRepository()
        DocumentChecksumPersister(draftRepo, ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z")), mock())
            .persist(invoice, GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = "invoices/d.pdf", sha256 = "a".repeat(64))))
        assertTrue(draftRepo.saved.none { it.documentType == "ARCHIVE" })
    }

    @Test
    fun `persists no row for a blank checksum`() {
        persister.persist(invoice(803L), GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = "invoices/b.pdf", sha256 = "")))

        assertTrue(repo.saved.isEmpty(), "a blank checksum must never produce a row that looks verified")
    }

    @Test
    fun `does nothing without result refs`() {
        persister.persist(invoice(804L), null)

        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `redelivery of the same key updates the existing row`() {
        val invoice = invoice(805L)
        persister.persist(invoice, GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = "invoices/r.pdf", sha256 = "a".repeat(64))))
        persister.persist(invoice, GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = "invoices/r.pdf", sha256 = "c".repeat(64))))

        assertEquals(1, repo.saved.size)
        assertEquals("c".repeat(64), repo.saved.single().sha256Checksum)
    }

    @Test
    fun `a write failure propagates to the surrounding completion transaction`() {
        repo.throwOnSave = true

        assertThrows(IllegalStateException::class.java) {
            persister.persist(invoice(806L), GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = "invoices/f.pdf", sha256 = "a".repeat(64))))
        }
    }

    private val journaled = mutableListOf<Pair<InvoiceAuditAction, Map<String, Any?>>>()
    private val auditOverride: AuditActionOverride = mock<AuditActionOverride>().also { override ->
        whenever(override.withAction(any(), any(), any<() -> Any?>())).thenAnswer {
            journaled.add(it.getArgument<InvoiceAuditAction>(0) to it.getArgument<Map<String, Any?>>(1))
            it.getArgument<() -> Any?>(2).invoke()
        }
    }
    private val recoveryPersister = DocumentChecksumPersister(repo, ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z")), auditOverride)

    private fun invoiceWithLostPdf(id: Long): Invoice = invoice(id).also { inv ->
        inv.documents.asMutableForTest().add(
            InvoiceDocument().apply {
                documentType = "PDF"
                generationStatus = DocumentGenerationStatus.LOST
                sha256Checksum = "9".repeat(64)
                generatedAt = OffsetDateTime.parse("2026-06-01T10:00:00Z")
            },
        )
    }

    private fun pdfRefs(key: String) = GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = key, sha256 = "a".repeat(64)))

    @Test
    fun `a reproduction job tags the new pdf row and journals the completion with old and new hash`() {
        recoveryPersister.persist(invoiceWithLostPdf(810L), pdfRefs("invoices/r.pdf"), GenerationJobTrigger.REPRODUCTION, 55L)

        val pdf = repo.saved.single()
        assertTrue(pdf.reproduced)
        assertEquals(OffsetDateTime.parse("2026-06-15T10:00:00Z"), pdf.reproducedAt)
        assertEquals("9".repeat(64), pdf.reproductionOfSha256)
        val (action, context) = journaled.single()
        assertEquals(InvoiceAuditAction.DOCUMENT_REPRODUCED, action)
        assertEquals(mapOf("phase" to "COMPLETED", "hash_old" to "9".repeat(64), "hash_new" to "a".repeat(64), "job_id" to 55L), context)
    }

    @Test
    fun `a regeneration job journals but never tags`() {
        recoveryPersister.persist(invoiceWithLostPdf(811L), pdfRefs("invoices/g.pdf"), GenerationJobTrigger.REGENERATION, 56L)

        assertFalse(repo.saved.single().reproduced)
        assertEquals(InvoiceAuditAction.DOCUMENT_REGENERATED, journaled.single().first)
    }

    @Test
    fun `a recovery job without a lost row has no old hash`() {
        recoveryPersister.persist(invoice(812L), pdfRefs("invoices/n.pdf"), GenerationJobTrigger.REPRODUCTION, 57L)

        assertNull(repo.saved.single().reproductionOfSha256)
        assertNull(journaled.single().second["hash_old"])
    }

    @Test
    fun `an ordinary job is not journaled as a recovery`() {
        recoveryPersister.persist(invoice(813L), pdfRefs("invoices/o.pdf"), GenerationJobTrigger.SEND, 58L)

        assertTrue(journaled.isEmpty())
        assertFalse(repo.saved.single().reproduced)
    }
}
