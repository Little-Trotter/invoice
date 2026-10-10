package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.repository.NumberCounterRepository
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * IMP-1/IMP-5 — the shared contract of every [NumberCounterRepository], formulated once and run
 * against **every** implementation: the in-memory fake used by unit tests
 * ([NumberCounterContractTest]) and the real Postgres class ([PostgresNumberCounterIT]).
 *
 * The trigger was a concrete bug: `PostgresNumberCounterRepository.peekNext` returned the column
 * value unchanged and thereby reported a number that had already been allocated, while the unit
 * test fakes reproduced the correct semantics. Both sides were tested on their own, just never
 * against each other — which is why the divergence stayed invisible. A shared contract check
 * closes exactly that gap, and only a shared one can.
 *
 * Deliberately **without** any claim about transaction or locking behavior: the difference
 * between `allocateGapless` (caller's transaction) and `allocateUniqueOnly` (its own transaction)
 * cannot be reproduced in an in-memory fake and stays the concern of [NumberCounterIT]. Only the
 * counting semantics live here.
 *
 * @param allocate the allocation path under test — both implementations must serve the same counter.
 */
fun assertNumberCounterContract(
    counters: NumberCounterRepository,
    range: NumberRange,
    scopeKey: String,
    periodKey: String,
    allocate: (NumberRange, String, String) -> Long,
) {
    val start = range.startValue

    assertEquals(start, counters.peekNext(range, scopeKey, periodKey), "Vorschau vor der ersten Vergabe")

    // A peek consumes nothing: same question, same answer.
    assertEquals(start, counters.peekNext(range, scopeKey, periodKey), "Vorschau ist wiederholbar")

    // The first allocation returns exactly the start value — not startValue + 1.
    assertEquals(start, allocate(range, scopeKey, periodKey), "erste Vergabe")

    // The core of IMP-1: the preview must no longer report the start value — it's gone.
    assertEquals(start + 1, counters.peekNext(range, scopeKey, periodKey), "Vorschau nach der ersten Vergabe")

    // ... and the next allocation returns exactly what the preview promised.
    assertEquals(start + 1, allocate(range, scopeKey, periodKey), "zweite Vergabe")
    assertEquals(start + 2, counters.peekNext(range, scopeKey, periodKey), "Vorschau nach der zweiten Vergabe")

    // A different scope/period key is its own, untouched counter.
    assertEquals(start, counters.peekNext(range, scopeKey + "-anders", periodKey), "fremder Scope-Schlüssel")
    assertEquals(start, counters.peekNext(range, scopeKey, periodKey + "-anders"), "fremder Perioden-Schlüssel")
}
