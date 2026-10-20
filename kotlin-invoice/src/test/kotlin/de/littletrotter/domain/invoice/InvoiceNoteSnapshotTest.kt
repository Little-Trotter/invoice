package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InvoiceNoteSnapshotTest {
    @Test
    fun `initial snapshot records empty seller default and provenance`() {
        val snapshot = initialInvoiceNoteSnapshot(7, "de", null)

        assertNull(snapshot.value)
        assertEquals(InvoiceNoteOrigin.SELLER_DEFAULT, snapshot.origin)
        assertEquals(7, snapshot.sourceSenderProfileId)
        assertEquals("de", snapshot.sourceLanguage)
        assertNull(snapshot.sourceDefaultValue)
    }

    @Test
    fun `manual edit and deliberate clearing remain manual across source changes`() {
        val initial = initialInvoiceNoteSnapshot(7, "de", "Default")
        val edited = updateInvoiceNoteSnapshot(initial, NoteSubmission(true, "Own note", false), SellerNoteDefault(7, "de", "Default"))
        val cleared = updateInvoiceNoteSnapshot(edited, NoteSubmission(true, "", false), SellerNoteDefault(7, "de", "Default"))
        val afterSenderChange = updateInvoiceNoteSnapshot(cleared, NoteSubmission(true, null, false), SellerNoteDefault(8, "en", "New default"))

        assertEquals("Own note", edited.value)
        assertEquals(InvoiceNoteOrigin.MANUAL, edited.origin)
        assertNull(cleared.value)
        assertEquals(InvoiceNoteOrigin.MANUAL, cleared.origin)
        assertEquals(cleared, afterSenderChange)
    }

    @Test
    fun `untouched default follows sender and language changes`() {
        val initial = initialInvoiceNoteSnapshot(7, "de", "Deutsch")
        val senderChanged = updateInvoiceNoteSnapshot(initial, NoteSubmission(true, "Deutsch", false), SellerNoteDefault(8, "de", "Neuer Anbieter"))
        val languageChanged = updateInvoiceNoteSnapshot(senderChanged, NoteSubmission(false, null, false), SellerNoteDefault(8, "en", "English"))

        assertEquals("Neuer Anbieter", senderChanged.value)
        assertEquals(8, senderChanged.sourceSenderProfileId)
        assertEquals("English", languageChanged.value)
        assertEquals("en", languageChanged.sourceLanguage)
    }

    @Test
    fun `unchanged source leaves default snapshot untouched`() {
        val initial = initialInvoiceNoteSnapshot(null, "de", "Default")
        val updated = updateInvoiceNoteSnapshot(initial, NoteSubmission(false, null, false), SellerNoteDefault(null, "de", "Changed master data"))

        assertEquals(initial, updated)
    }

    @Test
    fun `reset explicitly restores current default tracking`() {
        val manual = InvoiceNoteSnapshot("Own", InvoiceNoteOrigin.MANUAL, 7, "de", "Old")
        val reset = updateInvoiceNoteSnapshot(manual, NoteSubmission(true, "Own", true), SellerNoteDefault(9, "en", "Current"))

        assertEquals(initialInvoiceNoteSnapshot(9, "en", "Current"), reset)
    }

    @Test
    fun `G5 untouched default follows a source context change and back`() {
        val consumer = initialInvoiceNoteSnapshot(7, "de", "Hinweis", "CONSUMER")
        val business = updateInvoiceNoteSnapshot(consumer, NoteSubmission(false, null, false), SellerNoteDefault(7, "de", null, "BUSINESS"))
        val consumerAgain = updateInvoiceNoteSnapshot(business, NoteSubmission(false, null, false), SellerNoteDefault(7, "de", "Hinweis", "CONSUMER"))

        assertNull(business.value)
        assertEquals("BUSINESS", business.sourceContext)
        assertEquals(consumer, consumerAgain)
    }

    @Test
    fun `G6 manual value stays when only the source context changes`() {
        val consumer = initialInvoiceNoteSnapshot(7, "de", "Hinweis", "CONSUMER")
        val edited = updateInvoiceNoteSnapshot(consumer, NoteSubmission(true, "Eigen", false), SellerNoteDefault(7, "de", "Hinweis", "CONSUMER"))
        val switched = updateInvoiceNoteSnapshot(edited, NoteSubmission(true, "Eigen", false), SellerNoteDefault(7, "de", null, "BUSINESS"))

        assertEquals(edited, switched)
    }

    @Test
    fun `context defaults to empty so existing seller note behaviour is unchanged`() {
        assertEquals("", initialInvoiceNoteSnapshot(1, "de", "x").sourceContext)
    }
}
