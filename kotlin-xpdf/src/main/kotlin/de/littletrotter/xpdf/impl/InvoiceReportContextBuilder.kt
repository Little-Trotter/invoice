package de.littletrotter.xpdf.impl

import de.littletrotter.shared.PositionNumberFormatter
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.documents.DocumentTexts
import de.littletrotter.shared.findSellerVatRegistrationByCountry
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The tax-breakdown grouping key: the same 0% rate must not merge AE/E/O rows. */
internal data class TaxBreakdownKey(
    val category: String?,
    val rate: BigDecimal,
    val exemptionReasonCode: String?,
    val exemptionReasonText: String?,
)

/**
 * Builds formatted invoice rendering context for [InvoiceReportModelBuilder].
 * Handles number/date formatting for both German and English documents.
 */
class InvoiceReportContextBuilder {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** "15 Aug 2026", unambiguous for a non-German reader (no dd/MM ordering guesswork). */
    private val dateFormatterEn: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** German number format: 1.234,50 instead of 1234.50 */
    private val germanNumberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.GERMANY).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = true
    }

    /** English number format: 1,234.50 (Locale.UK — matches the day-month-year date style
     *  chosen above; the digit grouping/decimal separators are identical to Locale.US). */
    private val englishNumberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.UK).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = true
    }

    /**
     * German number format without a fixed decimal count — for quantities/percentages, where
     * "1,5" (not "1,50") and "100" (not "100,00") is the natural reading. Still uses comma as
     * decimal separator (unlike the previous `stripTrailingZeros().toPlainString()`, which
     * produced locale-independent "1.5" — inconsistent with every other German-formatted number
     * on the invoice).
     */
    private val germanPlainNumberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.GERMANY).apply {
        isGroupingUsed = true
        maximumFractionDigits = 3
    }

    /** English counterpart of [germanPlainNumberFormat]. */
    private val englishPlainNumberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.UK).apply {
        isGroupingUsed = true
        maximumFractionDigits = 3
    }

    /** "en" only when the frozen document language ([de.littletrotter.shared.InvoiceMetaDto.documentLanguage],
     *  not the unrelated per-export XML [de.littletrotter.shared.InvoiceMetaDto.languageId] override) says so;
     *  every other value (including `null`/blank) reads as German. */
    internal fun resolveRenderLanguage(invoice: InvoiceDto): String = resolveDocumentRenderLanguage(invoice.meta.documentLanguage)

    /** One [ItemRenderContext] per invoice item, in report template shape. */
    private fun buildItemContexts(invoice: InvoiceDto, language: String): List<ItemRenderContext> =
        invoice.items.mapIndexed { idx, item ->
            ItemRenderContext(
                position    = PositionNumberFormatter.format(item.positionId, fallbackIndex = idx + 1),
                description = item.description,
                qty         = fmtPlain(item.quantity, language),
                unitLabel   = item.unitLabel ?: "",
                unitPrice   = fmt(item.unitPriceNet.amount, language),
                lineTotal   = fmt(item.netAmount.amount, language),
                lineVat     = fmt(item.vatAmount.amount, language),
                vatPercent  = fmtPlain(item.taxRatePercent, language),
                // BT-155 (item seller's identifier) / BT-156 (item buyer's
                // identifier), read from the generic LINE-level B2B catalog values
                // (DocumentJobPublisher.b2bValuesFor). Raw pass-through here — whether BT-156 is
                // suppressed because it equals BT-155 is a *display* decision, made by the
                // template, not baked into the object model.
                // (PO order, 2026-08-31) — an explicitly captured BT-155 (typed or
                // profile-prefilled, possibly edited away from the article default) wins; falls
                // back to the linked Article's own system number so it shows on every invoice,
                // not only ones with a B2B profile that happens to capture BT-155.
                sellerItemId = item.b2bValues.firstOrNull { it.term == B2bTerm.BT_155 }?.value ?: item.articleNumber,
                buyerItemId = item.b2bValues.firstOrNull { it.term == B2bTerm.BT_156 }?.value,
                // (PO order, 2026-08-31) — the recipient-specific article
                // designation for [buyerItemId], resolved fresh at DTO-mapping time
                // (InvoiceDtoMapper.buyerArticleDescriptionFor); printed alongside it.
                buyerArticleDescription = item.buyerArticleDescription,
                // BT-154 (Item description), the free-text elaboration next to
                // [description]/BT-153. Raw pass-through; `null` stays `null` (never derived from
                // BT-153) so a consuming template can tell "not captured" apart from "empty".
                itemDescription = item.itemDescription,
                // The position's own Leistungszeitraum (BG-26): BT-134 (from)/
                // BT-135 (to), formatted the same way as the header's servicePeriod above.
                // Additive: the bundled default template's ITEMS_DS dataset doesn't declare a
                // SERVICE_PERIOD field, so it never reads this key — only a custom template that
                // does (e.g. the Handwerker one, see InvoiceReportRowBuilders.buildItems) is affected.
                servicePeriodFrom = formatIsoDate(item.servicePeriodFrom, language),
                servicePeriodTo   = formatIsoDate(item.servicePeriodTo, language),
                // The trip row's own subline/gross line and (detailed) its
                // sub-rows all read off these; every non-TRIP item leaves them at their harmless
                // defaults (category SERVICE's own code, tripDetailed false, empty lists, "0,00 €").
                category = item.category.code,
                tripDetailed = item.trip?.detailedOnInvoice ?: false,
                tripExpenses = item.trip?.expenses?.map { expense ->
                    TripExpenseRenderContext(
                        kindLabelKey = expense.kindLabelKey,
                        description = expense.description,
                        quantity = fmtPlain(expense.quantity, language),
                        unitLabel = expense.unitLabel ?: "",
                        netAmount = fmt(expense.netAmount.amount, language),
                    )
                } ?: emptyList(),
                tripPerDiemDays = item.trip?.perDiemDays?.size ?: 0,
                tripPerDiemTotal = fmt(
                    (item.trip?.perDiemDays?.fold(Money.ZERO) { acc, day -> acc + day.claimableAmount } ?: Money.ZERO).amount,
                    language,
                ),
            )
        }

    /**
     * The seller block of the report context.
     *
     * When the invoice is billed under one of the seller's foreign VAT registrations
     * (`meta.sellerVatRegistrationCountry`), the matching entry in `additionalVatRegistrations`
     * carries that registration's own VAT number. `vatId` stays the German home registration
     * unconditionally; the registration number is surfaced as its own pair of keys so the report
     * can render both. No matching entry — registration country set but not actually captured on
     * the sender profile — is not an error: both keys stay null and the renderer falls back to the
     * single home-VAT line. Resolution goes through [findSellerVatRegistrationByCountry] so
     * trimming and matching stay consistent with the XML export path.
     */
    private fun buildSenderContext(invoice: InvoiceDto): SenderRenderContext {
        val sender = invoice.senderProfile
        val registrationVat = findSellerVatRegistrationByCountry(sender, invoice.meta.sellerVatRegistrationCountry)
        return SenderRenderContext(
            name       = sender?.name ?: "",
            street     = sender?.street ?: "",
            postalCode = sender?.postalCode ?: "",
            city       = sender?.city ?: "",
            country    = sender?.country ?: "",
            vatId      = sender?.vatId,
            registrationVatId      = registrationVat?.vatId,
            registrationVatCountry = registrationVat?.country,
            iban       = sender?.iban,
            bic        = sender?.bic,
            taxNumber  = sender?.taxNumber,
            bankName   = sender?.bankName,
            managingDirector = sender?.managingDirector,
            courtOfJurisdiction = sender?.courtOfJurisdiction,
            registerCourt = sender?.registerCourt,
            commercialRegisterNumber = sender?.commercialRegisterNumber,
            email      = sender?.email,
            phone      = sender?.phone,
            footerNote = sender?.footerNote,
            website    = sender?.website,
            registeredSeat = sender?.registeredSeat,
        )
    }

    /** The buyer block of the report context. */
    private fun buildRecipientContext(invoice: InvoiceDto): RecipientRenderContext {
        val recipient = invoice.recipient
        return RecipientRenderContext(
            name       = recipient?.name ?: "",
            street     = recipient?.street ?: "",
            postalCode = recipient?.postalCode ?: "",
            city       = recipient?.city ?: "",
            country    = recipient?.country,
            customerNumber = recipient?.customerNumber,
        )
    }

    /**
     * Payment-due line (PO decisions of 06.09.2026, extended 28.09.2026) — the chosen payment
     * term's own resolved name ([de.littletrotter.shared.InvoiceDatesDto.paymentTermName]) is
     * the text whenever the invoice has one: this is the same field that the Mustang wire format
     * (BT-20) reads, so the term name itself never disagrees between the PDF and the wire. The
     * PDF card additionally names the concrete due date beside that name (the term name is a
     * short snapshot like "14 Tage netto"; the date gives the reader the actual target) — a
     * PDF-only addition BT-20 does not carry, since the wire already states due date and term
     * separately as their own BT fields. Without a due date, the name alone is printed, as
     * before. Only an invoice with no payment term at all falls back to composing a sentence: an
     * explicit due date wins, then the term in days, then "immediately". Follows the document
     * language, not reverseCharge.
     *
     * A storno document gets NO payment-due text at all (PO decision 2, 2026-09-02: the storno
     * flow stamps no payment term, no due date and zero term days — exactly what the
     * "immediately" fallback would otherwise match, and a document that annuls another must never
     * ask to be paid). `null` here — alongside `hasPaymentTerms`, which is already `false` for a
     * storno — keeps the payment line off the printed page for the bundled templates and for any
     * custom template printing the raw value. This check stays first and unconditional: a storno
     * never has a payment term to begin with, but even if one somehow survived on the row, the
     * storno flag still wins.
     */
    private fun buildPaymentDueText(invoice: InvoiceDto, language: String): String? =
        if (invoice.storno) {
            null
        } else {
            val termName = invoice.dates.paymentTermName
            val dueDate = invoice.dates.dueDate
            when {
                termName != null && !dueDate.isNullOrBlank() ->
                    "$termName · " + DocumentTexts.format("paymentTerms.dueOn", language, formatIsoDate(dueDate, language))
                termName != null -> termName
                !dueDate.isNullOrBlank() ->
                    DocumentTexts.format("paymentTerms.dueBy", language, formatIsoDate(dueDate, language))
                invoice.dates.paymentTermsDays > 0 ->
                    DocumentTexts.format("paymentTerms.net", language, invoice.dates.paymentTermsDays)
                else -> DocumentTexts.text("paymentTerms.immediate", language)
            }
        }

    /**
     * Second payment-block line, printed under [buildPaymentDueText]'s sentence: only when the
     * domain actually computed a cash discount for this invoice ([InvoiceDto.cashDiscount], see
     * its KDoc for the null cases — no discounted payment term, non-positive gross, or a storno
     * document that survived with a stray discount on its snapshot). `invoice.storno` is checked
     * again here regardless (same defensive stance as [buildPaymentDueText]): a document that
     * annuls another must never invite early payment, even if the discount field itself were
     * ever non-null on one.
     */
    private fun buildCashDiscountText(invoice: InvoiceDto, language: String): String? {
        val discount = invoice.cashDiscount ?: return null
        if (invoice.storno) return null
        val currencyUnit = printedCurrencyUnit(invoice.totals.currency.code)
        return DocumentTexts.format(
            "paymentTerms.cashDiscount",
            language,
            formatIsoDate(discount.deadline, language),
            formatCashDiscountRate(discount.percent, language),
            amount(fmt(discount.amount.amount, language), currencyUnit),
            amount(fmt(discount.payableAmount.amount, language), currencyUnit),
        )
    }

    /** Percentage points without trailing zeros ("2", "2,5"/"2.5"). German keeps the space
     *  before "%" used elsewhere on the invoice (see [amountUnit]'s per-item VAT rate); English's
     *  tighter typographic convention for a discount rate drops it ("2%"), so this stays its own
     *  helper rather than reusing the always-spaced one. */
    private fun formatCashDiscountRate(percent: BigDecimal, language: String): String =
        if (language == "en") "${fmtPlain(percent, language)}%" else "${fmtPlain(percent, language)} %"

    /**
     * MwSt. breakdown grouped by (category, rate, exemptionReasonCode,
     * exemptionReasonText): the same 0% rate can mean AE, E or O, each its own row with its own
     * legal-basis text, never merged into one generic "USt 0%" line. Sort by the numeric rate
     * (BigDecimal, natural order) before formatting — sorting the already-formatted German string
     * ("7,5") would parse wrong/throw (comma decimal).
     */
    private fun buildTaxBreakdownRows(invoice: InvoiceDto, language: String): List<TaxBreakdownRowContext> =
        invoice.items
            .groupBy {
                TaxBreakdownKey(it.taxCategory, it.taxRatePercent, it.exemptionReasonCode, it.exemptionReasonText)
            }
            .toList()
            .sortedWith(compareBy({ it.first.rate }, { it.first.category ?: "" }))
            .map { (key, items) ->
                TaxBreakdownRowContext(
                    rate = fmtPlain(key.rate, language),
                    category = key.category,
                    net = fmt(items.sumOf { it.netAmount.amount }, language),
                    vat = fmt(items.sumOf { it.vatAmount.amount }, language),
                )
            }

    internal fun buildContext(invoice: InvoiceDto): RenderContext {
        val language = resolveRenderLanguage(invoice)
        val items = buildItemContexts(invoice, language)
        val senderCtx = buildSenderContext(invoice)
        val recipientCtx = buildRecipientContext(invoice)

        // Service period from-to (or only to) for the info box.

        val servicePeriod = formatServicePeriod(invoice.dates.servicePeriodFrom, invoice.dates.servicePeriodTo, language)

        val paymentDueText = buildPaymentDueText(invoice, language)
        val cashDiscountText = buildCashDiscountText(invoice, language)

        val taxBreakdownRows = buildTaxBreakdownRows(invoice, language)

        val taxBreakdown = if (taxBreakdownRows.size > 1) {
            RenderTaxBreakdown(taxBreakdownRows)
        } else {
            null
        }

        // The Reverse-Charge PDF note uses the tax rule's own exemption
        // reason text (BT-120), so PDF and XML never drift; kept raw here, composed with the
        // standard-text family (composeTaxNote) only in InvoiceReportModelBuilder, which
        // knows the box's category (always "AE") and builds the printed rcNoteText.
        val reverseChargeNoteText = invoice.items.firstOrNull { it.taxCategory == "AE" }?.exemptionReasonText
        val reverseChargeExemptionReasonCode = invoice.items.firstOrNull { it.taxCategory == "AE" }?.exemptionReasonCode

        val invoiceCtx = InvoiceRenderContext(
            number       = invoice.invoiceNumber.value,
            reverseCharge = invoice.totals.reverseCharge,
            language     = language,
            // The raw WordingProfile code (InvoiceMetaDto owns it, not a new top-level
            // InvoiceDto field), resolved by InvoiceReportModelBuilder/InvoiceReportRowBuilders
            // (mirrors how "currencyCode" above is derived once here and read back by both).
            // `null`/unknown reads as WordingProfile.DEFAULT on the read side.
            wordingProfile = invoice.meta.wordingProfile,
            // The printed currency unit: derived once, here, from the invoice's own
            // currency (never from the document language, which only ever governed number/date
            // formatting and labels). InvoiceReportModelBuilder/InvoiceReportRowBuilders read
            // this field instead of each re-deriving a unit from `language`.
            // invoice.totals.currency is a typed, always-valid Currency now; Currency.ofOrdinal
            // (the null/out-of-range fallback) is exercised only by CurrencyOrdinalDeserializer at
            // the wire boundary (kotlin-shared), where the value is not yet type-checked.
            currencyCode = invoice.totals.currency.code,
            reverseChargeNoteText = reverseChargeNoteText,
            reverseChargeExemptionReasonCode = reverseChargeExemptionReasonCode,
            date         = formatIsoDate(invoice.dates.invoiceDate, language),
            dueDate      = formatIsoDate(invoice.dates.dueDate, language),
            netTotal     = fmt(invoice.totals.netAmount.amount, language),
            vatTotal     = fmt(invoice.totals.vatAmount.amount, language),
            grossTotal   = fmt(invoice.totals.grossAmount.amount, language),
            paymentDueText = paymentDueText,
            cashDiscountText = cashDiscountText,
            buyerReference = invoice.references.buyerReference,
            // BT-13 — its own row on the print image since the BT-10/BT-13 split; before it, the
            // "Bestellreferenz" the XML claimed had no counterpart on the PDF at all.
            purchaseOrderReference = invoice.references.purchaseOrderReference,
            orderReference = invoice.references.orderReference,
            offerReference = invoice.references.offerReference,
            offerDate      = formatIsoDate(invoice.references.offerDate, language),
            // BG-3 on the print image: the two values become rows in the facts box (both
            // absent on an ordinary invoice, so the box simply does not grow).
            // A storno document carries no payment side at all: no due line, no payment terms and
            // no payment block. The flag travels through the context so the model builder does not
            // have to re-derive it from the invoice type code (which moved 381 -> 384 with PO decision 1).
            storno       = invoice.storno,
            originalInvoiceNumber = invoice.references.originalInvoiceNumber,
            originalInvoiceDate   = formatIsoDate(invoice.references.originalInvoiceDate, language),
            servicePeriod = servicePeriod,
            freeText      = invoice.meta.freeText,
            consumerNote  = invoice.meta.consumerNote,
            taxBreakdown  = taxBreakdown,
            // A storno never shows a payment row, even if a stray due date survived on the row
            // (the storno flow stamps none — see buildPaymentDueText's own KDoc).
            hasPaymentTerms = !invoice.storno &&
                (!invoice.dates.dueDate.isNullOrBlank() || invoice.dates.paymentTermsDays > 0),
            // The invoice's project reference, i.e. BT-11 (`ram:SpecifiedProcuringProject`
            // in the XML export, MustangExportModel.projectReference). The former open
            // "does projectNumber overlap BT-11" question is resolved: it is BT-11, `BOUND` in the
            // B2B catalog on this exact property.
            projectNumber = invoice.references.projectNumber,
            items        = items,
        )

        return RenderContext(
            invoice   = invoiceCtx,
            sender    = senderCtx,
            recipient = recipientCtx,
        )
    }

    /** German or English number format with grouping: "1.234,50" / "1,234.50" */
    private fun fmt(value: BigDecimal, language: String): String =
        (if (language == "en") englishNumberFormat else germanNumberFormat).format(value.setScale(2, RoundingMode.HALF_UP))

    /** Number format without a fixed decimal count — for quantities/percentages: "1,5"/"1.5" not "1,50"/"1.50" */
    private fun fmtPlain(value: BigDecimal, language: String): String =
        (if (language == "en") englishPlainNumberFormat else germanPlainNumberFormat).format(value.stripTrailingZeros())

    /** ISO date (yyyy-MM-dd) → German (dd.MM.yyyy) or English (d MMM yyyy); null stays null */
    private fun formatIsoDate(isoDate: String?, language: String): String? {
        if (isoDate.isNullOrBlank()) return null
        return try {
            LocalDate.parse(isoDate).format(if (language == "en") dateFormatterEn else dateFormatter)
        } catch (_: Exception) {
            isoDate // fallback: return as-is if not parseable
        }
    }

    private fun formatServicePeriod(from: String?, to: String?, language: String): String? {
        if (from.isNullOrBlank() && to.isNullOrBlank()) return null
        val fmtFrom = formatIsoDate(from, language)
        val fmtTo = formatIsoDate(to, language)
        // No `both null` else case: the early return above already excludes "from and to both
        // blank", and formatIsoDate(...) only ever returns null for blank input (never for a
        // non-blank one, parseable or not — an unparseable date falls back to the raw string) —
        // so at least one of fmtFrom/fmtTo is always non-null past this point. A former
        // `else -> null` arm here was unreachable dead code, deleted.
        return when {
            fmtFrom != null && fmtTo != null -> "$fmtFrom – $fmtTo"
            fmtTo != null -> fmtTo
            language == "en" -> "since $fmtFrom"
            else -> "seit $fmtFrom"
        }
    }
}
