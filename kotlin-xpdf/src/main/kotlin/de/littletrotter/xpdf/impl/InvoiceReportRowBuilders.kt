package de.littletrotter.xpdf.impl

import de.littletrotter.shared.Currency
import de.littletrotter.shared.PositionNumberFormatter
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.registrationVatDesignation
import de.littletrotter.shared.wording.WordingProfile
import java.text.MessageFormat

/** The language/wording-profile/currency-unit triple every row builder in this file threads
 *  through — grouped so [buildTripExpenseSubRow] takes one parameter for it instead of three
 *  (LongParameterList). */
private data class RowRenderContext(
    val language: String,
    val wordingProfile: WordingProfile,
    val currencyUnit: String,
)

/**
 * Row-list builders used by [InvoiceReportModelBuilder] — split into its own file
 * to keep both files under the line-count target. Everything here reads the typed
 * `invoiceMap`/`senderMap`/`recipientMap` ([InvoiceRenderContext]/[SenderRenderContext]/
 * [RecipientRenderContext]) that [InvoiceReportContextBuilder.buildContext] already formatted
 * (numbers/dates stay the single source of truth there); this file's own job is turning
 * that into the typed row/box shapes [InvoiceReportModelBuilder] assembles into a
 * [InvoiceReportModel].
 */

// ── rinfo / pinfo info boxes (§6/§8) ──

private data class RinfoRow(val label: String, val value: String?)

/**
 * Recipient/invoice-facts box (§6): invoice number and date are mandatory rows, the rest only
 * appear when present. Which rows are present never depends on the language (only each
 * row's *label* text does), so one `language`-driven pass covers it.
 */
internal fun rinfoBox(invoiceMap: InvoiceRenderContext, recipientMap: RecipientRenderContext, language: String): ReportInfoBox {
    fun l(key: String) = Labels.label(key, language)
    val optional = listOf(
        // BG-3, first because it says what this document *is*: on a cancellation document these
        // two name the invoice it cancels. Absent on an ordinary invoice, so the box keeps its
        // previous shape there. BT-26 is optional even on a cancellation document; the reference row
        // stands on its own when the original carries no issue date.
        RinfoRow(l("info.originalInvoiceNo"), invoiceMap.originalInvoiceNumber),
        RinfoRow(l("info.originalInvoiceDate"), invoiceMap.originalInvoiceDate),
        RinfoRow(l("info.offerNo"), invoiceMap.offerReference),
        RinfoRow(l("info.offerDate"), invoiceMap.offerDate),
        // BT-10 (Invoice.buyerReference → getReferenceNumber() in the mustang
        // export). Was captured in the render context but never actually shown
        // — a B2B gap between what the XML carries and what the PDF prints.
        RinfoRow(l("info.buyerReference"), invoiceMap.buyerReference),
        // BT-13 — buyer's purchase order number, own field/row since BT-10/BT-13 split.
        // Labels matter: this row is PO reference, previous is buyer reference.
        RinfoRow(l("info.purchaseOrderReference"), invoiceMap.purchaseOrderReference),
        RinfoRow(l("info.servicePeriod"), invoiceMap.servicePeriod),
        RinfoRow(l("info.customerNo"), recipientMap.customerNumber),
        RinfoRow(l("info.orderNo"), invoiceMap.orderReference),
        // The invoice's project reference, BT-11 (see the invoiceMap.projectNumber
        // comment in InvoiceReportContextBuilder.buildContext for the CII export side).
        RinfoRow(l("info.projectNumber"), invoiceMap.projectNumber),
        RinfoRow(l("info.dueDate"), invoiceMap.dueDate),
    ).filter { isPresent(it.value) }

    val labels = mutableListOf(l("info.invoiceNo"), l("info.invoiceDate"))
    optional.forEach { labels.add(it.label) }
    val values = mutableListOf(invoiceMap.number, orEmpty(invoiceMap.date))
    optional.forEach { values.add(orEmpty(it.value)) }

    return ReportInfoBox(labels.zip(values, ::ReportInfoRow))
}

/**
 * Sender tax-facts box (§8) — VAT ID and tax number only: contact details (email/
 * phone) moved to the footer's own contact column and no longer duplicate here. A foreign VAT
 * registration (see [de.littletrotter.shared.registrationVatDesignation]) renders first, ahead of
 * the German home USt-ID/VAT ID line, so a dual-registered seller shows both numbers with the
 * registration one being the more prominent, first-read line.
 */
internal fun pinfoBox(senderMap: SenderRenderContext, language: String): ReportInfoBox {
    fun l(key: String) = Labels.label(key, language)
    val labels = mutableListOf<String>()
    val values = mutableListOf<String>()
    val registrationVatId = senderMap.registrationVatId
    if (isPresent(registrationVatId)) {
        // Official foreign designation from kotlin-shared; unknown country falls back
        // to this bundle's localized VAT label — kotlin-shared stays label-bundle-free.
        labels.add(registrationVatDesignation(senderMap.registrationVatCountry) ?: l("sender.vatId"))
        values.add(orEmpty(registrationVatId))
        // The seller's national tax number of the same country sits directly below that country's
        // VAT ID; the producer already dropped it when it only repeats the VAT ID.
        val taxNumberLabelKey = senderMap.registrationTaxNumberLabelKey
        if (isPresent(senderMap.registrationTaxNumber) && taxNumberLabelKey != null) {
            labels.add(MessageFormat.format(l(taxNumberLabelKey), senderMap.registrationVatCountry.orEmpty()))
            values.add(orEmpty(senderMap.registrationTaxNumber))
        }
    }
    if (isPresent(senderMap.vatId)) { labels.add(l("sender.vatId")); values.add(orEmpty(senderMap.vatId)) }
    if (isPresent(senderMap.taxNumber)) { labels.add(l("sender.taxNumber")); values.add(orEmpty(senderMap.taxNumber)) }
    return ReportInfoBox(labels.zip(values, ::ReportInfoRow))
}

// ── Items from the render context ──

internal fun buildItems(invoiceMap: InvoiceRenderContext, language: String, wordingProfile: WordingProfile = WordingProfile.DEFAULT): List<ReportItemRow> {
    // The invoice's own printed currency unit (see InvoiceReportModelBuilder's identical
    // rule); the empty-string fallback only guards a hand-built test context without the field.
    val currencyUnit = printedCurrencyUnit(invoiceMap.currencyCode.ifBlank { Currency.EUR.code })
    return invoiceMap.items.flatMap { raw -> buildItemRows(raw, language, wordingProfile, currencyUnit) }
}

/**
 * One [ReportItemRow] for the position itself (its description cell carries the trip subline too — no new column for every row) plus, only for a `detailedOnInvoice` TRIP
 * item with actual data (README border case 11), one indented "N.1, N.2 ..." sub-row per expense
 * and a closing per-diem summary row. Every non-TRIP item keeps exactly the single row it always had.
 */
private fun buildItemRows(raw: ItemRenderContext, language: String, wordingProfile: WordingProfile, currencyUnit: String): List<ReportItemRow> {
    val mainRow = buildMainItemRow(raw, language, wordingProfile, currencyUnit)
    if (raw.category != InvoiceItemCategory.TRIP.code) return listOf(mainRow)
    return listOf(mainRow) + buildTripSubRows(raw, language, wordingProfile, currencyUnit)
}

private fun buildMainItemRow(raw: ItemRenderContext, language: String, wordingProfile: WordingProfile, currencyUnit: String): ReportItemRow {
    val qty = raw.qty
    val unitLabel = raw.unitLabel
    val isTrip = raw.category == InvoiceItemCategory.TRIP.code
    val idLines = itemIdLines(raw, language, wordingProfile)
    val buyerArticleDescriptionLine = buyerArticleDescriptionLine(raw)
    val tripSubline = if (isTrip) tripSublineText(raw, language, wordingProfile) else ""
    return ReportItemRow(
        pos = raw.position,
        price = amount(raw.unitPrice, currencyUnit),
        net = amount(raw.lineTotal, currencyUnit),
        vatAmt = amount(raw.lineVat, currencyUnit),
        vatPct = amountUnit(raw.vatPercent, "%"),
        description = raw.description,
        itemDescription = orEmpty(raw.itemDescription),
        qtyPlain = qty,
        unitLabel = unitLabel,
        servicePeriod = combineServicePeriod(orEmpty(raw.servicePeriodFrom), orEmpty(raw.servicePeriodTo)),
        idLines = idLines,
        buyerArticleDescriptionLine = buyerArticleDescriptionLine,
        tripSubline = tripSubline,
    )
}

/**
 * The trip row's own second description line: "Zweck: ... / Datum: ..."
 * (either half omitted when blank, the whole line omitted when both are), never a new column.
 *
 * No amount of its own (PO 14.09.2026): the position's gross belongs to no column a reader can
 * reconcile it against. Every other position states net, VAT is settled once in the invoice
 * totals, and on a fixed-price trip the gross contradicts the very sub-rows printed underneath it.
 */
private fun tripSublineText(raw: ItemRenderContext, language: String, wordingProfile: WordingProfile): String {
    fun l(key: String) = Labels.label(key, language, wordingProfile)
    val purpose = orEmpty(raw.itemDescription)
    val period = combineServicePeriod(orEmpty(raw.servicePeriodFrom), orEmpty(raw.servicePeriodTo))
    return listOfNotNull(
        purpose.takeIf { it.isNotBlank() }?.let { "${l("subline.purpose")}: $it" },
        period.takeIf { it.isNotBlank() }?.let { "${l("subline.period")}: $it" },
    ).joinToString("   ")
}

/**
 * README border case 11 — no sub-rows at all when the trip is not `detailedOnInvoice`, or
 * is but has neither expenses nor per-diem days (rendered exactly like grouped, no empty note).
 * README border case 7 (100 expenses) needs nothing special here: the table's own
 * pagination already carries any number of rows across pages.
 */
private fun buildTripSubRows(raw: ItemRenderContext, language: String, wordingProfile: WordingProfile, currencyUnit: String): List<ReportItemRow> {
    if (!raw.tripDetailed) return emptyList()
    val expenses = raw.tripExpenses
    val perDiemDays = raw.tripPerDiemDays
    if (expenses.isEmpty() && perDiemDays == 0) return emptyList()
    fun l(key: String) = Labels.label(key, language, wordingProfile)
    val positionId = raw.position
    val rowContext = RowRenderContext(language, wordingProfile, currencyUnit)
    val rows = expenses.mapIndexed { idx, expense ->
        buildTripExpenseSubRow(positionId, idx + 1, expense, rowContext)
    }.toMutableList()
    if (perDiemDays > 0) {
        val label = "${l("trip.perDiem")}, $perDiemDays ${l("trip.days")}"
        rows += ReportItemRow(
            pos = PositionNumberFormatter.format(positionId, expenses.size + 1),
            price = "",
            net = amount(raw.tripPerDiemTotal, currencyUnit),
            vatAmt = "",
            vatPct = "",
            description = label,
            itemDescription = "",
            qtyPlain = "",
            unitLabel = "",
            servicePeriod = "",
            subRow = true,
        )
    }
    return rows
}

private fun buildTripExpenseSubRow(positionId: String, subIndex: Int, expense: TripExpenseRenderContext, ctx: RowRenderContext): ReportItemRow {
    val language = ctx.language
    val wordingProfile = ctx.wordingProfile
    val currencyUnit = ctx.currencyUnit
    val kind = Labels.label(orEmpty(expense.kindLabelKey).ifBlank { "trip.kind.other" }, language, wordingProfile)
    val description = orEmpty(expense.description)
    val label = if (description.isBlank()) kind else "$kind — $description"
    val qty = orEmpty(expense.quantity)
    val unitLabel = expense.unitLabel
    return ReportItemRow(
        pos = PositionNumberFormatter.format(positionId, subIndex),
        price = "",
        net = amount(expense.netAmount, currencyUnit),
        vatAmt = "",
        vatPct = "",
        // The expense kind ("Hotel", "Reisezeit", ...) travels in the label, or a reader would
        // see only the free-text description (or, for a kind with no description at all, an
        // empty cell).
        description = label,
        itemDescription = "",
        qtyPlain = qty,
        unitLabel = unitLabel,
        servicePeriod = "",
        subRow = true,
    )
}

/** Combines a position's formatted BT-134/BT-135 into one display string for
 *  a table cell: both non-blank → "from – to", only one → that one, neither → blank. Deliberately
 *  simpler than the header's `formatServicePeriod` (no "seit"/"since" prefix) — a table column
 *  reads as a bare range, not a sentence. */
private fun combineServicePeriod(from: String, to: String): String = when {
    from.isNotBlank() && to.isNotBlank() -> "$from – $to"
    to.isNotBlank() -> to
    from.isNotBlank() -> from
    else -> ""
}

/**
 * The identifier sublines of the description cell, structured, up to two entries:
 *   1. `<BT-155>`, unlabelled — the seller's own article number, falling back to the linked
 *      article's system number; the bare
 *      value is self-explanatory in that line.
 *   2. `Ref. <BT-156>` — the buyer's article number, labelled `item.buyerId` ("Ref.", identical
 *      spelling in German and English); omitted when BT-156 equals BT-155 (nothing new to tell the buyer, their own
 *      number is the seller's).
 * Travels on [ReportItemRow.idLines] — label and value kept apart, not concatenated, so the
 * template can style each part on its own. See [buyerArticleDescriptionLine] for the third
 * subline; BT-154 follows after the identifiers, BT-157 (item standard identifier) stays in the
 * export XML but never gets a printed subline.
 */
private fun itemIdLines(raw: ItemRenderContext, language: String, wordingProfile: WordingProfile): List<ReportItemIdLine> {
    fun l(key: String) = Labels.label(key, language, wordingProfile)
    val sellerId = orEmpty(raw.sellerItemId)
    val buyerId = buyerReferenceId(raw)
    return listOfNotNull(
        sellerId.takeIf { it.isNotBlank() }?.let { ReportItemIdLine(label = "", value = it) },
        buyerId.takeIf { it.isNotBlank() }?.let { ReportItemIdLine(label = l("item.buyerId"), value = it) },
    )
}

/** BT-156, suppressed to blank when it equals BT-155 — the shared condition behind both
 *  [itemIdLines]'s "Ref." line and [buyerArticleDescriptionLine]. */
private fun buyerReferenceId(raw: ItemRenderContext): String {
    val sellerId = orEmpty(raw.sellerItemId)
    val buyerIdRaw = orEmpty(raw.buyerItemId)
    return if (buyerIdRaw.isNotBlank() && buyerIdRaw == sellerId) "" else buyerIdRaw
}

/**
 * The recipient-specific article description (`ArticleRecipientRef.buyerArticleName`), its own
 * italic subline — only printed together with [itemIdLines]'s "Ref." line, and only when it adds
 * something the reader does not already have: a designation that, once normalised (trimmed,
 * inner whitespace runs collapsed to one space, case folded), reads exactly like the position
 * description (BT-153) or the seller's own item description (BT-154) repeats a line already on
 * the page instead of describing anything new.
 */
private fun buyerArticleDescriptionLine(raw: ItemRenderContext): String {
    val buyerDescription = orEmpty(raw.buyerArticleDescription)
    if (buyerDescription.isBlank() || buyerReferenceId(raw).isBlank()) return ""
    val normalizedDescription = normalizeForComparison(buyerDescription)
    val repeatsAnExistingLine = normalizedDescription == normalizeForComparison(raw.description) ||
        normalizedDescription == normalizeForComparison(orEmpty(raw.itemDescription))
    return if (repeatsAnExistingLine) "" else buyerDescription
}

/** Trim, collapse whitespace runs to one space, case-fold — the exact-equality comparison
 *  [buyerArticleDescriptionLine] uses against BT-153/BT-154, never a substring/contains check. */
private fun normalizeForComparison(s: String): String = s.trim().replace(Regex("\\s+"), " ").lowercase()

internal fun buildTaxRows(invoiceMap: InvoiceRenderContext): List<ReportTaxRow> {
    // The invoice's own printed currency unit (see InvoiceReportModelBuilder's identical
    // rule); the empty-string fallback only guards a hand-built test context without the field.
    val currencyUnit = printedCurrencyUnit(invoiceMap.currencyCode.ifBlank { Currency.EUR.code })
    val rows = invoiceMap.taxBreakdown?.rows ?: return emptyList()
    return rows.map {
        // A 0% rate alone doesn't say whether it's Reverse Charge, steuerbefreit or nicht
        // steuerbar; append the EN16931 category code (S is the implicit default, not shown) so
        // two 0% rows for different categories never read as the same line.
        val category = it.category?.takeIf { c -> c.isNotBlank() && c != "S" }
        val rateLabel = amountUnit(it.rate, "%") + (category?.let { c -> " ($c)" } ?: "")
        ReportTaxRow(
            rate = rateLabel,
            net = amount(it.net, currencyUnit),
            vat = amount(it.vat, currencyUnit),
        )
    }
}
