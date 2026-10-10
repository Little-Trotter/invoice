package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.domain.repository.NumberCounterRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * IMP-1/IMP-5 — the counter contract run against the **real**
 * [PostgresNumberCounterRepository] on real PostgreSQL.
 *
 * Deliberately separate from [NumberCounterIT]: that one checks a *copy* of the SQL statement
 * against the database (transaction and locking behavior) and would not notice a change to the
 * repository class itself — it doesn't know that class. This test calls the class directly, not
 * a transcript of it, and thereby covers `peekNext`/`currentValue`, which used to go unchecked
 * against the database — exactly where an off-by-one used to sit.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PostgresNumberCounterIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var counters: NumberCounterRepository

    /**
     * A dedicated, **inactive** range per test: `findActive` never returns it, so it interferes
     * with neither the seed ranges nor other tests, while the counter hangs off its own `id` and
     * belongs to it alone. `code` carries the test name so a leftover row stays traceable.
     */
    private fun persistInactiveRange(code: String, subject: NumberRangeSubject, start: Long): NumberRange =
        QuarkusTransaction.requiringNew().call<NumberRange> {
            val range = NumberRange().apply {
                this.code = code
                displayName = code
                this.subject = subject
                startValue = start
                prefixSource = PrefixSource.NONE
                counterWidth = 5
                continuity = Continuity.UNIQUE_ONLY
                isActive = false
                isDefault = false
            }
            em.persist(range)
            em.flush()
            range
        }

    @Test
    fun `IMP-1 - die echte Postgres-Implementierung erfuellt den Zaehler-Kontrakt`() {
        val range = persistInactiveRange("IMP1_CONTRACT_GAPLESS", NumberRangeSubject.CUSTOMER, start = 1)
        // allocateGapless runs in the caller's transaction, so the whole contract runs in one.
        // peekNext only reads, allocateGapless writes; both tolerate the same transaction.
        QuarkusTransaction.requiringNew().run {
            assertNumberCounterContract(counters, range, "*", "", counters::allocateGapless)
        }
    }

    /** N1-05 against the real class: the start value itself is the first number, not start+1. */
    @Test
    fun `IMP-1 - ein eigener Startwert wird auch aus der Datenbank unveraendert ausgegeben`() {
        val range = persistInactiveRange("IMP1_CONTRACT_START", NumberRangeSubject.CATALOGUE, start = 4712)
        QuarkusTransaction.requiringNew().run {
            assertNumberCounterContract(counters, range, "*", "", counters::allocateGapless)
        }
    }

    /**
     * `currentValue` reports the last value handed out. It must not compute `MAX(next_value) - 1`
     * on a column that already holds the last value handed out — that would report the
     * *second-to-last* one instead.
     */
    @Test
    fun `IMP-1 - currentValue nennt den zuletzt vergebenen Stand, nicht den vorletzten`() {
        val range = persistInactiveRange("IMP1_CURRENT_VALUE", NumberRangeSubject.SELLER, start = 100)

        QuarkusTransaction.requiringNew().run {
            assertEquals(100L, counters.allocateGapless(range, "*", ""))
            assertEquals(101L, counters.allocateGapless(range, "*", ""))
        }

        // SELLER has no other counter with allocations besides this range (seed ranges only
        // allocate on first save of a sender) — MAX over the subject is thus exactly this
        // test's own counter.
        val current = QuarkusTransaction.requiringNew().call { counters.currentValue(NumberRangeSubject.SELLER) }
        assertEquals(101L, current, "zuletzt vergeben wurde die 101")
    }

    /**
     * AP-IMP-6 — [PostgresNumberCounterRepository.hasBeenUsed] was not checked against the real
     * class anywhere else: `ReferenceDataBorderCasesTest` only mocks the interface. The core claim
     * under test is that a `number_counter` row exists only after the first `allocate…()`, not
     * already after `peekNext` — both branches therefore belong in the same test.
     */
    /** [PostgresNumberCounterRepository.allocateUniqueOnly] was only ever checked against
     *  the real class through [NumberCounterContractTest]'s fake, never against real Postgres. */
    @Test
    fun `COV-32 - allocateUniqueOnly erfuellt den Zaehler-Kontrakt gegen echtes Postgres`() {
        val range = persistInactiveRange("COV32_UNIQUE_ONLY", NumberRangeSubject.CATALOGUE, start = 1)
        QuarkusTransaction.requiringNew().run {
            assertNumberCounterContract(counters, range, "*", "", counters::allocateUniqueOnly)
        }
    }

    /** [PostgresNumberCounterRepository.raiseToAtLeast] had zero coverage against any
     *  implementation: it raises the counter to at least [target] (an admin maintenance action),
     *  and — via the same-statement `GREATEST(...)` — must never lower it back down again. */
    @Test
    fun `COV-32 - raiseToAtLeast raises the counter but never lowers it below its current value`() {
        val range = persistInactiveRange("COV32_RAISE_TO_AT_LEAST", NumberRangeSubject.CUSTOMER, start = 1)

        val firstRaise = QuarkusTransaction.requiringNew().call<Long> { counters.raiseToAtLeast(range, "*", "", 50L) }
        assertEquals(50L, firstRaise, "no prior row: raising creates it at the target")

        val loweringAttempt = QuarkusTransaction.requiringNew().call<Long> { counters.raiseToAtLeast(range, "*", "", 10L) }
        assertEquals(50L, loweringAttempt, "a target below the current value must never lower the counter")

        val secondRaise = QuarkusTransaction.requiringNew().call<Long> { counters.raiseToAtLeast(range, "*", "", 75L) }
        assertEquals(75L, secondRaise, "a target above the current value raises it")

        val next = QuarkusTransaction.requiringNew().call<Long> { counters.allocateGapless(range, "*", "") }
        assertEquals(76L, next, "allocation after a raise continues from the raised value")
    }

    @Test
    fun `IMP-1 - hasBeenUsed is false before any allocation and peekNext alone, true after the first allocate`() {
        val range = persistInactiveRange("IMP1_HAS_BEEN_USED", NumberRangeSubject.CATALOGUE, start = 1)

        QuarkusTransaction.requiringNew().run {
            assertEquals(1L, counters.peekNext(range, "*", ""), "peekNext liest nur, legt keine Zeile an")
        }
        assertEquals(false, counters.hasBeenUsed(range.id!!), "peekNext allein darf hasBeenUsed nicht auf true setzen")

        QuarkusTransaction.requiringNew().run {
            counters.allocateGapless(range, "*", "")
        }
        assertEquals(true, counters.hasBeenUsed(range.id!!), "nach der ersten Vergabe existiert die Zeile")
    }
}
