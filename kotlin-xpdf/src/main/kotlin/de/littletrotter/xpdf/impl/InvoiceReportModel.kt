package de.littletrotter.xpdf.impl

/**
 * Typed shape of everything the invoice layout consumes. [InvoiceReportModelBuilder] is the only
 * place that translates an `InvoiceDto` into this shape; [TypstInvoiceRenderer] serialises it as
 * the template's `data.json` (`toTypstData`). Values are pre-formatted plain text — the template
 * composes cells, weights and sizes itself.
 */
data class InvoiceReportModel(
    val header: ReportHeaderBlock,
    val rinfo: ReportInfoBox,
    val pinfo: ReportInfoBox,
    val tableHeaders: ReportTableHeaders,
    val items: List<ReportItemRow>,
    /**
     * True when [items] carries a service period or a seller/buyer article number for at least
     * one row — the template's own switch between its two item-table variants (
     * "detail" (Menge/Einheit/Zeitraum/Art.-Nr./Kd.-Art.-Nr. as their
     * own columns) when true, "plain" (Pos/Beschreibung/Menge/Einzelpreis/Netto only) when false.
     * A unit alone does not count (PO decision): every item carries one, so it cannot
     * tell an ordinary invoice from a detailed one; the plain variant's Menge column shows the
     * unit together with the quantity instead of losing it.
     */
    val hasItemDetailColumns: Boolean,
    val taxRows: List<ReportTaxRow>,
    val summary: ReportSummaryBlock,
    val payment: ReportPaymentBlock,
    val notes: ReportNotesBlock,
    val footer: ReportFooterBlock,
    val senderFacts: ReportSenderFactsBlock,
    val pageLabel: String,
)

/** Briefkopf / Adressfenster / Betreff (§6). */
data class ReportHeaderBlock(
    /** The sender-profile name exactly as entered; the template fits its own letterhead text. */
    val senderNameRaw: String,
    val returnLine: String,
    val recipientName: String,
    val recipientStreet: String,
    val recipientCityLine: String,
    /** Blank for a domestic address; see [InvoiceReportModelBuilder]. */
    val recipientCountryLine: String,
    val betreff: String,
    val freeText: String,
    val hasFreeText: Boolean,
    // A free text too tall for the title band prints as its own band after the items table;
    // the rule and its reasoning live in FreeTextLayout.kt.
    val freeTextAfterItems: Boolean,
)

/**
 * One label/value info box — rinfo (invoice/recipient facts) and pinfo (sender contact facts)
 * share this shape; only how each is assembled differs (builder-private).
 */
data class ReportInfoBox(val rows: List<ReportInfoRow>)

/** One label/value line of an info box. */
data class ReportInfoRow(val label: String, val value: String)

/** Tabellenkopf der Positionstabelle. */
data class ReportTableHeaders(
    val pos: String,
    val desc: String,
    val qty: String,
    val price: String,
    val net: String,
    val vatAmt: String,
    val vat: String,
    val unit: String,
    val period: String,
)

/**
 * One rendered position-table row — all values pre-formatted (G.3-G.6). The template composes the
 * description cell itself from [description], [itemDescription], [idLines],
 * [buyerArticleDescriptionLine] and [tripSubline]; [qtyPlain] and [unitLabel] stay apart so the
 * unit can print as a muted symbol next to the quantity.
 */
data class ReportItemRow(
    val pos: String,
    val price: String,
    val net: String,
    val vatAmt: String,
    val vatPct: String,
    /** BT-153. */
    val description: String,
    /** BT-154 *Item description* — the free-text elaboration next to BT-153; blank when not
     *  captured. */
    val itemDescription: String,
    /** The quantity alone (G.3-formatted), without the unit label. */
    val qtyPlain: String,
    val unitLabel: String,
    /** BG-26 Leistungszeitraum: "from – to" / "to" / "from" / blank, mirroring the header's
     *  servicePeriod combination rule (see `buildContext`/formatServicePeriod). */
    val servicePeriod: String,
    /** The first two small-print sublines of the description cell — the bare seller article
     *  number and "Ref. <buyer article number>" — in print order, up to two entries; empty when
     *  neither number is captured. [ReportItemIdLine.label] is blank for the bare seller number;
     *  label and value stay apart so the template can print them in a different weight/colour
     *  without re-parsing a combined string. */
    val idLines: List<ReportItemIdLine> = emptyList(),
    /** The third small-print subline, italic — the recipient-specific
     *  article description; blank unless [idLines] also carries the "Ref." line (a description with
     *  nothing to describe is never printed). */
    val buyerArticleDescriptionLine: String = "",
    /** The trip row's "Zweck: … / Datum: …" second line, plain; blank for every other row. */
    val tripSubline: String = "",
    /** True for a trip's indented expense and per-diem rows, which print muted and smaller. */
    val subRow: Boolean = false,
)

/** One [ReportItemRow.idLines] entry: [label] blank for the bare seller article number (BT-155),
 *  or the "Ref." wording (`item.buyerId`) for the buyer's own article number (BT-156) — kept apart
 *  from [value] so a template can print the label muted and the value in its normal ink without
 *  parsing a pre-joined string back apart. */
data class ReportItemIdLine(val label: String, val value: String)

/** One MwSt.-Aufschlüsselungszeile. */
data class ReportTaxRow(val rate: String, val net: String, val vat: String)

/** Aufschlüsselung/Summen (§7). */
data class ReportSummaryBlock(
    val taxHeaderRate: String,
    val taxHeaderNet: String,
    val taxHeaderVat: String,
    val labelNet: String,
    val labelVat: String,
    val labelGross: String,
    val netTotal: String,
    val vatTotal: String,
    val grossTotal: String,
)

/** Zahlungsblock (§8). */
data class ReportPaymentBlock(
    /**
     * `false` on a cancellation document, which prints no payment block at all — no title, no amount
     * row, no bank/IBAN/BIC and no Verwendungszweck. A document that annuls another must never
     * name a way to pay it; [hasPaymentTerms] alone only dropped the due line and left the rest
     * of the block, headline included, on the page.
     */
    val hasPaymentBlock: Boolean,
    val title: String,
    val labelAmount: String,
    val labelDue: String,
    val dueText: String,
    val hasPaymentTerms: Boolean,
    /** Skonto sentence under [dueText]; empty/`false` whenever the invoice has no computed
     *  cash discount or is a cancellation (see [InvoiceReportContextBuilder.buildCashDiscountText]). */
    val cashDiscountText: String = "",
    val hasCashDiscount: Boolean = false,
    val labelBank: String,
    val bankName: String,
    val hasBank: Boolean,
    val iban: String,
    val hasIban: Boolean,
    val bic: String,
    val hasBic: Boolean,
    val labelRef: String,
    val invoiceNumber: String,
    val hint: String,
    /** Label `payment.qr`. Default `""` for callers that never print a GiroCode. */
    val labelQr: String = "",
)

/** Rechnungshinweis / Reverse-Charge-Hinweis. */
data class ReportNotesBlock(
    val noteTitle: String,
    val footerNote: String,
    val hasFooterNote: Boolean,
    val consumerNote: String,
    val hasConsumerNote: Boolean,
    val reverseChargeNoteTitle: String,
    val reverseChargeNoteText: String,
    val reverseCharge: Boolean,
)

/**
 * Seitenfuß, Spalte 1 (Kontakt) — contact data only, one line per present value: [contactLines]
 * carries the printed lines in order (seat, phone, email, website — present ones only), raw text
 * and mutedness only; the template decides how each line fits its column. The seat line prints
 * only alongside a commercial register number — its own qualifier, unlike phone/email/website,
 * which print whenever their value is present.
 *
 * Column 2 (Registergericht/Gerichtsstand) and column 3 (Geschäftsführung; IBAN/BIC/Bankname
 * already travel via the payment block) are built from [ReportSenderFactsBlock]'s atomic values
 * and their labels.
 */
data class ReportFooterBlock(val contactLines: List<ReportContactLine>)

/** One footer contact line, raw. */
data class ReportContactLine(val text: String, val muted: Boolean)

/**
 * The sender-profile values the footer's register and management columns print, as atomic values
 * with a presence flag each, plus the printed labels ([Labels]-resolved, already in the target
 * language).
 */
data class ReportSenderFactsBlock(
    val registerNo: String,
    val hasRegisterNo: Boolean,
    val registerCourt: String,
    val hasRegisterCourt: Boolean,
    val registerCourtLabel: String,
    val jurisdiction: String,
    val hasJurisdiction: Boolean,
    val jurisdictionLabel: String,
    val managingDirector: String,
    val hasManagingDirector: Boolean,
    val managingDirectorLabel: String,
)
