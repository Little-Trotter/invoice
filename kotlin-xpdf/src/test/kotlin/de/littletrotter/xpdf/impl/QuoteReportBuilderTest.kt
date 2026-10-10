package de.littletrotter.xpdf.impl

import de.littletrotter.shared.documentjob.QuoteDocumentMark
import de.littletrotter.shared.documentjob.QuoteDocumentPayload
import de.littletrotter.shared.documentjob.QuoteDocumentReference
import de.littletrotter.shared.documentjob.QuoteDocumentTotals
import de.littletrotter.shared.documentjob.QuoteDocumentVatGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuoteReportBuilderTest {

    private val builder = QuoteReportBuilder()
    private fun build(payload: QuoteDocumentPayload = QuoteFixtures.payload()) = builder.build(payload)

    @Test
    fun `german offer is formatted in german with dates amounts and units`() {
        val data = build()

        assertEquals("de", data.language)
        assertEquals("Angebot Nr. AN-2026-00007 – Sanierung Badezimmer", data.document.subject)
        assertEquals("Seite ", data.document.pageLabel)
        assertEquals(
            listOf("Angebots-Nr.", "Angebotsdatum", "Gültig bis", "Kunden-Nr.", "Käuferreferenz"),
            data.quoteInfo.map { it.label },
        )
        assertEquals("05.10.2026", data.quoteInfo[1].value)
        assertEquals("04.11.2026", data.quoteInfo[2].value)
        val first = data.items.rows[0]
        assertEquals("12,5", first.quantity)
        assertEquals("m²", first.unitLabel)
        assertEquals("48,00 €", first.unitPrice)
        assertEquals("600,00 €", first.net)
        assertEquals("19 %", first.vatPercent)
        assertEquals("1.310,79 €", data.totals.gross)
        assertEquals("zzgl. 19 % Umsatzsteuer", data.totals.vatLabel)
        assertEquals("Angebotssumme", data.totals.grossLabel)
        assertEquals("Nettobetrag", data.items.headers.net)
    }

    @Test
    fun `english offer uses english wording and date order`() {
        val data = build(QuoteFixtures.payload(language = "en"))

        assertEquals("Quotation No. AN-2026-00007 – Sanierung Badezimmer", data.document.subject)
        assertEquals("5 Oct 2026", data.quoteInfo[1].value)
        assertEquals("hours", data.items.rows[2].unitLabel)
        assertEquals("This quotation is valid until 4 Nov 2026.", data.validity.lines[0])
        assertEquals("Quotation total", data.totals.grossLabel)
    }

    @Test
    fun `unknown language reads as german`() {
        assertEquals("de", build(QuoteFixtures.payload(language = "fr")).language)
    }

    @Test
    fun `a position shows the article number then the customer reference and the customer's own article name`() {
        val row = build().items.rows[0]

        assertEquals(listOf("" to "LV-4711", "Ref." to "KD-991"), row.idLines.map { it.label to it.value })
        assertEquals("Bodenfliese 30x60 anthrazit", row.buyerArticleDescriptionLine)
        assertEquals("", row.tripSubline)
        assertFalse(row.subRow)
    }

    @Test
    fun `a customer reference equal to the article number and a repeated customer name are not printed twice`() {
        val item = QuoteFixtures.payload().items[0].copy(customerReference = "LV-4711", customerArticleName = " fliesenarbeiten  BAD ")
        val row = build(QuoteFixtures.payload().copy(items = listOf(item))).items.rows[0]

        assertEquals(listOf("LV-4711"), row.idLines.map { it.value })
        assertEquals("", row.buyerArticleDescriptionLine)
    }

    @Test
    fun `a customer article name without a reference stays unprinted and a free line has no identifier line`() {
        val item = QuoteFixtures.payload().items[1].copy(customerArticleName = "Alias")
        val row = build(QuoteFixtures.payload().copy(items = listOf(item))).items.rows[0]

        assertTrue(row.idLines.isEmpty())
        assertEquals("", row.buyerArticleDescriptionLine)
    }

    @Test
    fun `gross prices switch the price headers and the price statement`() {
        val data = build(QuoteFixtures.payload().copy(grossPrices = true))

        assertEquals("Einzelpreis brutto", data.items.headers.unitPrice)
        assertEquals("Bruttobetrag", data.items.headers.net)
        assertEquals("Alle Preise verstehen sich einschließlich der gesetzlichen Umsatzsteuer.", data.validity.lines[2])
    }

    @Test
    fun `several vat rates add the tax column and breakdown and use the generic vat label`() {
        val totals = QuoteDocumentTotals(
            "100.00", "12.00", "112.00",
            listOf(QuoteDocumentVatGroup("19", "50.00", "9.50"), QuoteDocumentVatGroup("7", "50.00", "3.50")),
        )
        val data = build(QuoteFixtures.payload().copy(totals = totals))

        assertTrue(data.items.taxColumn)
        assertTrue(data.taxBreakdown.present)
        assertEquals(listOf("19 %", "7 %"), data.taxBreakdown.rows.map { it.rate })
        assertEquals("zzgl. Umsatzsteuer", data.totals.vatLabel)
    }

    @Test
    fun `a zero rate keeps the generic vat label`() {
        val totals = QuoteDocumentTotals("100.00", "0.00", "100.00", listOf(QuoteDocumentVatGroup("0", "100.00", "0.00")))
        assertEquals("zzgl. Umsatzsteuer", build(QuoteFixtures.payload().copy(totals = totals)).totals.vatLabel)
        assertFalse(build(QuoteFixtures.payload().copy(totals = totals)).items.taxColumn)
    }

    @Test
    fun `a draft without number says only offer and leaves the number row out`() {
        val data = build(QuoteFixtures.payload(QuoteDocumentMark.DRAFT).copy(quoteNumber = null, subject = null))

        assertEquals("Angebot", data.document.subject)
        assertFalse(data.quoteInfo.any { it.label == "Angebots-Nr." })
    }

    @Test
    fun `references print in a fixed order and unknown or blank ones are ignored`() {
        val refs = listOf(
            QuoteDocumentReference(13, "PO-1"), QuoteDocumentReference(11, "PRJ"), QuoteDocumentReference(10, "BR"),
            QuoteDocumentReference(99, "x"), QuoteDocumentReference(10, " "),
        )
        val rows = build(QuoteFixtures.payload().copy(references = refs)).quoteInfo.map { it.label to it.value }

        assertEquals(
            listOf("Käuferreferenz" to "BR", "Bestellreferenz" to "PO-1", "Projektreferenz" to "PRJ"),
            rows.takeLast(3),
        )
    }

    @Test
    fun `free texts become structured lines and the closing text always follows the table`() {
        val data = build()

        assertTrue(data.introText.present)
        assertFalse(data.introText.afterItems)
        assertTrue(data.introText.lines.single().runs.any { it.bold && it.text == "wie folgt" })
        assertTrue(data.closingText.afterItems)
        assertTrue(data.note.present)
        assertEquals("HINWEIS", data.note.title)
    }

    @Test
    fun `absent or blank free texts are not present`() {
        val data = build(QuoteFixtures.payload().copy(introText = null, closingText = " ", noteText = ""))

        assertFalse(data.introText.present)
        assertFalse(data.closingText.present)
        assertFalse(data.note.present)
        assertTrue(data.note.lines.isEmpty())
    }

    @Test
    fun `a long intro text moves behind the table`() {
        val data = build(QuoteFixtures.payload().copy(introText = List(30) { "Zeile $it" }.joinToString("\n")))
        assertTrue(data.introText.afterItems)
    }

    @Test
    fun `sender block carries return line vat facts and footer columns`() {
        val data = build()

        assertEquals("Musterwerk Handwerk GmbH · Beispielweg 12 · 54290 Trier", data.sender.returnLine)
        assertEquals(listOf("USt-ID" to "DE123456789", "St.-Nr." to "42/123/45678"), data.senderInfo.map { it.label to it.value })
        assertEquals(
            listOf("Sitz: Trier" to false, "Tel. 0651 123456" to true, "angebot@musterwerk.example" to true, "www.musterwerk.example" to true),
            data.footer.contact.map { it.text to it.muted },
        )
        assertEquals(listOf("HRB 12345", "Registergericht: Amtsgericht Trier"), data.footer.register)
        assertEquals(listOf("GF. Erika Mustermann", "DE89 3704 0044 0532 0130 00", "Musterbank Trier"), data.footer.management)
    }

    @Test
    fun `a sender without register entry prints no seat line and a bare sender prints no footer at all`() {
        val bare = QuoteFixtures.payload().copy(seller = QuoteFixtures.seller.copy(
            commercialRegisterNumber = null, registerCourt = null, phone = null, email = null, website = null,
            iban = null, bankName = null, managingDirector = null, vatId = null, taxNumber = null, street = null,
            postalCode = null, city = null,
        ))
        val data = build(bare)

        assertTrue(data.footer.contact.isEmpty() && data.footer.register.isEmpty() && data.footer.management.isEmpty())
        assertTrue(data.senderInfo.isEmpty())
        assertEquals("Musterwerk Handwerk GmbH", data.sender.returnLine)
    }

    @Test
    fun `a foreign customer address names its country in capitals and a domestic one does not`() {
        val foreign = QuoteFixtures.customer.copy(countryCode = "FR", postalCode = "75001", city = "Paris")

        assertEquals("FRANKREICH", build(QuoteFixtures.payload().copy(customer = foreign)).recipient.countryLine)
        assertEquals("FRANCE", build(QuoteFixtures.payload(language = "en").copy(customer = foreign)).recipient.countryLine)
        assertEquals("", build().recipient.countryLine)
        assertEquals("50667 Köln", build().recipient.cityLine)
    }

    @Test
    fun `a foreign customer without city line gets no country line and a customer without country is domestic`() {
        val noCity = QuoteFixtures.customer.copy(countryCode = "FR", postalCode = null, city = null)
        val noCountry = QuoteFixtures.customer.copy(countryCode = null)

        assertEquals("", build(QuoteFixtures.payload().copy(customer = noCity)).recipient.countryLine)
        assertEquals("", build(QuoteFixtures.payload().copy(customer = noCountry)).recipient.countryLine)
    }

    @Test
    fun `lfm rows add the footnote and other rows do not`() {
        val lfm = QuoteFixtures.payload().items[0].copy(unit = "LINEAR_METER")

        assertEquals(listOf("lfm = laufender Meter"), build(QuoteFixtures.payload().copy(items = listOf(lfm))).items.unitFootnotes)
        assertTrue(build().items.unitFootnotes.isEmpty())
    }

    @Test
    fun `non euro offers print the iso code`() {
        assertEquals("1.310,79 CHF", build(QuoteFixtures.payload().copy(currency = "CHF")).totals.gross)
    }

    @Test
    fun `both label bundles carry the same keys`() {
        assertEquals(QuoteLabels.keys("de"), QuoteLabels.keys("en"))
    }

    @Test
    fun `a missing label is a loud failure`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { QuoteLabels.label("nope", "de") }
    }

    @Test
    fun `a unit prints singular for a quantity of one and plural otherwise, in the document language`() {
        val flatRate = QuoteFixtures.payload().items[1]
        fun label(language: String, quantity: String) =
            build(QuoteFixtures.payload(language = language).let { it.copy(items = listOf(flatRate.copy(quantity = quantity))) }).items.rows.single().unitLabel

        assertEquals("Pauschale", label("de", "1"))
        assertEquals("Pauschalen", label("de", "2"))
        assertEquals("flat rate", label("en", "1"))
        assertEquals("flat rates", label("en", "3"))
    }
}
