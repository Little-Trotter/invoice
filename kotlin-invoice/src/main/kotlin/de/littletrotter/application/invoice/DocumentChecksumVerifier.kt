package de.littletrotter.application.invoice

import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import de.littletrotter.shared.DocumentStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * (`beratung-manipulationsschutz.md` M1) — verifies a fetched document's bytes against the
 * checksum [DocumentChecksumPersister] persisted atomically with the job's COMPLETED status
 * at generation time, right before those bytes are handed to a user through the in-app download path
 * ([de.littletrotter.ui.view.invoice.buildDownloadAction] et al. — the one place bytes actually
 * flow through this application; see this class's own "Residual gap" note below).
 *
 * Deliberately tolerant of a **missing** row: [InvoiceDocumentRepository.findByObjectKey]
 * returning `null` means either a legacy job that completed before checksums were tracked, or an object this table
 * never tracks — the download proceeds unverified exactly as it always has, rather than blocking
 * every pre-existing document. Only a row that DOES exist and carries a checksum is compared; a
 * **mismatch** against that stored value is the actual manipulation signal this task exists to
 * catch, and is never silently tolerated: it raises [DocumentChecksumMismatchException] and
 * records an ERROR system message (message-centre pattern, see
 * [de.littletrotter.application.message.MessageCenterEventHandler] for the
 * sibling event-driven producers — this one calls [MessageCenterService] directly since the
 * verification itself already runs synchronously on the download request, not as a reaction to a
 * fired domain event).
 *
 * **Residual gap:** `DocumentController`'s `/documents/presign` endpoint (kotlin-xpdf — a
 * sibling module that is deliberately not on this module's classpath, hence the plain name here)
 * hands out a presigned S3 URL — the bytes then flow straight from RustFS to the browser,
 * bypassing this application (and this verifier) entirely. That endpoint is not reachable from
 * this module's own download actions (see [de.littletrotter.ui.view.invoice.buildDownloadAction],
 * which fetches bytes in-process via [DocumentStore] instead), but if some other consumer
 * (`FacturXEngineIdleStopScheduler`? an external integration?) is ever wired to request presigned
 * URLs for archived invoice documents, verification would need to move to presign time instead
 * (fetch → hash → compare before signing) — this class only covers the path bytes actually take
 * through this application today.
 */
@ApplicationScoped
class DocumentChecksumVerifier @Inject constructor(
    private val invoiceDocumentRepository: InvoiceDocumentRepository,
    private val messageCenterService: MessageCenterService,
) {

    private val log = Logger.getLogger(javaClass)

    /**
     * Fetches [objectKey] from [documentStore] and verifies it against the persisted checksum
     * before returning the bytes. [invoiceId]/[invoiceNumber] are only used to record the system
     * message on a mismatch (never to look anything up) — `null` is accepted so a caller without
     * an invoice context on hand (should one ever exist) can still verify.
     *
     * @throws DocumentChecksumMismatchException when a persisted checksum exists for [objectKey]
     *   and does not match the fetched bytes' own SHA-256.
     */
    fun verifiedGet(documentStore: DocumentStore, objectKey: String, invoiceId: Long?, invoiceNumber: String?): ByteArray {
        val data = documentStore.get(objectKey)
        val expected = invoiceDocumentRepository.findByObjectKey(objectKey)?.sha256Checksum
        if (expected.isNullOrBlank()) {
            // No row (legacy/untracked) or a row without a checksum yet — nothing to verify
            // against; serve the bytes exactly as the download path always did before checksums were tracked.
            return data
        }
        val actual = Sha256Hex.of(data)
        if (!actual.equals(expected, ignoreCase = true)) {
            val ref = InvoiceReference.of(invoiceId, invoiceNumber)
            log.error("Checksum mismatch for $objectKey: expected $expected, got $actual (invoice ${ref.label})")
            messageCenterService.record(
                SystemMessageContent(
                    severity = SystemMessageSeverity.ERROR,
                    source = SystemMessageSource.EXPORT,
                    messageKey = "message.center.document.checksumMismatch",
                    args = listOf(ref.label, objectKey),
                    resolvedText = "Rechnung ${ref.label}: Prüfsummenabweichung beim archivierten Dokument " +
                        "$objectKey — Download blockiert, Manipulationsverdacht",
                    refType = "INVOICE",
                    refId = ref.idOrNull?.toString(),
                ),
            )
            throw DocumentChecksumMismatchException(objectKey)
        }
        return data
    }
}

/** A document's fetched bytes no longer hash to the checksum recorded at generation time.
 *  Carries [objectKey] as data so callers (the download handlers) can build an honest error
 *  response without re-parsing the message text. */
class DocumentChecksumMismatchException(val objectKey: String) :
    RuntimeException("Checksum mismatch for document '$objectKey' — download blocked, possible tampering")

/**
 * S6619 — [DocumentChecksumVerifier.verifiedGet] receives `invoiceId`/`invoiceNumber` as two
 * independent nullable primitives (see [de.littletrotter.ui.documents.DocumentDownloadResource]'s
 * own PO-review note: both are only ever forwarded from the same nullable `GenerationJob.invoice`,
 * so they are always both present or both absent together). Re-deriving a display label from that
 * pair via an elvis chain at every use site was exactly what Sonar's rule flagged (it could not
 * prove the left branch's reachability, and neither could a reader without re-deriving the same
 * pairing invariant) — [Known]/[Unknown] express that invariant once, in the type, instead.
 */
private sealed class InvoiceReference {
    abstract val label: String
    abstract val labelOrNull: String?
    abstract val idOrNull: Long?

    private class Known(invoiceId: Long, invoiceNumber: String?) : InvoiceReference() {
        private val resolvedLabel = invoiceNumber ?: invoiceId.toString()
        override val label get() = resolvedLabel
        override val labelOrNull get() = resolvedLabel
        override val idOrNull = invoiceId
    }

    private object Unknown : InvoiceReference() {
        override val label = "?"
        override val labelOrNull: String? = null
        override val idOrNull: Long? = null
    }

    companion object {
        fun of(invoiceId: Long?, invoiceNumber: String?): InvoiceReference =
            if (invoiceId != null) Known(invoiceId, invoiceNumber) else Unknown
    }
}
