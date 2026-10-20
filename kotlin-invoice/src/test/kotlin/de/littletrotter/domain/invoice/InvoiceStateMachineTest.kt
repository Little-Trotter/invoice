package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * ST-1 — exhaustive coverage of [InvoiceStateMachine]'s edge table: every ordered pair of
 * [InvoiceStatus] (self-edges included — there is no `X -> X` edge anywhere in the table), plus
 * [InvoiceStateMachine.isTerminal]/[InvoiceStateMachine.successors] and the [isReadOnly] predicate,
 * which deliberately does not collapse into [InvoiceStateMachine.isTerminal]: [InvoiceStatus.ISSUED]
 * is read-only but not terminal.
 */
class InvoiceStateMachineTest {

    private val legalEdges: Set<Pair<InvoiceStatus, InvoiceStatus>> = setOf(
        InvoiceStatus.DRAFT to InvoiceStatus.IN_REVIEW,
        InvoiceStatus.DRAFT to InvoiceStatus.VOIDED,
        InvoiceStatus.IN_REVIEW to InvoiceStatus.DRAFT,
        InvoiceStatus.IN_REVIEW to InvoiceStatus.ISSUED,
        InvoiceStatus.IN_REVIEW to InvoiceStatus.VOIDED,
        InvoiceStatus.ISSUED to InvoiceStatus.CANCELLED,
    )

    private val allStatuses = InvoiceStatus.entries

    @TestFactory
    fun `every ordered pair of statuses is checked against the legal-edges table`(): List<DynamicTest> =
        allStatuses.flatMap { from ->
            allStatuses.map { to ->
                DynamicTest.dynamicTest("$from -> $to") {
                    val expectedLegal = (from to to) in legalEdges
                    assertEquals(expectedLegal, InvoiceStateMachine.isLegal(from, to), "$from -> $to legality mismatch")
                    if (expectedLegal) {
                        assertDoesNotThrow { InvoiceStateMachine.assertLegal(from, to) }
                    } else {
                        assertThrows<InvoiceStatusTransitionException> { InvoiceStateMachine.assertLegal(from, to) }
                    }
                }
            }
        }

    @Test
    fun `there is no self-edge for any status`() {
        allStatuses.forEach { status ->
            assertFalse(InvoiceStateMachine.isLegal(status, status), "$status -> $status must not be legal")
        }
    }

    @Test
    fun `CANCELLED and VOIDED are terminal, ISSUED keeps the cancellation edge so it is not`() {
        assertTrue(InvoiceStateMachine.isTerminal(InvoiceStatus.CANCELLED))
        assertTrue(InvoiceStateMachine.isTerminal(InvoiceStatus.VOIDED))
        assertFalse(InvoiceStateMachine.isTerminal(InvoiceStatus.ISSUED), "ISSUED keeps the storno edge, it is not terminal")
        assertFalse(InvoiceStateMachine.isTerminal(InvoiceStatus.DRAFT))
        assertFalse(InvoiceStateMachine.isTerminal(InvoiceStatus.IN_REVIEW))
    }

    @Test
    fun `successors returns exactly the legal targets for each status`() {
        assertEquals(setOf(InvoiceStatus.IN_REVIEW, InvoiceStatus.VOIDED), InvoiceStateMachine.successors(InvoiceStatus.DRAFT))
        assertEquals(
            setOf(InvoiceStatus.DRAFT, InvoiceStatus.ISSUED, InvoiceStatus.VOIDED),
            InvoiceStateMachine.successors(InvoiceStatus.IN_REVIEW),
        )
        assertEquals(setOf(InvoiceStatus.CANCELLED), InvoiceStateMachine.successors(InvoiceStatus.ISSUED))
        assertEquals(emptySet<InvoiceStatus>(), InvoiceStateMachine.successors(InvoiceStatus.CANCELLED))
        assertEquals(emptySet<InvoiceStatus>(), InvoiceStateMachine.successors(InvoiceStatus.VOIDED))
    }

    @Test
    fun `assertLegal throws InvoiceStatusTransitionException naming both statuses`() {
        val ex = assertThrows<InvoiceStatusTransitionException> {
            InvoiceStateMachine.assertLegal(InvoiceStatus.ISSUED, InvoiceStatus.DRAFT)
        }
        assertTrue(ex.message!!.contains("ISSUED"))
        assertTrue(ex.message!!.contains("DRAFT"))
    }

    // ── isReadOnly — a distinct predicate from isTerminal (ISSUED is read-only but not terminal) ──

    @Test
    fun `isReadOnly is true for ISSUED, CANCELLED and VOIDED, false for DRAFT and IN_REVIEW`() {
        assertFalse(InvoiceStatus.DRAFT.isReadOnly)
        assertFalse(InvoiceStatus.IN_REVIEW.isReadOnly)
        assertTrue(InvoiceStatus.ISSUED.isReadOnly)
        assertTrue(InvoiceStatus.CANCELLED.isReadOnly)
        assertTrue(InvoiceStatus.VOIDED.isReadOnly)
    }
}
