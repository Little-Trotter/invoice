package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.fixture.aTripExpense
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem

class PdfRenderableTextGuardTest {

    private fun item(description: String) = anInvoiceItem(positionId = "1", description = description)

    @Test
    fun `German typography passes`() {
        val invoice = anInvoice(freeText = "Größe „A“ – 5 € ß\nZeile 2", invoiceNote = "Äöü")
        invoice.addItem(item("Beratung für Müller & Söhne"))

        assertDoesNotThrow { invoice.ensureTextRenderableInPdf() }
    }

    @Test
    fun `emoji in free text is reported with its field`() {
        val invoice = anInvoice(freeText = "Danke 💩")

        val ex = assertThrows(PdfUnrenderableTextException::class.java) { invoice.ensureTextRenderableInPdf() }
        assertEquals(mapOf("Freitext" to listOf("💩")), ex.fields)
        assertTrue(ex.message!!.contains("Freitext (💩)"))
    }

    @Test
    fun `item description, reference and note are checked with position label`() {
        val invoice = anInvoice(
            references = InvoiceReferencesChange(purchaseOrderReference = "PO\u0007"),
            invoiceNote = "漢",
            consumerNote = "😀",
        )
        invoice.addItem(item("Leistung 😀"))

        val fields = invoice.pdfUnrenderableTextFields()
        assertEquals(listOf("漢"), fields["Rechnungshinweis"])
        assertEquals(listOf("😀"), fields["Privatkunden-Hinweis"])
        assertEquals(listOf("U+0007"), fields["Bestellnummer"])
        assertEquals(listOf("😀"), fields["Position 1: Bezeichnung"])
    }

    @Test
    fun `trip expense text and additional B2B texts are reported`() {
        val trip = item("Reise").apply {
            addTripExpense(aTripExpense(description = "Taxi 🚕"))
        }
        val invoice = Invoice()
        invoice.addItem(trip)

        val fields = invoice.pdfUnrenderableTextFields(mapOf("B2B-Feld BT-18" to "x😀"))
        assertEquals(listOf("🚕"), fields["Position 1: Reisekosten"])
        assertEquals(listOf("😀"), fields["B2B-Feld BT-18"])
    }
}
