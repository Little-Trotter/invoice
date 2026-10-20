package de.littletrotter.application.invoice

import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.invoice.port.inbound.ReceiptView
import de.littletrotter.application.invoice.port.inbound.UploadReceiptUseCase
import de.littletrotter.application.invoice.port.outbound.ReceiptPageInspector
import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.domain.masterdata.ReceiptUpload
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptPageProfile
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.receipt.acceptsUnreduced
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/**
 * Application service for uploading a PDF into the receipt pool (dedup by checksum).
 * Only PDF documents (application/pdf) are accepted.
 *
 * The [cache] port provides a fast-path checksum lookup backed by Valkey to avoid repeated
 * [DocumentStore.exists] calls on the object store for already-known receipts.
 */
@ApplicationScoped
class ReceiptUploadService @Inject constructor(
    val receiptRepository: ReceiptRepository,
    val documentStore: DocumentStore,
    private val cache: InvoiceCachePort,
    private val time: TimeSource,
    private val receiptPdfReducer: ReceiptPdfReducer,
    private val pageInspector: ReceiptPageInspector,
    private val storeTransaction: ReceiptStoreTransaction,
) : UploadReceiptUseCase {

    companion object {
        private const val RECEIPT_PREFIX = "receipt-pool/"
        private const val PDF_MIME = "application/pdf"

        /** `%PDF` magic-number header: 4 bytes, [isPdf] checks the last at this index. */
        private const val PDF_MAGIC_BYTE_COUNT = 4
        private const val PDF_MAGIC_LAST_INDEX = 3
    }

    /**
     * Uploads a PDF to the receipt pool.
     *
     * @param fileName     original filename (must end with .pdf)
     * @param description  required human-readable description (non-blank)
     * @param pdfData      raw PDF bytes
     * @param documentDate required date the document itself carries (Belegdatum); only applied to
     *                     a freshly created pool entry — a dedup hit returns the existing receipt
     *                     unchanged, its description and date stay whatever the first upload
     *                     recorded
     * @param mode         [ReceiptUploadMode.REDUCE] (default) reduces an oversized upload towards
     *                     `ReceiptSizePolicy.TARGET_RECEIPT_SIZE`; when the target is out of reach the
     *                     smallest result is stored anyway and the caller warns about the budget; [ReceiptUploadMode.KEEP_UNREDUCED]
     *                     keeps the bytes as uploaded, refused above the smaller unreduced cap
     * @return persisted Receipt entity
     * @throws ApplicationValidationException when the data is not a valid PDF, the description is
     *                     missing/blank, or the document date is missing
     * @throws ReceiptUploadTooLargeException when the raw upload exceeds `ReceiptSizePolicy.MAX_UPLOAD_SIZE`
     * @throws ReceiptUnreducedTooLargeException when [mode] is [ReceiptUploadMode.KEEP_UNREDUCED]
     *                     and the raw upload exceeds `ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE`
     */
    // Not @Transactional itself: validation, reduction, hashing and the object write need no
    // transaction. Only the final step ([storeUnderChecksumLock]) is one short transaction that
    // serialises with [ReceiptPoolService.delete] per content hash.
    fun upload(
        fileName: String?,
        description: String?,
        pdfData: ByteArray,
        documentDate: LocalDate?,
        mode: ReceiptUploadMode = ReceiptUploadMode.REDUCE,
    ): Receipt {
        val (requiredDescription, requiredDocumentDate) = validateUploadInput(description, pdfData, documentDate, mode)
        val reduced = prepareVersion(pdfData, mode)
        val checksum = receiptSha256Hex(reduced.bytes)

        findExistingReceipt(checksum, reduced.bytes)?.let { return it }

        val objectKey = "$RECEIPT_PREFIX$checksum.pdf"
        // Content-addressed key, so writing the same bytes twice is harmless. Done before the
        // transaction so the checksum lock and the pooled connection are not held for the duration
        // of the object-store round trip.
        documentStore.put(objectKey, reduced.bytes, PDF_MIME)
        val uploadSpec = ReceiptUploadSpec(
            fileName = fileName,
            description = requiredDescription,
            documentDate = requiredDocumentDate,
            reduced = reduced,
            checksum = checksum,
            objectKey = objectKey,
        )
        return storeUnderChecksumLock(uploadSpec)
    }

    /** Port entry point: same upload, handed back as the read model. */
    override fun uploadReceipt(
        fileName: String?,
        description: String?,
        pdfData: ByteArray,
        documentDate: LocalDate?,
        mode: ReceiptUploadMode,
    ): ReceiptView = upload(fileName, description, pdfData, documentDate, mode).toView()

    internal class ReceiptUploadSpec(
        val fileName: String?,
        val description: String,
        val documentDate: LocalDate,
        val reduced: ReducedPayload,
        val checksum: String,
        val objectKey: String,
    )

    /** [reduceIfNeeded]'s result: the bytes that get stored, the stage that produced them and the
     *  uploaded size they were reduced from — kept together because the receipt records all three.
     *  A plain (non-`data`) class since [bytes] is a `ByteArray` and nothing compares two instances. */
    internal class ReducedPayload(
        val bytes: ByteArray,
        val stage: ReceiptNormalisationStage,
        val originalSizeBytes: Long,
        val profile: ReceiptPageProfile,
    )

    private fun validateUploadInput(
        description: String?,
        pdfData: ByteArray,
        documentDate: LocalDate?,
        mode: ReceiptUploadMode,
    ): Pair<String, LocalDate> {
        if (!isPdf(pdfData)) {
            throw ApplicationValidationException("Only PDF documents are allowed.")
        }
        val requiredDescription = requireReceiptDescription(description)
        val requiredDocumentDate = documentDate
            ?: throw ApplicationValidationException(receiptBundleMessage("receipt.error.documentDateRequired"))
        val uploadedSize = DocumentSize.ofBytes(pdfData.size.toLong())
        if (uploadedSize > ReceiptSizePolicy.MAX_UPLOAD_SIZE) {
            throw ReceiptUploadTooLargeException(uploadedSize, ReceiptSizePolicy.MAX_UPLOAD_SIZE)
        }
        if (mode == ReceiptUploadMode.KEEP_UNREDUCED && !ReceiptSizePolicy.acceptsUnreduced(uploadedSize)) {
            throw ReceiptUnreducedTooLargeException(uploadedSize, ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE)
        }
        return Pair(requiredDescription, requiredDocumentDate)
    }

    /**
     * The one version of the receipt that gets stored: the original or its reduction, together with
     * the page facts of exactly those bytes. Every step that must see the bytes before they are
     * stored (reduction, page inspection) runs here, before the checksum is taken.
     */
    private fun prepareVersion(pdfData: ByteArray, mode: ReceiptUploadMode): ReducedPayload {
        val chosen = reduceIfNeeded(pdfData, mode)
        return ReducedPayload(chosen.bytes, chosen.stage, pdfData.size.toLong(), pageInspector.inspect(chosen.bytes))
    }

    /** [ReceiptUploadMode.KEEP_UNREDUCED] never touches the bytes — [validateUploadInput] already
     *  enforced the unreduced cap. [ReceiptUploadMode.REDUCE] delegates to [receiptPdfReducer],
     *  whose own contract returns the input unchanged (stage [ReceiptNormalisationStage.NONE])
     *  when it already meets the target; when the target is out of reach it still hands back the
     *  smallest version, which is stored. */
    private fun reduceIfNeeded(pdfData: ByteArray, mode: ReceiptUploadMode): ChosenBytes {
        if (mode == ReceiptUploadMode.KEEP_UNREDUCED) {
            return ChosenBytes(pdfData, ReceiptNormalisationStage.NONE)
        }
        return when (val reduction = receiptPdfReducer.reduce(pdfData, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)) {
            is ReceiptReduction.Reduced -> ChosenBytes(reduction.bytes, reduction.stage)
            is ReceiptReduction.StillTooLarge -> ChosenBytes(reduction.bytes, reduction.stage)
        }
    }

    private class ChosenBytes(val bytes: ByteArray, val stage: ReceiptNormalisationStage)

    private fun findExistingReceipt(checksum: String, pdfData: ByteArray): Receipt? {
        if (cache.isChecksumKnown(checksum)) {
            val existing = receiptRepository.findByChecksum(checksum)
            if (existing != null) {
                if (!documentStore.exists(existing.objectKey)) {
                    documentStore.put(existing.objectKey, pdfData, PDF_MIME)
                }
                return existing
            }
            cache.evictChecksum(checksum)
        }

        val existing = receiptRepository.findByChecksum(checksum)
        if (existing != null) {
            cache.markChecksumKnown(checksum)
            if (!documentStore.exists(existing.objectKey)) {
                documentStore.put(existing.objectKey, pdfData, PDF_MIME)
            }
            return existing
        }
        return null
    }

    /**
     * Invariant: a receipt row exists only if its object exists, serialised per content hash.
     * The caller has already written the content-addressed object, but a concurrent delete of the
     * same bytes may have removed it again before this transaction took the lock. Under the
     * checksum lock the existing row is re-checked, the object is verified with one existence
     * check (rewritten only when that delete won the race), and only then the row is inserted; the
     * delete holds the same lock while it removes row and object. The normal path is therefore
     * lock, one existence check and one insert, with no object-store upload inside the
     * transaction. The lock also makes the unique-checksum race impossible, so there is no
     * conflict recovery to do.
     */
    internal fun storeUnderChecksumLock(spec: ReceiptUploadSpec): Receipt =
        storeTransaction.inNewTransaction { insertUnderChecksumLock(spec) }

    private fun insertUnderChecksumLock(spec: ReceiptUploadSpec): Receipt {
        receiptRepository.lockChecksum(spec.checksum)
        val existing = receiptRepository.findByChecksum(spec.checksum)
        if (existing != null) {
            ensureObject(existing.objectKey, spec.reduced.bytes)
            cache.markChecksumKnown(spec.checksum)
            return existing
        }

        val receipt = Receipt.create(
            ReceiptUpload(
                fileName = spec.fileName ?: "receipt.pdf",
                description = spec.description,
                objectKey = spec.objectKey,
                documentDate = spec.documentDate,
                sizeBytes = spec.reduced.bytes.size.toLong(),
                sha256 = spec.checksum,
                uploadedAt = time.nowUtc(),
                normalisationStage = spec.reduced.stage,
                originalSizeBytes = spec.reduced.originalSizeBytes,
                profile = spec.reduced.profile,
            ),
        )
        receipt.validate()
        ensureObject(spec.objectKey, spec.reduced.bytes)
        val saved = receiptRepository.saveAndFlush(receipt)
        cache.markChecksumKnown(spec.checksum)
        return saved
    }

    private fun ensureObject(objectKey: String, bytes: ByteArray) {
        if (!documentStore.exists(objectKey)) {
            documentStore.put(objectKey, bytes, PDF_MIME)
        }
    }

    private fun isPdf(data: ByteArray?): Boolean {
        return data != null && data.size >= PDF_MAGIC_BYTE_COUNT &&
                data[0] == 0x25.toByte() &&
                data[1] == 0x50.toByte() &&
                data[2] == 0x44.toByte() &&
                data[PDF_MAGIC_LAST_INDEX] == 0x46.toByte()
    }
}
