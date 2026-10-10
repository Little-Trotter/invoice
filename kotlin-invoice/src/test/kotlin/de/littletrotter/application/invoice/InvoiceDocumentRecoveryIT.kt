package de.littletrotter.application.invoice

import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.RustFsTestResource
import de.littletrotter.application.invoice.port.inbound.DocumentReproductionQueryPort
import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.inbound.ResolveInvoiceDocumentUseCase
import de.littletrotter.application.invoice.port.inbound.RestoreInvoiceDocumentUseCase
import de.littletrotter.application.shared.AuditActionOverride
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.RestoreSource
import de.littletrotter.domain.repository.GenerationJobRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Document recovery against real Postgres and RustFS: the stored original is deleted or altered
 * and the access path must restore, re-render or report, with the journal rows that make each
 * outcome auditable.
 *
 * The "document store unreachable" case is not covered here: the shared RustFS container cannot be
 * taken away mid-suite and the store bean is not replaceable. Its classification (SDK client
 * exception and I/O failure answer "storage unavailable", never a regeneration) is asserted in
 * `InvoiceDocumentRecoveryServiceTest`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(RustFsTestResource::class)
class InvoiceDocumentRecoveryIT : DocumentRecoveryItSupport() {

    @Inject
    lateinit var resolver: ResolveInvoiceDocumentUseCase

    @Inject
    lateinit var restorer: RestoreInvoiceDocumentUseCase

    @Inject
    lateinit var queryPort: DocumentReproductionQueryPort

    @Inject
    lateinit var generationJobRepository: GenerationJobRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var auditOverride: AuditActionOverride

    private fun context(row: JournalRow): Map<*, *> = objectMapper.readValue(row.contextJson, Map::class.java)

    @Test
    fun `T1 - unsent invoice, object deleted - regeneration is requested and audited`() {
        val f = issuedInvoice(sent = false)
        documentStore.delete(f.pdfKey)

        val result = resolver.resolveForInvoice(f.invoiceId)

        val started = result as DocumentResolution.RegenerationStarted
        assertFalse(started.reproduction)
        assertEquals(listOf(started.jobId), jobsOf(f.invoiceId, GenerationJobTrigger.REGENERATION.ordinal))
        assertEquals(DocumentGenerationStatus.LOST.ordinal, documentRow(f.documentId).statusOrdinal)
        val row = journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_REGENERATED).single()
        val ctx = context(row)
        assertEquals("REQUESTED", ctx["phase"])
        assertEquals("ORIGINAL_MISSING", ctx["reason"])
        assertEquals(f.pdfSha, ctx["hash_old"])
        assertEquals("RENDERED", ctx["source"])
        assertEquals(started.jobId.toInt(), (ctx["job_id"] as Number).toInt())
        assertTrue(row.actor.isNotBlank())
    }

    @Test
    fun `T2 - checksum mismatch - regeneration records the found hash`() {
        val f = issuedInvoice(sent = false)
        val altered = "%PDF altered".toByteArray()
        documentStore.put(f.pdfKey, altered, "application/pdf")

        assertTrue(resolver.resolveForInvoice(f.invoiceId) is DocumentResolution.RegenerationStarted)

        val ctx = context(journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_REGENERATED).single())
        assertEquals("CHECKSUM_MISMATCH", ctx["reason"])
        assertEquals(Sha256Hex.of(altered), ctx["hash_found"])
        assertEquals(f.pdfSha, ctx["hash_old"])
    }

    @Test
    fun `T4 - sent invoice with an identical sibling object - restored without a job`() {
        val f = issuedInvoice(sent = true)
        val siblingKey = jobKey(f.number, f.invoiceId, "9${f.jobId}")
        documentStore.put(siblingKey, f.pdfBytes, "application/pdf")
        documentStore.delete(f.pdfKey)

        val result = resolver.resolveForInvoice(f.invoiceId)

        assertEquals(DocumentResolution.Restored(f.pdfKey, RestoreSource.RUSTFS), result)
        assertArrayEquals(f.pdfBytes, documentStore.get(f.pdfKey))
        assertTrue(jobsOf(f.invoiceId, GenerationJobTrigger.REGENERATION.ordinal).isEmpty())
        assertTrue(jobsOf(f.invoiceId, GenerationJobTrigger.REPRODUCTION.ordinal).isEmpty())
        val ctx = context(journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_RESTORED).single())
        assertEquals("RUSTFS", ctx["source"])
        assertEquals("ORIGINAL_MISSING", ctx["reason"])
        assertEquals(f.pdfSha, ctx["hash_new"])
        assertEquals(DocumentGenerationStatus.COMPLETED.ordinal, documentRow(f.documentId).statusOrdinal)
    }

    @Test
    fun `T5 - sent invoice without any copy - reproduction is requested`() {
        val f = issuedInvoice(sent = true)
        documentStore.delete(f.pdfKey)

        val started = resolver.resolveForInvoice(f.invoiceId) as DocumentResolution.RegenerationStarted

        assertTrue(started.reproduction)
        assertEquals(listOf(started.jobId), jobsOf(f.invoiceId, GenerationJobTrigger.REPRODUCTION.ordinal))
        val ctx = context(journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_REPRODUCED).single())
        assertEquals("REQUESTED", ctx["phase"])
        assertEquals(DocumentGenerationStatus.LOST.ordinal, documentRow(f.documentId).statusOrdinal)
    }

    @Test
    fun `a second access while the regeneration runs answers with the same job`() {
        val f = issuedInvoice(sent = false)
        documentStore.delete(f.pdfKey)

        val first = resolver.resolveForInvoice(f.invoiceId) as DocumentResolution.RegenerationStarted
        val second = resolver.resolveForInvoice(f.invoiceId) as DocumentResolution.RegenerationStarted

        assertEquals(first.jobId, second.jobId)
        assertEquals(1, jobsOf(f.invoiceId, GenerationJobTrigger.REGENERATION.ordinal).size)
    }

    @Test
    fun `T8 - two concurrent accesses create exactly one job and one requested journal row`() {
        val f = issuedInvoice(sent = false)
        documentStore.delete(f.pdfKey)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<DocumentResolution> {
                    barrier.await(10, TimeUnit.SECONDS)
                    resolver.resolveForInvoice(f.invoiceId)
                }
            }
            val results = futures.map { it.get(60, TimeUnit.SECONDS) as DocumentResolution.RegenerationStarted }
            assertEquals(1, results.map { it.jobId }.toSet().size)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, jobsOf(f.invoiceId, GenerationJobTrigger.REGENERATION.ordinal).size)
        assertEquals(1, journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_REGENERATED).size)
    }

    @Test
    fun `T9 - a journal write failure leaves no lost row and no job`() {
        val f = issuedInvoice(sent = false)
        documentStore.delete(f.pdfKey)
        QuarkusMock.installMockForType(
            object : AuditActionOverride(em, objectMapper) {
                override fun <T> withAction(action: InvoiceAuditAction, context: Map<String, Any?>, block: () -> T): T =
                    error("journal write failed")
            },
            AuditActionOverride::class.java,
        )

        val failure = assertThrows(IllegalStateException::class.java) { resolver.resolveForInvoice(f.invoiceId) }
        assertEquals("journal write failed", failure.message)

        assertEquals(DocumentGenerationStatus.COMPLETED.ordinal, documentRow(f.documentId).statusOrdinal)
        assertTrue(jobsOf(f.invoiceId, GenerationJobTrigger.REGENERATION.ordinal).isEmpty())
    }

    @Test
    fun `a cancelled original without any copy is reproduced from its frozen state`() {
        val f = issuedInvoice(sent = true, kind = InvoiceKind(InvoiceStatus.CANCELLED))
        val frozen = storedState(f.invoiceId)
        documentStore.delete(f.pdfKey)

        val started = resolver.resolveForInvoice(f.invoiceId) as DocumentResolution.RegenerationStarted

        assertTrue(started.reproduction)
        assertEquals(listOf(started.jobId), jobsOf(f.invoiceId, GenerationJobTrigger.REPRODUCTION.ordinal))
        assertTrue(requireNotNull(generationJobRepository.findById(started.jobId)).issued)
        assertEquals(frozen, storedState(f.invoiceId))
    }

    @Test
    fun `a cancelled original with an identical stored copy is restored, never reproduced`() {
        val f = issuedInvoice(sent = true, kind = InvoiceKind(InvoiceStatus.CANCELLED))
        documentStore.put(jobKey(f.number, f.invoiceId, "9${f.jobId}"), f.pdfBytes, "application/pdf")
        documentStore.delete(f.pdfKey)

        assertEquals(DocumentResolution.Restored(f.pdfKey, RestoreSource.RUSTFS), resolver.resolveForInvoice(f.invoiceId))
        assertTrue(jobsOf(f.invoiceId, GenerationJobTrigger.REPRODUCTION.ordinal).isEmpty())
    }

    // The storno mark follows the type code of an issued job (never the draft mark), so an issued
    // reproduction job of the storno document is what puts the mark back on the new PDF.
    @Test
    fun `a storno document without any copy is reproduced as an issued document of its own type`() {
        val f = issuedInvoice(sent = true, kind = InvoiceKind(InvoiceStatus.CANCELLED, InvoiceTypeCode.CORRECTED_INVOICE))
        val frozen = storedState(f.invoiceId)
        documentStore.delete(f.pdfKey)

        val started = resolver.resolveForInvoice(f.invoiceId) as DocumentResolution.RegenerationStarted

        assertTrue(started.reproduction)
        val job = requireNotNull(generationJobRepository.findById(started.jobId))
        assertEquals(GenerationJobTrigger.REPRODUCTION, job.trigger)
        assertTrue(job.issued)
        assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, requireNotNull(job.invoice).typeCode)
        assertEquals(frozen, storedState(f.invoiceId))
    }

    @Test
    fun `completion of a reproduction job tags the new row and journals old and new hash`() {
        val f = issuedInvoice(sent = true)
        documentStore.delete(f.pdfKey)
        val started = resolver.resolveForInvoice(f.invoiceId) as DocumentResolution.RegenerationStarted
        val newBytes = "%PDF reproduced ${f.number}".toByteArray()
        val newSha = Sha256Hex.of(newBytes)
        val newKey = jobKey(f.number, f.invoiceId, started.jobId.toString())
        documentStore.put(newKey, newBytes, "application/pdf")

        generationJobResultHandler.markCompleted(
            requireNotNull(generationJobRepository.findById(started.jobId)),
            """{"objectKey":"$newKey","sha256":"$newSha"}""",
        )

        val row = documentRow(documentIdByKey(newKey))
        assertTrue(row.reproduced)
        assertEquals(f.pdfSha, row.reproductionOf)
        // A transaction of its own: the test's request-scoped session still caches the old job state.
        val view = QuarkusTransaction.requiringNew().call { queryPort.currentDocument(f.invoiceId) }!!
        assertEquals(newKey, view.objectKey)
        assertTrue(view.reproduced)
        assertEquals(f.pdfSha, view.reproductionOfSha256)
        val rows = journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_REPRODUCED)
        assertEquals(2, rows.size)
        val completed = context(rows.last())
        assertEquals("COMPLETED", completed["phase"])
        assertEquals(f.pdfSha, completed["hash_old"])
        assertEquals(newSha, completed["hash_new"])
        assertEquals(started.jobId.toInt(), (completed["job_id"] as Number).toInt())
    }

    @Test
    fun `T12 - an intact original is served and nothing is journaled`() {
        val f = issuedInvoice(sent = true)

        assertEquals(DocumentResolution.Serve(f.pdfKey), resolver.resolveForInvoice(f.invoiceId))

        for (action in listOf(
            InvoiceAuditAction.DOCUMENT_REGENERATED,
            InvoiceAuditAction.DOCUMENT_RESTORED,
            InvoiceAuditAction.DOCUMENT_REPRODUCED,
        )) {
            assertTrue(journal(f.invoiceId, action).isEmpty(), "unexpected $action")
        }
    }

    @Test
    fun `a voided invoice has no document and a plain access does not render one`() {
        val invoiceId = voidedInvoice()

        assertEquals(DocumentResolution.NoDocument, resolver.resolveForInvoice(invoiceId))
        assertTrue(jobsOf(invoiceId, GenerationJobTrigger.REPRODUCTION.ordinal).isEmpty())
    }

    @Test
    fun `an explicit restore of a voided invoice without any original reproduces it from its frozen state`() {
        val invoiceId = voidedInvoice()
        val frozen = storedState(invoiceId)

        val started = restorer.restore(invoiceId) as DocumentResolution.RegenerationStarted

        assertTrue(started.reproduction)
        val job = requireNotNull(generationJobRepository.findById(started.jobId))
        assertEquals(GenerationJobTrigger.REPRODUCTION, job.trigger)
        assertTrue(job.issued)
        assertEquals(frozen, storedState(invoiceId))
        assertEquals(InvoiceStatus.VOIDED.ordinal, frozen.first)
    }

    @Test
    fun `a second explicit restore of a voided invoice answers with the running reproduction`() {
        val invoiceId = voidedInvoice()

        val first = restorer.restore(invoiceId) as DocumentResolution.RegenerationStarted
        val second = restorer.restore(invoiceId) as DocumentResolution.RegenerationStarted

        assertEquals(first.jobId, second.jobId)
        assertEquals(1, jobsOf(invoiceId, GenerationJobTrigger.REPRODUCTION.ordinal).size)
    }

    @Test
    fun `a voided invoice whose original is gone everywhere is reproduced, not regenerated`() {
        val f = issuedInvoice(sent = false)
        markVoided(f.invoiceId)
        val frozen = storedState(f.invoiceId)
        documentStore.delete(f.pdfKey)

        val started = resolver.resolveForInvoice(f.invoiceId) as DocumentResolution.RegenerationStarted

        assertTrue(started.reproduction)
        assertTrue(jobsOf(f.invoiceId, GenerationJobTrigger.REGENERATION.ordinal).isEmpty())
        assertEquals(frozen, storedState(f.invoiceId))
    }

    @Test
    fun `a voided invoice with an identical stored copy is restored, never reproduced`() {
        val f = issuedInvoice(sent = false)
        markVoided(f.invoiceId)
        documentStore.put(jobKey(f.number, f.invoiceId, "9${f.jobId}"), f.pdfBytes, "application/pdf")
        documentStore.delete(f.pdfKey)

        assertEquals(DocumentResolution.Restored(f.pdfKey, RestoreSource.RUSTFS), resolver.resolveForInvoice(f.invoiceId))
        assertTrue(jobsOf(f.invoiceId, GenerationJobTrigger.REPRODUCTION.ordinal).isEmpty())
    }

    @Test
    fun `a voided invoice still refuses every ordinary generation job`() {
        val invoiceId = voidedInvoice()

        assertThrows(de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException::class.java) {
            generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK)
        }
        assertThrows(de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException::class.java) {
            generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK, trigger = GenerationJobTrigger.REGENERATION)
        }
    }

    @Test
    fun `an invoice without an issued job has no document`() {
        assertEquals(DocumentResolution.NoDocument, resolver.resolveForInvoice(999_999_999L))
    }

    @Test
    fun `an explicit restore replaces a reproduction with the byte-identical original`() {
        val f = issuedInvoice(sent = true)
        val reproduction = "%PDF reproduced".toByteArray()
        val reproSha = Sha256Hex.of(reproduction)
        // The reproduction's row, as completion leaves it: new bytes, tag, pointer to the lost original.
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "UPDATE invoice_document SET sha256_checksum = :s, reproduced = true, reproduction_of_sha256 = :o, version = version + 1 WHERE id = :id",
            ).setParameter("s", reproSha).setParameter("o", f.pdfSha).setParameter("id", f.documentId).executeUpdate()
        }
        documentStore.put(f.pdfKey, reproduction, "application/pdf")
        documentStore.put(jobKey(f.number, f.invoiceId, "8${f.jobId}"), f.pdfBytes, "application/pdf")

        val result = restorer.restore(f.invoiceId)

        assertEquals(DocumentResolution.Restored(f.pdfKey, RestoreSource.RUSTFS), result)
        assertArrayEquals(f.pdfBytes, documentStore.get(f.pdfKey))
        val row = documentRow(f.documentId)
        assertFalse(row.reproduced)
        assertEquals(f.pdfSha, row.sha)
        assertNotEquals(reproSha, row.sha)
        assertEquals("REPRODUCTION_REPLACED", context(journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_RESTORED).single())["reason"])
    }
}
