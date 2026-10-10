package de.littletrotter.application.reference

import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.NoActiveNumberRangeException
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.NumberRangeSyncNotSupportedException
import de.littletrotter.domain.reference.NumberRangeSyncUnsupportedConfigurationException
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Synchronize number counters to existing data" admin action (L4 follow-up, 2026-09-02+) —
 * [NumberRangeService.syncCounterTo] against fake ports (no CDI, no DB). Covers: never lowers
 * the counter, refuses GAPLESS ranges, refuses non-GLOBAL/non-NEVER configurations, and reports
 * "unchanged" when the observed suffix is `null` or already covered.
 */
class NumberRangeServiceSyncTest {

    private val ranges = FakeRanges()
    private val counters = FakeCounters()
    private val service = NumberRangeService(
        ranges, counters, RecordingNumberJournalRepository(),
        de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
    )

    private fun install(subject: NumberRangeSubject, configure: NumberRange.() -> Unit = {}): NumberRange {
        val range = NumberRange().apply {
            id = subject.ordinal.toLong() + 1
            code = "${subject.name}_DEFAULT"
            displayName = subject.name
            this.subject = subject
            startValue = 1
            counterWidth = 5
            continuity = Continuity.UNIQUE_ONLY
            datePart = DatePart.NONE
            resetCycle = ResetCycle.NEVER
            scope = CounterScope.GLOBAL
            prefixSource = PrefixSource.NONE
            configure()
        }
        ranges.install(range)
        return range
    }

    @Test
    fun `an empty counter is raised straight to the observed suffix`() {
        val range = install(NumberRangeSubject.CUSTOMER)

        val result = service.syncCounterTo(NumberRangeSubject.CUSTOMER, 42L)

        assertTrue(result.changed)
        assertNull(result.oldValue)
        assertEquals(42L, result.newValue)
        assertEquals(42L, counters.currentValue(NumberRangeSubject.CUSTOMER))
        // The very next real allocation must continue past the synced value, not restart it.
        assertEquals(43L, counters.peekNext(range, "*", ""))
    }

    @Test
    fun `an observed suffix behind the current counter never lowers it`() {
        install(NumberRangeSubject.CATALOGUE)
        service.syncCounterTo(NumberRangeSubject.CATALOGUE, 50L)

        val result = service.syncCounterTo(NumberRangeSubject.CATALOGUE, 10L)

        assertFalse(result.changed)
        assertEquals(50L, result.oldValue)
        assertEquals(50L, result.newValue)
        assertEquals(50L, counters.currentValue(NumberRangeSubject.CATALOGUE))
    }

    @Test
    fun `an observed suffix exactly equal to the current counter reports unchanged`() {
        install(NumberRangeSubject.SELLER)
        service.syncCounterTo(NumberRangeSubject.SELLER, 7L)

        val result = service.syncCounterTo(NumberRangeSubject.SELLER, 7L)

        assertFalse(result.changed)
        assertEquals(7L, result.oldValue)
        assertEquals(7L, result.newValue)
    }

    @Test
    fun `a null observed suffix - no persisted rows at all - reports unchanged with the counter untouched`() {
        install(NumberRangeSubject.CUSTOMER)

        val result = service.syncCounterTo(NumberRangeSubject.CUSTOMER, null)

        assertFalse(result.changed)
        assertNull(result.oldValue)
        assertNull(result.newValue)
        assertNull(counters.currentValue(NumberRangeSubject.CUSTOMER))
    }

    @Test
    fun `a higher observed suffix raises an already-used counter forward`() {
        install(NumberRangeSubject.CUSTOMER)
        service.syncCounterTo(NumberRangeSubject.CUSTOMER, 5L)

        val result = service.syncCounterTo(NumberRangeSubject.CUSTOMER, 12L)

        assertTrue(result.changed)
        assertEquals(5L, result.oldValue)
        assertEquals(12L, result.newValue)
    }

    @Test
    fun `a GAPLESS range - invoice - is refused, never healed by sync`() {
        install(NumberRangeSubject.INVOICE) { continuity = Continuity.GAPLESS }

        assertThrows(NumberRangeSyncNotSupportedException::class.java) {
            service.syncCounterTo(NumberRangeSubject.INVOICE, 99L)
        }
    }

    @Test
    fun `a PER_SELLER-scoped range is refused - no seller bookkeeping to reconstruct the scope key from`() {
        install(NumberRangeSubject.SELLER) { scope = CounterScope.PER_SELLER }

        assertThrows(NumberRangeSyncUnsupportedConfigurationException::class.java) {
            service.syncCounterTo(NumberRangeSubject.SELLER, 5L)
        }
    }

    @Test
    fun `a YEARLY-resetting range is refused - no allocation-year bookkeeping to reconstruct the period key from`() {
        install(NumberRangeSubject.CATALOGUE) { resetCycle = ResetCycle.YEARLY; datePart = DatePart.YEAR }

        assertThrows(NumberRangeSyncUnsupportedConfigurationException::class.java) {
            service.syncCounterTo(NumberRangeSubject.CATALOGUE, 5L)
        }
    }

    @Test
    fun `no active range for the subject throws instead of silently doing nothing`() {
        assertThrows(NoActiveNumberRangeException::class.java) {
            service.syncCounterTo(NumberRangeSubject.CUSTOMER, 5L)
        }
    }

    // ── fakes (per-range keying, unlike NumberServicesTest's simpler single-range fakes) ──────

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

    /** Keyed by `(rangeId, scopeKey, periodKey)` — unlike [NumberServicesTest]'s fake, which keys
     *  only by scope/period and would collide across subjects sharing GLOBAL/NEVER, exactly the
     *  case every subject under test here uses. */
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

        // The fake has no reverse range->subject lookup, so this relies on install() giving each
        // subject's range a stable, subject-derived id (subject.ordinal + 1) to filter by.
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
