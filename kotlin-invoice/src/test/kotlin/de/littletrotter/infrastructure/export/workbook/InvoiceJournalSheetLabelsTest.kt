package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [InvoiceJournalSheetWriterTest] always passes an explicit [InvoiceJournalSheetLabels
 * .actionLabels]; this class covers the default (empty map, kept for test/caller
 * compatibility per the class's own KDoc) plus the plain data-holder behaviour.
 */
class InvoiceJournalSheetLabelsTest {

    @Test
    fun `happy path - carries the given column headers and label maps verbatim`() {
        val labels = InvoiceJournalSheetLabels(
            columnHeaders = listOf("Datum", "Ereignis"),
            eventLabels = mapOf(InvoiceAuditEvent.INVOICE_CREATED to "Erstellt"),
            noEntriesNoteLabel = "Keine Journal-Eintraege",
            actionLabels = mapOf(InvoiceAuditAction.INVOICE_CREATED to "Rechnung erstellt"),
        )

        assertThat(labels.columnHeaders).containsExactly("Datum", "Ereignis")
        assertThat(labels.eventLabels).containsEntry(InvoiceAuditEvent.INVOICE_CREATED, "Erstellt")
        assertThat(labels.noEntriesNoteLabel).isEqualTo("Keine Journal-Eintraege")
        assertThat(labels.actionLabels).containsEntry(InvoiceAuditAction.INVOICE_CREATED, "Rechnung erstellt")
    }

    @Test
    fun `boundary - actionLabels defaults to an empty map when the caller omits it`() {
        val labels = InvoiceJournalSheetLabels(
            columnHeaders = listOf("Datum"),
            eventLabels = emptyMap(),
            noEntriesNoteLabel = "Keine Journal-Eintraege",
        )

        assertThat(labels.actionLabels).isEmpty()
    }

    @Test
    fun `boundary - two instances with equal fields are equal, a differing action map is not`() {
        val base = InvoiceJournalSheetLabels(
            columnHeaders = listOf("Datum"),
            eventLabels = emptyMap(),
            noEntriesNoteLabel = "Keine Journal-Eintraege",
        )
        val same = base.copy()
        val different = base.copy(actionLabels = mapOf(InvoiceAuditAction.HEADER_CHANGED to "Kopfdaten geaendert"))

        assertThat(base).isEqualTo(same)
        assertThat(base).isNotEqualTo(different)
    }
}
