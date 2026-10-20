package de.littletrotter.ui.view.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [JournalFieldLabels]: a curated label wins, and a column without one
 *  still reads as words rather than as the SQL identifier the trigger wrote. */
class JournalFieldLabelsTest {

    @Test
    fun `a defined journal field key wins over the humanized fallback`() {
        val label = JournalFieldLabels.resolve("cost_center") { key ->
            if (key == "journal.field.cost_center") "Kostenstelle" else null
        }

        assertEquals("Kostenstelle", label)
    }

    @Test
    fun `an undefined column falls back to readable words, never to the raw identifier`() {
        assertEquals("Payment terms days", JournalFieldLabels.resolve("payment_terms_days") { null })
    }

    @Test
    fun `humanize capitalises the first word only and drops the separators`() {
        assertEquals("Invoice number", JournalFieldLabels.humanize("invoice_number"))
        assertEquals("Id", JournalFieldLabels.humanize("id"))
    }

    @Test
    fun `humanize leaves a column name it cannot split alone`() {
        assertEquals("_", JournalFieldLabels.humanize("_"))
    }

    @Test
    fun `labelKey is namespaced per column so the bundle can define one label per column`() {
        assertEquals("journal.field.free_text", JournalFieldLabels.labelKey("free_text"))
    }
}
