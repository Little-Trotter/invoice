package de.littletrotter.application.reference

import de.littletrotter.domain.compliance.ChainVerification
import de.littletrotter.domain.compliance.NumberJournalEntities
import de.littletrotter.domain.compliance.NumberJournalEntry
import de.littletrotter.domain.compliance.NumberJournalEvent
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.domain.repository.InvoiceNumberAssignment
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.NumberJournalRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/**
 * COV — [NumberJournalReportService.reportFor] (task13-N4's combined audit report) had no
 * direct unit test at all before this AP. Covers the two "no evidence to report" early exits
 * (no active range, non-GAPLESS range), the subject gate that scopes reconciliation to
 * `NumberRangeSubject.INVOICE` only (every other subject must never touch [InvoiceRepository]
 * at all), and the mapNotNull skip of an ALLOCATED entry with no [NumberJournalEntry
 * .numberSequence] (IMP-2 — a made-up placeholder would point reconciliation at no real number).
 */
class NumberJournalReportServiceTest {

    private val ranges = FakeRanges()
    private val journal = mock<NumberJournalRepository>()
    private val invoiceRepo = mock<InvoiceRepository>()
    private val service = NumberJournalReportService(ranges, journal, invoiceRepo)

    private fun install(subject: NumberRangeSubject, configure: NumberRange.() -> Unit = {}): NumberRange {
        val range = NumberRange().apply {
            id = subject.ordinal.toLong() + 1
            code = "${subject.name}_DEFAULT"
            displayName = subject.name
            startValue = 1
            counterWidth = 5
            continuity = Continuity.GAPLESS
            datePart = DatePart.NONE
            resetCycle = ResetCycle.NEVER
            scope = CounterScope.GLOBAL
            prefixSource = PrefixSource.NONE
            this.subject = subject
            configure()
        }
        ranges.install(range)
        return range
    }

    private fun allocatedEntry(rangeId: Long, sequence: Long?, entityId: Long? = 1L) = NumberJournalEntry(
        numberRangeId = rangeId,
        event = NumberJournalEvent.ALLOCATED,
        periodKey = "",
        numberSequence = sequence,
        entityType = NumberJournalEntities.INVOICE,
        entityId = entityId,
        actor = "test",
        occurredAt = OffsetDateTime.now(),
        prevHash = "genesis",
        entryHash = "hash-1",
    )

    @Test
    fun `reportFor liefert null ohne aktiven Nummernkreis`() {
        val report = service.reportFor(NumberRangeSubject.INVOICE)

        assertNull(report)
        verify(journal, never()).findAll(org.mockito.kotlin.any())
    }

    @Test
    fun `reportFor liefert null fuer einen UNIQUE_ONLY-Kreis - er fuehrt kein Journal`() {
        install(NumberRangeSubject.CATALOGUE) { continuity = Continuity.UNIQUE_ONLY }

        val report = service.reportFor(NumberRangeSubject.CATALOGUE)

        assertNull(report)
    }

    @Test
    fun `reportFor liefert null fuer QUOTE - Zaehler und Journal liegen im Angebotsmodul`() {
        install(NumberRangeSubject.QUOTE)

        assertNull(service.reportFor(NumberRangeSubject.QUOTE))
        verify(journal, never()).findAll(org.mockito.kotlin.any())
    }

    @Test
    fun `reportFor rekonziliert ausschliesslich fuer den INVOICE-Kreis gegen tatsaechliche Rechnungen`() {
        val range = install(NumberRangeSubject.INVOICE)
        whenever(journal.findAll(range.id!!)).thenReturn(listOf(allocatedEntry(range.id!!, sequence = 1L, entityId = 42L)))
        whenever(invoiceRepo.findFinalizedInvoiceNumberAssignments()).thenReturn(listOf(InvoiceNumberAssignment(42L, 1L)))

        val report = service.reportFor(NumberRangeSubject.INVOICE)

        assertTrue(report != null && report.findings.isEmpty(), "a matching journal/invoice pair must reconcile cleanly")
        verify(invoiceRepo).findFinalizedInvoiceNumberAssignments()
    }

    @Test
    fun `reportFor laesst andere Kreise als INVOICE unangetastet von der Rechnungs-Rekonziliation`() {
        val range = install(NumberRangeSubject.CUSTOMER)
        whenever(journal.findAll(range.id!!)).thenReturn(emptyList())

        val report = service.reportFor(NumberRangeSubject.CUSTOMER)

        assertTrue(report != null)
        verify(invoiceRepo, never()).findFinalizedInvoiceNumberAssignments()
    }

    @Test
    fun `reportFor ueberspringt eine ALLOCATED-Eintragung ohne numberSequence bei der Rekonziliation statt einen Platzhalter zu erfinden`() {
        val range = install(NumberRangeSubject.INVOICE)
        // A malformed/legacy entry with no numberSequence at all — must not surface as a
        // reconciliation finding pointing at a fabricated number.
        whenever(journal.findAll(range.id!!)).thenReturn(listOf(allocatedEntry(range.id!!, sequence = null)))
        whenever(invoiceRepo.findFinalizedInvoiceNumberAssignments()).thenReturn(emptyList())

        val report = service.reportFor(NumberRangeSubject.INVOICE)

        assertTrue(report != null)
        assertTrue(report!!.findings.isEmpty(), "a sequence-less entry must be skipped, not turned into a fabricated reconciliation finding")
    }

    @Test
    fun `reportFor gibt den Kettenkopf-Status eines leeren Journals als gueltig zurueck`() {
        val range = install(NumberRangeSubject.INVOICE)
        whenever(journal.findAll(range.id!!)).thenReturn(emptyList())
        whenever(invoiceRepo.findFinalizedInvoiceNumberAssignments()).thenReturn(emptyList())

        val report = service.reportFor(NumberRangeSubject.INVOICE)

        assertEquals(0, report?.entryCount)
        assertTrue(report?.chainVerification is ChainVerification.Valid, "an empty journal has nothing to break the chain — must report Valid, not a false-positive break")
    }

    private class FakeRanges : NumberRangeRepository {
        private val bySubject = mutableMapOf<NumberRangeSubject, NumberRange>()
        fun install(range: NumberRange) { bySubject[range.subject] = range }
        override fun findActive(subject: NumberRangeSubject) = bySubject[subject]
        override fun findBySubject(subject: NumberRangeSubject) = listOfNotNull(bySubject[subject])
        override fun findById(id: Long) = bySubject.values.firstOrNull { it.id == id }
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?) = bySubject.values.toList()
        override fun count(filterText: String?) = bySubject.size.toLong()
        override fun countInactive(filterText: String?) = 0L
        override fun save(range: NumberRange): NumberRange { bySubject[range.subject] = range; return range }
        override fun delete(id: Long) { bySubject.entries.removeIf { it.value.id == id } }
    }
}
