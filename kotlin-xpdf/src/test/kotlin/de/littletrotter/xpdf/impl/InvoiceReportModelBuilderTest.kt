package de.littletrotter.xpdf.impl

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.Currency
import de.littletrotter.shared.InvoiceCashDiscountDto
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.Assertions.assertEquals
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceReferencesDto
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Direct unit coverage of [InvoiceReportModelBuilder]'s private footer/address-line helpers and
 *  [InvoiceReportModelBuilder]'s VAT-label decision, exercised through [InvoiceReportModelBuilder
 *  .build]'s typed result instead of rendering a PDF and scraping its text (the Typst
 *  render tests cover the end-to-end text). */
class InvoiceReportModelBuilderTest {

    private val builder = InvoiceReportModelBuilder()

    private fun invoice(sender: PartyDto, recipient: PartyDto = recipient(), items: List<InvoiceItemDto> = listOf(item())): InvoiceDto {
        val net = items.sumOf { it.netAmount.amount }
        val vat = items.sumOf { it.vatAmount.amount }
        return InvoiceDto(
            invoiceNumber = InvoiceNumberDto("JRMB-2026-0001"),
            dates = InvoiceDatesDto(invoiceDate = "2026-08-13"),
            senderProfile = sender,
            recipient = recipient,
            items = items,
            totals = InvoiceTotalsDto(netAmount = Money(net), vatAmount = Money(vat), grossAmount = Money(net.add(vat))),
        )
    }

    private fun recipient(country: String? = null, postalCode: String? = "10115", city: String? = "Berlin"): PartyDto =
        PartyDto(name = "Kunde AG", street = "Musterstr. 1", postalCode = postalCode, city = city, country = country)

    private fun item(rate: String = "19.0"): InvoiceItemDto = InvoiceItemDto(
        positionId = "1",
        description = "Beratung",
        quantity = BigDecimal.ONE,
        unitPriceNet = Money(BigDecimal("100.00")),
        netAmount = Money(BigDecimal("100.00")),
        vatAmount = Money(BigDecimal("19.00")),
        taxRatePercent = BigDecimal(rate),
    )

    // ── buildReturnLine (Briefkopf-Rücksendezeile) ─────────────────────────────────────────

    @Test
    fun `return line omits the postal-code-city segment entirely when the sender has neither`() {
        val sender = PartyDto(name = "Solo GmbH", street = "Einzelstr. 1", postalCode = null, city = null)

        val model = builder.build(invoice(sender))

        assertEquals("Solo GmbH · Einzelstr. 1", model.header.returnLine)
    }

    @Test
    fun `return line includes the postal-code-city segment when at least one is present`() {
        val sender = PartyDto(name = "Voll GmbH", street = "Vollstr. 2", postalCode = "28195", city = "Bremen")

        val model = builder.build(invoice(sender))

        assertEquals("Voll GmbH · Vollstr. 2 · 28195 Bremen", model.header.returnLine)
    }

    /** Isolates the `postalCode.isBlank() && city.isBlank()`-false outcome that is
     *  reached with only one of the two set (the two tests above only ever try "both blank" and
     *  "both set" — this fills the "exactly one" gap in between, on both sides). */
    @Test
    fun `boundary - return line still includes the segment when only the postal code is present`() {
        val sender = PartyDto(name = "Halb GmbH", street = "Halbstr. 3", postalCode = "50667", city = null)

        val model = builder.build(invoice(sender))

        assertEquals("Halb GmbH · Halbstr. 3 · 50667", model.header.returnLine)
    }

    @Test
    fun `boundary - return line still includes the segment when only the city is present`() {
        val sender = PartyDto(name = "Halb GmbH", street = "Halbstr. 3", postalCode = null, city = "Koeln")

        val model = builder.build(invoice(sender))

        assertEquals("Halb GmbH · Halbstr. 3 · Koeln", model.header.returnLine)
    }

    // ── buildRecipientCityLine (Adressfenster) ─────────────────────────────────────────────

    @Test
    fun `a domestic address prints postal code and city without any country (DIN 5008)`() {
        val model = builder.build(invoice(sender(), recipient(country = null)))

        assertEquals("10115 Berlin", model.header.recipientCityLine)
        assertEquals("", model.header.recipientCountryLine)
    }

    @Test
    fun `Germany given explicitly as DE still counts as domestic`() {
        val model = builder.build(invoice(sender(), recipient(country = "de")))

        assertEquals("", model.header.recipientCountryLine)
    }

    @Test
    fun `a foreign address names its country on its own last line, in capitals, in the document language`() {
        val vienna = invoice(sender(), recipient(country = "AT", postalCode = "1010", city = "Wien"))
        val german = builder.build(vienna)
        val english = builder.build(vienna.copy(meta = InvoiceMetaDto(documentLanguage = "en")))

        assertEquals("1010 Wien", german.header.recipientCityLine)
        assertEquals("ÖSTERREICH", german.header.recipientCountryLine)
        assertEquals("AUSTRIA", english.header.recipientCountryLine)
    }

    @Test
    fun `a country that is not an ISO code prints as given, in capitals`() {
        val model = builder.build(invoice(sender(), recipient(country = "Frankreich")))

        assertEquals("FRANKREICH", model.header.recipientCountryLine)
    }

    @Test
    fun `without postal code and city the country line is dropped too`() {
        val model = builder.build(invoice(sender(), recipient(country = "FR", postalCode = null, city = null)))

        assertEquals("", model.header.recipientCityLine)
        assertEquals("", model.header.recipientCountryLine)
    }

    // ── ST-6: the storno document names itself and the invoice it cancels ────────

    private fun storno(language: String): InvoiceDto = invoice(sender()).copy(
        // PO decision 1 (2026-09-02) — the storno document's own type code is 384; `storno` is the
        // flag the DTO mapper derives from it and the one the print image reads.
        typeCode = "384",
        storno = true,
        meta = InvoiceMetaDto(documentLanguage = language),
        references = InvoiceReferencesDto(
            originalInvoiceNumber = "JRMB-2026-0007",
            originalInvoiceDate = "2026-08-01",
        ),
    )

    private fun rinfoLabels(model: InvoiceReportModel) = model.rinfo.rows.joinToString("\n") { it.label }

    private fun rinfoValues(model: InvoiceReportModel) = model.rinfo.rows.joinToString("\n") { it.value }

    @Test
    fun `ST-6 - a storno document is titled Stornorechnung and names the cancelled invoice`() {
        val model = builder.build(storno("de"))

        assertTrue(model.header.betreff.startsWith("Stornorechnung Nr."), "title: ${model.header.betreff}")
        assertTrue(rinfoLabels(model).contains("Storno zu Rechnung"), "labels: ${rinfoLabels(model)}")
        assertTrue(rinfoValues(model).contains("JRMB-2026-0007"), "values: ${rinfoValues(model)}")
        assertTrue(rinfoLabels(model).contains("Original vom"), "labels: ${rinfoLabels(model)}")
    }

    @Test
    fun `ST-6 - the same document in English uses the international wording`() {
        val model = builder.build(storno("en"))

        assertTrue(model.header.betreff.startsWith("Credit note No."), "title: ${model.header.betreff}")
        assertTrue(rinfoLabels(model).contains("Reversal of invoice"), "labels: ${rinfoLabels(model)}")
        assertTrue(rinfoValues(model).contains("JRMB-2026-0007"), "values: ${rinfoValues(model)}")
    }

    @Test
    fun `ST-6 - an ordinary invoice keeps its title and gains no reference rows`() {
        val model = builder.build(invoice(sender()))

        assertTrue(model.header.betreff.startsWith("Rechnung Nr."), "title: ${model.header.betreff}")
        assertFalse(rinfoLabels(model).contains("Storno"), "labels: ${rinfoLabels(model)}")
        assertFalse(rinfoLabels(model).contains("Original vom"), "labels: ${rinfoLabels(model)}")
    }

    @Test
    fun `a storno document prints no payment block at all`() {
        val model = builder.build(storno("de"))

        assertFalse(model.payment.hasPaymentBlock, "no payment block on a storno document")
        assertFalse(model.payment.hasPaymentTerms, "and no payment-due row either")
    }

    @Test
    fun `an ordinary invoice keeps its payment block`() {
        assertTrue(builder.build(invoice(sender())).payment.hasPaymentBlock)
    }

    @Test
    fun `a computed cash discount reaches the payment block's own sentence and flag`() {
        val withDiscount = invoice(sender()).copy(
            cashDiscount = InvoiceCashDiscountDto(BigDecimal("2"), 7, "2026-10-05", Money(BigDecimal("2.00")), Money(BigDecimal("98.00"))),
        )
        val model = builder.build(withDiscount).payment

        assertTrue(model.hasCashDiscount)
        assertTrue(model.cashDiscountText.startsWith("Bei Zahlung bis"), model.cashDiscountText)
    }

    @Test
    fun `no cash discount leaves the payment block's sentence and flag at their defaults`() {
        val model = builder.build(invoice(sender())).payment

        assertFalse(model.hasCashDiscount)
        assertEquals("", model.cashDiscountText)
    }

    /** BT-26 is optional even on a storno document: without it the reference row still stands. */
    @Test
    fun `ST-6 - a storno document without the original issue date still names the invoice`() {
        val model = builder.build(
            storno("de").let { it.copy(references = it.references.copy(originalInvoiceDate = null)) },
        )

        assertTrue(rinfoLabels(model).contains("Storno zu Rechnung"), "labels: ${rinfoLabels(model)}")
        assertFalse(rinfoLabels(model).contains("Original vom"), "labels: ${rinfoLabels(model)}")
    }

    private fun sender(name: String = "Anbieter GmbH"): PartyDto = PartyDto(name = name, street = "Anbieterweg 1", postalCode = "50667", city = "Koeln")

    @Test
    fun `senderNameRaw carries the sender-profile name unwrapped, even past forty characters`() {
        val original = "Ä".repeat(41) + " & Partner"

        val raw = builder.build(invoice(sender(original))).header.senderNameRaw

        assertEquals(original, raw)
        assertEquals(1, raw.lines().size)
    }

    // ── vatTotalLabel / distinctRates (Positionen ohne Steuersatz werden ignoriert) ────────

    @Test
    fun `a single real tax rate across every item names that rate explicitly in the VAT summary label`() {
        val model = builder.build(invoice(sender(), items = listOf(item(rate = "19.0"), item(rate = "19.0"))))

        assertTrue(model.summary.labelVat.contains("19"), "expected the single shared rate (19%) in the label, was: ${model.summary.labelVat}")
    }

    // ── rcNoteText composition (the tax-note box, always category "AE") ──────────────────

    private fun aeInvoice(
        language: String?,
        exemptionReasonText: String? = "Steuerschuldnerschaft aus § 3a Abs. 2 UStG.",
        exemptionReasonCode: String? = "VATEX-EU-AE",
    ): InvoiceDto {
        val aeItem = InvoiceItemDto(
            positionId = "1", description = "Beratung", quantity = BigDecimal.ONE,
            unitPriceNet = Money(BigDecimal("100.00")), netAmount = Money(BigDecimal("100.00")),
            vatAmount = Money(BigDecimal.ZERO), taxRatePercent = BigDecimal.ZERO,
            taxCategory = "AE", exemptionReasonCode = exemptionReasonCode, exemptionReasonText = exemptionReasonText,
        )
        return InvoiceDto(
            invoiceNumber = InvoiceNumberDto("JRMB-RC-0001"),
            dates = InvoiceDatesDto(invoiceDate = "2026-09-06"),
            meta = InvoiceMetaDto(documentLanguage = language),
            senderProfile = sender(),
            recipient = recipient(),
            items = listOf(aeItem),
            totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal.ZERO), grossAmount = Money(BigDecimal("100.00")), reverseCharge = true),
        )
    }

    @Test
    fun `IN-6 - a German document's tax note is exactly today's text - golden, byte-identical`() {
        val model = builder.build(aeInvoice("de"))

        assertEquals("Steuerschuldnerschaft aus § 3a Abs. 2 UStG.", model.notes.reverseChargeNoteText)
    }

    @Test
    fun `IN-6 - a German document with a blank master-data text falls back to today's German default - golden`() {
        val model = builder.build(aeInvoice("de", exemptionReasonText = null))

        assertEquals("Steuerschuldnerschaft des Leistungsempfängers (Reverse Charge).", model.notes.reverseChargeNoteText)
    }

    @Test
    fun `IN-6 - an English document keeps the German original and appends the English standard sentence`() {
        val model = builder.build(aeInvoice("en"))

        assertEquals(
            "Steuerschuldnerschaft aus § 3a Abs. 2 UStG.\n" +
                "Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).",
            model.notes.reverseChargeNoteText,
        )
    }

    @Test
    fun `IN-6 - an English document with a blank master-data text shows the English standard sentence alone`() {
        val model = builder.build(aeInvoice("en", exemptionReasonText = null))

        assertEquals("Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).", model.notes.reverseChargeNoteText)
    }

    @Test
    fun `IN-6 - an English document without a VATEX code falls back to the category text`() {
        val model = builder.build(aeInvoice("en", exemptionReasonCode = null))

        assertEquals(
            "Steuerschuldnerschaft aus § 3a Abs. 2 UStG.\n" +
                "Reverse charge: the recipient of the service is liable for VAT.",
            model.notes.reverseChargeNoteText,
        )
    }

    // ── The printed currency comes from the invoice, never from the document language.
    //    The decisive case: a CHF invoice issued in German must show no Euro sign at all, on
    //    both the header totals and the item rows — the two derivations sit in separate files. ──

    private fun withCurrency(currency: Currency, language: String? = null): InvoiceDto =
        invoice(sender()).let {
            it.copy(
                totals = it.totals.copy(currency = currency),
                meta = InvoiceMetaDto(documentLanguage = language),
            )
        }

    @Test
    fun `R8 - a CHF invoice issued in German shows CHF, never a Euro sign, on the header totals`() {
        val model = builder.build(withCurrency(Currency.CHF, language = "de"))

        assertTrue(model.summary.netTotal.endsWith("CHF"), "net total: ${model.summary.netTotal}")
        assertTrue(model.summary.vatTotal.endsWith("CHF"), "vat total: ${model.summary.vatTotal}")
        assertTrue(model.summary.grossTotal.endsWith("CHF"), "gross total: ${model.summary.grossTotal}")
        assertFalse(model.summary.netTotal.contains("€"), "must not contain a Euro sign: ${model.summary.netTotal}")
        assertFalse(model.summary.vatTotal.contains("€"), "must not contain a Euro sign: ${model.summary.vatTotal}")
        assertFalse(model.summary.grossTotal.contains("€"), "must not contain a Euro sign: ${model.summary.grossTotal}")
    }

    @Test
    fun `R8 - a CHF invoice issued in German shows CHF, never a Euro sign, on the item rows too`() {
        val model = builder.build(withCurrency(Currency.CHF, language = "de"))
        val row = model.items.single()

        assertTrue(row.price.endsWith("CHF"), "price: ${row.price}")
        assertTrue(row.net.endsWith("CHF"), "net: ${row.net}")
        assertTrue(row.vatAmt.endsWith("CHF"), "vat: ${row.vatAmt}")
        assertFalse(row.price.contains("€"), "must not contain a Euro sign: ${row.price}")
    }

    @Test
    fun `an EUR invoice in German shows the Euro sign`() {
        val model = builder.build(withCurrency(Currency.EUR, language = "de"))

        assertTrue(model.summary.grossTotal.endsWith("€"), "gross total: ${model.summary.grossTotal}")
    }

    @Test
    fun `an EUR invoice in English also shows the Euro sign`() {
        val model = builder.build(withCurrency(Currency.EUR, language = "en"))

        assertTrue(model.summary.grossTotal.endsWith("€"), "gross total: ${model.summary.grossTotal}")
    }

    @Test
    fun `an invoice without a set currency falls back to EUR, like the display rule elsewhere`() {
        val model = builder.build(invoice(sender()))

        assertTrue(model.summary.grossTotal.endsWith("€"), "gross total: ${model.summary.grossTotal}")
    }

    @Test
    fun `EUR uses the Euro sign and all other currencies use their ISO code on header totals`() {
        Currency.entries.forEach { currency ->
            val model = builder.build(withCurrency(currency, language = "de"))
            val expectedUnit = if (currency == Currency.EUR) "€" else currency.code
            assertTrue(model.summary.grossTotal.endsWith(expectedUnit), "currency $currency: ${model.summary.grossTotal}")
        }
    }

    // ═══ B7 — free-text guard, model level: header.freeText/hasFreeText (the
    //     source for the template's free-text block) equal the context freeText, independent of whether the invoice carries b2bValues. ═══

    @Test
    fun `B7 - the model header freeText equals the context freeText value, independent of the presence of b2bValues`() {
        val freeText = "Bitte Rechnungsnummer im Verwendungszweck angeben."
        val withB2bValues = invoice(sender()).copy(
            meta = InvoiceMetaDto(freeText = freeText),
            b2bValues = listOf(B2bValueDto(B2bTerm.BT_19, "KST-9001"), B2bValueDto(B2bTerm.BT_12, "VERTRAG-9001")),
        )
        val withoutB2bValues = invoice(sender()).copy(meta = InvoiceMetaDto(freeText = freeText))

        val contextFreeText = InvoiceReportContextBuilder().buildContext(withB2bValues).invoice.freeText

        val modelWithB2b = builder.build(withB2bValues)
        val modelWithoutB2b = builder.build(withoutB2bValues)

        assertEquals(contextFreeText, modelWithB2b.header.freeText)
        assertEquals(freeText, modelWithB2b.header.freeText)
        assertEquals(
            modelWithoutB2b.header.freeText,
            modelWithB2b.header.freeText,
            "FREE_TEXT must not depend on whether the invoice carries b2bValues",
        )
        assertTrue(modelWithB2b.header.hasFreeText)
    }

    @Test
    fun `B7 - hasFreeText is false and freeText reads empty without a free text, even with b2bValues present`() {
        val withB2bValues = invoice(sender()).copy(
            meta = InvoiceMetaDto(freeText = null),
            b2bValues = listOf(B2bValueDto(B2bTerm.BT_19, "KST-9002")),
        )

        val model = builder.build(withB2bValues)

        assertFalse(model.header.hasFreeText, "b2bValues must never make hasFreeText true as a fallback")
        assertEquals("", model.header.freeText)
    }

    // ── hasItemDetailColumns (only a service period is "detail data"; a unit or an article
    //    number alone keeps the plain variant, whose description cell prints the article number
    //    as an identifier line itself) ──────────────────────────────────────────────────────────

    @Test
    fun `a unit label alone keeps the plain variant - it no longer selects the detail columns`() {
        val withUnit = item().copy(unitLabel = "Stunde")

        assertFalse(builder.build(invoice(sender(), items = listOf(withUnit))).hasItemDetailColumns)
    }

    @Test
    fun `a service period alone selects the detail variant`() {
        val withPeriod = item().copy(servicePeriodFrom = "2026-08-01", servicePeriodTo = "2026-08-03")

        assertTrue(builder.build(invoice(sender(), items = listOf(withPeriod))).hasItemDetailColumns)
    }

    @Test
    fun `a seller article number alone keeps the plain variant and prints it as an identifier line`() {
        val withSellerNo = item().copy(b2bValues = listOf(B2bValueDto(B2bTerm.BT_155, "ART-4711")))
        val model = builder.build(invoice(sender(), items = listOf(withSellerNo)))

        assertFalse(model.hasItemDetailColumns)
        val idLines = model.items.single().idLines
        assertTrue(idLines.isNotEmpty(), "the plain variant must still print the article number as an identifier line")
        assertEquals("", idLines.first().label, "the bare seller article number carries no label")
        assertEquals("ART-4711", idLines.first().value)
    }

    @Test
    fun `a buyer article number alone keeps the plain variant`() {
        val withBuyerNo = item().copy(b2bValues = listOf(B2bValueDto(B2bTerm.BT_156, "KD-991")))

        assertFalse(builder.build(invoice(sender(), items = listOf(withBuyerNo))).hasItemDetailColumns)
    }

    @Test
    fun `no unit, period or article number keeps the plain variant`() {
        assertFalse(builder.build(invoice(sender(), items = listOf(item()))).hasItemDetailColumns)
    }

    // ── wordingProfile read: no invoice-labels_*.properties key carries a
    //    {term:...} marker yet (see Labels.label's own KDoc), so the resolved WordingProfile has
    //    no visible effect on the print image today; these tests exercise the null/known-code/
    //    unknown-code branches of build()'s own `(invoiceMap["wordingProfile"] as Int?)?.let(...)
    //    ?: WordingProfile.DEFAULT` read without depending on a future rendering difference. ────

    private fun withWordingProfile(code: Int?): InvoiceDto =
        invoice(sender()).copy(meta = InvoiceMetaDto(wordingProfile = code))

    @Test
    fun `wordingProfile absent (null) builds successfully under the DEFAULT profile`() {
        assertEquals(
            builder.build(withWordingProfile(null)).items.single(),
            builder.build(withWordingProfile(WordingProfile.DEFAULT.code)).items.single(),
            "an absent wordingProfile must read exactly like the explicit default code",
        )
    }

    @Test
    fun `boundary - a known non-default wordingProfile code builds successfully`() {
        val model = builder.build(withWordingProfile(WordingProfile.BERATUNG.code))

        assertEquals(1, model.items.size)
    }

    @Test
    fun `boundary - an unrecognised wordingProfile code falls back to DEFAULT instead of crashing`() {
        val withUnknownCode = builder.build(withWordingProfile(999))
        val withDefaultCode = builder.build(withWordingProfile(WordingProfile.DEFAULT.code))

        assertEquals(
            withDefaultCode.items.single(), withUnknownCode.items.single(),
            "fromCodeOrNull(999) is null -> the elvis DEFAULT fallback must apply, same as an explicit default code",
        )
    }
}
