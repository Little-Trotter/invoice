package de.littletrotter.application.invoice

import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.error.ApplicationExceptionTranslator
import de.littletrotter.application.invoice.port.inbound.NormaliseReceiptUseCase
import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.exception.ReceiptNotFoundException
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.util.logging.Logger

/**
 * Implements [NormaliseReceiptUseCase]: reduces one already-stored receipt towards
 * `ReceiptSizePolicy.TARGET_RECEIPT_SIZE` and overwrites it in place. Mirrors [ReceiptUploadService
 * .upload]'s own checksum/collision handling (same [receiptPdfReducer], same [receiptSha256Hex])
 * so the two write paths that touch a receipt's bytes can never disagree about what "the same
 * content" means.
 */
@ApplicationScoped
class NormaliseReceipt @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val documentStore: DocumentStore,
    private val receiptPdfReducer: ReceiptPdfReducer,
) : NormaliseReceiptUseCase {

    companion object {
        private const val PDF_MIME = "application/pdf"
        private const val RECEIPT_ID_MISSING = "Receipt id is missing"
        private val logger = Logger.getLogger(NormaliseReceipt::class.java.name)
    }

    @Transactional
    override fun normalise(receiptId: Long): ReceiptRowDto {
        val receipt = receiptRepository.findById(receiptId)
            ?: throw ApplicationExceptionTranslator.from(ReceiptNotFoundException(receiptId))
        val storedBytes = readStoredBytes(receipt, receiptId)

        return when (val reduction = receiptPdfReducer.reduce(storedBytes, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)) {
            is ReceiptReduction.StillTooLarge ->
                throw ReceiptReductionFailedException(reduction.pageCount, reduction.resultingSize)
            is ReceiptReduction.Reduced -> applyReduction(receipt, storedBytes, reduction)
        }
    }

    /** ST-B2 — same translation [ReceiptPoolService.getBytes] applies for a receipt row whose
     *  object went missing from the store. */
    private fun readStoredBytes(receipt: Receipt, receiptId: Long): ByteArray =
        runDocumentStoreOp(onFailure = { failure ->
            logger.warning("Could not read receipt object from store: receiptId=$receiptId, objectKey=${receipt.objectKey} – ${failure.message}")
            throw ReceiptNotFoundException(receiptId, failure)
        }) { documentStore.get(receipt.objectKey) }

    private fun applyReduction(receipt: Receipt, storedBytes: ByteArray, reduction: ReceiptReduction.Reduced): ReceiptRowDto {
        if (receipt.originalSizeBytes == null) {
            receipt.originalSizeBytes = storedBytes.size.toLong()
        }
        if (reduction.stage == ReceiptNormalisationStage.NONE) {
            return receipt.toRowDto()
        }

        val newChecksum = receiptSha256Hex(reduction.bytes)
        rejectIfDuplicate(receipt, newChecksum)

        documentStore.put(receipt.objectKey, reduction.bytes, PDF_MIME)
        receipt.sizeBytes = reduction.bytes.size.toLong()
        receipt.sha256Checksum = newChecksum
        receipt.normalisationStage = reduction.stage
        receipt.validate()
        return try {
            receiptRepository.saveAndFlush(receipt).toRowDto()
        } catch (e: jakarta.persistence.PersistenceException) {
            // A concurrent write claimed the same checksum between rejectIfDuplicate's check and
            // this flush — the same race ReceiptUploadService.upload's own catch resolves, but
            // there the resolution is "return the existing row"; here it cannot be, the receipt
            // being normalised is this row, not the other one, so it stays a duplicate rejection.
            throwDuplicateAfterRace(receipt, newChecksum, e)
        }
    }

    private fun throwDuplicateAfterRace(receipt: Receipt, newChecksum: String, cause: Exception): Nothing {
        val collision = receiptRepository.findByChecksum(newChecksum)
        throw ReceiptDuplicateException(
            receiptId = requireNotNull(receipt.id) { RECEIPT_ID_MISSING },
            duplicateOfReceiptId = collision?.id ?: -1L,
            cause = cause,
        )
    }

    /** A normalised receipt is a specific row other tables already reference by id — unlike
     *  upload's dedup, a checksum collision here cannot silently reuse the other row, it has to
     *  refuse instead ([ReceiptDuplicateException]'s own KDoc). */
    private fun rejectIfDuplicate(receipt: Receipt, newChecksum: String) {
        val collision = receiptRepository.findByChecksum(newChecksum) ?: return
        if (collision.id != receipt.id) {
            throw ReceiptDuplicateException(
                receiptId = requireNotNull(receipt.id) { RECEIPT_ID_MISSING },
                duplicateOfReceiptId = requireNotNull(collision.id) { RECEIPT_ID_MISSING },
            )
        }
    }
}
