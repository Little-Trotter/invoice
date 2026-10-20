package de.littletrotter.ui.view.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MailPlaceholderTextTest {

    private val values = mapOf("invoiceNumber" to "RE-1", "dueDate" to "01.11.2026")

    @Test
    fun `known tokens are replaced and unknown ones stay as typed`() {
        assertEquals("Rechnung RE-1 {{other}}", MailPlaceholderText.resolve("Rechnung {{invoiceNumber}} {{other}}", values))
    }

    @Test
    fun `an unclosed brace pair is not a token`() {
        assertEquals("{{invoiceNumber", MailPlaceholderText.resolve("{{invoiceNumber", values))
    }

    @Test
    fun `unresolved lists each missing name once in order`() {
        assertEquals(listOf("a", "b"), MailPlaceholderText.unresolved("{{a}} {{invoiceNumber}} {{b}} {{a}}", values))
    }

    @Test
    fun `nothing is unresolved when every token has a value`() {
        assertEquals(emptyList<String>(), MailPlaceholderText.unresolved("{{dueDate}}", values))
    }

    @Test
    fun `a section with a value is kept, its tokens filled and its markers removed`() {
        val text = "A {{#dueDate}}bis {{dueDate}}, Nr. {{invoiceNumber}}{{/dueDate}} Ende"
        assertEquals("A bis 01.11.2026, Nr. RE-1 Ende", MailPlaceholderText.resolve(text, values))
        assertEquals("A bis 01.11.2026, Nr. RE-1 Ende", MailPlaceholderText.resolveBody(text, values))
    }

    @Test
    fun `a section without or with a blank value is dropped with its unknown tokens`() {
        val text = "A {{#iban}}Konto {{iban}}{{/iban}}\n{{#other}}x{{/other}}Ende"
        assertEquals("A \nEnde", MailPlaceholderText.resolve(text, values + ("iban" to " ")))
        assertEquals("A \nEnde", MailPlaceholderText.resolveBody(text, values))
        assertEquals(emptyList<String>(), MailPlaceholderText.unresolved(text, values))
    }

    @Test
    fun `standalone section markers vanish with their lines whether the section is kept or dropped`() {
        val text = "A\n\n{{#dueDate}}\nbis {{dueDate}}\n\n{{/dueDate}}\nEnde"
        assertEquals("A\n\nbis 01.11.2026\n\nEnde", MailPlaceholderText.resolve(text, values))
        assertEquals("A\n\nEnde", MailPlaceholderText.resolveBody(text, values - "dueDate"))
    }

    @Test
    fun `standalone markers tolerate spaces tabs and CRLF`() {
        val text = "A\r\n\r\n \t{{#dueDate}} \r\nbis\r\n\r\n{{/dueDate}}\t\r\nEnde"
        assertEquals("A\r\n\r\nbis\r\n\r\nEnde", MailPlaceholderText.resolve(text, values))
        assertEquals("A\r\n\r\nEnde", MailPlaceholderText.resolve(text, values - "dueDate"))
    }

    @Test
    fun `inline markers keep their line breaks`() {
        val text = "A\n{{#dueDate}}bis{{/dueDate}}\nEnde"
        assertEquals("A\nbis\nEnde", MailPlaceholderText.resolve(text, values))
        assertEquals("A\n\nEnde", MailPlaceholderText.resolve(text, values - "dueDate"))
    }

    @Test
    fun `section markers are listed by name`() {
        assertEquals(listOf("a", "a"), MailPlaceholderText.sectionNames("{{#a}}x{{/a}}"))
    }

    @Test
    fun `a chip token wraps the key`() {
        assertEquals("{{dueDate}}", MailPlaceholderText.token("dueDate"))
    }
}
