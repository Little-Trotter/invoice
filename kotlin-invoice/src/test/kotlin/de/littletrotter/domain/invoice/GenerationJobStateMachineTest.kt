package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * exhaustive coverage of [GenerationJobStateMachine]'s edge table, every legal edge and
 * every illegal one (every ordered pair of [GenerationJobStatus] that isn't legal, including every
 * self-edge — there is no `X -> X` edge anywhere in the table).
 */
class GenerationJobStateMachineTest {

    private val legalEdges: Set<Pair<GenerationJobStatus, GenerationJobStatus>> = setOf(
        GenerationJobStatus.PENDING to GenerationJobStatus.DELEGATED,
        GenerationJobStatus.DELEGATED to GenerationJobStatus.PENDING,
        GenerationJobStatus.DELEGATED to GenerationJobStatus.RUNNING,
        GenerationJobStatus.DELEGATED to GenerationJobStatus.COMPLETED,
        GenerationJobStatus.DELEGATED to GenerationJobStatus.FAILED,
        GenerationJobStatus.RUNNING to GenerationJobStatus.COMPLETED,
        GenerationJobStatus.RUNNING to GenerationJobStatus.FAILED,
        GenerationJobStatus.PENDING to GenerationJobStatus.FAILED,
        // The one narrow exception to "FAILED is terminal": attempt-budget-exhaustion
        // reopened once a worker is confirmed active again.
        GenerationJobStatus.FAILED to GenerationJobStatus.PENDING,
    )

    private val allStatuses = GenerationJobStatus.entries

    @TestFactory
    fun `every ordered pair of statuses is checked against the legal-edges table`(): List<DynamicTest> =
        allStatuses.flatMap { from ->
            allStatuses.map { to ->
                DynamicTest.dynamicTest("$from -> $to") {
                    val expectedLegal = (from to to) in legalEdges
                    assertEquals(expectedLegal, GenerationJobStateMachine.isLegal(from, to), "$from -> $to legality mismatch")
                    if (expectedLegal) {
                        assertDoesNotThrow { GenerationJobStateMachine.assertLegal(from, to) }
                    } else {
                        assertThrows<IllegalStateException> { GenerationJobStateMachine.assertLegal(from, to) }
                    }
                }
            }
        }

    @Test
    fun `there is no self-edge for any status`() {
        allStatuses.forEach { status ->
            assertFalse(GenerationJobStateMachine.isLegal(status, status), "$status -> $status must not be legal")
        }
    }

    @Test
    fun `COMPLETED is terminal — no outgoing edge at all`() {
        allStatuses.forEach { to ->
            assertFalse(GenerationJobStateMachine.isLegal(GenerationJobStatus.COMPLETED, to), "COMPLETED -> $to must not be legal")
        }
    }

    @Test
    fun `FAILED is terminal except for the ST-A3 attempt-budget reopen edge to PENDING`() {
        allStatuses.forEach { to ->
            val expectedLegal = to == GenerationJobStatus.PENDING
            assertEquals(
                expectedLegal,
                GenerationJobStateMachine.isLegal(GenerationJobStatus.FAILED, to),
                "FAILED -> $to legality mismatch",
            )
        }
    }

    @Test
    fun `assertLegal throws IllegalStateException with a precise message naming both statuses`() {
        val ex = assertThrows<IllegalStateException> {
            GenerationJobStateMachine.assertLegal(GenerationJobStatus.PENDING, GenerationJobStatus.COMPLETED)
        }
        assertTrue(ex.message!!.contains("PENDING"))
        assertTrue(ex.message!!.contains("COMPLETED"))
    }

    // ── GenerationJob.transitionTo — the entity-level entry point into the guard ────────────────

    @Test
    fun `transitionTo changes status on a legal edge`() {
        val job = GenerationJob().apply { status = GenerationJobStatus.PENDING }

        job.transitionTo(GenerationJobStatus.DELEGATED)

        assertEquals(GenerationJobStatus.DELEGATED, job.status)
    }

    @Test
    fun `transitionTo throws and leaves status unchanged on an illegal edge`() {
        val job = GenerationJob().apply { status = GenerationJobStatus.PENDING }

        assertThrows<IllegalStateException> { job.transitionTo(GenerationJobStatus.COMPLETED) }

        assertEquals(GenerationJobStatus.PENDING, job.status, "an illegal transitionTo call must not mutate status")
    }
}
