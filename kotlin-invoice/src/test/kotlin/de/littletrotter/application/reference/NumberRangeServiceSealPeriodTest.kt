package de.littletrotter.application.reference

import de.littletrotter.domain.compliance.NumberJournalEvent
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.fixture.NumberRangeEditor
import de.littletrotter.fixture.edit
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.NoActiveNumberRangeException
import de.littletrotter.domain.reference.NotSealableException
import de.littletrotter.domain.reference.NumberContext
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PeriodAlreadySealedException
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * COV — [NumberRangeService.sealPeriod] (the compliance-critical audit-chain sealing step) had
 * no direct unit test at all before this AP; only reachable at the IT level
 * ([NumberJournalReportServiceIT]) against a real Postgres. Covers the guard order (UNIQUE_ONLY
 * refusal before the double-seal guard), the double-seal guard preserving the original chain
 * head in its exception, and the sealed entry's summary fields (count/first/last number) both
 * with and without any prior allocation in the period.
 */
class NumberRangeServiceSealPeriodTest {

    private val ranges = FakeRanges()
    private val counters = FakeCounters()
    private val journal = RecordingNumberJournalRepository()
    private val service = NumberRangeService(
        ranges, counters, journal,
        de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
    )

    private fun install(subject: NumberRangeSubject, configure: NumberRangeEditor.() -> Unit = {}): NumberRange {
        val range = NumberRange().edit {
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

    @Test
    fun `sealPeriod ohne aktiven Nummernkreis wirft statt still nichts zu tun`() {
        assertThrows(NoActiveNumberRangeException::class.java) {
            service.sealPeriod(NumberRangeSubject.INVOICE, "2026")
        }
    }

    @Test
    fun `boundary - sealPeriod auf einem Nummernkreis ohne id wirft statt eine NPE durchschlagen zu lassen`() {
        install(NumberRangeSubject.INVOICE) { id = null }

        assertThrows(IllegalStateException::class.java) {
            service.sealPeriod(NumberRangeSubject.INVOICE, "2026")
        }
    }

    @Test
    fun `sealPeriod lehnt einen UNIQUE_ONLY-Kreis ab weil er kein Journal fuehrt`() {
        install(NumberRangeSubject.CATALOGUE) { continuity = Continuity.UNIQUE_ONLY }

        assertThrows(NotSealableException::class.java) {
            service.sealPeriod(NumberRangeSubject.CATALOGUE, "2026")
        }
    }

    @Test
    fun `sealPeriod versiegelt eine Periode ohne Vergaben mit leerem Zaehler und ohne erste-letzte Nummer`() {
        install(NumberRangeSubject.INVOICE)

        val entry = service.sealPeriod(NumberRangeSubject.INVOICE, "2026")

        assertEquals(NumberJournalEvent.PERIOD_SEALED, entry.event)
        assertEquals(0L, entry.sealedCount)
        assertNull(entry.sealedFirstNumber)
        assertNull(entry.sealedLastNumber)
    }

    @Test
    fun `sealPeriod fasst die Vergaben einer Periode zusammen sortiert nach Nummernfolge`() {
        val range = install(NumberRangeSubject.INVOICE)
        service.allocate(NumberRangeSubject.INVOICE, NumberContext(LocalDate.of(2026, 1, 15)))
        service.allocate(NumberRangeSubject.INVOICE, NumberContext(LocalDate.of(2026, 3, 1)))

        val entry = service.sealPeriod(NumberRangeSubject.INVOICE, NEVER_PERIOD_KEY)

        assertEquals(2L, entry.sealedCount)
        assertEquals("00001", entry.sealedFirstNumber)
        assertEquals("00002", entry.sealedLastNumber)
        assertEquals(range.startValue, entry.sealedStartValue)
    }

    @Test
    fun `sealPeriod schliesst nur Vergaben der angefragten Periode ein - nicht anderer Kreise`() {
        install(NumberRangeSubject.INVOICE)
        install(NumberRangeSubject.CUSTOMER) { continuity = Continuity.GAPLESS }
        service.allocate(NumberRangeSubject.INVOICE, NumberContext(LocalDate.of(2026, 1, 1)))
        service.allocate(NumberRangeSubject.CUSTOMER, NumberContext(LocalDate.of(2026, 1, 1)))

        val entry = service.sealPeriod(NumberRangeSubject.INVOICE, NEVER_PERIOD_KEY)

        assertEquals(1L, entry.sealedCount, "a different range's allocations must not leak into this seal")
    }

    @Test
    fun `ein zweites Versiegeln derselben Periode wird abgelehnt und traegt den urspruenglichen Kettenkopf`() {
        install(NumberRangeSubject.INVOICE)
        val firstSeal = service.sealPeriod(NumberRangeSubject.INVOICE, NEVER_PERIOD_KEY)

        val ex = assertThrows(PeriodAlreadySealedException::class.java) {
            service.sealPeriod(NumberRangeSubject.INVOICE, NEVER_PERIOD_KEY)
        }

        assertEquals(firstSeal.entryHash, ex.sealEntryHash, "the exception must carry the original head, not attempt a new one")
        assertEquals(firstSeal.prevHash, ex.chainHead)
        assertTrue(journal.entries.count { it.event == NumberJournalEvent.PERIOD_SEALED } == 1, "a rejected re-seal must not append a second PERIOD_SEALED entry")
    }

    @Test
    fun `Versiegeln verschiedener Perioden desselben Kreises ist unabhaengig moeglich`() {
        install(NumberRangeSubject.INVOICE)

        service.sealPeriod(NumberRangeSubject.INVOICE, "2026-01")
        val second = service.sealPeriod(NumberRangeSubject.INVOICE, "2026-02")

        assertEquals(NumberJournalEvent.PERIOD_SEALED, second.event)
        assertEquals("2026-02", second.periodKey)
    }

    private companion object {
        const val NEVER_PERIOD_KEY = ""
    }

    // ── fakes (mirrors NumberRangeServiceSyncTest's, duplicated: those are private there) ─────

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

    private class FakeCounters : NumberCounterRepository {
        private val state = mutableMapOf<Triple<Long?, String, String>, Long>()
        private fun key(range: NumberRange, scopeKey: String, periodKey: String) = Triple(range.id, scopeKey, periodKey)

        override fun peekNext(range: NumberRange, scopeKey: String, periodKey: String): Long =
            (state[key(range, scopeKey, periodKey)]?.plus(1)) ?: range.startValue

        override fun allocateUniqueOnly(range: NumberRange, scopeKey: String, periodKey: String): Long {
            val next = peekNext(range, scopeKey, periodKey)
            state[key(range, scopeKey, periodKey)] = next
            return next
        }

        override fun allocateGapless(range: NumberRange, scopeKey: String, periodKey: String): Long =
            allocateUniqueOnly(range, scopeKey, periodKey)

        override fun currentValue(subject: NumberRangeSubject): Long? =
            state.entries.filter { it.key.first == subject.ordinal.toLong() + 1 }.maxOfOrNull { it.value }

        override fun raiseToAtLeast(range: NumberRange, scopeKey: String, periodKey: String, target: Long): Long {
            val k = key(range, scopeKey, periodKey)
            val current = state[k]
            val raised = if (current == null || current < target) target else current
            state[k] = raised
            return raised
        }
    }
}
