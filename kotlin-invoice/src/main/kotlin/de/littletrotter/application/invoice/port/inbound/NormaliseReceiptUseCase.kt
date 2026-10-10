package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.ReceiptRowDto

/**
 * Input port for reducing an already-stored receipt towards `ReceiptSizePolicy
 * .TARGET_RECEIPT_SIZE` on demand — receipt-size-normalisation's retrofit path for receipts
 * uploaded before the upload-time reducer existed, or left at [de.littletrotter.domain.masterdata
 * .ReceiptNormalisationStage.NONE] because they met the target back then.
 */
interface NormaliseReceiptUseCase : CommandEntryPoint {
    /** Overwrites the stored object in place when reduction actually changes anything; returns
     *  the receipt unchanged when it already meets the target. */
    fun normalise(receiptId: Long): ReceiptRowDto
}
