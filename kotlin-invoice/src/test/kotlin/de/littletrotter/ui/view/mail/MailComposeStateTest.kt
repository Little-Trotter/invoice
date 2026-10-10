package de.littletrotter.ui.view.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MailComposeState — what the send bar announces")
class MailComposeStateTest {

    private fun of(
        recipientValid: Boolean = true,
        subject: String = "Betreff",
        body: String = "Text",
        rendered: String = "Text",
        unresolved: List<String> = emptyList(),
    ) = MailComposeState.of(MailComposeFields(recipientValid, subject, body, rendered, unresolved))

    @Test
    fun `an untouched template is the calm state`() {
        assertEquals(MailComposeState.TEMPLATE, of())
        assertFalse(MailComposeState.TEMPLATE.isBlocker)
    }

    @Test
    fun `a changed body is an edit`() {
        assertEquals(MailComposeState.EDITED, of(body = "Anders"))
        assertFalse(MailComposeState.EDITED.isBlocker)
    }

    @Test
    fun `an open placeholder blocks and beats an edit`() {
        assertEquals(MailComposeState.UNRESOLVED, of(body = "Anders", unresolved = listOf("dueDate")))
        assertTrue(MailComposeState.UNRESOLVED.isBlocker)
    }

    @Test
    fun `an invalid recipient wins over every other state`() {
        assertEquals(MailComposeState.RECIPIENT, of(recipientValid = false, subject = " ", body = "", unresolved = listOf("x")))
        assertTrue(MailComposeState.RECIPIENT.isBlocker)
    }

    @Test
    fun `a blank subject wins over a blank body and placeholders`() {
        assertEquals(MailComposeState.SUBJECT, of(subject = " ", body = "", unresolved = listOf("x")))
    }

    @Test
    fun `a blank body wins over placeholders`() {
        assertEquals(MailComposeState.BODY, of(body = " ", unresolved = listOf("x")))
        assertTrue(MailComposeState.BODY.isBlocker)
    }
}
