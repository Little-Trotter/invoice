package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.outbound.MailJournalAttachment
import de.littletrotter.application.invoice.port.outbound.MailJournalAttachmentRestorePort
import de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.DocumentLossReason
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.invoice.RestoreSource
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.exception.SdkClientException
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture

/** Branches of the recovery service that need no database; the transactional paths are in the IT. */
class InvoiceDocumentRecoveryServiceTest {

    private val locator: IssuedDocumentLocator = mock()
    private val invoices: InvoiceRepository = mock()
    private val store: DocumentStore = mock()
    private val transactions: InvoiceDocumentRecoveryTransactions = mock()
    private val publisher: DocumentJobPublisher = mock()
    private val mailInstance: Instance<MailJournalAttachmentRestorePort> = mock()
    private val mailPort: MailJournalAttachmentRestorePort = mock()
    private val service = InvoiceDocumentRecoveryService(locator, invoices, store, transactions, publisher, mailInstance)

    private val bytes = "%PDF original".toByteArray()
    private val sha = Sha256Hex.of(bytes)
    private val key = "invoices/RE-2026-0001-7-aaaaaaaaaaaa.pdf"

    private fun invoice(sent: Boolean, status: InvoiceStatus = InvoiceStatus.SENT) = Invoice().apply {
        id = 5L
        invoiceNumber = InvoiceNumber("RE-2026-0001")
        mailSent = sent
        forceStatusForTest(status)
    }

    private fun row(status: DocumentGenerationStatus = DocumentGenerationStatus.COMPLETED, checksum: String? = sha) =
        InvoiceDocument().apply {
            id = 11L
            objectKey = key
            sha256Checksum = checksum
            generationStatus = status
        }

    private fun given(sent: Boolean, doc: InvoiceDocument, status: InvoiceStatus = InvoiceStatus.SENT) {
        whenever(invoices.findById(5L)).thenReturn(invoice(sent, status))
        whenever(locator.currentPdf(5L)).thenReturn(IssuedPdf(doc, 7L))
        whenever(mailInstance.isResolvable).thenReturn(true)
        whenever(mailInstance.get()).thenReturn(mailPort)
    }

    private fun job(id: Long, trigger: GenerationJobTrigger) = GenerationJob().apply {
        this.id = id
        this.trigger = trigger
    }

    @BeforeEach
    fun publishWorks() {
        whenever(publisher.publish(any<GenerationJob>(), any())).thenReturn(CompletableFuture.completedFuture(null))
    }

    @Test
    fun `an unknown invoice or one without a current pdf has no document`() {
        assertEquals(DocumentResolution.NoDocument, service.resolveForInvoice(5L))
        whenever(invoices.findById(5L)).thenReturn(invoice(false))
        assertEquals(DocumentResolution.NoDocument, service.resolveForInvoice(5L))
        assertNull(service.currentDocument(5L))
    }

    @Test
    fun `a present matching original is served and the view mirrors the row`() {
        val doc = row().apply { reproduced = true; reproductionOfSha256 = "x"; reproducedAt = OffsetDateTime.MAX }
        given(true, doc)
        whenever(store.get(key)).thenReturn(bytes)

        assertEquals(DocumentResolution.Serve(key), service.resolveForInvoice(5L))
        val view = service.currentDocument(5L)!!
        assertEquals(key, view.objectKey)
        assertEquals(7L, view.jobId)
        assertTrue(view.reproduced)
        assertEquals("x", view.reproductionOfSha256)
    }

    @Test
    fun `a row without checksum cannot mismatch`() {
        given(false, row(checksum = null))
        whenever(store.get(key)).thenReturn(bytes)
        assertEquals(DocumentResolution.Serve(key), service.resolveForInvoice(5L))
    }

    @Test
    fun `an I-O failure or SDK failure is storage unavailable, never a regeneration`() {
        given(false, row())
        org.mockito.kotlin.doThrow(SdkClientException.create("connection refused")).whenever(store).get(key)
        assertEquals(DocumentResolution.StorageUnavailable, service.resolveForInvoice(5L))
        org.mockito.kotlin.doAnswer { throw IOException("disk") }.whenever(store).get(key)
        assertEquals(DocumentResolution.StorageUnavailable, service.resolveForInvoice(5L))
        verify(transactions, never()).requestRegeneration(any(), any(), any(), any())
    }

    @Test
    fun `a missing object of an unsent invoice starts a regeneration and publishes the job`() {
        given(false, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        val created = job(31L, GenerationJobTrigger.REGENERATION)
        whenever(transactions.requestRegeneration(eq(11L), eq(5L), eq(false), any())).thenReturn(RegenerationOutcome.Created(created))

        assertEquals(DocumentResolution.RegenerationStarted(31L, false), service.resolveForInvoice(5L))

        val facts = argumentCaptor<LossFacts>()
        verify(transactions).requestRegeneration(eq(11L), eq(5L), eq(false), facts.capture())
        assertEquals(DocumentLossReason.ORIGINAL_MISSING, facts.firstValue.reason)
        assertEquals(sha, facts.firstValue.hashOld)
        assertNull(facts.firstValue.hashFound)
        verify(publisher).publish(created, skipValidation = false)
    }

    @Test
    fun `a failing publish does not fail the request`() {
        given(false, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(transactions.requestRegeneration(any(), any(), any(), any()))
            .thenReturn(RegenerationOutcome.Created(job(31L, GenerationJobTrigger.REGENERATION)))
        whenever(publisher.publish(any<GenerationJob>(), any())).thenReturn(CompletableFuture.failedFuture(IllegalStateException("nats down")))

        assertEquals(DocumentResolution.RegenerationStarted(31L, false), service.resolveForInvoice(5L))
    }

    @Test
    fun `a checksum mismatch carries the found hash`() {
        given(false, row())
        val other = "tampered".toByteArray()
        whenever(store.get(key)).thenReturn(other)
        whenever(transactions.requestRegeneration(any(), any(), any(), any()))
            .thenReturn(RegenerationOutcome.AlreadyRunning(40L, false))

        assertEquals(DocumentResolution.RegenerationStarted(40L, false), service.resolveForInvoice(5L))

        val facts = argumentCaptor<LossFacts>()
        verify(transactions).requestRegeneration(any(), any(), any(), facts.capture())
        assertEquals(DocumentLossReason.CHECKSUM_MISMATCH, facts.firstValue.reason)
        assertEquals(Sha256Hex.of(other), facts.firstValue.hashFound)
        verify(publisher, never()).publish(any<GenerationJob>(), any())
    }

    @Test
    fun `a sent invoice without any copy is reproduced`() {
        given(true, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(store.listObjectKeys(any())).thenReturn(emptyList())
        whenever(mailPort.findAttachment(any(), any())).thenReturn(null)
        whenever(transactions.requestRegeneration(eq(11L), eq(5L), eq(true), any()))
            .thenReturn(RegenerationOutcome.Created(job(32L, GenerationJobTrigger.REPRODUCTION)))

        assertEquals(DocumentResolution.RegenerationStarted(32L, true), service.resolveForInvoice(5L))
    }

    @Test
    fun `a voided invoice without any document is reproduced only on an explicit restore`() {
        whenever(invoices.findById(5L)).thenReturn(invoice(false, InvoiceStatus.VOIDED))
        val created = job(80L, GenerationJobTrigger.REPRODUCTION)
        whenever(transactions.requestReproductionWithoutRow(5L)).thenReturn(RegenerationOutcome.Created(created))

        assertEquals(DocumentResolution.NoDocument, service.resolveForInvoice(5L))
        verify(transactions, never()).requestReproductionWithoutRow(any())

        assertEquals(DocumentResolution.RegenerationStarted(80L, true), service.restore(5L))
        verify(publisher).publish(created, skipValidation = false)
    }

    @Test
    fun `a draft or cancelled invoice without any document is never reproduced by a restore`() {
        for (status in listOf(InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW, InvoiceStatus.CANCELLED)) {
            whenever(invoices.findById(5L)).thenReturn(invoice(false, status))
            assertEquals(DocumentResolution.NoDocument, service.restore(5L))
        }
        verify(transactions, never()).requestReproductionWithoutRow(any())
    }

    @Test
    fun `a voided restore answers with the running reproduction instead of creating another`() {
        whenever(invoices.findById(5L)).thenReturn(invoice(false, InvoiceStatus.VOIDED))
        whenever(transactions.requestReproductionWithoutRow(5L)).thenReturn(RegenerationOutcome.AlreadyRunning(81L, true))

        assertEquals(DocumentResolution.RegenerationStarted(81L, true), service.restore(5L))
        verify(publisher, never()).publish(any<GenerationJob>(), any())
    }

    @Test
    fun `a voided restore rejected by a running print job answers with that job or rethrows`() {
        whenever(invoices.findById(5L)).thenReturn(invoice(false, InvoiceStatus.VOIDED))
        whenever(transactions.requestReproductionWithoutRow(5L)).thenThrow(InvoiceGenerationAlreadyRunningException("RE-2026-0001"))
        whenever(transactions.runningPrintJob(5L)).thenReturn(job(82L, GenerationJobTrigger.REPRODUCTION))
        assertEquals(DocumentResolution.RegenerationStarted(82L, true), service.restore(5L))

        whenever(transactions.runningPrintJob(5L)).thenReturn(null)
        assertThrows(InvoiceGenerationAlreadyRunningException::class.java) { service.restore(5L) }
    }

    @Test
    fun `a voided invoice whose original is in neither source is reproduced, not plainly regenerated`() {
        given(false, row(), InvoiceStatus.VOIDED)
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(store.listObjectKeys(any())).thenReturn(emptyList())
        whenever(mailPort.findAttachment(any(), any())).thenReturn(null)
        whenever(transactions.requestRegeneration(eq(11L), eq(5L), eq(true), any()))
            .thenReturn(RegenerationOutcome.Created(job(83L, GenerationJobTrigger.REPRODUCTION)))

        assertEquals(DocumentResolution.RegenerationStarted(83L, true), service.resolveForInvoice(5L))
        verify(transactions, never()).requestRegeneration(any(), any(), eq(false), any())
    }

    @Test
    fun `a voided invoice with an identical copy in the store is restored, never reproduced`() {
        val sibling = "invoices/RE-2026-0001-9-bbbbbbbbbbbb.pdf"
        given(false, row(), InvoiceStatus.VOIDED)
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(store.listObjectKeys(any())).thenReturn(listOf(sibling))
        whenever(store.get(sibling)).thenReturn(bytes)

        assertEquals(DocumentResolution.Restored(key, RestoreSource.RUSTFS), service.resolveForInvoice(5L))
        verify(transactions, never()).requestRegeneration(any(), any(), any(), any())
    }

    @Test
    fun `a voided invoice with an identical copy in the mail journal is restored, never reproduced`() {
        given(false, row(), InvoiceStatus.VOIDED)
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(store.listObjectKeys(any())).thenReturn(emptyList())
        whenever(mailPort.findAttachment("RE-2026-0001", sha)).thenReturn(MailJournalAttachment(bytes, 99L, "message.eml"))

        assertEquals(DocumentResolution.Restored(key, RestoreSource.MAIL_JOURNAL), service.resolveForInvoice(5L))
        verify(transactions, never()).requestRegeneration(any(), any(), any(), any())
    }

    @Test
    fun `a sibling object with the same bytes is restored under the original key`() {
        val sibling = "invoices/RE-2026-0001-9-bbbbbbbbbbbb.pdf"
        given(true, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(store.listObjectKeys(DocumentKeyRules.issuedJobExportPrefix("RE-2026-0001", 5L)))
            .thenReturn(listOf(key, "invoices/RE-2026-0001-9-bbbbbbbbbbbb.xml", "invoices/RE-2026-0001-8-cccccccccccc.pdf", sibling))
        whenever(store.get("invoices/RE-2026-0001-8-cccccccccccc.pdf")).thenThrow(NoSuchElementException("gone"))
        whenever(store.get(sibling)).thenReturn(bytes)

        assertEquals(DocumentResolution.Restored(key, RestoreSource.RUSTFS), service.resolveForInvoice(5L))

        verify(store).put(key, bytes, "application/pdf")
        verify(transactions).recordRestore(eq(11L), eq("ORIGINAL_MISSING"), any(), eq(false))
        verify(mailPort, never()).findAttachment(any(), any())
    }

    @Test
    fun `a listing failure is storage unavailable`() {
        given(true, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(store.listObjectKeys(any())).thenThrow(SdkClientException.create("boom"))
        assertEquals(DocumentResolution.StorageUnavailable, service.resolveForInvoice(5L))
    }

    @Test
    fun `the mail journal copy is restored when the store has none`() {
        given(true, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(store.listObjectKeys(any())).thenReturn(emptyList())
        whenever(mailPort.findAttachment("RE-2026-0001", sha)).thenReturn(MailJournalAttachment(bytes, 99L, "message.eml"))

        assertEquals(DocumentResolution.Restored(key, RestoreSource.MAIL_JOURNAL), service.resolveForInvoice(5L))

        val restored = argumentCaptor<RestoredCopy>()
        verify(transactions).recordRestore(eq(11L), any(), restored.capture(), eq(false))
        assertEquals(RestoreSource.MAIL_JOURNAL, restored.firstValue.source)
        assertEquals(99L, restored.firstValue.journalId)
        assertEquals("message.eml", restored.firstValue.file)
    }

    @Test
    fun `a mail copy with the wrong hash and an unavailable mail component both fall back to reproduction`() {
        given(true, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(store.listObjectKeys(any())).thenReturn(emptyList())
        whenever(mailPort.findAttachment(any(), any())).thenReturn(MailJournalAttachment("other".toByteArray(), 1L, "f"))
        whenever(transactions.requestRegeneration(any(), any(), eq(true), any()))
            .thenReturn(RegenerationOutcome.AlreadyRunning(50L, true))
        assertEquals(DocumentResolution.RegenerationStarted(50L, true), service.resolveForInvoice(5L))

        whenever(mailInstance.isResolvable).thenReturn(false)
        assertEquals(DocumentResolution.RegenerationStarted(50L, true), service.resolveForInvoice(5L))
        verify(store, never()).put(any(), any(), any())
    }

    @Test
    fun `a lost row with a running job answers with that job, without probing`() {
        given(true, row(DocumentGenerationStatus.LOST))
        whenever(transactions.runningPrintJob(5L)).thenReturn(job(60L, GenerationJobTrigger.REPRODUCTION))

        assertEquals(DocumentResolution.RegenerationStarted(60L, true), service.resolveForInvoice(5L))
        verify(store, never()).get(any())
    }

    @Test
    fun `a lost row without a running job is regenerated again`() {
        given(false, row(DocumentGenerationStatus.LOST))
        whenever(transactions.runningPrintJob(5L)).thenReturn(null)
        whenever(transactions.requestRegeneration(eq(11L), eq(5L), eq(false), any()))
            .thenReturn(RegenerationOutcome.Created(job(61L, GenerationJobTrigger.REGENERATION)))

        assertEquals(DocumentResolution.RegenerationStarted(61L, false), service.resolveForInvoice(5L))
        verify(store, never()).get(any())
    }

    @Test
    fun `a rejected job creation answers with the running print job`() {
        given(false, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(transactions.requestRegeneration(any(), any(), any(), any())).thenThrow(InvoiceGenerationAlreadyRunningException("RE-2026-0001"))
        whenever(transactions.runningPrintJob(5L)).thenReturn(job(70L, GenerationJobTrigger.MANUAL))

        assertEquals(DocumentResolution.RegenerationStarted(70L, false), service.resolveForInvoice(5L))
    }

    @Test
    fun `a rejection without any running job is rethrown`() {
        given(false, row())
        whenever(store.get(key)).thenThrow(NoSuchElementException(key))
        whenever(transactions.requestRegeneration(any(), any(), any(), any())).thenThrow(InvoiceGenerationAlreadyRunningException("RE-2026-0001"))
        whenever(transactions.runningPrintJob(5L)).thenReturn(null)

        assertThrows(InvoiceGenerationAlreadyRunningException::class.java) { service.resolveForInvoice(5L) }
    }

    @Test
    fun `an explicit restore replaces a reproduction with the original and clears the tag`() {
        val doc = row().apply { reproduced = true; reproductionOfSha256 = sha }
        given(true, doc)
        whenever(store.listObjectKeys(any())).thenReturn(emptyList())
        whenever(mailPort.findAttachment("RE-2026-0001", sha)).thenReturn(MailJournalAttachment(bytes, 5L, "m.eml"))

        assertEquals(DocumentResolution.Restored(key, RestoreSource.MAIL_JOURNAL), service.restore(5L))

        verify(store).put(key, bytes, "application/pdf")
        verify(transactions).recordRestore(eq(11L), eq("REPRODUCTION_REPLACED"), any(), eq(true))
    }

    @Test
    fun `an explicit restore without an original to find just resolves normally`() {
        val doc = row().apply { reproduced = true; reproductionOfSha256 = "f".repeat(64) }
        given(true, doc)
        whenever(store.listObjectKeys(any())).thenReturn(emptyList())
        whenever(store.get(key)).thenReturn(bytes)

        assertEquals(DocumentResolution.Serve(key), service.restore(5L))
        verify(transactions, never()).recordRestore(any(), any(), any(), any())
    }

    @Test
    fun `an explicit restore of an unsent invoice or a plain row never searches the sources`() {
        given(false, row().apply { reproduced = true; reproductionOfSha256 = sha })
        whenever(store.get(key)).thenReturn(bytes)
        assertEquals(DocumentResolution.Serve(key), service.restore(5L))
        given(true, row())
        assertEquals(DocumentResolution.Serve(key), service.restore(5L))
        verify(store, never()).listObjectKeys(any())
    }
}
