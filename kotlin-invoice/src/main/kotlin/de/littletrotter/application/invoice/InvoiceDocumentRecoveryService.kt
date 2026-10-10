package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.CurrentDocumentView
import de.littletrotter.application.invoice.port.inbound.DocumentReproductionQueryPort
import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.inbound.ResolveInvoiceDocumentUseCase
import de.littletrotter.application.invoice.port.inbound.RestoreInvoiceDocumentUseCase
import de.littletrotter.application.invoice.port.outbound.MailJournalAttachmentRestorePort
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.DocumentReproductionPolicy
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.MissingOriginalResolution
import de.littletrotter.domain.invoice.OriginalState
import de.littletrotter.domain.invoice.RestoreSource
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.control.ActivateRequestContext
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/**
 * Resolves the document of record of an invoice whose stored original may be gone or altered:
 * serve it, put back a byte-identical copy, or render it again through the normal job pipeline.
 *
 * Nothing here holds a database transaction across a document-store or mail-component call; the
 * transactional pieces live in [InvoiceDocumentRecoveryTransactions]. Rendering never happens in
 * the request: it is a started job, and a storage error is reported as such instead of being read
 * as "missing" (which would re-render a document that may still exist).
 */
@ApplicationScoped
class InvoiceDocumentRecoveryService @Inject constructor(
    private val issuedDocumentLocator: IssuedDocumentLocator,
    private val invoiceRepository: InvoiceRepository,
    private val documentStore: DocumentStore,
    private val transactions: InvoiceDocumentRecoveryTransactions,
    private val documentJobPublisher: DocumentJobPublisher,
    private val mailJournalRestore: Instance<MailJournalAttachmentRestorePort>,
) : ResolveInvoiceDocumentUseCase, RestoreInvoiceDocumentUseCase, DocumentReproductionQueryPort {

    private val log = Logger.getLogger(InvoiceDocumentRecoveryService::class.java)

    @ActivateRequestContext
    override fun resolveForInvoice(invoiceId: Long): DocumentResolution = resolve(invoiceId, explicit = false)

    @ActivateRequestContext
    @RequiresPermission(AppPermission.DOCUMENT_REPRODUCE)
    override fun restore(invoiceId: Long): DocumentResolution = resolve(invoiceId, explicit = true)

    @ActivateRequestContext
    override fun currentDocument(invoiceId: Long): CurrentDocumentView? =
        issuedDocumentLocator.currentPdf(invoiceId)?.let {
            CurrentDocumentView(
                objectKey = it.document.objectKey,
                jobId = it.jobId,
                reproduced = it.document.reproduced,
                reproducedAt = it.document.reproducedAt,
                reproductionOfSha256 = it.document.reproductionOfSha256,
            )
        }

    private fun resolve(invoiceId: Long, explicit: Boolean): DocumentResolution {
        val invoice = invoiceRepository.findById(invoiceId)
        val current = issuedDocumentLocator.currentPdf(invoiceId)
        if (invoice == null) return DocumentResolution.NoDocument
        if (current == null) {
            // A voided invoice has no document of record anywhere (nothing was ever stored or
            // mailed), so an explicit request may render it from its frozen state.
            return if (explicit && invoice.status == InvoiceStatus.VOIDED) reproduceWithoutRow(requireNotNull(invoice.id)) else DocumentResolution.NoDocument
        }
        val document = current.document
        // A voided invoice is never re-rendered as a plain regeneration: its bytes are only
        // reproduced after the store and the mail journal were searched for an identical copy.
        val sent = invoice.mailSent == true || invoice.status == InvoiceStatus.VOIDED
        val replaced = if (explicit && sent) replaceReproduction(invoice, document) else null
        return replaced ?: resolveStoredOriginal(invoice, document, sent)
    }

    private fun resolveStoredOriginal(invoice: Invoice, document: InvoiceDocument, sent: Boolean): DocumentResolution {
        val lost = document.generationStatus == DocumentGenerationStatus.LOST
        val running = if (lost) transactions.runningPrintJob(requireNotNull(invoice.id)) else null
        if (running != null) return started(running)
        val probe = (if (lost) Probe.Done(OriginalState.MISSING, null) else probe(document.objectKey, document.sha256Checksum)) as? Probe.Done
            ?: return DocumentResolution.StorageUnavailable
        return if (probe.state == OriginalState.PRESENT) DocumentResolution.Serve(document.objectKey) else recover(invoice, document, sent, probe)
    }

    /** The original is gone or altered: restore an identical copy, else render the document again. */
    private fun recover(invoice: Invoice, document: InvoiceDocument, sent: Boolean, probe: Probe.Done): DocumentResolution {
        val key = document.objectKey
        val expected = document.sha256Checksum
        val lookup = if (sent && !expected.isNullOrBlank()) findCopy(invoice, key, expected) else SourceLookup(null)
        if (lookup.unavailable) return DocumentResolution.StorageUnavailable
        val copy = lookup.copy
        val resolution = DocumentReproductionPolicy.resolve(
            probe.state,
            sent,
            rustfsRestorable = copy?.source == RestoreSource.RUSTFS,
            mailJournalRestorable = copy?.source == RestoreSource.MAIL_JOURNAL,
        )
        val invoiceId = requireNotNull(invoice.id)
        val documentId = requireNotNull(document.id)
        return when (resolution) {
            is MissingOriginalResolution.ServeOriginal -> DocumentResolution.Serve(key)
            is MissingOriginalResolution.RestoreFrom -> {
                val restored = requireNotNull(copy)
                documentStore.put(key, restored.bytes, PDF_CONTENT_TYPE)
                transactions.recordRestore(documentId, resolution.reason.name, restored.asRestoredCopy(), clearReproduction = false)
                DocumentResolution.Restored(key, restored.source)
            }
            is MissingOriginalResolution.Regenerate ->
                requestRegeneration(invoiceId, documentId, false, LossFacts(resolution.reason, expected, probe.foundSha))
            is MissingOriginalResolution.Reproduce ->
                requestRegeneration(invoiceId, documentId, true, LossFacts(resolution.reason, expected, probe.foundSha))
        }
    }

    /**
     * Explicit restore of a reproduced document: when the original that was sent turns up again,
     * it replaces the reproduction and the reproduction tag disappears.
     */
    private fun replaceReproduction(invoice: Invoice, document: InvoiceDocument): DocumentResolution? {
        val originalSha = document.reproductionOfSha256
        if (!document.reproduced || originalSha.isNullOrBlank()) return null
        val copy = findCopy(invoice, document.objectKey, originalSha).copy ?: return null
        documentStore.put(document.objectKey, copy.bytes, PDF_CONTENT_TYPE)
        transactions.recordRestore(requireNotNull(document.id), REPRODUCTION_REPLACED, copy.asRestoredCopy(), clearReproduction = true)
        return DocumentResolution.Restored(document.objectKey, copy.source)
    }

    private fun requestRegeneration(invoiceId: Long, documentId: Long, reproduction: Boolean, facts: LossFacts): DocumentResolution =
        try {
            when (val outcome = transactions.requestRegeneration(documentId, invoiceId, reproduction, facts)) {
                is RegenerationOutcome.Created -> {
                    publish(outcome.job)
                    DocumentResolution.RegenerationStarted(requireNotNull(outcome.job.id), reproduction)
                }
                is RegenerationOutcome.AlreadyRunning ->
                    DocumentResolution.RegenerationStarted(outcome.jobId, outcome.reproduction)
            }
        } catch (rejected: InvoiceGenerationAlreadyRunningException) {
            val running = transactions.runningPrintJob(invoiceId) ?: throw rejected
            started(running)
        }

    private fun reproduceWithoutRow(invoiceId: Long): DocumentResolution =
        try {
            when (val outcome = transactions.requestReproductionWithoutRow(invoiceId)) {
                is RegenerationOutcome.Created -> {
                    publish(outcome.job)
                    DocumentResolution.RegenerationStarted(requireNotNull(outcome.job.id), true)
                }
                is RegenerationOutcome.AlreadyRunning ->
                    DocumentResolution.RegenerationStarted(outcome.jobId, outcome.reproduction)
            }
        } catch (rejected: InvoiceGenerationAlreadyRunningException) {
            val running = transactions.runningPrintJob(invoiceId) ?: throw rejected
            started(running)
        }

    private fun started(job: GenerationJob) =
        DocumentResolution.RegenerationStarted(requireNotNull(job.id), job.trigger == GenerationJobTrigger.REPRODUCTION)

    // A failed publish leaves the PENDING job for the scheduled republish; the request itself
    // already succeeded durably.
    private fun publish(job: GenerationJob) {
        documentJobPublisher.publish(job, skipValidation = false).exceptionally { failure ->
            log.warnf("Publishing recovery job %s failed, the poller will retry: %s", job.id, failure.message)
            null
        }
    }

    private sealed interface Probe {
        data class Done(val state: OriginalState, val foundSha: String?) : Probe

        data object Unavailable : Probe
    }

    private fun probe(key: String, expectedSha: String?): Probe =
        runDocumentStoreOp(onFailure = { failure -> if (isMissing(failure)) Probe.Done(OriginalState.MISSING, null) else Probe.Unavailable }) {
            val bytes = documentStore.get(key)
            val actual = Sha256Hex.of(bytes)
            // A row without a checksum cannot be compared; the download path serves it the same way.
            if (expectedSha.isNullOrBlank() || actual.equals(expectedSha, ignoreCase = true)) {
                Probe.Done(OriginalState.PRESENT, actual)
            } else {
                Probe.Done(OriginalState.MISMATCH, actual)
            }
        }

    private fun isMissing(failure: Exception) = failure is NoSuchElementException || failure is NoSuchKeyException

    private class Copy(
        val bytes: ByteArray,
        val sha256: String,
        val source: RestoreSource,
        val journalId: Long? = null,
        val file: String? = null,
    )

    private fun Copy.asRestoredCopy() = RestoredCopy(source, sha256, journalId, file)

    private class SourceLookup(val copy: Copy?, val unavailable: Boolean = false)

    /** Document store first, the mail journal only when the store holds no identical copy. */
    private fun findCopy(invoice: Invoice, currentKey: String, sha256: String): SourceLookup {
        val fromStore = findInStore(invoice, currentKey, sha256)
        if (fromStore.copy != null || fromStore.unavailable) return fromStore
        return SourceLookup(findInMailJournal(invoice, sha256))
    }

    private fun findInStore(invoice: Invoice, currentKey: String, sha256: String): SourceLookup {
        val number = invoice.invoiceNumber.value
        val prefix = DocumentKeyRules.issuedJobExportPrefix(number, invoice.id)
        return runDocumentStoreOp(onFailure = { SourceLookup(null, unavailable = true) }) {
            val copy = documentStore.listObjectKeys(prefix)
                .filter { it != currentKey && DocumentKeyRules.isIssuedJobPdfKey(it, number, invoice.id) }
                .firstNotNullOfOrNull { key -> identicalBytes(key, sha256) }
            SourceLookup(copy?.let { Copy(it, sha256, RestoreSource.RUSTFS) })
        }
    }

    /** A sibling object that cannot be read is simply not a candidate. */
    private fun identicalBytes(key: String, sha256: String): ByteArray? =
        runDocumentStoreOp(onFailure = { null }) {
            documentStore.get(key).takeIf { Sha256Hex.of(it).equals(sha256, ignoreCase = true) }
        }

    private fun findInMailJournal(invoice: Invoice, sha256: String): Copy? {
        if (!mailJournalRestore.isResolvable) return null
        val attachment = mailJournalRestore.get().findAttachment(invoice.invoiceNumber.value, sha256) ?: return null
        if (!Sha256Hex.of(attachment.bytes).equals(sha256, ignoreCase = true)) {
            log.warnf("Mail journal copy for invoice %s does not match the expected checksum; ignored", invoice.invoiceNumber.value)
            return null
        }
        return Copy(attachment.bytes, sha256, RestoreSource.MAIL_JOURNAL, attachment.journalId, attachment.file)
    }

    private companion object {
        const val PDF_CONTENT_TYPE = "application/pdf"
        const val REPRODUCTION_REPLACED = "REPRODUCTION_REPLACED"
    }
}
