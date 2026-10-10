package de.littletrotter.xpdf.impl.mustang

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ShipToDto
import de.littletrotter.shared.TripExportDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.shared.resolveActiveSellerVatId
import de.littletrotter.xpdf.impl.Labels
import de.littletrotter.xpdf.impl.resolveDocumentRenderLanguage
import java.text.NumberFormat
import java.util.Locale

/**
 * Maps [de.littletrotter.shared.InvoiceDto] -> [MustangExportModel]. The only
 * class that knows both `InvoiceDto`'s shape and the export model's shape; no Mustang import.
 * Behaviour is a 1:1 extraction of what `XmlExportServiceImpl.buildTransaction` used to compute
 * inline before this split — see [MustangTransactionBuilder] for the second stage.
 */
object MustangExportModelMapper {

    private fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

    /** See [toModel]'s KDoc for why this reads only `meta.profileId`, never the Leitweg fallback. */
    private fun defaultExportProfile(invoice: InvoiceDto): String =
        nonBlank(invoice.meta.profileId) ?: "EN16931"

    /**
     * The captured value of one FREE catalog term, or `null` when the profile
     * doesn't carry it. The generic `term -> value` list is resolved into named model fields
     * *here*, so that everything downstream of this mapper is typed; see the class KDoc of
     * [MustangExportModel] for why the term's `xpath` is not walked instead.
     *
     * The first entry wins: `b2b_field_value` holds at most one row per (owner, term), so a second
     * one would be a data defect, and picking it over the first would only hide it.
     */
    private fun b2bValue(values: List<B2bValueDto>, term: B2bTerm): String? =
        nonBlank(values.firstOrNull { it.term == term }?.value)

    /**
     * BG-24 — every receipt this invoice references, from the invoice-level list
     * (`InvoiceDto.receipts`) and every item's own list (`InvoiceItemDto.receipts`), deduplicated
     * into **one** [MustangReceiptRefModel] per distinct receipt: the same receipt commonly hangs
     * on both an item and the invoice as a whole (`invoice_receipt`'s table comment), and BG-24
     * admits it once, not once per place it happens to be attached. Deduplicates by `objectKey`
     * when present (the receipt's real identity in the document store); a receipt with no
     * `objectKey` falls back to its file name, which is the best identity available for it. First
     * occurrence wins, invoice-level before item-level, so the resulting order is stable.
     */
    private fun collectReceipts(invoice: InvoiceDto): List<MustangReceiptRefModel> {
        val allRefs = invoice.receipts + invoice.items.flatMap { it.receipts }
        val seen = HashSet<String>()
        val result = mutableListOf<MustangReceiptRefModel>()
        for ((rawFileName, rawObjectKey) in allRefs) {
            val fileName = nonBlank(rawFileName) ?: continue
            val objectKey = nonBlank(rawObjectKey)
            val dedupeKey = objectKey ?: fileName
            if (seen.add(dedupeKey)) {
                result.add(MustangReceiptRefModel(fileName = fileName, objectKey = objectKey))
            }
        }
        return result
    }

    /**
     * The resolved accounting-reference (cost center) value for [invoice]:
     * the captured BT-19 FREE catalog value wins over the older, app-level
     * `Invoice.references.costCenter` field when both are present. Shared by [toModel] (fanned out
     * to every line as BT-133) and [de.littletrotter.xpdf.impl.XmlExportServiceImpl]'s own
     * header-level BT-19 post-processing injection (see [MustangExportModel]'s class KDoc for why
     * Mustang itself still cannot write the header element) - a single source of truth for "what
     * is BT-19's effective value", so the two seams can never resolve it differently.
     */
    fun resolveAccountingReference(invoice: InvoiceDto): String? =
        b2bValue(invoice.b2bValues, B2bTerm.BT_19) ?: nonBlank(invoice.references.costCenter)

    /**
     * [exportProfile] defaults to the same profile-id-first read
     * `XmlExportServiceImpl.resolveProfile` starts from (never the XRechnung/EN16931
     * Leitweg fallback: that distinction is irrelevant here, only "is this EXTENDED" is, and a
     * legacy message with no `profileId` is never EXTENDED). Production call sites
     * (`XmlExportServiceImpl.buildTransaction`) always pass the profile they already resolved, so
     * the export decision has one source of truth; the default only keeps the many existing
     * single-argument test call sites compiling and behaviourally unchanged.
     */
    fun toModel(invoice: InvoiceDto, exportProfile: String = defaultExportProfile(invoice)): MustangExportModel {
        // Resolved once here (see resolveAccountingReference) and fanned out
        // identically to every line below (BT-133) - see the class KDoc of MustangExportModel for
        // why the header element itself needed a separate seam (see XmlExportServiceImpl).
        val accountingReference = resolveAccountingReference(invoice)
        // BT-127/BT-20 — resolved once here (not just at the bottom of this model) so the BT-127 trip
        // note built per line below follows the exact same document-language rule as BT-20.
        val documentLanguage = resolveDocumentRenderLanguage(invoice.meta.documentLanguage)

        return MustangExportModel(
            number = invoice.invoiceNumber.value.ifBlank { "UNBEKANNT" },
            buyerReference = nonBlank(invoice.references.buyerReference),
            // BT-13 — its own wire field since the BT-10/BT-13 split. Never falls back to
            // `buyerReference`: that value is BT-10 (a Leitweg-ID for a public buyer), and offering it
            // as a purchase order number is exactly the defect the split removes.
            purchaseOrderReference = nonBlank(invoice.references.purchaseOrderReference),
            orderReference = nonBlank(invoice.references.orderReference),
            orderReferenceDate = nonBlank(invoice.references.orderReferenceDate),
            invoiceDate = invoice.dates.invoiceDate,
            // BT-72 — the item's own delivery date wins; without it the fallback chain (BT-74,
            // then the invoice date in the builder) applies, so a message without `deliveryDate`
            // exports unchanged.
            deliveryDate = nonBlank(invoice.dates.deliveryDate) ?: nonBlank(invoice.dates.servicePeriodTo),
            billingPeriodFrom = nonBlank(invoice.dates.servicePeriodFrom),
            billingPeriodTo = nonBlank(invoice.dates.servicePeriodTo),
            // BT-3 — a missing type code reads as an ordinary invoice (default 380), the
            // behavior before this field existed.
            documentTypeCode = nonBlank(invoice.typeCode) ?: "380",
            precedingInvoiceNumber = nonBlank(invoice.references.originalInvoiceNumber),
            precedingInvoiceDate = nonBlank(invoice.references.originalInvoiceDate),
            // invoice.totals.currency is now a typed, always-valid Currency (was a raw
            // Int? ordinal); the entries.getOrNull/EUR fallback moved to CurrencyOrdinalDeserializer
            // (kotlin-shared), the one place a wire payload's currency is actually unvalidated.
            currencyCode = invoice.totals.currency.code,
            // BT-31: the seller's foreign VAT registration (if active for
            // this invoice) replaces the German home vatId here, never for the recipient below.
            // See SellerVatIdResolution.kt's class KDoc for the full reasoning.
            sender = invoice.senderProfile?.let {
                toParty(it, includeContact = true, vatIdOverride = resolveActiveSellerVatId(it, invoice.meta))
            },
            // BG-8 (BT-56/BT-58) — the buyer's contact point is a HEADER term in the catalog but
            // sits on the BuyerTradeParty in the document; it is therefore read from the header
            // list and attached to the recipient, not derived from the recipient master data.
            recipient = invoice.recipient?.let {
                toParty(
                    it,
                    includeContact = false,
                    contactPointName = b2bValue(invoice.b2bValues, B2bTerm.BT_56),
                    contactPointEmail = b2bValue(invoice.b2bValues, B2bTerm.BT_58),
                )
            },
            items = invoice.items.map { toItemModel(it, accountingReference, documentLanguage) },
            paymentTermsDays = invoice.dates.paymentTermsDays,
            dueDate = invoice.dates.dueDate,
            // (PO decisions of 06.09.2026) — already resolved (snapshot-aware,
            // language-resolved) upstream in InvoiceDtoMapper; passed through unchanged so
            // BT-20 and the printed line read the exact same string.
            paymentTermName = nonBlank(invoice.dates.paymentTermName),
            // BR-DE-18: the domain already decided whether a discount applies (null on storno,
            // non-positive gross, or a term without one) - passed through unchanged, the builder
            // only formats it.
            cashDiscountDays = invoice.cashDiscount?.days,
            cashDiscountPercent = invoice.cashDiscount?.percent,
            exportProfile = exportProfile,
            senderIban = nonBlank(invoice.senderProfile?.iban),
            // The header's FREE terms. BT-19 itself is deliberately unused
            // here: Mustang 2.25.0 offers no header setter for it (see MustangExportModel's KDoc).
            // Its value still reaches the document — as BT-133 on every line, resolved in
            // `accountingReference` above.
            contractReference = b2bValue(invoice.b2bValues, B2bTerm.BT_12),
            invoicedObjectIdentifier = b2bValue(invoice.b2bValues, B2bTerm.BT_18),
            // BG-13. `invoice.shipTo` is already `null` unless kotlin-invoice determined a
            // delivery address that differs in substance from the billing address; no further
            // fallback/comparison logic belongs here.
            shipTo = invoice.shipTo?.let(::toShipToModel),
            // BT-22, one header note built from `Invoice.freeText`; blank stays absent.
            headerNote = nonBlank(invoice.meta.freeText?.let(FreeTextMarkup::toPlain)),
            consumerNote = nonBlank(invoice.meta.consumerNote),
            // BT-11, BOUND to `references.projectNumber` (no b2bValues lookup: a BOUND
            // term never gets a b2b_field_value row).
            projectReference = nonBlank(invoice.references.projectNumber),
            // BG-24 — the deduplicated, document-level receipt list; see [collectReceipts].
            receipts = collectReceipts(invoice),
            // BG-24, one entry per TRIP item whose workbook was actually resolved by
            // JobConsumer.resolveTripWorkbookRefs; see [buildTripWorkbookAttachments].
            tripWorkbookAttachments = buildTripWorkbookAttachments(invoice),
            // BT-20 (getPaymentTermDescription) must follow the same frozen document
            // language the PDF already does; one normalisation rule, not two.
            documentLanguage = documentLanguage,
        )
    }

    /** One line item, extracted out of [toModel] to keep that method short. */
    private fun toItemModel(
        item: InvoiceItemDto,
        accountingReference: String?,
        documentLanguage: String,
    ): MustangItemModel {
        // Wire-format sign normalization, see the KDoc on MustangItemModel.unitPriceNet.
        // The domain keeps the rebate/reduction sign on unitPriceNet (InvoiceItem.validate's B1
        // comment); EN 16931 BR-27/BR-28 and the Nekar policy both require BT-146 (unit price)
        // non-negative and put the sign on BT-129 (quantity) instead. Flipping both factors leaves
        // the line net amount (quantity * unitPriceNet) unchanged.
        val rawUnitPrice = item.unitPriceNet.amount
        val negativePrice = rawUnitPrice.signum() < 0
        val exportUnitPrice = if (negativePrice) rawUnitPrice.negate() else rawUnitPrice
        val exportQuantity = if (negativePrice) item.quantity.negate() else item.quantity
        return MustangItemModel(
            description = item.description,
            // BT-154 — empty instead of null: Mustang's writer skips the element for ""
            // and would write an element full of whitespace for "   " (PEPPOL-EN16931-R008).
            itemDescription = nonBlank(item.itemDescription) ?: "",
            servicePeriodFrom = nonBlank(item.servicePeriodFrom),
            servicePeriodTo = nonBlank(item.servicePeriodTo),
            unitCode = item.unitCode?.ifBlank { null } ?: "C62",
            taxRatePercent = item.taxRatePercent,
            unitPriceNet = exportUnitPrice,
            quantity = exportQuantity,
            taxCategoryCode = item.taxCategory,
            exemptionReasonCode = item.exemptionReasonCode,
            exemptionReasonText = item.exemptionReasonText,
            productClassificationScheme = item.productClassificationScheme?.ifBlank { null } ?: "ETIM",
            productClassificationCode = item.productClassificationCode?.takeIf { it.isNotBlank() },
            sellerItemIdentifier = b2bValue(item.b2bValues, B2bTerm.BT_155),
            buyerItemIdentifier = b2bValue(item.b2bValues, B2bTerm.BT_156),
            // BT-133, the same resolved cost center on every line.
            accountingReference = accountingReference,
            // BT-127, null for every non-TRIP item and for a TRIP item with
            // nothing to summarise (buildTripLineNote's own border-case-11 guard).
            lineNote = item.trip?.let { buildTripLineNote(it, documentLanguage) },
        )
    }

    private fun toShipToModel(shipTo: ShipToDto): MustangShipToModel = MustangShipToModel(
        name = nonBlank(shipTo.name),
        street = nonBlank(shipTo.street),
        postalCode = nonBlank(shipTo.postalCode),
        city = nonBlank(shipTo.city),
        country = nonBlank(shipTo.country) ?: "DE",
    )

    /** See [MustangExportModel.tripWorkbookAttachments]'s own KDoc. */
    private fun buildTripWorkbookAttachments(invoice: InvoiceDto): List<MustangWorkbookAttachmentModel> =
        invoice.items.mapNotNull { item ->
            val trip = item.trip ?: return@mapNotNull null
            val ref = trip.workbookRef ?: return@mapNotNull null
            val base64 = trip.resolvedWorkbookBase64 ?: return@mapNotNull null
            MustangWorkbookAttachmentModel(
                fileName = ref.fileName,
                mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                base64Content = base64,
            )
        }

    private const val TRIP_NOTE_MAX_LENGTH = 1000
    private const val TRIP_NOTE_TRUNCATION_SUFFIX = "… siehe Anlage"

    /**
     * BT-127, built only for a `detailedOnInvoice` TRIP item with something to summarise
     * (README border case 11: neither expenses nor per-diem days -> `null`, same "no note" outcome
     * every other item already has). Truncated at [TRIP_NOTE_MAX_LENGTH] characters with
     * [TRIP_NOTE_TRUNCATION_SUFFIX] (README border case 7, 100 expenses) — the workbook attachment
     * (BG-24) carries the full detail regardless of where this note gets cut.
     */
    private fun buildTripLineNote(trip: TripExportDto, language: String): String? {
        if (!trip.detailedOnInvoice) return null
        if (trip.expenses.isEmpty() && trip.perDiemDays.isEmpty()) return null
        val locale = if (language == "en") Locale.UK else Locale.GERMANY
        val quantityFormat = NumberFormat.getNumberInstance(locale)
        val moneyFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val expenseParts = trip.expenses.map { expense ->
            val kind = Labels.label(expense.kindLabelKey, language)
            val description = expense.description.trim()
            val label = if (description.isBlank()) kind else "$kind: $description"
            val qty = quantityFormat.format(expense.quantity.stripTrailingZeros())
            val quantityPart = expense.unitLabel?.takeIf { it.isNotBlank() }?.let { "$qty $it" } ?: qty
            "$label, $quantityPart, ${moneyFormat.format(expense.netAmount.amount)} €"
        }
        val perDiemPart = if (trip.perDiemDays.isEmpty()) {
            null
        } else {
            val total = trip.perDiemDays.fold(Money.ZERO) { acc, day -> acc + day.claimableAmount }
            "${Labels.label("trip.perDiem", language)}, ${trip.perDiemDays.size} ${Labels.label("trip.days", language)}, " +
                "${moneyFormat.format(total.amount)} €"
        }
        val note = (expenseParts + listOfNotNull(perDiemPart)).joinToString("; ")
        return if (note.length > TRIP_NOTE_MAX_LENGTH) {
            note.take(TRIP_NOTE_MAX_LENGTH - TRIP_NOTE_TRUNCATION_SUFFIX.length) + TRIP_NOTE_TRUNCATION_SUFFIX
        } else {
            note
        }
    }

    private fun toParty(
        p: PartyDto,
        includeContact: Boolean,
        contactPointName: String? = null,
        contactPointEmail: String? = null,
        // BT-31 override, seller-only. `null` (every recipient call site,
        // and a sender with no active foreign registration) keeps today's behavior: p.vatId as-is.
        vatIdOverride: String? = null,
    ): MustangPartyModel {
        val identifier = p.identifier
        val legalRegistration = p.legalRegistration
        val electronicAddress = p.electronicAddress
        return MustangPartyModel(
            name = nonBlank(p.name),
            street = nonBlank(p.street),
            postalCode = nonBlank(p.postalCode),
            city = nonBlank(p.city),
            country = nonBlank(p.country) ?: "DE",
            vatId = vatIdOverride ?: nonBlank(p.vatId),
            taxNumber = nonBlank(p.taxNumber),
            // BR-CL-10/ISO 6523 ICD: the legacy bare globalId field has no scheme of its own, so
            // a GS1 GLN is implied — its ICD code is "0088", not the literal string "GLN".
            globalIdSchemeId = identifier?.schemeId ?: (nonBlank(p.globalId)?.let { "0088" }),
            globalIdValue = identifier?.value ?: nonBlank(p.globalId),
            legalRegistrationSchemeId = legalRegistration?.schemeId,
            legalRegistrationValue = legalRegistration?.value,
            electronicAddressSchemeId = electronicAddress?.schemeId,
            electronicAddressValue = electronicAddress?.value,
            email = nonBlank(p.email),
            phone = nonBlank(p.phone),
            includeContact = includeContact,
            contactPointName = contactPointName,
            contactPointEmail = contactPointEmail,
        )
    }
}
