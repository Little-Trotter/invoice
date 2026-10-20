package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [reconcileAgainstInvoices] — the check [buildReport] alone can't do. Pure set difference, no
 *  database. */
class NumberJournalReconciliationTest {

    @Test
    fun `matching journal and invoice entries produce no findings`() {
        val findings = reconcileAgainstInvoices(
            journalAssignments = listOf(1L to 1L, 2L to 2L),
            invoiceAssignments = listOf(1L to 1L, 2L to 2L),
        )
        assertTrue(findings.isEmpty())
    }

    // A number written straight into the database, bypassing the allocation service: no journal
    // entry exists for it.
    @Test
    fun `an invoice number with no matching journal entry is InvoiceWithoutJournalEntry`() {
        val findings = reconcileAgainstInvoices(
            journalAssignments = listOf(1L to 1L),
            invoiceAssignments = listOf(1L to 1L, 2L to 2L),
        )
        assertEquals(listOf(NumberJournalFinding.InvoiceWithoutJournalEntry(2L, 2L)), findings)
    }

    // An invoice was removed outside the application: the journal entry stays (append-only),
    // but no invoice matches it anymore.
    @Test
    fun `a journal entry with no matching invoice is JournalEntryWithoutInvoice`() {
        val findings = reconcileAgainstInvoices(
            journalAssignments = listOf(1L to 1L, 2L to 2L),
            invoiceAssignments = listOf(1L to 1L),
        )
        assertEquals(listOf(NumberJournalFinding.JournalEntryWithoutInvoice(2L, 2L)), findings)
    }

    @Test
    fun `a journal entry without an entityId at all is reported, never silently matched`() {
        val findings = reconcileAgainstInvoices(
            journalAssignments = listOf(null to 1L),
            invoiceAssignments = emptyList(),
        )
        assertEquals(listOf(NumberJournalFinding.JournalEntryWithoutInvoice(null, 1L)), findings)
    }

    @Test
    fun `a mismatched sequence for the same entity counts as both directions of finding`() {
        // Same invoice, but the journal entry names a different sequence than the invoice itself
        // carries -- neither side may let that pass as "close enough".
        val findings = reconcileAgainstInvoices(
            journalAssignments = listOf(1L to 1L),
            invoiceAssignments = listOf(1L to 2L),
        )
        assertEquals(
            setOf(
                NumberJournalFinding.InvoiceWithoutJournalEntry(1L, 2L),
                NumberJournalFinding.JournalEntryWithoutInvoice(1L, 1L),
            ),
            findings.toSet(),
        )
    }

    @Test
    fun `both empty is clean`() {
        assertTrue(reconcileAgainstInvoices(emptyList(), emptyList()).isEmpty())
    }

    // ─── IMP-2 — two allocations for the same invoice ─────────────────

    /** `associateBy` would silently drop the first of two entries for the same invoice — and
     *  exactly "one invoice pulled two numbers" would then vanish from the report. */
    @Test
    fun `IMP-2 - zwei Journaleintraege fuer denselben Beleg sind selbst ein Befund`() {
        val findings = reconcileAgainstInvoices(
            journalAssignments = listOf(1L to 7L, 1L to 8L),
            invoiceAssignments = listOf(1L to 8L),
        )

        assertTrue(
            NumberJournalFinding.MultipleJournalEntriesForInvoice(1L, listOf(7L, 8L)) in findings,
            "die Mehrfachvergabe muss benannt werden: $findings",
        )
        // 7 no longer belongs to any invoice -- the invoice carries 8. Both statements appear in
        // the report; they describe different sides of the same anomaly.
        assertTrue(NumberJournalFinding.JournalEntryWithoutInvoice(1L, 7L) in findings)
    }

    /** The converse of the previous case: the invoice carries one of the two numbers, so it must
     *  **not** additionally be reported as "without a journal entry". */
    @Test
    fun `IMP-2 - ein Beleg mit passender zweiter Vergabe gilt nicht als eintragslos`() {
        val findings = reconcileAgainstInvoices(
            journalAssignments = listOf(1L to 7L, 1L to 8L),
            invoiceAssignments = listOf(1L to 8L),
        )

        assertTrue(
            findings.none { it is NumberJournalFinding.InvoiceWithoutJournalEntry },
            "zur Nummer 8 gibt es sehr wohl einen Eintrag: $findings",
        )
    }
}
