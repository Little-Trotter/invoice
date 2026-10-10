package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle

/** [JournalActionLabels] action→(i18n key, CSS modifier) mapping, plus the completeness
 *  half the spec asks for: every one of the 18 [InvoiceAuditAction] values has a
 *  `journal.action.<NAME>` key in BOTH bundles (de default, en backup). Same shape as
 *  [JournalEventLabelsTest]. */
class JournalActionLabelsTest {

    private val german = ResourceBundle.getBundle(
        "i18n.messages", Locale.GERMAN, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )
    private val english = ResourceBundle.getBundle(
        "i18n.messages", Locale.ENGLISH, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )

    @Test
    fun `every InvoiceAuditAction has a non-blank label in the German default bundle`() {
        InvoiceAuditAction.entries.forEach { action ->
            val key = JournalActionLabels.labelKey(action)
            assertTrue(german.containsKey(key), "German bundle missing key $key for $action")
            assertFalse(german.getString(key).isBlank(), "German translation blank for $key")
        }
    }

    @Test
    fun `every InvoiceAuditAction has a non-blank label in the English backup bundle`() {
        InvoiceAuditAction.entries.forEach { action ->
            val key = JournalActionLabels.labelKey(action)
            assertTrue(english.containsKey(key), "English bundle missing key $key for $action")
            assertFalse(english.getString(key).isBlank(), "English translation blank for $key")
        }
    }

    @Test
    fun `every InvoiceAuditAction label key follows the journal-action-NAME convention`() {
        InvoiceAuditAction.entries.forEach { action ->
            assertEquals("journal.action.${action.name}", JournalActionLabels.labelKey(action))
        }
    }

    @Test
    fun `only UNSPECIFIED is flagged as an external write`() {
        InvoiceAuditAction.entries.forEach { action ->
            assertEquals(action == InvoiceAuditAction.UNSPECIFIED, JournalActionLabels.isExternalWrite(action))
        }
    }

    @Test
    fun `every enum value gets exactly one of the three chip modifiers - no new colour is introduced`() {
        val modifiers = InvoiceAuditAction.entries.map { JournalActionLabels.cssModifier(it) }.toSet()
        assertEquals(setOf("success", "warning", "cancelled"), modifiers)
    }
}
