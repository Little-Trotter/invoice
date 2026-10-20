package de.littletrotter.application.mail

import de.littletrotter.application.invoice.IssuedDocumentLocator
import de.littletrotter.application.mail.port.outbound.DocumentPresignPort
import de.littletrotter.application.mail.port.outbound.MailDispatchPort
import de.littletrotter.application.mail.port.outbound.PresignedDocument
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.anInvoiceDocument
import de.littletrotter.fixture.edit
import de.littletrotter.shared.GenerationJobResultRefs
import de.littletrotter.shared.Money
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.LocalDate
import de.littletrotter.application.dto.MailComposeHandover
import de.littletrotter.application.dto.MailComposeResult

/**
 * The service that turns one invoice id into a compose handover.
 *
 * The two behaviours worth pinning are the availability gate (an adapter that is not a bean means
 * "no entry point", not "a button that throws") and the attachment rule: an invoice mail without
 * its invoice is not a degraded success, so a missing document or a store that cannot presign
 * fails the whole handover.
 */
@DisplayName("InvoiceMailDispatchService")
class InvoiceMailDispatchServiceTest {

    private lateinit var repository: InvoiceRepository
    private lateinit var port: MailDispatchPort
    private lateinit var portInstance: Instance<MailDispatchPort>
    private lateinit var jobs: GenerationJobRepository
    private lateinit var locator: IssuedDocumentLocator
    private lateinit var journal: ReproductionSendBlockJournal

    @BeforeEach
    fun setUp() {
        repository = mock()
        port = mock()
        portInstance = mock()
        whenever(portInstance.isResolvable).thenReturn(true)
        whenever(portInstance.get()).thenReturn(port)
        whenever(port.compose(any())).thenReturn(MailComposeHandover("d-1", "/mail/compose/d-1"))
        jobs = mock()
        locator = IssuedDocumentLocator(jobs, repository)
        journal = mock()
        // Default: the latest COMPLETED issued job names the default document's key.
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job(1L, pdfKey = "2026/RE-2026-0001.pdf")))
    }

    private fun job(
        id: Long,
        pdfKey: String,
        xmlKey: String? = null,
        finishedMinute: Int = 0,
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
        issued: Boolean = true,
    ) = aGenerationJob {
        this.id = id
        this.status = status
        this.issued = issued
        this.finishedAt = java.time.OffsetDateTime.parse("2026-10-01T10:00:00Z").plusMinutes(finishedMinute.toLong())
        this.resultRefs = GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = pdfKey, zugferdObjectKey = xmlKey))
    }

    private fun service(
        enabled: Boolean = true,
        presigner: DocumentPresignPort = DocumentPresignPort { key, _ -> PresignedDocument("https://s3/$key?sig=1", 2048L) },
    ) = InvoiceMailDispatchService(MailAvailability(enabled), portInstance, InvoiceMailComposeTransactions(repository, presigner, locator, journal, jobs))

    private fun document(
        key: String = "2026/RE-2026-0001.pdf",
        status: DocumentGenerationStatus = DocumentGenerationStatus.COMPLETED,
        sha: String? = "c".repeat(64),
        fileName: String = "Rechnung-RE-2026-0001.pdf",
        mime: String = "application/pdf",
    ) = anInvoiceDocument {
        this.objectKey = key
        this.generationStatus = status
        this.sha256Checksum = sha
        this.fileName = fileName
        this.mimeType = mime
    }

    private fun invoice(documents: List<InvoiceDocument> = listOf(document())) = Invoice().edit {
        this.id = 5L
        this.invoiceNumber = InvoiceNumber("RE-2026-0001")
        this.invoiceDate = LocalDate.of(2026, 9, 7)
        forceStatusForTest(InvoiceStatus.ISSUED)
        this.validationStatus = ValidationStatus.VALID
        totals = InvoiceTotals(netAmount = totals.netAmount, vatAmount = totals.vatAmount, grossAmount = Money.of("100.00"))
        this.recipient = aRecipient {
            name = "Muster GmbH"
            email = Email("kunde@example.com")
        }
        this.documents.asMutableForTest().addAll(documents)
    }

    private fun assertRefusedWith(gate: String) {
        val e = assertThrows(MailDispatchException::class.java) { service().compose(5L) }
        assertTrue(e.message!!.contains(gate), e.message)
        verify(port, never()).compose(any())
    }

    // ── Availability ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("not available when the feature is off, even if an adapter bean somehow exists")
    fun notAvailableWhenDisabled() {
        assertFalse(service(enabled = false).isAvailable())
    }

    @Test
    @DisplayName("not available when no adapter bean exists — @LookupIfProperty removed it")
    fun notAvailableWithoutAnAdapterBean() {
        whenever(portInstance.isResolvable).thenReturn(false)
        assertFalse(service().isAvailable())
    }

    @Test
    @DisplayName("available only when the feature is on AND an adapter bean exists")
    fun availableWhenBothHold() {
        assertTrue(service().isAvailable())
    }

    @Test
    @DisplayName("compose refuses outright when the feature is off — the UI should never have offered it")
    fun composeRefusesWhenUnavailable() {
        val e = assertThrows(MailDispatchException::class.java) { service(enabled = false).compose(5L) }
        assertTrue(e.message!!.contains("not available"))
        verify(repository, never()).findById(any())
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the invoice's completed, checksummed document is handed over as a presigned attachment")
    fun handsOverPresignedAttachments() {
        whenever(repository.findById(5L)).thenReturn(invoice())
        val captured = mutableListOf<InvoiceMailDraft>()
        whenever(port.compose(any())).thenAnswer {
            captured.add(it.arguments[0] as InvoiceMailDraft)
            MailComposeHandover("d-1", "/mail/compose/d-1")
        }

        val handover = (service().compose(5L) as MailComposeResult.Ready).handover

        assertEquals("/mail/compose/d-1", handover.composeUrl)
        val attachment = captured.single().attachments.single()
        assertEquals("Rechnung-RE-2026-0001.pdf", attachment.filename)
        assertEquals("application/pdf", attachment.contentType)
        assertEquals(2048L, attachment.sizeBytes)
        assertEquals("c".repeat(64), attachment.sha256)
        assertEquals("https://s3/2026/RE-2026-0001.pdf?sig=1", attachment.downloadUrl)
    }

    @Test
    @DisplayName("the presign TTL is short-lived — the component copies the bytes at draft creation")
    fun presignsWithAShortTtl() {
        whenever(repository.findById(5L)).thenReturn(invoice())
        var seenTtl: Duration? = null
        service(presigner = { key, ttl -> seenTtl = ttl; PresignedDocument("https://s3/$key", 1L) }).compose(5L)
        assertEquals(Duration.ofMinutes(15), seenTtl)
    }

    @Test
    @DisplayName("a document with a blank filename falls back to the object key's last segment")
    fun fallsBackToTheObjectKeyName() {
        whenever(repository.findById(5L)).thenReturn(invoice(listOf(document(fileName = "", mime = ""))))
        val captured = mutableListOf<InvoiceMailDraft>()
        whenever(port.compose(any())).thenAnswer {
            captured.add(it.arguments[0] as InvoiceMailDraft)
            MailComposeHandover("d", "/mail/compose/d")
        }

        service().compose(5L)

        val attachment = captured.single().attachments.single()
        assertEquals("RE-2026-0001.pdf", attachment.filename)
        assertEquals("application/octet-stream", attachment.contentType)
    }

    // ── Refusals ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown invoice id is refused")
    fun refusesUnknownInvoice() {
        whenever(repository.findById(5L)).thenReturn(null)
        val e = assertThrows(MailDispatchException::class.java) { service().compose(5L) }
        assertTrue(e.message!!.contains("not found"))
    }

    @Test
    @DisplayName("a document that is still PENDING does not qualify (DOCUMENT_MISSING) as an attachment")
    fun pendingForPendingDocuments() {
        whenever(repository.findById(5L)).thenReturn(
            invoice(listOf(document(status = DocumentGenerationStatus.PENDING))),
        )
        assertRefusedWith("DOCUMENT_MISSING")
        verify(port, never()).compose(any())
    }

    @Test
    @DisplayName("a document without a checksum does not qualify (DOCUMENT_MISSING) — the contract requires one")
    fun pendingForUnchecksummedDocuments() {
        whenever(repository.findById(5L)).thenReturn(invoice(listOf(document(sha = null))))
        assertRefusedWith("DOCUMENT_MISSING")
    }

    @Test
    @DisplayName("boundary - a document with a blank (non-null) checksum does not qualify (DOCUMENT_MISSING) either")
    fun pendingForBlankChecksumDocuments() {
        whenever(repository.findById(5L)).thenReturn(invoice(listOf(document(sha = "   "))))
        assertRefusedWith("DOCUMENT_MISSING")
    }

    @Test
    @DisplayName("boundary - a completed, checksummed document with a blank object key does not qualify (DOCUMENT_MISSING)")
    fun pendingForBlankObjectKeyDocuments() {
        whenever(repository.findById(5L)).thenReturn(invoice(listOf(document(key = "   "))))
        assertRefusedWith("DOCUMENT_MISSING")
    }

    @Test
    @DisplayName("an invoice with no documents at all is reported as DOCUMENT_MISSING rather than mailed empty")
    fun pendingForWithoutDocuments() {
        whenever(repository.findById(5L)).thenReturn(invoice(emptyList()))
        assertRefusedWith("DOCUMENT_MISSING")
    }

    @Test
    @DisplayName("a store that cannot presign fails the handover — never a mail without its invoice")
    fun refusesWhenThePresignerCannotSign() {
        whenever(repository.findById(5L)).thenReturn(invoice())
        val e = assertThrows(MailDispatchException::class.java) {
            service(presigner = { _, _ -> null }).compose(5L)
        }
        assertTrue(e.message!!.contains("cannot hand attachments over"))
        verify(port, never()).compose(any())
    }

    @Test
    @DisplayName("no issued generation job at all: error, nothing is presigned or sent")
    fun failsWithoutAnyJob() {
        whenever(repository.findById(5L)).thenReturn(invoice(emptyList()))
        whenever(jobs.findByInvoiceId(5L)).thenReturn(emptyList())
        val e = assertThrows(MailDispatchException::class.java) { service().compose(5L) }
        assertTrue(e.message!!.contains("DOCUMENT_MISSING"), e.message)
        verify(port, never()).compose(any())
    }

    @Test
    @DisplayName("every issued job FAILED: error instead of an endless pending hint")
    fun failsWhenAllIssuedJobsFailed() {
        whenever(repository.findById(5L)).thenReturn(invoice(emptyList()))
        whenever(jobs.findByInvoiceId(5L)).thenReturn(
            listOf(
                job(1L, pdfKey = "a.pdf", status = GenerationJobStatus.FAILED),
                job(2L, pdfKey = "b.pdf", status = GenerationJobStatus.FAILED),
            ),
        )
        val e = assertThrows(MailDispatchException::class.java) { service().compose(5L) }
        assertTrue(e.message!!.contains("DOCUMENT_MISSING"), e.message)
    }

    @Test
    @DisplayName("one FAILED and one RUNNING issued job: still DOCUMENT_MISSING")
    fun pendingWhenOneJobFailedAndOneRuns() {
        whenever(repository.findById(5L)).thenReturn(invoice(emptyList()))
        whenever(jobs.findByInvoiceId(5L)).thenReturn(
            listOf(
                job(1L, pdfKey = "a.pdf", status = GenerationJobStatus.FAILED),
                job(2L, pdfKey = "b.pdf", status = GenerationJobStatus.RUNNING),
            ),
        )
        assertRefusedWith("DOCUMENT_MISSING")
    }

    @Test
    @DisplayName("a completed job with its attachable document is Ready")
    fun readyWithCompletedJob() {
        whenever(repository.findById(5L)).thenReturn(invoice())
        assertTrue(service().compose(5L) is MailComposeResult.Ready)
    }

    @Test
    @DisplayName("latest issued job not COMPLETED yet: DOCUMENT_MISSING")
    fun pendingWhileJobRuns() {
        whenever(repository.findById(5L)).thenReturn(invoice())
        whenever(jobs.findByInvoiceId(5L)).thenReturn(
            listOf(job(1L, pdfKey = "2026/RE-2026-0001.pdf", status = GenerationJobStatus.RUNNING)),
        )
        assertRefusedWith("DOCUMENT_MISSING")
    }

    @Test
    @DisplayName("the HTTP handover runs only after the read transaction returned, and compose itself is not transactional")
    fun handoverRunsOutsideTheTransaction() {
        val transactions = mock<InvoiceMailComposeTransactions>()
        val draft = InvoiceMailDraft("RE-1", de.littletrotter.domain.shared.InvoiceFormat.GERMAN, Email("kunde@example.com"), "Muster")
        whenever(transactions.prepare(5L)).thenReturn(InvoiceMailPreparation.Ready(draft))
        InvoiceMailDispatchService(MailAvailability(true), portInstance, transactions).compose(5L)

        val order = org.mockito.kotlin.inOrder(transactions, port)
        order.verify(transactions).prepare(5L)
        order.verify(port).compose(draft)
        assertFalse(InvoiceMailDispatchService::class.java.isAnnotationPresent(jakarta.transaction.Transactional::class.java))
        assertFalse(InvoiceMailDispatchService::class.java.getMethod("compose", Long::class.java).isAnnotationPresent(jakarta.transaction.Transactional::class.java))
        assertTrue(InvoiceMailComposeTransactions::class.java.getMethod("prepare", Long::class.java).isAnnotationPresent(jakarta.transaction.Transactional::class.java))
    }

    // ── Per-job documents: only the latest COMPLETED issued job is attached ───────

    @Test
    @DisplayName("with several completed jobs only the latest issued job's PDF + XML are attached, no duplicates")
    fun attachesOnlyTheLatestIssuedJobsDocuments() {
        val docs = listOf(
            document(key = "invoices/RE-1-10-a.pdf", fileName = "old.pdf"),
            document(key = "invoices/RE-1-10-a.xml", fileName = "old.xml", mime = "application/xml"),
            document(key = "invoices/RE-1-11-b.pdf", fileName = "new.pdf"),
            document(key = "invoices/RE-1-11-b.xml", fileName = "new.xml", mime = "application/xml"),
            document(key = "invoices/draft/5-12-c.pdf", fileName = "draft.pdf"),
            document(key = "invoices/RE-1-11-b.tar.gz", fileName = "new.tar.gz", mime = "application/gzip"),
        )
        whenever(repository.findById(5L)).thenReturn(invoice(docs))
        whenever(jobs.findByInvoiceId(5L)).thenReturn(
            listOf(
                job(12L, pdfKey = "invoices/draft/5-12-c.pdf", finishedMinute = 9, issued = false),
                job(13L, pdfKey = "invoices/RE-1-13-d.pdf", finishedMinute = 10, status = GenerationJobStatus.FAILED),
                job(10L, pdfKey = "invoices/RE-1-10-a.pdf", xmlKey = "invoices/RE-1-10-a.xml", finishedMinute = 1),
                job(11L, pdfKey = "invoices/RE-1-11-b.pdf", xmlKey = "invoices/RE-1-11-b.xml", finishedMinute = 5),
            ),
        )
        val captured = mutableListOf<InvoiceMailDraft>()
        whenever(port.compose(any())).thenAnswer {
            captured.add(it.arguments[0] as InvoiceMailDraft)
            MailComposeHandover("d", "/mail/compose/d")
        }

        service().compose(5L)

        assertEquals(listOf("new.pdf", "new.xml"), captured.single().attachments.map { it.filename })
    }

    @Test
    @DisplayName("a reproduced current document is never handed over: journaled first, then refused")
    fun refusesReproducedDocumentAfterJournaling() {
        val reproduced = document().edit { id = 77L; reproduced = true }
        whenever(repository.findById(5L)).thenReturn(invoice(listOf(reproduced)))

        val e = assertThrows(ReproductionNotSendableException::class.java) { service().compose(5L) }

        assertEquals("DOCUMENT_REPRODUCTION_NOT_SENDABLE", e.code)
        verify(journal).record(77L)
        verify(port, never()).compose(any())
    }

    @Test
    @DisplayName("an original (not reproduced) current document passes the send gate")
    fun originalDocumentPassesTheGate() {
        val original = document().edit { id = 78L }
        whenever(repository.findById(5L)).thenReturn(invoice(listOf(original)))

        service().compose(5L)

        verify(journal, never()).record(any())
    }

    @Test
    @DisplayName("documents exist but no issued job names them: error, never an arbitrary pick")
    fun failsForWithoutAnIssuedJob() {
        whenever(repository.findById(5L)).thenReturn(invoice())
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job(1L, pdfKey = "2026/RE-2026-0001.pdf", issued = false)))

        val e = assertThrows(MailDispatchException::class.java) { service().compose(5L) }
        assertTrue(e.message!!.contains("DOCUMENT_MISSING"), e.message)
    }

    // ── SFS — deployment AND feature flag decide, never one alone ─────────────

    @Test
    @DisplayName("SFS — an installed component is still unavailable while the email feature flag is off")
    fun featureFlagOffHidesTheEntryOnADeployedInstance() {
        whenever(repository.findById(5L)).thenReturn(invoice())
        val gate = de.littletrotter.application.settings.featureGateWithDisabled(
            de.littletrotter.domain.settings.FeatureFlag.EMAIL_SEND,
        )
        val withFlag = InvoiceMailDispatchService(MailAvailability(true), portInstance, InvoiceMailComposeTransactions(repository, presignerOff(), locator, journal, jobs), gate)

        assertFalse(withFlag.isAvailable(), "System → Einstellungen AUS ⇒ kein Versandangebot")
        val e = assertThrows(MailDispatchException::class.java) { withFlag.compose(5L) }
        assertTrue(e.message!!.contains("switched off"), "die Meldung benennt die Ursache: ${e.message}")
        verify(port, never()).compose(any())
    }

    @Test
    @DisplayName("boundary - a switched-off feature flag combined with an undeployed instance reports the deployment cause, not the flag")
    fun featureFlagOffAndUndeployedReportsDeploymentCause() {
        val gate = de.littletrotter.application.settings.featureGateWithDisabled(
            de.littletrotter.domain.settings.FeatureFlag.EMAIL_SEND,
        )
        val withFlag = InvoiceMailDispatchService(MailAvailability(false), portInstance, InvoiceMailComposeTransactions(repository, presignerOff(), locator, journal, jobs), gate)

        val e = assertThrows(MailDispatchException::class.java) { withFlag.compose(5L) }
        assertTrue(e.message!!.contains("not available"), "both off at once still names the deployment, not the flag: ${e.message}")
        verify(port, never()).compose(any())
    }

    @Test
    @DisplayName("SFS — flag on with a deployed component dispatches as before")
    fun featureFlagOnKeepsTheDeployedBehaviour() {
        whenever(repository.findById(5L)).thenReturn(invoice())
        val gate = de.littletrotter.application.settings.featureGateWithDisabled()
        val service = InvoiceMailDispatchService(MailAvailability(true), portInstance, InvoiceMailComposeTransactions(repository, presignerOff(), locator, journal, jobs), gate)

        assertTrue(service.isAvailable())
        service.compose(5L)
        verify(port).compose(any())
    }

    @Test
    @DisplayName("boundary - an enabled feature flag with a missing adapter bean reports the deployment cause, not the flag")
    fun featureFlagEnabledButAdapterMissingReportsDeploymentCause() {
        whenever(portInstance.isResolvable).thenReturn(false)
        val gate = de.littletrotter.application.settings.featureGateWithDisabled()
        val withFlag = InvoiceMailDispatchService(MailAvailability(true), portInstance, InvoiceMailComposeTransactions(repository, presignerOff(), locator, journal, jobs), gate)

        assertFalse(withFlag.isAvailable())
        val e = assertThrows(MailDispatchException::class.java) { withFlag.compose(5L) }
        assertTrue(e.message!!.contains("not available"), "flag is enabled, so the missing adapter must be named: ${e.message}")
    }

    private fun presignerOff() =
        DocumentPresignPort { key, _ -> PresignedDocument("https://s3/$key?sig=1", 2048L) }
}
