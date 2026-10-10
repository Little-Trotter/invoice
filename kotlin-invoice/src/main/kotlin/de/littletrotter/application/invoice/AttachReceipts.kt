package de.littletrotter.application.invoice

import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/** Implements [AttachReceiptsUseCase] on top of [ReceiptUploadService]'s and
 *  [ReceiptLinkService]'s existing writes. */
@ApplicationScoped
class AttachReceipts @Inject constructor(
    private val receiptUploadService: ReceiptUploadService,
    private val receiptLinkService: ReceiptLinkService,
) : AttachReceiptsUseCase {

    override fun upload(
        fileName: String?,
        description: String?,
        pdfData: ByteArray,
        documentDate: LocalDate?,
        mode: ReceiptUploadMode,
    ): ReceiptRowDto {
        val receipt = receiptUploadService.upload(fileName, description, pdfData, documentDate, mode)
        return ReceiptRowDto(
            id = requireNotNull(receipt.id) { "Receipt id is missing" },
            fileName = receipt.fileName,
            description = receipt.description,
            documentDate = receipt.documentDate,
            sizeBytes = receipt.sizeBytes,
            uploadedAt = receipt.uploadedAt,
            objectKey = receipt.objectKey,
            normalisationStage = receipt.normalisationStage,
            originalSizeBytes = receipt.originalSizeBytes,
        )
    }

    override fun addReceiptsToInvoice(invoiceId: Long, receiptIds: List<Long>, expectedVersion: Long?) =
        receiptLinkService.addReceiptsToInvoice(invoiceId, receiptIds, expectedVersion)

    override fun addReceiptsToItem(invoiceItemId: Long, receiptIds: List<Long>, expectedVersion: Long?) =
        receiptLinkService.addReceiptsToItem(invoiceItemId, receiptIds, expectedVersion)

    override fun detachReceipts(invoiceId: Long, selection: ReceiptDetachSelection, expectedVersion: Long?) =
        receiptLinkService.detachReceipts(invoiceId, selection, expectedVersion)

    override fun removeReceiptsFromItem(invoiceItemId: Long, receiptIds: List<Long>, expectedVersion: Long?) =
        receiptLinkService.removeReceiptsFromItem(invoiceItemId, receiptIds, expectedVersion)

    override fun removeReceiptsFromInvoice(invoiceId: Long, receiptIds: List<Long>, expectedVersion: Long?) =
        receiptLinkService.removeReceiptsFromInvoice(invoiceId, receiptIds, expectedVersion)
}
