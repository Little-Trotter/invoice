package de.littletrotter.xpdf.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import de.littletrotter.shared.InvoiceCashDiscountDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The `data.json` contract of `standard-invoice.typ`, pinned on the parity fixtures: which
 * variant flags each situation sets, and that the free text arrives as structured runs. Every
 * fixture's JSON is also written to `target/typst-samples/` — the files a template author
 * compiles against by hand.
 */
class TypstInvoiceDataTest {

    private val renderer = TypstInvoiceRenderer(TypstCompiler(), ClockTimeSource.fixedAt(Instant.ofEpochSecond(1_790_000_000L)), PaymentQrSetting.OFF)
    private val mapper = ObjectMapper()
    private val samples: Map<String, JsonNode> = TypstParityFixtures.cases().associate { case ->
        val json = renderer.dataJson(case.invoice)
        val dir = Path.of("target", "typst-samples")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${case.name}.json"), json)
        case.name to mapper.readTree(json)
    }

    private fun sample(name: String): JsonNode = samples.getValue(name)

    /** [TypstIdLine] pairs as this JSON node's `idLines` array carries them. */
    private fun JsonNode.idLinePairs(): List<Pair<String, String>> =
        map { it.get("label").asText() to it.get("value").asText() }

    @Test
    fun `top-level keys are exactly the template contract`() {
        val expected = setOf(
            "language", "document", "sender", "recipient", "invoiceInfo", "senderInfo", "freeText",
            "items", "taxBreakdown", "totals", "payment", "notes", "footer",
        )
        samples.values.forEach { assertEquals(expected, it.fieldNames().asSequence().toSet()) }
    }

    @Test
    fun `no styled markup leaks into the data`() {
        samples.forEach { (name, node) ->
            assertFalse(node.toString().contains("<style"), "$name carries styled markup")
            assertFalse(node.toString().contains("&amp;"), "$name carries HTML escapes")
        }
    }

    @Test
    fun `table variant and VAT column selection is correct`() {
        fun items(name: String) = sample(name).get("items")
        assertFalse(items("plain-single-rate").get("detailColumns").asBoolean())
        assertFalse(items("plain-single-rate").get("taxColumn").asBoolean())
        assertFalse(sample("plain-single-rate").get("taxBreakdown").get("present").asBoolean())
        assertTrue(items("plain-mixed-rates").get("taxColumn").asBoolean())
        assertEquals(2, sample("plain-mixed-rates").get("taxBreakdown").get("rows").size())
        assertTrue(items("detail-single-rate").get("detailColumns").asBoolean())
        assertFalse(items("detail-single-rate").get("taxColumn").asBoolean())
        assertTrue(items("detail-mixed-rates").get("detailColumns").asBoolean())
        assertTrue(items("detail-mixed-rates").get("taxColumn").asBoolean())
    }

    @Test
    fun `unit footnote for lfm prints only when a row — main or trip sub-row — carries it`() {
        fun footnotes(name: String) = sample(name).get("items").get("unitFootnotes").map { it.asText() }
        assertEquals(emptyList<String>(), footnotes("plain-single-rate"))
        assertEquals(listOf("lfm = laufender Meter"), footnotes("unit-footnote-lfm-de"))
        assertEquals(listOf("lfm = linear metre"), footnotes("unit-footnote-lfm-en"))
        assertEquals(listOf("lfm = laufender Meter"), footnotes("unit-footnote-lfm-trip-subrow"))
    }

    @Test
    fun `detail rows carry period and the identifier line, plain rows their id sublines`() {
        assertTrue(sample("detail-single-rate").get("items").get("detailColumns").asBoolean())
        val detail = sample("detail-single-rate").get("items").get("rows").get(0)
        assertEquals("01.08.2026 – 03.08.2026", detail.get("servicePeriod").asText())
        assertEquals(listOf("" to "ART-4711", "Ref." to "KD-991"), detail.get("idLines").idLinePairs())
        assertFalse(detail.get("idLines").isEmpty)
        assertEquals("", detail.get("buyerArticleDescriptionLine").asText())
        assertEquals("Austausch Therme inkl. Verrohrung, 2. OG", detail.get("itemDescription").asText())
        val plain = sample("plain-single-rate").get("items").get("rows").get(0)
        assertEquals("1,5", plain.get("quantity").asText())
        assertEquals("Stunden", plain.get("unitLabel").asText())
        assertTrue(plain.get("idLines").isEmpty)
        assertFalse(plain.get("subRow").asBoolean())
    }

    @Test
    fun `the note box prints for a consumer note alone and carries both paragraphs when both exist`() {
        val items = listOf(TypstParityFixtures.plainItem(1))
        fun notes(footer: String?, consumer: String?) = mapper.readTree(
            renderer.dataJson(TypstParityFixtures.invoice("PAR-N", items, footerNote = footer, consumerNote = consumer)),
        ).get("notes")
        val consumerOnly = notes(null, "Hinweis")
        assertTrue(consumerOnly.get("hasNote").asBoolean())
        assertFalse(consumerOnly.get("hasSellerNote").asBoolean())
        assertTrue(consumerOnly.get("hasConsumerNote").asBoolean())
        assertEquals("Hinweis", consumerOnly.get("consumerNoteText").asText())
        val both = notes("Danke", "Hinweis")
        assertTrue(both.get("hasSellerNote").asBoolean() && both.get("hasConsumerNote").asBoolean())
        assertFalse(notes(null, " ").get("hasNote").asBoolean())
    }

    @Test
    fun `cancellation drops the payment block, reverse charge adds the tax note, no IBAN shows the hint`() {
        val cancellation = sample("storno-with-note")
        assertFalse(cancellation.get("payment").get("present").asBoolean())
        assertTrue(cancellation.get("notes").get("hasNote").asBoolean())
        assertTrue(cancellation.get("document").get("subject").asText().contains("PAR-2026-0005"))
        val rc = sample("reverse-charge-note-no-bank")
        assertTrue(rc.get("payment").get("present").asBoolean())
        assertTrue(rc.get("payment").get("showHint").asBoolean())
        assertFalse(rc.get("payment").get("hasIban").asBoolean())
        assertTrue(rc.get("notes").get("hasTaxNote").asBoolean())
        assertTrue(rc.get("notes").get("taxNoteText").asText().isNotBlank())
        assertTrue(rc.get("footer").get("management").none { it.asText().startsWith("DE89") })
    }

    @Test
    fun `a computed cash discount reaches the payment JSON, an ordinary invoice leaves it off`() {
        val base = TypstParityFixtures.invoice("TC-SKONTO-JSON", listOf(TypstParityFixtures.plainItem(1)))
        val withDiscount = base.copy(
            cashDiscount = InvoiceCashDiscountDto(BigDecimal("2"), 7, "2026-10-05", Money.of("12.09"), Money.of("592.57")),
        )
        val json = mapper.readTree(renderer.dataJson(withDiscount))

        assertTrue(json.get("payment").get("hasCashDiscount").asBoolean())
        assertTrue(json.get("payment").get("cashDiscountText").asText().startsWith("Bei Zahlung bis"))
        assertFalse(sample("plain-single-rate").get("payment").get("hasCashDiscount").asBoolean())
        assertEquals("", sample("plain-single-rate").get("payment").get("cashDiscountText").asText())
    }

    @Test
    fun `free text arrives as lines with marks and formatted runs, placed inline or after the items`() {
        val inline = sample("free-text-inline").get("freeText")
        assertTrue(inline.get("present").asBoolean())
        assertFalse(inline.get("afterItems").asBoolean())
        val lines = inline.get("lines")
        assertEquals(5, lines.size())
        val first = lines.get(0).get("runs")
        assertEquals("Wichtiger Hinweis:", first.get(0).get("text").asText())
        assertTrue(first.get(0).get("bold").asBoolean())
        assertTrue(first.any { it.get("italic").asBoolean() && it.get("text").asText() == "13.08.2026" })
        assertEquals("•", lines.get(1).get("mark").asText())
        assertEquals(1, lines.get(2).get("level").asInt())
        assertEquals("–", lines.get(2).get("mark").asText())
        assertTrue(lines.get(2).get("runs").any { it.get("strike").asBoolean() && it.get("text").asText() == "12" })
        assertEquals("1.", lines.get(3).get("mark").asText())
        val after = sample("free-text-after-items").get("freeText")
        assertTrue(after.get("afterItems").asBoolean())
        assertFalse(sample("plain-single-rate").get("freeText").get("present").asBoolean())
    }

    @Test
    fun `underlined free text reaches the Typst data as an underline run, not as literal markup`() {
        val runs = sample("free-text-inline").get("freeText").get("lines").get(1).get("runs")

        assertTrue(runs.any { it.get("underline").asBoolean() && it.get("text").asText() == "24 Monate" })
        assertTrue(runs.none { it.get("text").asText().contains("++") })
    }

    @Test
    fun `footer columns carry raw contact lines, register facts and bank facts`() {
        val footer = sample("plain-single-rate").get("footer")
        val contact = footer.get("contact")
        assertEquals(4, contact.size())
        assertEquals("Sitz: Bremen", contact.get(0).get("text").asText())
        assertFalse(contact.get(0).get("muted").asBoolean())
        assertTrue(contact.get(1).get("text").asText().startsWith("Tel."))
        assertEquals("www.schmidt-sanitaer.example", contact.get(3).get("text").asText())
        contact.forEach { line -> assertFalse(line.has("sizePt"), "contact line still carries a fitted size") }
        assertEquals(listOf("HRA 12345", "Registergericht: Amtsgericht Bremen", "Gerichtsstand: Bremen"), footer.get("register").map { it.asText() })
        assertEquals(listOf("GF. Klaus Schmidt", "DE89 3704 0044 0532 0130 00", "Commerzbank Bremen"), footer.get("management").map { it.asText() })
    }

    @Test
    fun `an 80-character sender name arrives unwrapped, as one string with no nameLines key`() {
        val longName = "Handwerksbetrieb fuer Sanitaer Heizung Klimatechnik und Elektroinstallation GmbH"
        val invoice = TypstParityFixtures.cases().first().invoice.copy(
            senderProfile = TypstParityFixtures.sender().copy(name = longName),
        )
        val model = InvoiceReportModelBuilder().build(invoice)
        val kotlinMapper = jsonMapper { addModule(kotlinModule()) }
        val node = mapper.readTree(kotlinMapper.writeValueAsString(model.toTypstData("de")))

        val sender = node.get("sender")
        assertEquals(longName, sender.get("name").asText())
        assertFalse(sender.has("nameLines"))
    }

    @Test
    fun `trip sub-rows are flagged and the main row carries the purpose subline`() {
        val rows = sample("trip-detailed").get("items").get("rows")
        val trip = rows.first { it.get("pos").asText() == "002" }
        assertFalse(trip.get("subRow").asBoolean())
        assertEquals("Zweck: Reisefahrt   Datum: 05.09.2026 – 12.09.2026", trip.get("tripSubline").asText())
        assertTrue(rows.first { it.get("pos").asText() == "001" }.get("tripSubline").asText().isBlank())
        assertEquals(5, rows.count { it.get("subRow").asBoolean() })
        assertTrue(rows.last().get("description").asText().startsWith("Verpflegungspauschalen"))
    }

    @Test
    fun `qrFile and qrCaption pass through to the payment block, qrFile null unless the renderer stages a code`() {
        val invoice = TypstParityFixtures.cases().first { it.name == "plain-single-rate" }.invoice
        val model = InvoiceReportModelBuilder().build(invoice)
        val kotlinMapper = jsonMapper { addModule(kotlinModule()) }

        val withoutQr = mapper.readTree(kotlinMapper.writeValueAsString(model.toTypstData("de")))
        assertTrue(withoutQr.get("payment").get("qrFile").isNull, "qrFile must default to null")

        val withQr = mapper.readTree(kotlinMapper.writeValueAsString(model.toTypstData("de", qrFile = "qr.svg")))
        assertEquals("qr.svg", withQr.get("payment").get("qrFile").asText())
        assertEquals(model.payment.labelQr, withQr.get("payment").get("qrCaption").asText())
    }

    @Test
    fun `the english document uses english labels and the english page label`() {
        val en = sample("english")
        assertEquals("en", en.get("language").asText())
        assertTrue(en.get("document").get("subject").asText().startsWith("Invoice"))
        assertEquals("Page ", en.get("document").get("pageLabel").asText())
        assertTrue(en.get("items").get("headers").get("description").asText().startsWith("Desc"))
    }

    /**
     * The detail table variant's identifier line (BT-154/155/156, printed under the description
     * exactly as the plain variant prints it — no dedicated article-number columns any more)
     * renders the same way in both document languages. This detail fixture's item has no
     * captured buyer article description, so it pins the two-line case (no buyer id → no third
     * line either).
     */
    @Test
    fun `the english detail variant carries the same identifier line as the german one`() {
        val enDetail = sample("english-detail").get("items").get("rows").get(0)
        assertTrue(sample("english-detail").get("items").get("detailColumns").asBoolean())
        assertEquals(listOf("" to "ART-4711", "Ref." to "KD-991"), enDetail.get("idLines").idLinePairs())
        assertFalse(enDetail.get("idLines").isEmpty)
        assertEquals("Austausch Therme inkl. Verrohrung, 2. OG", enDetail.get("itemDescription").asText())
    }

    /**
     * The buyer's own article designation prints as its own third field, only alongside the
     * "Ref." buyer article-number line it describes — pinned in both document languages against
     * the two-line case above (no designation captured there). It travels off `idLines`
     * (`buyerArticleDescriptionLine`) because the Typst template renders it in italic.
     */
    @Test
    fun `a captured buyer article description adds a third italic line, in german and in english`() {
        val kotlinMapper = jsonMapper { addModule(kotlinModule()) }
        val itemWithDescription = TypstParityFixtures.detailItem(1).copy(buyerArticleDescription = "Kabelbinder 200mm")
        val builder = InvoiceReportModelBuilder()

        val deModel = builder.build(TypstParityFixtures.invoice("ID-DESC-0001", listOf(itemWithDescription)))
        val deRow = mapper.readTree(kotlinMapper.writeValueAsString(deModel.toTypstData("de")))
            .get("items").get("rows").get(0)
        assertEquals(listOf("" to "ART-4711", "Ref." to "KD-991"), deRow.get("idLines").idLinePairs())
        assertEquals("Kabelbinder 200mm", deRow.get("buyerArticleDescriptionLine").asText())

        val enModel = builder.build(TypstParityFixtures.invoice("ID-DESC-0002", listOf(itemWithDescription), language = "en"))
        val enRow = mapper.readTree(kotlinMapper.writeValueAsString(enModel.toTypstData("en")))
            .get("items").get("rows").get(0)
        assertEquals(listOf("" to "ART-4711", "Ref." to "KD-991"), enRow.get("idLines").idLinePairs())
        assertEquals("Kabelbinder 200mm", enRow.get("buyerArticleDescriptionLine").asText())
    }

    /**
     * The detail variant's dedicated article-number columns are gone (the identifier line under
     * the description now carries that data in both variants) — a regression pin against the
     * template regrowing a "Art.-Nr."/"Kd.-Art.-Nr." column instead of widening the description.
     */
    @Test
    fun `the template carries no article-number column reference`() {
        val template = renderer.bundledTemplate()
        assertFalse(template.contains("sellerArticleNo"), "template still references a sellerArticleNo column")
        assertFalse(template.contains("buyerArticleNo"), "template still references a buyerArticleNo column")
    }
}
