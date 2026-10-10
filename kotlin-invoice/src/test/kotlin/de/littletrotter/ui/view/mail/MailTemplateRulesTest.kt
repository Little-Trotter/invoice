package de.littletrotter.ui.view.mail

import de.littletrotter.application.mail.port.inbound.MailPlaceholderEntry
import de.littletrotter.application.mail.port.inbound.MailPlaceholderType
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.domain.shared.InvoiceFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MailTemplateRules — placeholder check, grouping and samples")
class MailTemplateRulesTest {

    private fun entry(key: String, type: MailPlaceholderType = MailPlaceholderType.TEXT) = MailPlaceholderEntry(key, type, "s-$key")

    private val catalog = listOf(entry("invoiceNumber"), entry("quoteNumber"), entry("recipientName"))

    @Test
    fun `the document number is required per kind`() {
        assertEquals("invoiceNumber", MailTemplateRules.requiredKey(MailTemplateKind.INVOICE_DISPATCH))
        assertEquals("quoteNumber", MailTemplateRules.requiredKey(MailTemplateKind.QUOTE_DISPATCH))
    }

    @Test
    fun `check reports unknown and missing tokens once`() {
        val problems = MailTemplateRules.check(MailTemplateKind.QUOTE_DISPATCH, "{{foo}} {{foo}}", "{{recipientName}} {{bar}}", catalog)

        assertEquals(listOf("foo", "bar"), problems.unknown)
        assertEquals(listOf("quoteNumber"), problems.missing)
        assertTrue(problems.any)
    }

    @Test
    fun `a section marker with an unknown name is a problem, a known one is not`() {
        val problems = MailTemplateRules.check(MailTemplateKind.INVOICE_DISPATCH, "{{invoiceNumber}}", "{{#nope}}x{{/nope}}", catalog)

        assertEquals(listOf("nope"), problems.unknown)
        assertEquals(emptyList<String>(), problems.missing)
    }

    @Test
    fun `a complete template has no problems and the number may sit in the subject or the body`() {
        assertFalse(MailTemplateRules.check(MailTemplateKind.INVOICE_DISPATCH, "{{invoiceNumber}}", "x", catalog).any)
        assertFalse(MailTemplateRules.check(MailTemplateKind.INVOICE_DISPATCH, "x", "{{invoiceNumber}}", catalog).any)
    }

    @Test
    fun `groups follow the key and fall back to the value type`() {
        assertEquals(MailPlaceholderGroup.DOCUMENT, MailTemplateRules.groupOf(entry("quoteNumber")))
        assertEquals(MailPlaceholderGroup.AMOUNTS, MailTemplateRules.groupOf(entry("iban")))
        assertEquals(MailPlaceholderGroup.DATES, MailTemplateRules.groupOf(entry("validUntil")))
        assertEquals(MailPlaceholderGroup.SENDER, MailTemplateRules.groupOf(entry("senderPhone")))
        assertEquals(MailPlaceholderGroup.RECIPIENT, MailTemplateRules.groupOf(entry("recipientName")))
        assertEquals(MailPlaceholderGroup.AMOUNTS, MailTemplateRules.groupOf(entry("x", MailPlaceholderType.MONEY)))
        assertEquals(MailPlaceholderGroup.DATES, MailTemplateRules.groupOf(entry("y", MailPlaceholderType.DATE)))
        assertEquals(MailPlaceholderGroup.OTHER, MailTemplateRules.groupOf(entry("z")))
    }

    @Test
    fun `samples and families come from the catalogue and the language`() {
        assertEquals(mapOf("invoiceNumber" to "s-invoiceNumber"), MailTemplateRules.sampleValues(listOf(entry("invoiceNumber"))))
        assertEquals(InvoiceFormat.GERMAN, MailTemplateRules.familyOf(MailTemplateLanguage.DE))
        assertEquals(InvoiceFormat.INTERNATIONAL, MailTemplateRules.familyOf(MailTemplateLanguage.EN))
        assertEquals("a s-x {{y}}", MailTemplateRules.fill("a {{x}} {{y}}", mapOf("x" to "s-x")))
    }
}
