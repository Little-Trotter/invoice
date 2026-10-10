package de.littletrotter.xpdf.impl

/**
 * The `data.json` contract of the shipped `standard-quote.typ`: everything the template lays
 * out, as preformatted strings and presence flags — the template never calculates, rounds or
 * parses markup. Letterhead, address, table and totals blocks reuse the invoice template's value
 * types, so both templates fit their letterhead and footer identically. Jackson serialises the
 * property names verbatim, so a rename here is a template change.
 */
data class TypstQuoteData(
    val language: String,
    val document: TypstDocument,
    val sender: TypstSender,
    val recipient: TypstRecipient,
    val quoteInfo: List<TypstInfoRow>,
    val senderInfo: List<TypstInfoRow>,
    val introText: TypstFreeText,
    val closingText: TypstFreeText,
    val items: TypstItems,
    val taxBreakdown: TypstTaxBreakdown,
    val totals: TypstTotals,
    val note: TypstQuoteNote,
    val validity: TypstQuoteValidity,
    val footer: TypstFooter,
)

/** The offer's free-text remark; an offer has no payment terms, only this note. */
data class TypstQuoteNote(val present: Boolean, val title: String, val lines: List<TypstFreeTextLine>)

/** The validity sentence, the non-binding clause and the VAT statement of the prices, in print order. */
data class TypstQuoteValidity(val title: String, val lines: List<String>)
