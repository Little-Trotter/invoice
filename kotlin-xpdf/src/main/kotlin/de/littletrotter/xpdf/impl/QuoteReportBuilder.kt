package de.littletrotter.xpdf.impl

import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.documentjob.QuoteDocumentItem
import de.littletrotter.shared.documentjob.QuoteDocumentParty
import de.littletrotter.shared.documentjob.QuoteDocumentPayload
import de.littletrotter.shared.pdf.normalizeWebsiteForDisplay
import java.math.BigDecimal
import java.text.MessageFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** BT codes of the business-profile references an offer prints in its info card. */
private const val BT_BUYER_REFERENCE = 10
private const val BT_PROJECT_NUMBER = 11
private const val BT_PURCHASE_ORDER_REFERENCE = 13

/** The sender's own country: an address there is domestic and prints no country line. */
private const val DOMESTIC_COUNTRY = "DE"

private const val IBAN_GROUP_SIZE = 4

/**
 * Turns the offer payload into the preformatted `data.json` model of `standard-quote.typ`.
 * Amounts are formatted here, in the document language, from the strings the offer domain
 * computed; nothing is calculated, so the figures on paper are the figures the domain produced.
 */
internal class QuoteReportBuilder {

    fun build(payload: QuoteDocumentPayload): TypstQuoteData {
        val language = if (payload.language == "en") "en" else "de"
        val format = QuoteFormats(language, printedCurrencyUnit(payload.currency))
        fun l(key: String) = Labels.label(key, language)
        fun q(key: String) = QuoteLabels.label(key, language)
        val rows = payload.items.map { itemRow(it, language, format) }
        return TypstQuoteData(
            language = language,
            document = TypstDocument(subject = subjectLine(payload, language), pageLabel = l("page.prefix") + " "),
            sender = TypstSender(name = payload.seller.name, returnLine = returnLine(payload.seller)),
            recipient = recipient(payload.customer, language),
            quoteInfo = quoteInfo(payload, language, format),
            senderInfo = senderInfo(payload.seller, language),
            introText = freeText(payload.introText, afterItems = false),
            closingText = freeText(payload.closingText, afterItems = true),
            items = TypstItems(
                detailColumns = false,
                taxColumn = payload.totals.groups.size > 1,
                headers = tableHeaders(payload.grossPrices, language),
                rows = rows,
                unitFootnotes = if (rows.any { it.unitLabel.trim() == LFM }) listOf(l("table.unitFootnote.lfm")) else emptyList(),
            ),
            taxBreakdown = TypstTaxBreakdown(
                present = payload.totals.groups.size > 1,
                headers = TypstTaxHeaders(l("breakdown.rate"), l("breakdown.net"), l("breakdown.vat")),
                rows = payload.totals.groups.map { TypstTaxRow(format.percent(it.rate), format.money(it.base), format.money(it.vat)) },
            ),
            totals = TypstTotals(
                netLabel = l("total.net"),
                net = format.money(payload.totals.net),
                vatLabel = vatLabel(payload, language, format),
                vat = format.money(payload.totals.vat),
                grossLabel = q("total.gross"),
                gross = format.money(payload.totals.gross),
            ),
            note = TypstQuoteNote(
                present = !payload.noteText.isNullOrBlank(),
                title = l("note.title"),
                lines = payload.noteText?.takeIf { it.isNotBlank() }?.let(::freeTextLines).orEmpty(),
            ),
            validity = TypstQuoteValidity(
                title = q("validity.title"),
                lines = listOf(
                    MessageFormat.format(q("validity.text"), format.date(payload.validUntil)),
                    q("validity.clause"),
                    q(if (payload.grossPrices) "price.gross" else "price.net"),
                ),
            ),
            footer = footer(payload.seller, language),
        )
    }

    private fun subjectLine(payload: QuoteDocumentPayload, language: String): String {
        val number = payload.quoteNumber?.takeIf { it.isNotBlank() }
        val heading = if (number != null) "${QuoteLabels.label("subject.prefix", language)} $number" else QuoteLabels.label("subject.noNumber", language)
        val subject = payload.subject?.takeIf { it.isNotBlank() }
        return if (subject != null) "$heading – $subject" else heading
    }

    private fun quoteInfo(payload: QuoteDocumentPayload, language: String, format: QuoteFormats): List<TypstInfoRow> {
        fun l(key: String) = Labels.label(key, language)
        fun q(key: String) = QuoteLabels.label(key, language)
        val referenceLabels = mapOf(
            BT_BUYER_REFERENCE to l("info.buyerReference"),
            BT_PURCHASE_ORDER_REFERENCE to l("info.purchaseOrderReference"),
            BT_PROJECT_NUMBER to l("info.projectNumber"),
        )
        val references = payload.references
            .filter { it.btCode in referenceLabels && it.value.isNotBlank() }
            .sortedBy { referenceLabels.keys.indexOf(it.btCode) }
            .map { TypstInfoRow(referenceLabels.getValue(it.btCode), it.value) }
        return listOfNotNull(
            payload.quoteNumber?.takeIf { it.isNotBlank() }?.let { TypstInfoRow(q("info.quoteNo"), it) },
            TypstInfoRow(q("info.quoteDate"), format.date(payload.quoteDate)),
            TypstInfoRow(q("info.validUntil"), format.date(payload.validUntil)),
            payload.customer.customerNumber?.takeIf { it.isNotBlank() }?.let { TypstInfoRow(l("info.customerNo"), it) },
        ) + references
    }

    private fun senderInfo(seller: QuoteDocumentParty, language: String): List<TypstInfoRow> = listOfNotNull(
        seller.vatId?.takeIf { it.isNotBlank() }?.let { TypstInfoRow(Labels.label("sender.vatId", language), it) },
        seller.taxNumber?.takeIf { it.isNotBlank() }?.let { TypstInfoRow(Labels.label("sender.taxNumber", language), it) },
    )

    private fun tableHeaders(gross: Boolean, language: String): TypstItemHeaders {
        fun l(key: String) = Labels.label(key, language)
        fun q(key: String) = QuoteLabels.label(key, language)
        return TypstItemHeaders(
            pos = l("table.pos"),
            description = l("table.description"),
            quantity = l("table.quantity"),
            unitPrice = if (gross) q("table.unitPrice.gross") else l("table.unitPrice"),
            net = if (gross) q("table.amount.gross") else l("table.netAmount"),
            vatAmount = l("table.taxAmount"),
            vat = l("table.tax"),
            unit = l("table.unit"),
            period = l("table.servicePeriod"),
        )
    }

    private fun itemRow(item: QuoteDocumentItem, language: String, format: QuoteFormats): TypstItemRow {
        val quantity = BigDecimal(item.quantity)
        val article = item.articleNumber?.trim().orEmpty()
        val reference = item.customerReference?.trim().orEmpty().takeUnless { it == article }.orEmpty()
        val idLines = buildList {
            if (article.isNotEmpty()) add(TypstIdLine(label = "", value = article))
            if (reference.isNotEmpty()) add(TypstIdLine(label = Labels.label("item.buyerId", language), value = reference))
        }
        return TypstItemRow(
            pos = item.position.toString(),
            description = item.description,
            itemDescription = "",
            idLines = idLines,
            buyerArticleDescriptionLine = customerArticleLine(item, reference),
            tripSubline = "",
            subRow = false,
            quantity = format.plain(quantity),
            unitLabel = InvoiceItemUnit.valueOf(item.unit).invoiceLabel(english = language == "en", plural = quantity.compareTo(BigDecimal.ONE) != 0),
            unitPrice = format.money(item.unitPrice),
            net = format.money(item.lineAmount),
            vatPercent = format.percent(item.vatRate),
            servicePeriod = "",
        )
    }

    /** The customer's own article name sits under the reference line and only prints with it,
     *  and only when it says something the position description does not already. */
    private fun customerArticleLine(item: QuoteDocumentItem, reference: String): String {
        val name = item.customerArticleName?.trim().orEmpty()
        if (name.isEmpty() || reference.isEmpty()) return ""
        return if (normalize(name) == normalize(item.description)) "" else name
    }

    private fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ").lowercase()

    private fun freeText(source: String?, afterItems: Boolean): TypstFreeText {
        val text = source?.takeIf { it.isNotBlank() } ?: return TypstFreeText(present = false, afterItems = afterItems, lines = emptyList())
        return TypstFreeText(present = true, afterItems = afterItems || printsFreeTextAfterItems(text), lines = freeTextLines(text))
    }

    private fun vatLabel(payload: QuoteDocumentPayload, language: String, format: QuoteFormats): String {
        val groups = payload.totals.groups
        val single = groups.singleOrNull()
        return if (single != null && BigDecimal(single.rate).signum() != 0) {
            MessageFormat.format(Labels.label("total.vat.withRate", language), format.percent(single.rate))
        } else {
            Labels.label("total.vat", language)
        }
    }

    private fun returnLine(seller: QuoteDocumentParty): String {
        val place = listOfNotNull(seller.postalCode, seller.city).filter { it.isNotBlank() }.joinToString(" ")
        return listOf(seller.name, seller.street.orEmpty(), place).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun recipient(customer: QuoteDocumentParty, language: String): TypstRecipient {
        val cityLine = listOfNotNull(customer.postalCode, customer.city).filter { it.isNotBlank() }.joinToString(" ")
        val country = customer.countryCode?.trim().orEmpty()
        val foreign = country.isNotEmpty() && !country.equals(DOMESTIC_COUNTRY, ignoreCase = true) && cityLine.isNotBlank()
        val countryLine = if (foreign) {
            val locale = Locale.forLanguageTag(language)
            Locale.of("", country.uppercase(Locale.ROOT)).getDisplayCountry(locale).uppercase(locale)
        } else {
            ""
        }
        return TypstRecipient(customer.name, customer.street.orEmpty(), cityLine, countryLine)
    }

    private fun footer(seller: QuoteDocumentParty, language: String): TypstFooter {
        fun l(key: String) = Labels.label(key, language)
        val register = seller.commercialRegisterNumber?.takeIf { it.isNotBlank() }
        val seat = seller.city?.takeIf { register != null && it.isNotBlank() }
        val contact = listOfNotNull(
            seat?.let { TypstFooterLine(l("footer.seat") + " " + it, muted = false) },
            seller.phone?.takeIf { it.isNotBlank() }?.let { TypstFooterLine(l("footer.phone") + " " + it, muted = true) },
            seller.email?.takeIf { it.isNotBlank() }?.let { TypstFooterLine(it, muted = true) },
            seller.website?.takeIf { it.isNotBlank() }?.let { TypstFooterLine(normalizeWebsiteForDisplay(it), muted = true) },
        )
        val management = listOfNotNull(
            seller.managingDirector?.takeIf { it.isNotBlank() }?.let { l("footer.managingDirector") + " " + it },
            seller.iban?.takeIf { it.isNotBlank() }?.chunked(IBAN_GROUP_SIZE)?.joinToString(" "),
            seller.bankName?.takeIf { it.isNotBlank() },
        )
        return TypstFooter(
            contact = contact,
            register = listOfNotNull(register, seller.registerCourt?.takeIf { it.isNotBlank() }?.let { l("footer.registerCourt") + " " + it }),
            management = management,
        )
    }
}

/** Number and date formatting of one document language; German and English differ in grouping,
 *  decimal separator and date order. */
internal class QuoteFormats(language: String, private val currencyUnit: String) {
    private val locale: Locale = if (language == "en") Locale.UK else Locale.GERMANY
    private val dateFormat: DateTimeFormatter =
        if (language == "en") DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH) else DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private val moneyFormat: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = true
    }
    private val plainFormat: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = true
        maximumFractionDigits = PLAIN_FRACTION_DIGITS
    }

    fun money(value: String): String = "${moneyFormat.format(BigDecimal(value))} $currencyUnit"

    fun plain(value: BigDecimal): String = plainFormat.format(value)

    fun percent(value: String): String = "${plainFormat.format(BigDecimal(value))} %"

    fun date(isoDate: String): String = LocalDate.parse(isoDate).format(dateFormat)

    private companion object {
        const val PLAIN_FRACTION_DIGITS = 3
    }
}

private val WHITESPACE = Regex("\\s+")
private const val LFM = "lfm"
