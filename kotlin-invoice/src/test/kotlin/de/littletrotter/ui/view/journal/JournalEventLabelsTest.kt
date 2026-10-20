package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.ui.component.ChipTone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle

/** [JournalEventLabels] event→(i18n key, CSS modifier) mapping, data-driven over every
 *  [InvoiceAuditEvent] rather than one assertion per value (per this suite's own test note),
 *  plus the completeness check the spec asks for: every event value has a
 *  `journal.event.<NAME>` key in BOTH bundles (de default, en backup — `code-language-english.md`
 *  makes i18n VALUES the one exception to English-only code, not the completeness that both
 *  bundles carry the same keys). */
class JournalEventLabelsTest {

    // Same no-fallback loading InvoiceDetailTranslationsTest/TranslationService use — a request
    // for the English base bundle must not silently resolve messages_de.properties instead.
    private val german = ResourceBundle.getBundle(
        "i18n.messages", Locale.GERMAN, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )
    private val english = ResourceBundle.getBundle(
        "i18n.messages", Locale.ENGLISH, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )

    @Test
    fun `every InvoiceAuditEvent has a non-blank label in the German default bundle`() {
        InvoiceAuditEvent.entries.forEach { event ->
            val key = JournalEventLabels.labelKey(event)
            assertTrue(german.containsKey(key), "German bundle missing key $key for $event")
            assertFalse(german.getString(key).isBlank(), "German translation blank for $key")
        }
    }

    @Test
    fun `every InvoiceAuditEvent has a non-blank label in the English backup bundle`() {
        InvoiceAuditEvent.entries.forEach { event ->
            val key = JournalEventLabels.labelKey(event)
            assertTrue(english.containsKey(key), "English bundle missing key $key for $event")
            assertFalse(english.getString(key).isBlank(), "English translation blank for $key")
        }
    }

    @Test
    fun `every InvoiceAuditEvent label key follows the journal-event-NAME convention`() {
        InvoiceAuditEvent.entries.forEach { event ->
            assertEquals("journal.event.${event.name}", JournalEventLabels.labelKey(event))
        }
    }

    @Test
    fun `CREATED events map to the success chip modifier`() {
        listOf(
            InvoiceAuditEvent.INVOICE_CREATED,
            InvoiceAuditEvent.INVOICE_ITEM_CREATED,
            InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED,
        ).forEach { event -> assertEquals("success", JournalEventLabels.cssModifier(event)) }
    }

    @Test
    fun `UPDATED events map to the warning chip modifier`() {
        listOf(
            InvoiceAuditEvent.INVOICE_UPDATED,
            InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            InvoiceAuditEvent.INVOICE_DOCUMENT_UPDATED,
        ).forEach { event -> assertEquals("warning", JournalEventLabels.cssModifier(event)) }
    }

    @Test
    fun `DELETED events map to the cancelled chip modifier`() {
        listOf(
            InvoiceAuditEvent.INVOICE_DELETED,
            InvoiceAuditEvent.INVOICE_ITEM_DELETED,
            InvoiceAuditEvent.INVOICE_DOCUMENT_DELETED,
        ).forEach { event -> assertEquals("cancelled", JournalEventLabels.cssModifier(event)) }
    }

    @Test
    fun `receipt assignment events identify the receipt and use action-specific verbs`() {
        assertEquals(InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED, InvoiceAuditEvent.fromOrdinal(12))
        assertEquals("success", JournalEventLabels.cssModifier(InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED))
        assertEquals("cancelled", JournalEventLabels.cssModifier(InvoiceAuditEvent.ITEM_RECEIPT_UNASSIGNED))
        assertEquals(JournalEventLabels.Entity.RECEIPT, JournalEventLabels.entityOf(InvoiceAuditEvent.ITEM_RECEIPT_ASSIGNED))
    }

    @Test
    fun `every enum value gets exactly one of the three chip modifiers`() {
        val modifiers = InvoiceAuditEvent.entries.map { JournalEventLabels.cssModifier(it) }.toSet()
        assertEquals(setOf("success", "warning", "cancelled"), modifiers)
    }

    @Test
    fun `entityLabelKey follows the journal-entity-NAME convention, from entityOf's own result`() {
        assertEquals("journal.entity.INVOICE", JournalEventLabels.entityLabelKey(InvoiceAuditEvent.INVOICE_CREATED))
        assertEquals("journal.entity.RECEIPT", JournalEventLabels.entityLabelKey(InvoiceAuditEvent.ITEM_RECEIPT_ASSIGNED))
    }

    @Test
    fun `tone maps every event by its verb`() {
        InvoiceAuditEvent.entries.forEach { event ->
            val expected = when (JournalEventLabels.verbOf(event)) {
                JournalEventLabels.Verb.CREATED, JournalEventLabels.Verb.ASSIGNED -> ChipTone.OK
                JournalEventLabels.Verb.UPDATED -> ChipTone.WARN
                JournalEventLabels.Verb.DELETED, JournalEventLabels.Verb.UNASSIGNED -> ChipTone.CANCELLATION
            }
            assertEquals(expected, JournalEventLabels.tone(event), "event=$event")
        }
    }
}
