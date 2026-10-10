package de.littletrotter.domain.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `SortDirection.fromString` is the only place the sort direction from the UI (a raw string
 * from Vaadin's Grid) is translated into the enum. A slip here silently sorts the invoice
 * overview in the wrong direction — a bug nobody notices as a crash, only as "the newest
 * invoice is at the bottom".
 *
 * The contract is deliberately narrow: **only** `"desc"` (case-insensitive) means descending;
 * everything else — including `null` and garbage — means ascending. This matches
 * `BasePanacheRepository`'s semantics exactly; a second, diverging translator would be the
 * start of sort bugs that differ by entry point.
 */
class SortDirectionTest {

    @Test
    fun `nur desc bedeutet absteigend, unabhaengig von der Schreibweise`() {
        assertEquals(SortDirection.DESC, SortDirection.fromString("desc"))
        assertEquals(SortDirection.DESC, SortDirection.fromString("DESC"))
        assertEquals(SortDirection.DESC, SortDirection.fromString("Desc"))
    }

    @Test
    fun `alles andere bedeutet aufsteigend`() {
        assertEquals(SortDirection.ASC, SortDirection.fromString("asc"))
        assertEquals(SortDirection.ASC, SortDirection.fromString("ASC"))
        // No value, no sort request: the UI passes null when no column has been clicked.
        // This must not end in an exception.
        assertEquals(SortDirection.ASC, SortDirection.fromString(null))
        // Unknown input is neither guessed at nor rejected -- it falls back to the
        // inconspicuous direction.
        assertEquals(SortDirection.ASC, SortDirection.fromString(""))
        assertEquals(SortDirection.ASC, SortDirection.fromString("descending"))
        assertEquals(SortDirection.ASC, SortDirection.fromString("absteigend"))
    }
}
