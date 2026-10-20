package de.littletrotter.application.invoice

import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [InvoiceReceiptQueryPort] on top of [ReceiptLinkService]'s,
 *  [ReceiptBudgetService]'s and [ReceiptPoolService]'s existing reads, mapping the [Receipt]
 *  entities they return to [ReceiptRowDto]. */
@ApplicationScoped
class InvoiceReceiptQueries @Inject constructor(
    private val receiptLinkService: ReceiptLinkService,
    private val receiptBudgetService: ReceiptBudgetService,
    private val receiptPoolService: ReceiptPoolService,
) : InvoiceReceiptQueryPort {

    override fun findReceiptsForInvoice(invoiceId: Long): List<ReceiptRowDto> =
        receiptLinkService.findReceiptsForInvoice(invoiceId).map { it.toRowDto() }

    override fun findItemReceiptsForInvoice(invoiceId: Long): Map<Long, List<ReceiptRowDto>> =
        receiptLinkService.findItemReceiptsForInvoice(invoiceId).mapValues { (_, receipts) -> receipts.map { it.toRowDto() } }

    override fun findReceiptsForItem(invoiceItemId: Long): List<ReceiptRowDto> =
        receiptLinkService.findReceiptsForItem(invoiceItemId).map { it.toRowDto() }

    override fun countReceiptsForItem(invoiceItemId: Long): Long = receiptLinkService.countReceiptsForItem(invoiceItemId)

    override fun invoiceDocumentBudget(invoiceId: Long): InvoiceDocumentBudget = receiptBudgetService.invoiceDocumentBudget(invoiceId)

    override fun getBytes(receiptId: Long): ByteArray = receiptPoolService.getBytes(receiptId)

    override fun findAssignableForItem(
        invoiceItemId: Long?,
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        excludeReceiptIds: Set<Long>,
    ): List<ReceiptRowDto> =
        receiptLinkService.findAssignableForItem(invoiceItemId, pageIndex, pageSize, filterText, excludeReceiptIds).map { it.toRowDto() }

    override fun countAssignableForItem(invoiceItemId: Long?, filterText: String?, excludeReceiptIds: Set<Long>): Long =
        receiptLinkService.countAssignableForItem(invoiceItemId, filterText, excludeReceiptIds)

    override fun findAssignableForInvoice(invoiceId: Long?, pageIndex: Int, pageSize: Int, filterText: String?): List<ReceiptRowDto> =
        receiptLinkService.findAssignableForInvoice(invoiceId, pageIndex, pageSize, filterText).map { it.toRowDto() }

    override fun countAssignableForInvoice(invoiceId: Long?, filterText: String?): Long =
        receiptLinkService.countAssignableForInvoice(invoiceId, filterText)

    override fun findUsageSummaries(receiptIds: List<Long>, unusedText: String, invoiceLevelLabel: String): Map<Long, String> =
        receiptPoolService.findUsageSummaries(receiptIds, unusedText, invoiceLevelLabel)
}

/** Projects the persisted entity's fields the UI actually reads onto [ReceiptRowDto]. Internal
 *  (not private): shared by the receipt write and read paths that hand a [Receipt] back to the UI,
 *  so none of them can map it differently. */
internal fun Receipt.toRowDto(): ReceiptRowDto = ReceiptRowDto(
    id = requireNotNull(id) { "Receipt id is missing" },
    fileName = fileName,
    description = description,
    documentDate = documentDate,
    sizeBytes = sizeBytes,
    uploadedAt = uploadedAt,
    objectKey = objectKey,
    normalisationStage = normalisationStage,
    originalSizeBytes = originalSizeBytes,
)
