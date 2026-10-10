package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.requiresExtendedProfile
import de.littletrotter.domain.invoice.resolveExportProfileId
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.wording.WordingProfile

/**
 * [InvoiceDtoMapper]'s header-level sub-DTOs (dates, references, totals, meta), extracted
 * verbatim. Pure value mapping, no collaborators — a plain object rather than a CDI bean.
 */
object InvoiceHeaderDtoMapper {
    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    /** PO decisions 06.09.2026 — the payment term's own resolved name, snapshot-aware
     *  (W7 rule: the frozen snapshot wins over the live association for an already-SENT invoice). */
    fun buildDates(inv: Invoice): InvoiceDatesDto {
        val paymentTermName = inv.paymentTermSnapshot?.name ?: inv.paymentTerm?.nameFor(inv.documentLanguage)
        return InvoiceDatesDto(
            // date invariants (PO 2026-09-02) — inv.invoiceDate is mandatory, a plain conversion.
            invoiceDate = inv.invoiceDate.toString(),
            servicePeriodFrom = inv.servicePeriodFrom?.toString(),
            servicePeriodTo = inv.servicePeriodTo?.toString(),
            deliveryDate = inv.deliveryDate?.toString(),
            dueDate = inv.dueDate?.toString(),
            paymentTermsDays = inv.paymentTermsDays,
            paymentTermName = paymentTermName,
        )
    }

    fun buildReferences(inv: Invoice): InvoiceReferencesDto =
        InvoiceReferencesDto(
            // BT-10 — derived on the read path, not just once at creation: the print/export job runs
            // for any invoice status, so the SENT-only gate in InvoiceLifecycleService never sees
            // this path. Falls back to the recipient's Leitweg-ID (BT-10 for public buyers) — never
            // an invented reference, never persisted here.
            buyerReference = inv.references.buyerReference.blankToNull() ?: inv.recipient?.routingId.blankToNull(),
            // BT-13 — deliberately without the routingId fallback above: a Leitweg-ID is BT-10, not
            // a purchase order number, and BT-13 is optional in EN 16931.
            purchaseOrderReference = inv.references.purchaseOrderReference.blankToNull(),
            orderReference = inv.references.orderReference,
            orderReferenceDate = inv.references.orderReferenceDate?.toString(),
            offerReference = inv.references.offerReference,
            offerDate = inv.references.offerReferenceDate?.toString(),
            // BT-11 reaches the wire so kotlin-xpdf can export
            // ram:SpecifiedProcuringProject/ram:ID.
            projectNumber = inv.references.projectNumber.blankToNull(),
            // costCenter is deliberately not mapped here — see InvoiceReferencesDto's own KDoc: only
            // the frontend decides whether it is collected via a field-module.
            originalInvoiceNumber = inv.originalInvoiceNumber.blankToNull(),
            originalInvoiceDate = inv.originalInvoiceDate?.toString(),
        )

    fun buildTotals(inv: Invoice): InvoiceTotalsDto =
        InvoiceTotalsDto(
            currency = inv.currency,
            netAmount = inv.totals.netAmount,
            vatAmount = inv.totals.vatAmount,
            grossAmount = inv.totals.grossAmount,
            // Always derived from the item categories, never the freely settable legacy
            // field: a stale/wrong inv.reverseCharge must not leak onto the wire.
            reverseCharge = inv.hasReverseChargeItems(),
        )

    /** Bundles [buildMeta]'s two independent settings-derived inputs — the active [wordingProfile]
     *  and the admin's [extendedProfileEnabled] opt-in (PO decision 2026-09-28) — purely so the
     *  function itself stays under detekt's `LongParameterList` threshold; neither field depends
     *  on the other. */
    data class MetaSettings(val wordingProfile: WordingProfile, val extendedProfileEnabled: Boolean)

    fun buildMeta(inv: Invoice, sender: SenderProfile?, recipient: Recipient?, settings: MetaSettings): InvoiceMetaDto =
        InvoiceMetaDto(
            languageId = inv.languageId,
            // R3 — the frozen document language, distinct from the languageId override above.
            documentLanguage = inv.documentLanguage,
            documentStatusReasonCode = inv.documentStatusReasonCode,
            freeText = inv.freeText,
            consumerNote = inv.consumerNote,
            // R8 — resolved once here so the finalization gate and the actual XML export agree.
            profileId = resolveExportProfileId(
                sender?.vatId?.value,
                recipient?.vatId?.value,
                inv.requiresExtendedProfile(settings.extendedProfileEnabled),
            ),
            // Populated here since no producer did before.
            sellerVatRegistrationCountry = inv.sellerVatRegistrationCountry?.alpha2,
            // The profile active at publish time; the trip workbook and the worker's print
            // labels both read it.
            wordingProfile = settings.wordingProfile.code,
            invoiceFormat = inv.invoiceFormat.name,
        )
}
