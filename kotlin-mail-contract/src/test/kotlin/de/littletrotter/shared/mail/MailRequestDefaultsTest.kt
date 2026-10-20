package de.littletrotter.shared.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** An omitted optional field of a request or view means "not given", never a half-filled value. */
class MailRequestDefaultsTest {

    @Test
    fun `a test send without a recipient override carries an empty address`() {
        assertEquals("", MailTestSendRequest().recipientAddress)
        assertEquals("me@example.com", MailTestSendRequest("me@example.com").recipientAddress)
    }

    @Test
    fun `a template Bcc test without an override carries an empty address`() {
        assertEquals("", MailTemplateBccTestRequest().address)
        assertEquals("bcc@example.com", MailTemplateBccTestRequest("bcc@example.com").address)
    }

    @Test
    fun `an attachment view without a recorded size has no size`() {
        val view = MailJournalAttachmentView(filename = "rechnung.pdf", contentType = "application/pdf", sha256 = "ab")

        assertNull(view.sizeBytes)
        assertEquals(1024L, view.copy(sizeBytes = 1024L).sizeBytes)
    }
}
