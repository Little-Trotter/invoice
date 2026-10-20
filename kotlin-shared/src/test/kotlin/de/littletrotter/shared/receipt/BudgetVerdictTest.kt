package de.littletrotter.shared.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BudgetVerdictTest {

    @Test
    fun `Accepted is a singleton data object`() {
        assertEquals(BudgetVerdict.Accepted, BudgetVerdict.Accepted)
    }

    @Test
    fun `TooManyReceipts carries limit and attempted, equality is by value`() {
        val a = BudgetVerdict.TooManyReceipts(limit = 19, attempted = 20)
        val b = BudgetVerdict.TooManyReceipts(limit = 19, attempted = 20)
        val different = BudgetVerdict.TooManyReceipts(limit = 19, attempted = 21)

        assertEquals(19, a.limit)
        assertEquals(20, a.attempted)
        assertEquals(a, b)
        assertFalse(a == different)
        assertFalse(a.equals(BudgetVerdict.Accepted))
    }

    @Test
    fun `DocumentTooLarge carries limit and estimated, equality is by value`() {
        val limit = DocumentSize.ofMebiBytes(20L)
        val a = BudgetVerdict.DocumentTooLarge(limit = limit, estimated = limit + DocumentSize.ofBytes(1L))
        val b = BudgetVerdict.DocumentTooLarge(limit = limit, estimated = limit + DocumentSize.ofBytes(1L))
        val different = BudgetVerdict.DocumentTooLarge(limit = limit, estimated = limit + DocumentSize.ofBytes(2L))

        assertEquals(limit, a.limit)
        assertEquals(limit + DocumentSize.ofBytes(1L), a.estimated)
        assertEquals(a, b)
        assertFalse(a == different)
    }
}
