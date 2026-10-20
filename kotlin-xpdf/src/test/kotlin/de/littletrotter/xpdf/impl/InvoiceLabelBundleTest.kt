package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/** The two prepared label bundles stay in lockstep. */
class InvoiceLabelBundleTest {

    private fun loadBundle(fileName: String): Map<String, String> {
        val stream = javaClass.getResourceAsStream("/reports/$fileName")
            ?: error("$fileName not found on classpath")
        val props = Properties()
        InputStreamReader(stream, StandardCharsets.UTF_8).use { props.load(it) }
        return props.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    private val de = loadBundle("invoice-labels_de.properties")
    private val en = loadBundle("invoice-labels_en.properties")

    /** Every key `buildParams`/`rinfoLabels`/`pinfoLabels`/`buildFooterCol2`/`buildFooterCol3`
     *  actually looks up — kept as an explicit list so a renamed/removed key in the
     *  renderer without a matching bundle update fails here, not as a runtime IllegalStateException. */
    private val keysUsedByRenderer = setOf(
        "subject.prefix",
        "info.invoiceNo", "info.invoiceDate", "info.offerNo", "info.offerDate",
        "info.servicePeriod", "info.customerNo", "info.orderNo", "info.dueDate",
        // BT-10 and BT-13 sit next to each other in the Rechnungs-Infobox and must stay
        // distinguishable by their labels alone — that is the whole point of splitting them.
        "info.buyerReference", "info.purchaseOrderReference", "info.projectNumber",
        "sender.vatId", "sender.taxNumber", "sender.email", "sender.phone",
        "foreignTaxNumber.label.siren", "foreignTaxNumber.label.nFiscal", "foreignTaxNumber.label.kbo",
        "foreignTaxNumber.label.rrn", "foreignTaxNumber.label.rsin", "foreignTaxNumber.label.bsn",
        "foreignTaxNumber.label.matricule", "foreignTaxNumber.label.tin",
        "table.pos", "table.description", "table.quantity", "table.unitPrice",
        "table.netAmount", "table.taxAmount", "table.tax",
        "breakdown.rate", "breakdown.net", "breakdown.vat",
        "total.net", "total.vat", "total.vat.withRate", "total.gross",
        "payment.title", "payment.amount", "payment.due", "payment.bank",
        "payment.reference", "payment.hint.noBankDetails",
        "note.title", "taxnote.title",
        // The standard-text family composeTaxNote looks up (VATEX code first, then
        // category); replaces the single taxnote.reverseCharge key.
        "taxnote.standard.AE", "taxnote.standard.E", "taxnote.standard.O",
        "taxnote.standard.VATEX-EU-AE",
        "footer.jurisdiction", "footer.registerCourt", "footer.managingDirector",
        "page.prefix",
    )

    @Test
    fun `UI party terminology does not rename printed customer numbers`() {
        assertEquals("Kunden-Nr.", de.getValue("info.customerNo"))
        assertEquals("Customer No.", en.getValue("info.customerNo"))
    }

    @Test
    fun `R3-T08 - de and en have exactly the same key set, no blank values`() {
        assertEquals(de.keys, en.keys, "the two bundles must carry identical key sets")
        (de.values + en.values).forEach { assertTrue(it.isNotBlank(), "no bundle value may be blank") }
    }

    @Test
    fun `R3-T09 - every key the renderer looks up exists in both bundles`() {
        keysUsedByRenderer.forEach { key ->
            assertTrue(de.containsKey(key), "missing in de bundle: $key")
            assertTrue(en.containsKey(key), "missing in en bundle: $key")
        }
    }

    @Test
    fun `R3-T10 - the umlaut-bearing key decodes correctly, no mojibake`() {
        val value = de.getValue("payment.hint.noBankDetails")
        assertEquals("Bankverbindung auf Anfrage erhältlich.", value)
        assertFalse(value.contains("Ã"), "mojibake marker found — bundle was not read as UTF-8")
    }

    @Test
    fun `payment title, note title and tax note title are already upper-case in both bundles`() {
        // Umsetzung §2 — casing lives in the bundle, code must not .uppercase() it.
        listOf("payment.title", "note.title", "taxnote.title").forEach { key ->
            assertEquals(de.getValue(key), de.getValue(key).uppercase(), "de.$key should already be upper-case")
            assertEquals(en.getValue(key), en.getValue(key).uppercase(), "en.$key should already be upper-case")
        }
    }

    @Test
    fun `every trip expense kind label key the invoice mapper can emit exists in both bundles`() {
        listOf("hotel", "transport", "mileage", "travelTime", "other").forEach { kind ->
            assertTrue(de.containsKey("trip.kind.$kind"), "missing in de bundle: trip.kind.$kind")
            assertTrue(en.containsKey("trip.kind.$kind"), "missing in en bundle: trip.kind.$kind")
        }
    }
}
