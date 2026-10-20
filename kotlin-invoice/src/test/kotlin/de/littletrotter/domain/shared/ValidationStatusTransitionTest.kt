package de.littletrotter.domain.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * ( pattern transferred from [de.littletrotter.domain.invoice
 * .GenerationJobStateMachineTest]) — exhaustive coverage of [ValidationStatus.canTransitionTo]'s
 * edge table: every legal edge and every illegal one (every ordered pair of [ValidationStatus]
 * that isn't legal). See that method's own KDoc for how each legal edge was derived from the real
 * write sites, not invented.
 */
class ValidationStatusTransitionTest {

    private val legalEdges: Set<Pair<ValidationStatus, ValidationStatus>> = setOf(
        ValidationStatus.NOT_VALIDATED to ValidationStatus.PENDING,
        ValidationStatus.PENDING to ValidationStatus.PENDING,
        ValidationStatus.PENDING to ValidationStatus.VALID,
        ValidationStatus.PENDING to ValidationStatus.INVALID,
        ValidationStatus.PENDING to ValidationStatus.UNAVAILABLE,
        ValidationStatus.VALID to ValidationStatus.PENDING,
        ValidationStatus.INVALID to ValidationStatus.PENDING,
        ValidationStatus.UNAVAILABLE to ValidationStatus.PENDING,
    )

    private val allStatuses = ValidationStatus.entries

    @TestFactory
    fun `every ordered pair of statuses is checked against the legal-edges table`(): List<DynamicTest> =
        allStatuses.flatMap { from ->
            allStatuses.map { to ->
                DynamicTest.dynamicTest("$from -> $to") {
                    val expectedLegal = (from to to) in legalEdges
                    assertEquals(expectedLegal, from.canTransitionTo(to), "$from -> $to legality mismatch")
                }
            }
        }

    @Test
    fun `NOT_VALIDATED is never a legal transition target`() {
        allStatuses.forEach { from ->
            assertFalse(from.canTransitionTo(ValidationStatus.NOT_VALIDATED), "$from -> NOT_VALIDATED must not be legal")
        }
    }

    @Test
    fun `PENDING has a legal self-edge, unlike GenerationJobStatus`() {
        // See ValidationStatus.canTransitionTo's own KDoc: DocumentJobPublisher.publish writes
        // PENDING unconditionally on every publish, with no read of the prior value, so a second
        // publish landing while an earlier job for the same invoice is still in flight is a real,
        // legal PENDING -> PENDING edge — unlike GenerationJobStatus, which has none.
        assertTrue(ValidationStatus.PENDING.canTransitionTo(ValidationStatus.PENDING))
    }

    @Test
    fun `every non-PENDING status can only reach PENDING`() {
        listOf(ValidationStatus.VALID, ValidationStatus.INVALID, ValidationStatus.UNAVAILABLE).forEach { from ->
            allStatuses.forEach { to ->
                assertEquals(to == ValidationStatus.PENDING, from.canTransitionTo(to), "$from -> $to legality mismatch")
            }
        }
    }
}
