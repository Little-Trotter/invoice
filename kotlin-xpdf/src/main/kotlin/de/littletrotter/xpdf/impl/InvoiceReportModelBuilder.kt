package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.pdf.normalizeWebsiteForDisplay
import de.littletrotter.shared.wording.WordingProfile
import java.util.Locale

/**
 * Builds a typed [InvoiceReportModel] from an [InvoiceDto]. Number/date formatting
 * and pagination stay [de.littletrotter.xpdf.impl.InvoiceReportContextBuilder.buildContext]'s job — the single
 * source of truth the class doc there documents — this builder reuses that typed [RenderContext]
 * (formerly a `Map<String, Any?>`) but never lets it escape: everything from here on (row
 * lists, info boxes, the assembled blocks) is a typed data class, so the renderer never sees
 * `invoiceMap`/`senderMap`/`recipientMap`.
 */
class InvoiceReportModelBuilder {

    private val contextSource = InvoiceReportContextBuilder()

    fun build(invoice: InvoiceDto): InvoiceReportModel {
        val ctx = contextSource.buildContext(invoice)
        val invoiceMap = ctx.invoice
        val senderMap = ctx.sender
        val recipientMap = ctx.recipient

        // InvoiceReportContextBuilder.buildContext sets "language" unconditionally from
        // resolveRenderLanguage(), which returns String, never null — a typed property read
        // instead of an untyped map lookup, no cast needed either way.
        val language = invoiceMap.language.takeIf { it == "en" } ?: "de"
        // The wording profile active when this InvoiceDto was mapped (render-time, never
        // retroactive); read back the same way "currencyCode" is above.
        val wordingProfile = invoiceMap.wordingProfile?.let(WordingProfile::fromCodeOrNull) ?: WordingProfile.DEFAULT
        fun l(key: String) = Labels.label(key, language)

        // Built up front (not inline in the InvoiceReportModel(...) call below) — `labelVat`
        // needs the per-item VAT rates to decide whether it can name the single rate.
        val items = buildItems(invoiceMap, language, wordingProfile)

        return InvoiceReportModel(
            header = buildHeaderBlock(invoiceMap, senderMap, recipientMap, language),
            rinfo = rinfoBox(invoiceMap, recipientMap, language),
            pinfo = pinfoBox(senderMap, language),
            tableHeaders = buildTableHeaders(language),
            items = items,
            hasItemDetailColumns = hasItemDetailColumns(items),
            taxRows = buildTaxRows(invoiceMap),
            summary = buildSummaryBlock(invoiceMap, language, items),
            // Zahlungsblock — payment.title/note.title/taxnote.title are already upper-case in
            // the bundle (Umsetzung §2: casing lives in the bundle, not `.uppercase()` in code).
            payment = buildPaymentBlock(invoiceMap, senderMap, language),
            notes = buildNotesBlock(invoiceMap, senderMap, language),
            footer = buildFooterBlock(senderMap, language),
            senderFacts = buildSenderFactsBlock(senderMap, language),
            pageLabel = l("page.prefix") + " ",
        )
    }

    // ── Kopf / Betreff / Freitext ──

    private fun buildHeaderBlock(
        invoiceMap: InvoiceRenderContext,
        senderMap: SenderRenderContext,
        recipientMap: RecipientRenderContext,
        language: String,
    ): ReportHeaderBlock {
        fun l(key: String) = Labels.label(key, language)
        val number = invoiceMap.number
        val hasFreeText = isPresent(invoiceMap.freeText)
        // The document names itself: a storno document says so in its subject line,
        // everything else reads as an ordinary invoice. Driven by the DTO's storno flag,
        // the single source of truth, rather than by a type code compared here — the code
        // moved 381 -> 384 with PO decision 1 (2026-09-02) and this comparison did not.
        val storno = invoiceMap.storno
        return ReportHeaderBlock(
            senderNameRaw = senderMap.name,
            returnLine = buildReturnLine(senderMap),
            recipientName = recipientMap.name,
            recipientStreet = recipientMap.street,
            recipientCityLine = buildRecipientCityLine(recipientMap),
            recipientCountryLine = buildRecipientCountryLine(recipientMap, language),
            betreff = l(if (storno) "subject.prefix.creditNote" else "subject.prefix") + " " + number,
            freeText = orEmpty(invoiceMap.freeText),
            hasFreeText = hasFreeText,
            freeTextAfterItems = hasFreeText && printsFreeTextAfterItems(orEmpty(invoiceMap.freeText)),
        )
    }

    /** Only a service period switches the whole table to the "detail" variant: a trade invoice
     *  that carries per-item service periods needs the extra columns to show them. An article
     *  number alone stays on the "plain" variant, whose description cell already prints the
     *  seller/buyer article number as an identifier subline — the standard way an invoice shows
     *  it — so the detail variant's own Art.-Nr./Kd.-Art.-Nr. columns would otherwise make every
     *  invoice with an article number switch variant and that identifier line never print. */
    private fun hasItemDetailColumns(items: List<ReportItemRow>): Boolean = items.any {
        it.servicePeriod.isNotBlank()
    }

    private fun buildTableHeaders(language: String): ReportTableHeaders {
        fun l(key: String) = Labels.label(key, language)
        return ReportTableHeaders(
            pos = l("table.pos"),
            desc = l("table.description"),
            qty = l("table.quantity"),
            price = l("table.unitPrice"),
            net = l("table.netAmount"),
            vatAmt = l("table.taxAmount"),
            vat = l("table.tax"),
            unit = l("table.unit"),
            period = l("table.servicePeriod"),
        )
    }

    private fun buildSummaryBlock(invoiceMap: InvoiceRenderContext, language: String, items: List<ReportItemRow>): ReportSummaryBlock {
        fun l(key: String) = Labels.label(key, language)
        // The printed unit of the invoice's own currency (never the language), resolved
        // from the ISO code once at the PDF boundary; EUR is rendered as the compact € symbol.
        // "currencyCode" is Currency.ofOrdinal(...).code, one of the enum's own 3-letter
        // codes ("EUR", "CHF", ...), always written and never blank, so `.ifBlank { ... }`'s
        // fallback could never fire; dropped rather than kept as untestable dead code.
        val currencyUnit = printedCurrencyUnit(invoiceMap.currencyCode)
        return ReportSummaryBlock(
            taxHeaderRate = l("breakdown.rate"),
            taxHeaderNet = l("breakdown.net"),
            taxHeaderVat = l("breakdown.vat"),
            labelNet = l("total.net"),
            labelVat = vatTotalLabel(items, language),
            labelGross = l("total.gross"),
            netTotal = amount(invoiceMap.netTotal, currencyUnit),
            vatTotal = amount(invoiceMap.vatTotal, currencyUnit),
            grossTotal = amount(invoiceMap.grossTotal, currencyUnit),
        )
    }

    private fun buildPaymentBlock(invoiceMap: InvoiceRenderContext, senderMap: SenderRenderContext, language: String): ReportPaymentBlock {
        fun l(key: String) = Labels.label(key, language)
        val storno = invoiceMap.storno
        return ReportPaymentBlock(
            hasPaymentBlock = !storno,
            title = l("payment.title"),
            labelAmount = l("payment.amount"),
            labelDue = l("payment.due"),
            dueText = orEmpty(invoiceMap.paymentDueText),
            hasPaymentTerms = invoiceMap.hasPaymentTerms,
            cashDiscountText = orEmpty(invoiceMap.cashDiscountText),
            hasCashDiscount = isPresent(invoiceMap.cashDiscountText),
            labelBank = l("payment.bank"),
            bankName = orEmpty(senderMap.bankName),
            hasBank = isPresent(senderMap.bankName),
            // Grouped in fours ("DE89 3704 …") wherever IBAN prints, footer column 3 included.
            iban = groupIban(orEmpty(senderMap.iban)),
            hasIban = isPresent(senderMap.iban),
            bic = orEmpty(senderMap.bic),
            hasBic = isPresent(senderMap.bic),
            labelRef = l("payment.reference"),
            invoiceNumber = invoiceMap.number,
            hint = l("payment.hint.noBankDetails"),
            labelQr = l("payment.qr"),
        )
    }

    private fun buildNotesBlock(invoiceMap: InvoiceRenderContext, senderMap: SenderRenderContext, language: String): ReportNotesBlock {
        fun l(key: String) = Labels.label(key, language)
        // BT-120 from the tax rule wins over the bundle's German
        // standard text, which is only the fallback for a rule without a maintained exemption
        // reason text (composeTaxNote's ifBlank branch, preserved exactly). On an English
        // document the German original is kept and the English standard text is appended after a
        // newline — this box only ever shows for Reverse Charge (see `notes.reverseCharge`
        // below), so the category is always "AE"; the code is whatever the AE item's own tax
        // rule carries (VATEX-EU-AE in every seeded rule, but not hardcoded here).
        val rcNoteText = orEmpty(
            composeTaxNote(
                germanText = invoiceMap.reverseChargeNoteText,
                classification = TaxExemptionClassification(
                    category = "AE",
                    vatexCode = invoiceMap.reverseChargeExemptionReasonCode,
                ),
                language = language,
                separator = "\n",
            ),
        )
        return ReportNotesBlock(
            noteTitle = l("note.title"),
            footerNote = orEmpty(senderMap.footerNote),
            hasFooterNote = isPresent(senderMap.footerNote),
            consumerNote = orEmpty(invoiceMap.consumerNote),
            hasConsumerNote = isPresent(invoiceMap.consumerNote),
            reverseChargeNoteTitle = l("taxnote.title"),
            reverseChargeNoteText = rcNoteText,
            reverseCharge = invoiceMap.reverseCharge,
        )
    }

    // ── Footer / Briefkopf-Helfer ──

    private fun buildReturnLine(senderMap: SenderRenderContext): String {
        val name = senderMap.name
        val street = senderMap.street
        val postalCode = senderMap.postalCode
        val city = senderMap.city
        return listOf(name, street, if (postalCode.isBlank() && city.isBlank()) "" else "$postalCode $city".trim())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun buildRecipientCityLine(recipientMap: RecipientRenderContext): String =
        "${recipientMap.postalCode} ${recipientMap.city}".trim()

    /** DIN 5008: a foreign address names its destination country alone on the last line, in
     *  capitals; a domestic one (no country, or Germany) has no country line at all. The country
     *  arrives as an ISO 3166-1 alpha-2 code and is named in the document's language; any other
     *  value prints as given. Without a postal code and city there is no address line for the
     *  country to complete, so it is left out as well. */
    private fun buildRecipientCountryLine(recipientMap: RecipientRenderContext, language: String): String {
        val country = orEmpty(recipientMap.country).trim()
        val domestic = country.isBlank() || country.equals(DOMESTIC_COUNTRY, ignoreCase = true)
        if (domestic || buildRecipientCityLine(recipientMap).isBlank()) return ""
        val locale = Locale.forLanguageTag(language)
        val isIsoCode = country.length == 2 && country.all { it.isLetter() }
        val name = if (isIsoCode) Locale.of("", country.uppercase(Locale.ROOT)).getDisplayCountry(locale) else country
        return name.uppercase(locale)
    }

    private fun buildFooterBlock(senderMap: SenderRenderContext, language: String): ReportFooterBlock {
        val seat = resolvedSeat(senderMap)
        val registerNoPresent = isPresent(senderMap.commercialRegisterNumber)
        // The seat line is the one footer contact line with an extra gate beyond
        // "the value is present": it only prints alongside an actual register entry, the same
        // register-number condition column 2's own register-court/jurisdiction lines already key
        // off of ([ReportSenderFactsBlock.hasRegisterNo]).
        val hasSeat = registerNoPresent && seat.isNotBlank()
        val seatLine = if (hasSeat) Labels.label("footer.seat", language) + " " + seat else ""
        val phoneLine = if (isPresent(senderMap.phone)) Labels.label("footer.phone", language) + " " + orEmpty(senderMap.phone) else ""
        val emailLine = orEmpty(senderMap.email)
        val webLine = normalizeWebsiteForDisplay(orEmpty(senderMap.website))
        // The seat line prints in the text colour, the other three muted.
        val contactLines = listOf(seatLine to false, phoneLine to true, emailLine to true, webLine to true)
            .filter { (text, _) -> text.isNotBlank() }
            .map { (text, muted) -> ReportContactLine(text, muted) }
        return ReportFooterBlock(contactLines)
    }

    /** [SenderRenderContext.registeredSeat] with [SenderRenderContext.city] as its fallback
     *  (a blank seat prints the billing address's city instead). */
    private fun resolvedSeat(senderMap: SenderRenderContext): String =
        orEmpty(senderMap.registeredSeat).ifBlank { senderMap.city }

    private fun buildSenderFactsBlock(senderMap: SenderRenderContext, language: String): ReportSenderFactsBlock {
        fun l(key: String) = Labels.label(key, language)
        return ReportSenderFactsBlock(
            registerNo = orEmpty(senderMap.commercialRegisterNumber), hasRegisterNo = isPresent(senderMap.commercialRegisterNumber),
            registerCourt = orEmpty(senderMap.registerCourt), hasRegisterCourt = isPresent(senderMap.registerCourt),
            registerCourtLabel = l("footer.registerCourt"),
            jurisdiction = orEmpty(senderMap.courtOfJurisdiction), hasJurisdiction = isPresent(senderMap.courtOfJurisdiction),
            jurisdictionLabel = l("footer.jurisdiction"),
            managingDirector = orEmpty(senderMap.managingDirector), hasManagingDirector = isPresent(senderMap.managingDirector),
            managingDirectorLabel = l("footer.managingDirector"),
        )
    }

    /**
     * PO follow-up — the VAT-total label names the rate ("zzgl. 19 % Umsatzsteuer") when every
     * item shares exactly one rate, so a single-rate invoice reads without having to cross-check
     * the tax breakdown table. [buildTaxRows]/`taxBreakdown` can't answer this: the wire only
     * populates a breakdown for >1 distinct rate (see `buildContext`), so
     * for the single-rate case that this label targets there is no breakdown row to read at all —
     * the per-item [ReportItemRow.vatPct] (already formatted, e.g. "19 %") is the only source.
     * Mixed rates keep the generic label (there is no single rate to name); a single rate of
     * exactly 0 % also keeps it — an RC/exempt invoice's "zzgl. 0 % Umsatzsteuer" would be noise,
     * not information, since 0 % never distinguishes Reverse-Charge from steuerbefreit/nicht
     * steuerbar (the tax-note block already carries that distinction).
     */
    private fun vatTotalLabel(items: List<ReportItemRow>, language: String): String {
        fun l(key: String) = Labels.label(key, language)
        val distinctRates = items.map { it.vatPct }.filter { it.isNotBlank() }.distinct()
        val singleRate = distinctRates.singleOrNull()
        return if (singleRate != null && !isZeroRate(singleRate)) {
            // {0} is a MessageFormat placeholder per the bundle's own header comment.
            java.text.MessageFormat.format(l("total.vat.withRate"), singleRate)
        } else {
            l("total.vat")
        }
    }

    /** [rate] is already formatted (e.g. "19 %", "0 %") — parsed back to a number rather than
     *  string-compared against "0 %" so a locale-formatted zero (comma decimal separator) still
     *  matches. */
    private fun isZeroRate(rate: String): Boolean =
        rate.removeSuffix("%").trim().replace(",", ".").toDoubleOrNull() == 0.0

    /** "DE89370400440532013000" -> "DE89 3704 0044 0532 0130 00" — blank stays blank,
     *  an already-blank-space-free value is what [de.littletrotter.domain.value.Iban] always
     *  stores, so grouping every four characters is the whole rule. */
    private fun groupIban(iban: String): String = iban.chunked(GROUP_SIZE_IBAN).joinToString(" ")
}

private const val GROUP_SIZE_IBAN = 4

/** The sender's own country: an address there is domestic and prints no country line. */
private const val DOMESTIC_COUNTRY = "DE"
