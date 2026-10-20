package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [InvoiceTexts]: the note snapshots must survive being applied and read back unchanged. */
class InvoiceTextsTest {

    @Test
    fun `a fresh texts value carries the entity defaults`() {
        val texts = InvoiceTexts()

        assertNull(texts.freeText)
        assertNull(texts.invoiceNote)
        assertEquals(InvoiceNoteOrigin.SELLER_DEFAULT, texts.invoiceNoteOrigin)
        assertEquals("de", texts.invoiceNoteSourceLanguage)
        assertEquals("", texts.consumerNoteSourceContext)
        assertEquals(emptySet<String>(), texts.importedB2bFreeTextCodes())
    }

    @Test
    fun `invoice note snapshot round trips through apply and read`() {
        val texts = InvoiceTexts()
        val snapshot = InvoiceNoteSnapshot("Zahlbar sofort", InvoiceNoteOrigin.MANUAL, 7L, "en", "Default note")

        texts.applyInvoiceNote(snapshot)

        assertEquals(snapshot, texts.invoiceNoteSnapshot())
        assertEquals("Zahlbar sofort", texts.invoiceNote)
        assertEquals(7L, texts.invoiceNoteSourceSenderProfileId)
    }

    @Test
    fun `consumer note snapshot round trips including its source context`() {
        val texts = InvoiceTexts()
        val snapshot = InvoiceNoteSnapshot("Widerruf", InvoiceNoteOrigin.MANUAL, 7L, "en", "Default", "CONSUMER")

        texts.applyConsumerNote(snapshot)

        assertEquals(snapshot, texts.consumerNoteSnapshot())
        assertEquals("CONSUMER", texts.consumerNoteSourceContext)
    }

    @Test
    fun `the two note snapshots do not overwrite each other`() {
        val texts = InvoiceTexts()

        texts.applyInvoiceNote(InvoiceNoteSnapshot("seller", InvoiceNoteOrigin.MANUAL, 1L, "de", null))
        texts.applyConsumerNote(InvoiceNoteSnapshot(null, InvoiceNoteOrigin.SELLER_DEFAULT, 2L, "en", "d", "ctx"))

        assertEquals("seller", texts.invoiceNote)
        assertNull(texts.consumerNote)
        assertEquals(InvoiceNoteOrigin.MANUAL, texts.invoiceNoteOrigin)
        assertEquals(InvoiceNoteOrigin.SELLER_DEFAULT, texts.consumerNoteOrigin)
    }

    @Test
    fun `changeFreeText sets and clears the free text`() {
        val texts = InvoiceTexts()

        texts.changeFreeText("Danke")
        assertEquals("Danke", texts.freeText)

        texts.changeFreeText(null)
        assertNull(texts.freeText)
    }

    @Test
    fun `imported codes are stored sorted and without duplicates`() {
        val texts = InvoiceTexts()

        texts.replaceImportedB2bFreeTextCodes(setOf("BT-19", "BT-12", "BT-19"))

        assertEquals(setOf("BT-12", "BT-19"), texts.importedB2bFreeTextCodes())
    }

    @Test
    fun `replacing the imported codes discards the previous ones`() {
        val texts = InvoiceTexts()
        texts.replaceImportedB2bFreeTextCodes(setOf("BT-12", "BT-19"))

        texts.replaceImportedB2bFreeTextCodes(setOf("BT-13"))

        assertEquals(setOf("BT-13"), texts.importedB2bFreeTextCodes())
    }
}
