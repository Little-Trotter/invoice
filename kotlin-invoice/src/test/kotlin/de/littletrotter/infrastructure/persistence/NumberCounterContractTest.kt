package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.repository.NumberCounterRepository
import org.junit.jupiter.api.Test

/**
 * IMP-1 — the counter contract run against the in-memory fake. The counter-check against real
 * PostgreSQL lives in [PostgresNumberCounterIT]; both call the same [assertNumberCounterContract]
 * so the fake and production cannot silently drift apart again.
 */
class NumberCounterContractTest {

    /**
     * Reproduces the `number_counter.last_value` column: what's stored is the **last** value
     * handed out, the preview is the one following it. The same semantics the fakes in
     * `NumberServicesTest` and `InvoiceLifecycleServiceTest` already had.
     */
    private class InMemoryCounters : NumberCounterRepository {
        private val lastValues = mutableMapOf<Triple<Long?, String, String>, Long>()
        private fun key(range: NumberRange, scopeKey: String, periodKey: String) =
            Triple(range.id, scopeKey, periodKey)

        override fun peekNext(range: NumberRange, scopeKey: String, periodKey: String): Long =
            lastValues[key(range, scopeKey, periodKey)]?.plus(1) ?: range.startValue

        private fun allocate(range: NumberRange, scopeKey: String, periodKey: String): Long =
            peekNext(range, scopeKey, periodKey).also { lastValues[key(range, scopeKey, periodKey)] = it }

        override fun allocateUniqueOnly(range: NumberRange, scopeKey: String, periodKey: String) =
            allocate(range, scopeKey, periodKey)

        override fun allocateGapless(range: NumberRange, scopeKey: String, periodKey: String) =
            allocate(range, scopeKey, periodKey)

        override fun currentValue(subject: NumberRangeSubject): Long? = lastValues.values.maxOrNull()
    }

    private fun range(startValue: Long) = NumberRange().apply {
        id = 1L
        code = "CONTRACT"
        displayName = "Kontraktprüfung"
        subject = NumberRangeSubject.CUSTOMER
        this.startValue = startValue
    }

    @Test
    fun `IMP-1 - allocateGapless erfuellt den Zaehler-Kontrakt`() {
        val counters = InMemoryCounters()
        assertNumberCounterContract(counters, range(startValue = 1), "*", "", counters::allocateGapless)
    }

    @Test
    fun `IMP-1 - allocateUniqueOnly erfuellt denselben Kontrakt`() {
        val counters = InMemoryCounters()
        assertNumberCounterContract(counters, range(startValue = 1), "*", "", counters::allocateUniqueOnly)
    }

    /** A start value far from 1 separates "the first number is the start value" from "the first
     *  number is 1" — with `startValue = 1` both would look identical (N1-05). */
    @Test
    fun `IMP-1 - ein eigener Startwert wird unveraendert als erste Nummer ausgegeben`() {
        val counters = InMemoryCounters()
        assertNumberCounterContract(counters, range(startValue = 4712), "*", "", counters::allocateGapless)
    }
}
