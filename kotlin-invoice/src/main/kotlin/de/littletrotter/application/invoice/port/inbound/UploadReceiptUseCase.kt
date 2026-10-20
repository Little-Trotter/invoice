package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.invoice.ReceiptUploadMode
import java.time.LocalDate

/** Input port for uploading a PDF into the receipt pool (deduplicated by checksum). */
interface UploadReceiptUseCase : CommandEntryPoint {
    fun uploadReceipt(
        fileName: String?,
        description: String?,
        pdfData: ByteArray,
        documentDate: LocalDate?,
        mode: ReceiptUploadMode = ReceiptUploadMode.REDUCE,
    ): ReceiptView
}
