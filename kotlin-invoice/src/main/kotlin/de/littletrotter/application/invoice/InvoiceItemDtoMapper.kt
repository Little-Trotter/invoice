package de.littletrotter.application.invoice

import de.littletrotter.application.masterdata.ArticleManagementService
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.shared.wording.WordingProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal

/**
 * [InvoiceDtoMapper]'s per-position mapping (`InvoiceItem` -> [InvoiceItemDto]), extracted
 * verbatim; delegates `TRIP` detail to [TripExportDtoMapper] and free B2B values to
 * [B2bWireValueMapper].
 */
@ApplicationScoped
class InvoiceItemDtoMapper
@Inject
constructor(
    private val articleManagementService: ArticleManagementService?,
    private val b2bWireValueMapper: B2bWireValueMapper,
    private val tripExportDtoMapper: TripExportDtoMapper,
) {
    fun toDto(item: InvoiceItem, invoice: Invoice, poolReceiptsByItemId: Map<Long, List<ReceiptDto>>?, wordingProfile: WordingProfile): InvoiceItemDto {
        // Frozen snapshot once issued, the referenced rate while still a draft (preview rendering).
        val effectiveRate = try {
            item.effectiveTaxRate()
        } catch (ex: InvoiceValidationException) {
            // AP-EXC-1: narrow catch, already rethrowing with the cause chain attached.
            throw InvoiceValidationException(
                ex.error,
                ex.args,
                "Invoice ${invoice.invoiceNumber.value}, position ${item.positionId}: ${ex.message}",
                cause = ex,
            )
        }
        // belege-improve A4 / R10 — prefer the pool-derived data: the entity's own collection may be
        // id-only stubs or an unfetched lazy proxy.
        val receiptDtos = when {
            item.id != null && poolReceiptsByItemId != null -> poolReceiptsByItemId[item.id] ?: emptyList()
            else -> try {
                item.receiptList.map { r -> r.toJobReceiptDto() }
            } catch (_: Exception) {
                // Receipts may be an unfetched lazy collection on a detached entity; the export can
                // proceed without them rather than failing the whole publish.
                emptyList()
            }
        }
        return InvoiceItemDto(
            positionId = item.positionId,
            description = item.description,
            quantity = item.quantity,
            unitPriceNet = item.unitPriceNet,
            unitCode = item.unitCode,
            // R3 — follows the document language, not the tax case: a Reverse-Charge invoice can be
            // German and an EU-B2C invoice can be English without any Reverse Charge at all.
            unitLabel = item.unit.invoiceLabel(
                english = invoice.documentLanguage == DocumentLanguage.EN,
                plural = item.quantity.compareTo(BigDecimal.ONE) != 0,
            ),
            productClassificationScheme = item.productClassificationScheme,
            productClassificationCode = item.productClassificationCode,
            netAmount = item.netAmount,
            vatAmount = item.vatAmount,
            // Mirrors the already-persisted InvoiceItem.grossAmount (net + vat, rounded
            // there); the trip position's printed "Brutto: X" subline is its first reader.
            grossAmount = item.grossAmount,
            receipts = receiptDtos,
            taxRuleCode = item.taxRuleCode,
            taxCategory = item.taxCategory?.code,
            taxRatePercent = effectiveRate,
            exemptionReasonCode = item.exemptionReasonCode,
            exemptionReasonText = item.exemptionReasonText,
            taxLegalBasis = item.taxLegalBasis,
            // Every other category could carry a cost centre; the trip workbook's own
            // "Kostenstelle" field is the first real reader.
            costCenter = item.costCenter,
            itemDescription = item.itemDescription?.trim()?.takeIf { it.isNotBlank() },
            servicePeriodFrom = item.servicePeriodFrom?.toString(),
            servicePeriodTo = item.servicePeriodTo?.toString(),
            b2bValues = b2bWireValueMapper.valuesFor(B2bFieldOwnerType.INVOICE_ITEM, item.id),
            // HARDEN — [InvoiceDtoMapper.mapToDto]'s own id-based reload guarantees a live session
            // for the whole call, so this direct read is safe the same way any other plain column on
            // this item already was (InvoiceDtoMapperDetachedIT/InvoiceDtoMapperArticleNumberLazyIT).
            articleNumber = item.articleRef?.articleNumber?.value,
            buyerArticleDescription = buyerArticleDescriptionFor(item, invoice),
            buyerItemDescription = item.buyerItemDescription?.trim()?.takeIf { it.isNotBlank() },
            category = item.category,
            trip = if (item.category == InvoiceItemCategory.TRIP) {
                tripExportDtoMapper.buildTripExportDto(item, invoice, receiptDtos.size, wordingProfile)
            } else {
                null
            },
        )
    }

    /**
     * Resolves [InvoiceItemDto.buyerArticleDescription]: the recipient-specific
     * [de.littletrotter.domain.masterdata.ArticleRecipientRef.buyerArticleName] for this position's
     * article and the invoice's current recipient, looked up fresh at mapping time (never frozen
     * onto the position) — `null` when no article, recipient, service, or mapping exists.
     */
    private fun buyerArticleDescriptionFor(item: InvoiceItem, invoice: Invoice): String? {
        val svc = articleManagementService ?: return null
        val articleId = item.articleRef?.id ?: return null
        val recipientId = invoice.recipient?.id ?: return null
        return svc.findRecipientRef(articleId, recipientId)?.buyerArticleName
    }
}
