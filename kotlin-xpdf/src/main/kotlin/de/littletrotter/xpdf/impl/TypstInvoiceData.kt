package de.littletrotter.xpdf.impl

import de.littletrotter.shared.documents.FreeTextFormat
import de.littletrotter.shared.documents.FreeTextMarkup

/**
 * The `data.json` contract of the shipped `standard-invoice.typ`: everything the template lays
 * out, as preformatted strings and presence flags — the template never calculates, rounds or
 * parses markup. Built from the engine-neutral [InvoiceReportModel] by [toTypstData]; Jackson
 * serialises the property names below verbatim, so a rename here is a template change.
 */
data class TypstInvoiceData(
    val language: String,
    val document: TypstDocument,
    val sender: TypstSender,
    val recipient: TypstRecipient,
    val invoiceInfo: List<TypstInfoRow>,
    val senderInfo: List<TypstInfoRow>,
    val freeText: TypstFreeText,
    val items: TypstItems,
    val taxBreakdown: TypstTaxBreakdown,
    val totals: TypstTotals,
    val payment: TypstPayment,
    val notes: TypstNotes,
    val footer: TypstFooter,
)

data class TypstDocument(val subject: String, val pageLabel: String)

/** [name] is the sender-profile name exactly as entered — the template fits it into the
 *  letterhead itself, with its own width and size ladder, no pre-wrapping here. */
data class TypstSender(val name: String, val returnLine: String)

data class TypstRecipient(val name: String, val street: String, val cityLine: String, val countryLine: String)

data class TypstInfoRow(val label: String, val value: String)

/** [afterItems] moves the block behind the items table when it is too tall for page 1's head. */
data class TypstFreeText(val present: Boolean, val afterItems: Boolean, val lines: List<TypstFreeTextLine>)

data class TypstFreeTextLine(val level: Int, val mark: String?, val runs: List<TypstFreeTextRun>)

data class TypstFreeTextRun(
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
)

/** [detailColumns]/[taxColumn] select one of four table variants. [unitFootnotes] are the
 *  print-language explanations for unit symbols on rows that require them — printed as one muted
 *  line under the table, empty when no row needs an explanation. */
data class TypstItems(
    val detailColumns: Boolean,
    val taxColumn: Boolean,
    val headers: TypstItemHeaders,
    val rows: List<TypstItemRow>,
    val unitFootnotes: List<String>,
)

data class TypstItemHeaders(
    val pos: String,
    val description: String,
    val quantity: String,
    val unitPrice: String,
    val net: String,
    val vatAmount: String,
    val vat: String,
    val unit: String,
    val period: String,
)

/** One [TypstItemRow.idLines] entry: [label] blank for the bare seller article number, or the
 *  "Ref." wording for the buyer's own article number — kept apart so the template's shared
 *  `desc-body` builder can print the label muted and the value in the normal ink colour, joined
 *  on one line by its own " · " separator instead of the template re-splitting a combined string. */
data class TypstIdLine(val label: String, val value: String)

data class TypstItemRow(
    val pos: String,
    val description: String,
    val itemDescription: String,
    val idLines: List<TypstIdLine>,
    val buyerArticleDescriptionLine: String,
    val tripSubline: String,
    val subRow: Boolean,
    val quantity: String,
    val unitLabel: String,
    val unitPrice: String,
    val net: String,
    val vatPercent: String,
    val servicePeriod: String,
)

data class TypstTaxBreakdown(val present: Boolean, val headers: TypstTaxHeaders, val rows: List<TypstTaxRow>)

data class TypstTaxHeaders(val rate: String, val net: String, val vat: String)

data class TypstTaxRow(val rate: String, val net: String, val vat: String)

data class TypstTotals(
    val netLabel: String,
    val net: String,
    val vatLabel: String,
    val vat: String,
    val grossLabel: String,
    val gross: String,
)

data class TypstPayment(
    val present: Boolean,
    val title: String,
    val amountLabel: String,
    val amount: String,
    val dueLabel: String,
    val dueText: String,
    val hasDue: Boolean,
    /** Skonto sentence printed under [dueText] (empty/`false` without a computed cash
     *  discount or on a cancellation — see [ReportPaymentBlock.cashDiscountText]). */
    val cashDiscountText: String,
    val hasCashDiscount: Boolean,
    val bankLabel: String,
    val bankName: String,
    val hasBank: Boolean,
    val iban: String,
    val hasIban: Boolean,
    val bic: String,
    val hasBic: Boolean,
    val referenceLabel: String,
    val reference: String,
    val hint: String,
    val showHint: Boolean,
    /** `"qr.svg"` when a GiroCode was rendered for this invoice, `null` otherwise (setting off,
     *  cancellation, missing IBAN, non-EUR currency). */
    val qrFile: String?,
    /** Label `payment.qr`, printed under the code only when [qrFile] is not `null`. */
    val qrCaption: String,
)

data class TypstNotes(
    /** The box prints when either the seller note or the consumer note is present. */
    val hasNote: Boolean,
    val noteTitle: String,
    val hasSellerNote: Boolean,
    val noteText: String,
    val hasConsumerNote: Boolean,
    val consumerNoteText: String,
    val hasTaxNote: Boolean,
    val taxNoteTitle: String,
    val taxNoteText: String,
)

/** The three footer columns as printed lines, raw; the template fits [contact] into its column
 *  with its own size ladder — [register]/[management] print at the column's fixed size. */
data class TypstFooter(val contact: List<TypstFooterLine>, val register: List<String>, val management: List<String>)

data class TypstFooterLine(val text: String, val muted: Boolean)

/**
 * Model → `data.json` shape, field for field; no formatting happens here.
 *
 * @param qrFile the GiroCode SVG's filename (`"qr.svg"`) when the renderer staged one alongside
 *   this data file, `null` otherwise. Defaulted so callers that never render a code (most tests,
 *   the parity path) need no change.
 */
internal fun InvoiceReportModel.toTypstData(language: String, qrFile: String? = null): TypstInvoiceData = TypstInvoiceData(
    language = language,
    document = TypstDocument(subject = header.betreff, pageLabel = pageLabel),
    sender = TypstSender(name = header.senderNameRaw, returnLine = header.returnLine),
    recipient = TypstRecipient(
        header.recipientName, header.recipientStreet, header.recipientCityLine, header.recipientCountryLine,
    ),
    invoiceInfo = rinfo.rows.filledRows(),
    senderInfo = pinfo.rows.filledRows(),
    freeText = TypstFreeText(
        present = header.hasFreeText,
        afterItems = header.freeTextAfterItems,
        lines = if (header.hasFreeText) freeTextLines(header.freeText) else emptyList(),
    ),
    items = TypstItems(
        detailColumns = hasItemDetailColumns,
        taxColumn = taxRows.size > 1,
        headers = tableHeaders.toTypst(),
        rows = items.map { it.toTypst() },
        unitFootnotes = items.unitFootnotes(language),
    ),
    taxBreakdown = TypstTaxBreakdown(
        present = taxRows.size > 1,
        headers = TypstTaxHeaders(summary.taxHeaderRate, summary.taxHeaderNet, summary.taxHeaderVat),
        rows = taxRows.map { TypstTaxRow(it.rate, it.net, it.vat) },
    ),
    totals = TypstTotals(
        netLabel = summary.labelNet, net = summary.netTotal,
        vatLabel = summary.labelVat, vat = summary.vatTotal,
        grossLabel = summary.labelGross, gross = summary.grossTotal,
    ),
    payment = payment.toTypst(summary.grossTotal, qrFile),
    notes = TypstNotes(
        hasNote = notes.hasFooterNote || notes.hasConsumerNote, noteTitle = notes.noteTitle,
        hasSellerNote = notes.hasFooterNote, noteText = notes.footerNote,
        hasConsumerNote = notes.hasConsumerNote, consumerNoteText = notes.consumerNote,
        hasTaxNote = notes.reverseCharge, taxNoteTitle = notes.reverseChargeNoteTitle, taxNoteText = notes.reverseChargeNoteText,
    ),
    footer = TypstFooter(
        contact = footer.contactLines.map { TypstFooterLine(it.text, it.muted) },
        register = senderFacts.registerLines(),
        management = managementLines(),
    ),
)

/**
 * The info boxes on paper hold only rows with a value: the Typst head sizes each grey box to its
 * rows, so a label without a value would print as an empty line, not as a gap the reader ignores.
 */
private fun List<ReportInfoRow>.filledRows(): List<TypstInfoRow> =
    filter { it.value.isNotBlank() }.map { TypstInfoRow(it.label, it.value) }

internal fun freeTextLines(source: String): List<TypstFreeTextLine> = FreeTextMarkup.toLines(source).map { line ->
    TypstFreeTextLine(
        level = line.level,
        mark = line.mark,
        runs = line.runs.map { run ->
            TypstFreeTextRun(
                text = run.text,
                bold = FreeTextFormat.BOLD in run.formats,
                italic = FreeTextFormat.ITALIC in run.formats,
                underline = FreeTextFormat.UNDERLINE in run.formats,
                strike = FreeTextFormat.STRIKE in run.formats,
            )
        },
    )
}

/**
 * Print-unit symbols that need a footnote explaining them: today only "lfm" (the German trade
 * convention "laufender Meter"), the one non-SI abbreviation this invoice prints as a symbol — a
 * further convention abbreviation is added here by one more label key, never by template logic.
 */
private val unitFootnoteKeysBySymbol: Map<String, String> = mapOf("lfm" to "table.unitFootnote.lfm")

/** One line per distinct unit symbol on [this] that needs explaining, in [unitFootnoteKeysBySymbol]
 *  order — checked against every row (main and trip/sub rows alike), not just the main item rows,
 *  since a trip's per-expense unit label ([ReportItemRow.unitLabel]) can carry "lfm" too. */
private fun List<ReportItemRow>.unitFootnotes(language: String): List<String> =
    unitFootnoteKeysBySymbol.filterKeys { symbol -> any { it.unitLabel.trim() == symbol } }
        .values.map { key -> Labels.label(key, language) }

private fun ReportTableHeaders.toTypst() = TypstItemHeaders(
    pos = pos, description = desc, quantity = qty, unitPrice = price, net = net, vatAmount = vatAmt, vat = vat,
    unit = unit, period = period,
)

private fun ReportItemRow.toTypst() = TypstItemRow(
    pos = pos,
    description = description,
    itemDescription = itemDescription,
    idLines = idLines.map { TypstIdLine(label = it.label, value = it.value) },
    buyerArticleDescriptionLine = buyerArticleDescriptionLine,
    tripSubline = tripSubline,
    subRow = subRow,
    quantity = qtyPlain,
    unitLabel = unitLabel,
    unitPrice = price,
    net = net,
    vatPercent = vatPct,
    servicePeriod = servicePeriod,
)

private fun ReportPaymentBlock.toTypst(grossTotal: String, qrFile: String?) = TypstPayment(
    present = hasPaymentBlock,
    title = title,
    amountLabel = labelAmount,
    amount = grossTotal,
    dueLabel = labelDue,
    dueText = dueText,
    hasDue = hasPaymentTerms,
    cashDiscountText = cashDiscountText,
    hasCashDiscount = hasCashDiscount,
    bankLabel = labelBank,
    bankName = bankName,
    hasBank = hasBank,
    iban = iban,
    hasIban = hasIban,
    bic = bic,
    hasBic = hasBic,
    referenceLabel = labelRef,
    reference = invoiceNumber,
    hint = hint,
    showHint = !hasIban,
    qrFile = qrFile,
    qrCaption = labelQr,
)

/** Footer column 2 — register number, register court, and jurisdiction. */
private fun ReportSenderFactsBlock.registerLines(): List<String> = listOfNotNull(
    registerNo.takeIf { hasRegisterNo },
    "$registerCourtLabel $registerCourt".takeIf { hasRegisterCourt },
    "$jurisdictionLabel $jurisdiction".takeIf { hasJurisdiction },
)

/** Footer column 3 — managing director, IBAN (grouped), bank name. */
private fun InvoiceReportModel.managementLines(): List<String> = listOfNotNull(
    "${senderFacts.managingDirectorLabel} ${senderFacts.managingDirector}".takeIf { senderFacts.hasManagingDirector },
    payment.iban.takeIf { payment.hasIban },
    payment.bankName.takeIf { payment.hasBank },
)
