package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

/**
 * [InvoiceExportReadRepositoryImpl]'s per-item batch lookups (`tripExpensesByItem`,
 * `perDiemDaysByItem`, `b2bIdentifiersByItem`, `receiptsByItem`) are only ever called from
 * `findPositionRows` with an `itemIds` list derived from already-fetched tuples — never empty in
 * that call path — so each method's own `if (itemIds.isEmpty()) return emptyMap()` guard is
 * unreachable through any current caller. It stays (an empty JPQL `IN ()` predicate is invalid,
 * not merely wasteful) and is `internal` so this guard is driven directly, without a real
 * `EntityManager` — the returned-before-touching-`em` short circuit is exactly what a mocked `em`
 * (never stubbed, so any real interaction would throw) lets this test prove.
 */
class InvoiceExportReadRepositoryImplBatchGuardsTest {

    private val em = mock<jakarta.persistence.EntityManager>()
    private val repository = InvoiceExportReadRepositoryImpl(em, mock(), mock())

    @Test
    fun `boundary - tripExpensesByItem short-circuits to an empty map for an empty itemIds list`() {
        assertTrue(repository.tripExpensesByItem(emptyList()).isEmpty())
        verifyNoInteractions(em)
    }

    @Test
    fun `boundary - perDiemDaysByItem short-circuits to an empty map for an empty itemIds list`() {
        assertTrue(repository.perDiemDaysByItem(emptyList()).isEmpty())
        verifyNoInteractions(em)
    }

    @Test
    fun `boundary - b2bIdentifiersByItem short-circuits to an empty map for an empty itemIds list`() {
        assertTrue(repository.b2bIdentifiersByItem(emptyList()).isEmpty())
        verifyNoInteractions(em)
    }

    @Test
    fun `boundary - receiptsByItem short-circuits to an empty map for an empty itemIds list`() {
        assertTrue(repository.receiptsByItem(emptyList()).isEmpty())
        verifyNoInteractions(em)
    }
}
