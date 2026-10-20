package de.littletrotter.shared.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The subject, stream and enum *names* are the contract. Renaming one of them is a
 * two-repository change (the mail component's `application.properties` defaults and its
 * `MailNatsStreamSetup` carry the same literals); these assertions make an accidental rename fail
 * here instead of at runtime, where the symptom is a consumer bound to a stream nobody feeds.
 */
class MailContractNamesTest {

    @Test
    fun `subject names match the mail component's configured defaults`() {
        assertEquals("mail.send.requested", MailSubjects.SEND_REQUESTED)
        assertEquals("mail.send.result", MailSubjects.SEND_RESULT)
    }

    @Test
    fun `stream names match MailNatsStreamSetup`() {
        assertEquals("LITTLE_TROTTER_MAIL", MailStreams.MAIL)
        assertEquals("LITTLE_TROTTER_MAIL_RESULT", MailStreams.RESULT)
    }

    @Test
    fun `document family is a closed two-constant wire enum, serialized by name`() {
        assertEquals(listOf("GERMAN", "INTERNATIONAL"), MailDocumentFamily.entries.map { it.name })
    }

    @Test
    fun `the result outcome carries only the two terminal states, not the component's lifecycle`() {
        assertEquals(listOf("SENT", "FAILED"), MailSendOutcome.entries.map { it.name })
    }

    @Test
    fun `placeholder value types match the component's own type tokens`() {
        assertEquals(listOf("STRING", "DATE", "MONEY"), MailPlaceholderValueType.entries.map { it.name })
    }

    @Test
    fun `document kind is a closed two-constant wire enum, serialized by name`() {
        assertEquals(listOf("INVOICE_DISPATCH", "QUOTE_DISPATCH"), MailDocumentKind.entries.map { it.name })
    }

    @Test
    fun `schema version is stamped by default on both wire types`() {
        assertEquals(5, MailContract.SCHEMA_VERSION)
        assertEquals(
            MailContract.SCHEMA_VERSION,
            MailComposeRequest(
                referenceKey = "RE-1",
                documentFamily = MailDocumentFamily.GERMAN,
                recipientAddress = "a@b.de",
                recipientName = "A",
            ).schemaVersion,
        )
        assertEquals(
            MailContract.SCHEMA_VERSION,
            MailSendResultEvent(
                journalId = 1,
                referenceKey = "RE-1",
                status = MailSendOutcome.SENT,
                messageId = "<m@lt>",
                sentAt = java.time.Instant.parse("2026-09-07T10:15:30Z"),
            ).schemaVersion,
        )
    }
}
