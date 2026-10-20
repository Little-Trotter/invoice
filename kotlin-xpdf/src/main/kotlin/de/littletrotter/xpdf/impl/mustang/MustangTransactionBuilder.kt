package de.littletrotter.xpdf.impl.mustang

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.documents.DocumentDates
import de.littletrotter.shared.documents.DocumentTexts
import de.littletrotter.shared.receipt.readReceipt
import de.littletrotter.xpdf.impl.TaxExemptionClassification
import de.littletrotter.xpdf.impl.composeTaxNote
import org.mustangproject.XMLTools
import org.mustangproject.ClassCode
import org.mustangproject.Contact
import org.mustangproject.DesignatedProductClassification
import org.mustangproject.FileAttachment
import org.mustangproject.Item
import org.mustangproject.LegalOrganisation
import org.mustangproject.Product
import org.mustangproject.ReferencedDocument
import org.mustangproject.SchemedID
import org.mustangproject.TradeParty
import org.mustangproject.ZUGFeRD.IExportableTransaction
import org.mustangproject.ZUGFeRD.IReferencedDocument
import org.mustangproject.ZUGFeRD.IZUGFeRDExportableItem
import org.mustangproject.ZUGFeRD.IZUGFeRDExportableTradeParty
import org.mustangproject.ZUGFeRD.IZUGFeRDPaymentDiscountTerms
import org.mustangproject.ZUGFeRD.IZUGFeRDPaymentTerms
import org.mustangproject.ZUGFeRD.IZUGFeRDTradeSettlement
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Date as SqlDate
import java.time.LocalDate
import java.util.Base64
import java.util.Date

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * BR-DE-18's machine-readable Skonto line, appended to BT-20's free text. The KoSIT scenario
 * (`KositValidationIT`'s `BR-DE-18 -` probes) accepts the percentage only with exactly two
 * decimals and a dot, and the line must start and end with `#`, terminated by its own line
 * break - no `BASISBETRAG` here, the rule's basis defaults to the amount due, which already is
 * the gross amount this discount was computed from.
 */
internal fun skontoLine(days: Int, percent: BigDecimal): String =
    "#SKONTO#TAGE=$days#PROZENT=${percent.setScale(2, RoundingMode.HALF_UP).toPlainString()}#\n"

/**
 * The EXTENDED-profile structured Skonto element's payload — a small named type (rather than an
 * anonymous object inline in [MustangTransactionBuilder.buildDiscountTerms]) so the
 * percent-rounding and base-date decisions below are reachable from a unit test without building
 * a whole transaction first.
 *
 * [getBaseDate] is always `null`: our invoices always carry a due date, and Mustang's writer
 * (`ZUGFeRD2PullProvider.writePaymentTerms`) throws `IllegalStateException` when both a payment
 * term's due date and its discount terms' base date are set. [getBasePeriodMeasure] and
 * [getBasePeriodUnitCode] exist only because `IZUGFeRDPaymentDiscountTerms` demands them; Mustang's
 * writer only emits `BasisPeriodMeasure` alongside a non-null base date, so with [getBaseDate]
 * fixed to `null` these two values never reach the wire. `BasisAmount` (the invoice's gross total)
 * is likewise not this class's concern — Mustang's writer computes it itself from the
 * transaction's own totals, the same amount BR-DE-18's basis (`skontoLine`) is already computed
 * from.
 */
private class MustangPaymentDiscountTerms(
    private val days: Int,
    private val percent: BigDecimal,
) : IZUGFeRDPaymentDiscountTerms {
    override fun getCalculationPercentage(): BigDecimal = percent.setScale(2, RoundingMode.HALF_UP)

    override fun getBaseDate(): Date? = null

    override fun getBasePeriodMeasure(): Int = days

    override fun getBasePeriodUnitCode(): String = "DAY"
}

/**
 * BG-24's one, MIME-aware attachment loop: [MustangTransactionBuilder.build]'s
 * `getAdditionalReferencedDocuments()` turns exactly one list of these into Mustang's
 * `FileAttachment` array, whether the bytes come from an S3-stored receipt (resolved lazily via
 * [de.littletrotter.shared.DocumentStore], the original shape) or an already-resolved trip
 * workbook (base64-decoded eagerly). [resolve] is a function rather than a plain `ByteArray`
 * so a receipt's S3 round trip only ever happens for an attachment actually being emitted, exactly
 * like the original code already did — the trip workbook added a second attachment *source*, never
 * a second attachment *loop*.
 */
private class MustangAttachmentSpec(
    val fileName: String,
    val mimeType: String,
    /** `Mustang`'s `FileAttachment.description` (the BG-24 `Name`) — `null` keeps the library's
     *  own default ("Additional file attachment"), unchanged for a receipt. */
    val description: String? = null,
    val resolve: () -> ByteArray,
)

/**
 * One spec per receipt (a receipt that cannot be located or read fails the export instead of being
 * left out) plus one per already-resolved trip workbook. A `null` [documentStore] drops
 * every receipt spec (nothing to resolve them with) but still keeps the workbook specs — bytes
 * that never needed a document store to begin with.
 */
private fun buildAttachmentSpecs(model: MustangExportModel, documentStore: DocumentStore?): List<MustangAttachmentSpec> {
    val receiptSpecs = documentStore?.let { store ->
        model.receipts.map { r ->
            MustangAttachmentSpec(
                fileName = r.fileName,
                mimeType = "application/pdf",
                description = r.description?.takeIf { it.isNotBlank() } ?: r.fileName,
            ) { store.readReceipt(r.fileName, r.objectKey) }
        }
    } ?: emptyList()
    val workbookSpecs = model.tripWorkbookAttachments.map { wb ->
        MustangAttachmentSpec(fileName = wb.fileName, mimeType = wb.mimeType, description = wb.fileName) {
            Base64.getDecoder().decode(wb.base64Content)
        }
    }
    return receiptSpecs + workbookSpecs
}

/**
 * Maps [MustangExportModel] -> Mustang's [IExportableTransaction]. The only class
 * in kotlin-xpdf that imports Mustang's export-model types (`TradeParty`, `Item`, `Product`, …);
 * everything upstream of this (`XmlExportServiceImpl`, [MustangExportModelMapper]) works only
 * with [MustangExportModel]. A future Mustang version/library swap touches this file and the
 * `renderPdfWithZugferd` call in `PdfExportServiceImpl` (which only calls three primitive
 * `ZUGFeRDExporterFromA3` methods, not this builder), nothing else.
 *
 * Behaviour is a 1:1 port of what `XmlExportServiceImpl.buildTransaction` used to do inline —
 * see that class's git history for the former inline shape if a discrepancy needs tracing.
 */
class MustangTransactionBuilder(private val documentStore: DocumentStore?) {

    fun build(model: MustangExportModel): IExportableTransaction {
        val issueDateVal: Date = model.invoiceDate?.let { SqlDate.valueOf(it) } ?: Date()
        val deliveryDateVal: Date = model.deliveryDate?.let { SqlDate.valueOf(it) } ?: issueDateVal
        val contractReferenceDoc: ReferencedDocument? =
            model.contractReference?.let { ReferencedDocument(it) }
        val objectIdentifierDoc: ReferencedDocument? = createObjectIdentifierDoc(model.invoicedObjectIdentifier)

        return object : IExportableTransaction {
            override fun getNumber(): String = model.number
            override fun getReferenceNumber(): String? = model.buyerReference
            override fun getBuyerOrderReferencedDocument(): IReferencedDocument? =
                model.purchaseOrderReference?.let { ReferencedDocument(it) }
            override fun getSellerOrderReferencedDocument(): IReferencedDocument? =
                model.orderReference?.let { ReferencedDocument(it) }
            override fun getContractReferencedDocument(): IReferencedDocument? = contractReferenceDoc
            override fun getObjectIdentifierReferencedDocument(): IReferencedDocument? = objectIdentifierDoc
            override fun getIssueDate(): Date = issueDateVal
            override fun getDeliveryDate(): Date = deliveryDateVal
            // BT-3 — without this override Mustang's default "380" would export a credit note
            // (type code 381) as an ordinary invoice.
            override fun getDocumentCode(): String = model.documentTypeCode
            // BT-25/BT-26 (BG-3) — the credit note's reference to the cancelled invoice. BT-26 is
            // optional and only emitted when the date was actually captured (since V033 the cancellation
            // takes it over from the original); it is never guessed.
            override fun getInvoiceReferencedDocuments(): List<ReferencedDocument>? =
                model.precedingInvoiceNumber?.let { number ->
                    val ref = ReferencedDocument(number)
                    model.precedingInvoiceDate?.let { ref.setFormattedIssueDateTime(SqlDate.valueOf(it)) }
                    listOf(ref)
                }
            // BG-14 (BT-73/BT-74) — the header-level billing period. Mustang emits the group only
            // when at least one of the two dates is set, which structurally satisfies BR-CO-19.
            override fun getDetailedDeliveryPeriodFrom(): Date? =
                model.billingPeriodFrom?.let { SqlDate.valueOf(it) }
            override fun getDetailedDeliveryPeriodTo(): Date? =
                model.billingPeriodTo?.let { SqlDate.valueOf(it) }
            override fun getCurrency(): String = model.currencyCode
            override fun getSender(): IZUGFeRDExportableTradeParty? =
                model.sender?.let { buildParty(it) }
            override fun getRecipient(): IZUGFeRDExportableTradeParty? =
                model.recipient?.let { buildParty(it) }
            // BG-13. Mustang's own default returns
            // `null` here, which is exactly what an invoice without a ship-to must keep doing:
            // `ram:ShipToTradeParty` is then omitted (ZUGFeRD2PullProvider.java, zf2 profiles).
            override fun getDeliveryAddress(): IZUGFeRDExportableTradeParty? =
                model.shipTo?.let { buildShipToParty(it) }
            // BT-22, the header notes: the free text first, then the hint for private customers.
            // `getNotes()` returns `null` (not `emptyArray()`) when both are blank, matching
            // Mustang's own default and keeping this override a no-op on every invoice without
            // either text.
            override fun getNotes(): Array<String>? =
                listOfNotNull(model.headerNote, model.consumerNote).takeIf { it.isNotEmpty() }?.toTypedArray()
            // BT-11, Mustang's own header hook (`IExportableTransaction`, 2.25.0): a
            // native `getSpecifiedProcuringProjectID/Name()` pair exists, so no `enrichInvoiceXml`
            // fixup is needed. `ram:SpecifiedProcuringProject/ram:Name` is not optional in the CII
            // schema (`ProcuringProjectType`, unlike its sibling `ram:Description`, which
            // EN 16931/XRechnung's CII-SR-307 forbids outright) even though EN 16931 defines no
            // business term for it; a fixed label satisfies the schema without inventing a second,
            // unfounded business value. Both getters return null together, so the element is
            // omitted entirely when no project reference was captured.
            override fun getSpecifiedProcuringProjectID(): String? = model.projectReference
            override fun getSpecifiedProcuringProjectName(): String? =
                model.projectReference?.let { "Project" }
            // BG-24 (BT-122/BT-125) — every receipt this invoice references, document-level, one
            // `AdditionalReferencedDocument` per unique receipt (dedup already done by
            // [MustangExportModelMapper.collectReceipts]). This is the ONLY place receipts reach
            // the CII wire: earlier versions additionally wrote a filename reference (TypeCode 130
            // + ReferenceTypeCode "5") and a binary-carrying reference (TypeCode 916) at LINE level
            // (`SpecifiedLineTradeAgreement`), which a live KoSIT run disproved on three counts —
            // BR-CL-07 ("5" is not in the UNTDID 1153 restriction for TypeCode 130),
            // CII-SR-039/040 (more than one `IncludedNote` per line once ≥2 receipts hung off one
            // item), and fundamentally CII-SR-113 (XRechnung 3.x forbids ANY
            // `AdditionalReferencedDocument` inside `SpecifiedLineTradeAgreement`, so there never
            // was a conformant line-level shape to begin with). Moving to
            // `IExportableTransaction.getAdditionalReferencedDocuments(): FileAttachment[]`
            // (`ApplicableHeaderTradeAgreement`, BG-24) sidesteps all three: Mustang's
            // `ZUGFeRD2PullProvider` (2.25.0) writes this array through its own hardcoded template
            // — `IssuerAssignedID` from `FileAttachment.getFilename()` (BT-122, satisfying BR-52),
            // a fixed `TypeCode` of 916, `Name` from `getDescription()`, and the binary from
            // `getData()` — never through `ReferencedDocument.getAsCII()`. That matters because
            // `ReferencedDocument.getAsCII()` is where Mustang 2.25.0's `setName()` produces a
            // mismatched `<ram:name>…</ram:Name>` tag pair (confirmed by disassembling
            // ZUGFeRD2PullProvider.class: the `getAdditionalReferencedDocuments()` block never
            // calls `getAsCII()` and its own `<ram:Name>` template is correctly cased on both
            // ends) — this path is unaffected by that bug and needs no `setName`/`setDescription`
            // workaround; the file name reaches the wire via the constructor's `filename` argument
            // alone, which is also all BT-122 requires.
            //
            // The trip workbook joins the exact same array, via the exact same
            // FileAttachment template: [buildAttachmentSpecs] merges receipts and resolved
            // workbooks into one [MustangAttachmentSpec] list *before* this override ever runs, so
            // there is only ever one place that turns a spec into a FileAttachment.
            override fun getAdditionalReferencedDocuments(): Array<FileAttachment>? {
                val specs = buildAttachmentSpecs(model, documentStore)
                if (specs.isEmpty()) return null
                return specs.map { spec ->
                    FileAttachment(spec.fileName, spec.mimeType, spec.resolve()).also { fa ->
                        spec.description?.let { fa.setDescription(it) }
                    }
                }.toTypedArray()
            }
            override fun getZFItems(): Array<IZUGFeRDExportableItem> {
                if (model.items.isEmpty()) return emptyArray()
                return model.items.map { item -> buildItem(item, model.documentLanguage) }.toTypedArray()
            }
            // PO decision 2 (2026-09-02) — a cancellation carries no payment-terms text at all
            // (`paymentTermsDays == 0` AND `model.dueDate == null`, the exact shape
            // `InvoiceCancellationService` produces and only it produces: any ordinary invoice gets a
            // non-null `dueDate` derived at creation regardless of its day count, see
            // `InvoiceLifecycleService.create`). Returning null here matters beyond this override's
            // own callers: Mustang's writer (`ZUGFeRD2PullProvider.generateXML`, 2.25.0) reads
            // `trans.getPaymentTermDescription()` directly and unconditionally to seed its
            // `SpecifiedTradePaymentTerms` fallback path, independent of `getPaymentTerms()`.
            //
            // (PO decisions of 06.09.2026) — `model.paymentTermName` (the chosen payment
            // term's own reference-data name, already resolved for `model.documentLanguage`
            // upstream in `InvoiceDtoMapper`) BEGINS this text, verbatim, whenever the invoice has
            // a payment term: the same field `InvoiceReportContextBuilder.buildPaymentDueText`
            // reads for the printed line, so BT-20 and the PDF start alike and can never disagree
            // on the term name. When the invoice carries a cash discount, BT-20 appends the
            // BR-DE-18 `#SKONTO#` line (`skontoLine` below) that the PDF's own Skonto sentence has
            // no counterpart for. A cancellation never carries a payment term, so
            // `model.paymentTermName` is always null for it and falls through unaffected. Only an
            // invoice with no payment term at all falls back to composing a sentence from the
            // `DocumentTexts` bundle (`documents/document-texts_{de,en}.properties`) — decision 3:
            // "net" keeps the PDF's pre-existing German wording (no "von"); the wire follows the
            // print image.
            //
            // Precedence fix — the fallback used to branch on
            // `model.paymentTermsDays` first, a `create()`-time-only default
            // (`InvoiceLifecycleService`'s own KDoc) never updated once a real payment term is
            // chosen: an invoice whose term wasn't the class default (14 days) got a stale "14"
            // here while `getPaymentTerms()` below and `buildPaymentDueText` (both due-date-first)
            // had it right. `dueDate` now wins here too, one precedence for every reader. That
            // reorder also fixes a second defect in the old branch order: `dueDate != null ->
            // "immediate"` claimed the invoice was payable immediately in exactly the case where a
            // due date exists — that shape now names the due date instead.
            //
            // No "immediate" branch survives the reorder. Once due date and positive days are both
            // ruled out, what's left (`paymentTermsDays == 0 && dueDate == null`) is, per the
            // `InvoiceLifecycleService.create` guarantee above, precisely the cancellation shape — never
            // reached by an ordinary invoice. Returning "payable immediately" there (as
            // `buildPaymentDueText`'s ladder does, which has no such shape to worry about because it
            // checks `invoice.cancellation` explicitly before composing anything) would put payment
            // wording back onto a document that must carry none; `null` here is load-bearing.
            override fun getPaymentTermDescription(): String? = resolvePaymentTermDescription(model)
            override fun getPaymentTerms(): IZUGFeRDPaymentTerms? = buildPaymentTerms(model)
            override fun getTradeSettlement(): Array<IZUGFeRDTradeSettlement> = buildTradeSettlement(model.senderIban)
        }
    }

    private fun createObjectIdentifierDoc(invoicedObjectIdentifier: String?): ReferencedDocument? =
        invoicedObjectIdentifier?.let { id ->
            ReferencedDocument(id).also {
                it.referenceTypeCode = "IL"
            }
        }

    private fun resolvePaymentTermDescription(model: MustangExportModel): String? {
        val description = model.paymentTermName ?: run {
            val dueDate = model.dueDate
            val days = model.paymentTermsDays
            when {
                dueDate != null ->
                    DocumentTexts.format(
                        "paymentTerms.dueBy",
                        model.documentLanguage,
                        DocumentDates.format(LocalDate.parse(dueDate), model.documentLanguage),
                    )
                days > 0 -> DocumentTexts.format("paymentTerms.net", model.documentLanguage, days)
                else -> null
            }
        }
        // Cancellation stays null (load-bearing, see getPaymentTermDescription's KDoc above); a
        // discount never resurrects text for a document that must carry none.
        val cashDiscountDays = model.cashDiscountDays
        val cashDiscountPercent = model.cashDiscountPercent
        return if (description != null && cashDiscountDays != null && cashDiscountPercent != null) {
            description + "\n" + skontoLine(cashDiscountDays, cashDiscountPercent)
        } else {
            description
        }
    }

    private fun buildPaymentTerms(model: MustangExportModel): IZUGFeRDPaymentTerms? {
        val dueDate = model.dueDate?.let { SqlDate.valueOf(it) }
            ?: model.invoiceDate?.takeIf { model.paymentTermsDays > 0 }?.let { SqlDate.valueOf(it) }?.let { date ->
                Date(date.time + model.paymentTermsDays * MILLIS_PER_DAY)
            }
        return if (dueDate != null || model.paymentTermsDays > 0) {
            object : IZUGFeRDPaymentTerms {
                // Mustang 2.25.0 gap: `buildPaymentTermsXml` concatenates this raw into
                // `<ram:Description>` (unlike the `getPaymentTermDescription()` fallback path,
                // which it encodes itself). Escaped here, once, before handover; not stripped.
                // Upstream issue: not yet filed with mustangproject.
                override fun getDescription(): String = XMLTools.encodeXML(resolvePaymentTermDescription(model)!!)
                override fun getDueDate(): Date? = dueDate
                override fun getDiscountTerms(): IZUGFeRDPaymentDiscountTerms? = buildDiscountTerms(model)
            }
        } else {
            null
        }
    }

    /**
     * The structured Skonto element (`ram:ApplicableTradePaymentDiscountTerms`), EXTENDED
     * profile only — EN16931/XRechnung forbid it, so BT-20 stays text-only (`skontoLine` above)
     * for those. Mustang's writer (`ZUGFeRD2PullProvider.writePaymentTerms`) emits the element
     * unconditionally whenever this returns non-null, with no profile check of its own; gating
     * EXTENDED is entirely this builder's job.
     */
    private fun buildDiscountTerms(model: MustangExportModel): IZUGFeRDPaymentDiscountTerms? {
        val days = model.cashDiscountDays
        val percent = model.cashDiscountPercent
        if (!model.exportProfile.equals("EXTENDED", ignoreCase = true) || days == null || percent == null) {
            return null
        }
        return MustangPaymentDiscountTerms(days, percent)
    }

    private fun buildTradeSettlement(iban: String?): Array<IZUGFeRDTradeSettlement> {
        return if (iban != null) {
            arrayOf(org.mustangproject.BankDetails(iban))
        } else {
            arrayOf(
                IZUGFeRDTradeSettlement { _ ->
                    "<ram:SpecifiedTradeSettlementPaymentMeans>" +
                        "<ram:TypeCode>42</ram:TypeCode>" +
                        "</ram:SpecifiedTradeSettlementPaymentMeans>"
                },
            )
        }
    }

    /**
     * The name/street/postalCode/city/country shape [MustangPartyModel] and [MustangShipToModel]
     * both carry, as [applyBasicAddress] needs it — grouped so it takes one parameter for the
     * address instead of five (LongParameterList).
     */
    private data class MustangBasicAddress(
        val name: String?,
        val street: String?,
        val postalCode: String?,
        val city: String?,
        val country: String,
    )

    /**
     * BT-address field mapping shared by [buildParty] and [buildShipToParty] — both a full party
     * and a bare ship-to party place name/street/postalCode/city/country onto a [TradeParty] the
     * same way; keeping this in one place means an address-mapping fix (e.g. a normalization
     * rule) can't be applied to one and forgotten on the other.
     */
    private fun applyBasicAddress(party: TradeParty, address: MustangBasicAddress) {
        address.name?.let { party.setName(it) }
        address.street?.let { party.setStreet(it) }
        address.postalCode?.let { party.setZIP(it) }
        address.city?.let { party.setLocation(it) }
        party.setCountry(address.country)
    }

    private fun buildParty(p: MustangPartyModel): TradeParty {
        val party = TradeParty()
        applyBasicAddress(party, MustangBasicAddress(p.name, p.street, p.postalCode, p.city, p.country))
        applyIdentifiers(party, p)
        applyCommunicationChannel(party, p)
        applyContact(party, p)
        return party
    }

    private fun applyIdentifiers(party: TradeParty, p: MustangPartyModel) {
        p.vatId?.let { party.addVATID(it) }
        p.taxNumber?.let { party.addTaxID(it) }

        if (p.globalIdSchemeId != null && p.globalIdValue != null) {
            party.addGlobalID(SchemedID(p.globalIdSchemeId, p.globalIdValue))
        }

        if (p.legalRegistrationSchemeId != null && p.legalRegistrationValue != null) {
            party.setLegalOrganisation(LegalOrganisation(SchemedID(p.legalRegistrationSchemeId, p.legalRegistrationValue), p.name))
        }
    }

    private fun applyCommunicationChannel(party: TradeParty, p: MustangPartyModel) {
        if (p.electronicAddressSchemeId != null && p.electronicAddressValue != null) {
            party.addUriUniversalCommunicationID(SchemedID(p.electronicAddressSchemeId, p.electronicAddressValue))
        } else {
            p.email?.let { party.setEmail(it) }
        }
    }

    private fun applyContact(party: TradeParty, p: MustangPartyModel) {
        if (p.includeContact) {
            val contactName = p.name
            val contactPhone = p.phone
            val contactEmail = p.email
            if (contactName != null || contactPhone != null || contactEmail != null) {
                val contact = Contact()
                contactName?.let { contact.setName(it) }
                contactPhone?.let { contact.setPhone(it) }
                contactEmail?.let { contact.setEMail(it) }
                party.setContact(contact)
            }
        } else if (p.contactPointName != null || p.contactPointEmail != null) {
            // BG-8 (BT-56/BT-58), the buyer's contact point. It is built
            // exclusively from captured B2B values: BG-8 names a contact person at the buyer, not
            // the buyer itself, so neither `name` nor `email` of the recipient flows in here.
            // Mustang emits the DefinedTradeContact on the BuyerTradeParty in the EN16931/
            // XRechnung/Extended profiles — exactly the ones this export uses.
            val contact = Contact()
            p.contactPointName?.let { contact.setName(it) }
            p.contactPointEmail?.let { contact.setEMail(it) }
            party.setContact(contact)
        }
    }

    /**
     * BG-13's `ram:ShipToTradeParty`. Deliberately a bare `TradeParty` with only
     * name/address set, not [buildParty]: the CII writer skips VAT id/tax id/contact/electronic
     * address for a ship-to party regardless (`ZUGFeRD2PullProvider.getTradePartyAsXML`,
     * `isShipToTradeParty=true`), and [MustangShipToModel] never carries them in the first place.
     */
    private fun buildShipToParty(s: MustangShipToModel): TradeParty {
        val party = TradeParty()
        applyBasicAddress(party, MustangBasicAddress(s.name, s.street, s.postalCode, s.city, s.country))
        return party
    }

    private fun buildItem(item: MustangItemModel, documentLanguage: String): IZUGFeRDExportableItem {
        val product = Product(item.description, item.itemDescription, item.unitCode, item.taxRatePercent)
        // BT-155/BT-156. Mustang writes both as ram:SellerAssignedID /
        // ram:BuyerAssignedID directly before ram:Name in the SpecifiedTradeProduct block, so the
        // schema order holds without this code knowing it.
        item.sellerItemIdentifier?.let { product.setSellerAssignedID(it) }
        item.buyerItemIdentifier?.let { product.setBuyerAssignedID(it) }
        // The position's actual EN16931 category/exemption, not just a
        // bare percentage: Mustang derives the VAT breakdown's category/exemption grouping from
        // these per-line values, so a missing category here means an unclassified 0% line
        // silently reads as "standard rate" instead of AE/E/O.
        item.taxCategoryCode?.let { category ->
            product.setTaxCategoryCode(category)
            if (category == "AE") product.setReverseCharge()
        }
        // BT-121 stays exactly what the tax rule carries — the machine-readable code, untouched
        // by this. BT-120 (the human-readable text) is where the standard-text family
        // applies: the German original stays first, the English standard text follows after
        // " — " (the wire keeps BT-120 to one XML text line, unlike the printed page's newline).
        // PO decision 4 (06.09.2026) fallback ruling — if the mandatory embedded KoSIT run
        // (KositValidationIT/ZugferdCreditNoteConformanceE2EIT) ever rejects a composed BT-120,
        // the wire falls back to the German original alone; BT-121 already makes the exemption
        // machine-interpretable, so the PDF alone would carry the English addition in that case.
        item.exemptionReasonCode?.let { product.setTaxExemptionReasonCode(it) }
        composeTaxNote(
            germanText = item.exemptionReasonText,
            classification = TaxExemptionClassification(
                category = item.taxCategoryCode,
                vatexCode = item.exemptionReasonCode,
            ),
            language = documentLanguage,
            separator = " — ",
        )?.let { product.setTaxExemptionReason(it) }
        item.productClassificationCode?.let { classification ->
            // Mustang 2.25.0 gap: `ZUGFeRD2PullProvider` encodes the ClassCode listID but writes
            // `getCode()` raw. Escaped here before handover (listID is encoded by Mustang itself).
            product.addClassification(
                DesignatedProductClassification(ClassCode(item.productClassificationScheme, XMLTools.encodeXML(classification))),
            )
        }
        val zugItem = Item(product, item.unitPriceNet, item.quantity)
        // BT-133, the resolved cost center (BT-19 FREE value, falling back to
        // `Invoice.references.costCenter`), the same on every line of the invoice.
        item.accountingReference?.let { zugItem.setAccountingReference(it) }
        // BG-26 (BT-134/BT-135) — the line-level performance period. Mustang emits the line's
        // BillingSpecifiedPeriod only when at least one date is set (BR-CO-20 thus structurally
        // satisfied), so calling with two nulls is harmless.
        if (item.servicePeriodFrom != null || item.servicePeriodTo != null) {
            zugItem.setDetailedDeliveryPeriod(
                item.servicePeriodFrom?.let { SqlDate.valueOf(it) },
                item.servicePeriodTo?.let { SqlDate.valueOf(it) },
            )
        }
        // BG-24 — receipts no longer produce anything at line level; see the KDoc on
        // `getAdditionalReferencedDocuments()` in [build] for where they go and why.
        // BT-127, a plain ram:IncludedNote (Item's own concrete addNote, not a
        // SubjectCode'd one — MustangItemModel.lineNote's own KDoc has the border cases).
        item.lineNote?.let { zugItem.addNote(it) }
        return zugItem
    }
}
