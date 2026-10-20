package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.outbound.DelegatedMessageProbe
import de.littletrotter.application.invoice.port.outbound.DelegatedStreamSnapshot
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.repository.StaleDelegatedCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [StaleDelegatedResetCoordinator]'s own orchestration: the lazy snapshot fetch, the
 * per-tick probe-failure abort (no partial resets), and the plain candidates-empty/no-reset-ids
 * short circuits. [StaleDelegatedResetDeciderTest] already covers the decision itself; this class
 * covers the wiring around it.
 */
class StaleDelegatedResetCoordinatorTest {

    private fun coordinator(ops: StaleDelegatedResetTransactions, probe: DelegatedMessageProbe) =
        StaleDelegatedResetCoordinator(ops, probe)

    @Test
    fun `an empty candidate list never touches the probe or applies any reset`() {
        val ops: StaleDelegatedResetTransactions = mock()
        whenever(ops.findCandidates(2)).thenReturn(emptyList())
        val probe: DelegatedMessageProbe = mock()

        val reset = coordinator(ops, probe).resetStale(2)

        assertEquals(0, reset)
        verify(probe, never()).snapshot()
        verify(ops, never()).applyResets(any())
    }

    @Test
    fun `every candidate with a null sequence resets outright without ever calling the probe`() {
        val ops: StaleDelegatedResetTransactions = mock()
        whenever(ops.findCandidates(2)).thenReturn(listOf(StaleDelegatedCandidate(1L, null, DocumentJobLane.BULK), StaleDelegatedCandidate(2L, null, DocumentJobLane.BULK)))
        whenever(ops.applyResets(listOf(1L, 2L))).thenReturn(2)
        val probe: DelegatedMessageProbe = mock()

        val reset = coordinator(ops, probe).resetStale(2)

        assertEquals(2, reset)
        verify(probe, never()).snapshot()
        verify(ops).applyResets(listOf(1L, 2L))
    }

    @Test
    fun `a mix of candidates fetches the snapshot once and resets only the ones the decider selects`() {
        val ops: StaleDelegatedResetTransactions = mock()
        whenever(ops.findCandidates(2)).thenReturn(
            listOf(
                StaleDelegatedCandidate(1L, null, DocumentJobLane.BULK), // MISSING_SEQ -> reset
                StaleDelegatedCandidate(2L, 80L, DocumentJobLane.BULK), // STILL_QUEUED -> skip
                StaleDelegatedCandidate(3L, 10L, DocumentJobLane.BULK), // delivered, gone -> reset
            ),
        )
        whenever(ops.applyResets(listOf(1L, 3L))).thenReturn(2)
        val probe: DelegatedMessageProbe = mock()
        whenever(probe.snapshot()).thenReturn(DelegatedStreamSnapshot(deliveredStreamSeq = 50, interactiveDeliveredStreamSeq = 50, lastSeq = 100))
        whenever(probe.exists(10L)).thenReturn(false)

        val reset = coordinator(ops, probe).resetStale(2)

        assertEquals(2, reset)
        verify(probe).snapshot()
        verify(ops).applyResets(listOf(1L, 3L))
    }

    @Test
    fun `no candidate needing the probe means applyResets is skipped when nothing was decided to reset`() {
        val ops: StaleDelegatedResetTransactions = mock()
        whenever(ops.findCandidates(2)).thenReturn(listOf(StaleDelegatedCandidate(1L, 80L, DocumentJobLane.BULK))) // STILL_QUEUED
        val probe: DelegatedMessageProbe = mock()
        whenever(probe.snapshot()).thenReturn(DelegatedStreamSnapshot(deliveredStreamSeq = 50, interactiveDeliveredStreamSeq = 50, lastSeq = 100))

        val reset = coordinator(ops, probe).resetStale(2)

        assertEquals(0, reset)
        verify(ops, never()).applyResets(any())
    }

    @Test
    fun `a probe failure aborts the whole tick -- no resets applied, never rethrows`() {
        val ops: StaleDelegatedResetTransactions = mock()
        whenever(ops.findCandidates(2)).thenReturn(listOf(StaleDelegatedCandidate(1L, 10L, DocumentJobLane.BULK)))
        val probe: DelegatedMessageProbe = mock()
        whenever(probe.snapshot()).thenThrow(RuntimeException("NATS unreachable"))

        // Not wrapped in assertDoesNotThrow: a Kotlin lambda returning Int is also assignable to
        // that overload's (Unit-returning) Executable parameter, which silently discards the
        // result — a plain call already proves "does not throw" (an escaping exception would
        // fail this test on its own) while still letting the return value be asserted on.
        val reset = coordinator(ops, probe).resetStale(2)

        assertEquals(0, reset, "never reset on missing evidence")
        verify(ops, never()).applyResets(any())
    }

    @Test
    fun `a probe failure during an individual existence check also aborts the whole tick`() {
        val ops: StaleDelegatedResetTransactions = mock()
        whenever(ops.findCandidates(2)).thenReturn(
            listOf(StaleDelegatedCandidate(1L, null, DocumentJobLane.BULK), StaleDelegatedCandidate(2L, 10L, DocumentJobLane.BULK)),
        )
        val probe: DelegatedMessageProbe = mock()
        whenever(probe.snapshot()).thenReturn(DelegatedStreamSnapshot(deliveredStreamSeq = 50, interactiveDeliveredStreamSeq = 50, lastSeq = 100))
        whenever(probe.exists(10L)).thenThrow(RuntimeException("NATS unreachable"))

        val reset = coordinator(ops, probe).resetStale(2)

        assertEquals(0, reset, "even the evidence-free candidate (id 1) must not be reset once the tick aborts")
        verify(ops, never()).applyResets(any())
    }

    @Test
    fun `an interactive candidate is judged against the interactive cursor not the bulk one`() {
        val ops: StaleDelegatedResetTransactions = mock()
        whenever(ops.findCandidates(2)).thenReturn(
            listOf(
                StaleDelegatedCandidate(1L, 80L, DocumentJobLane.INTERACTIVE), // delivered, still exists -> keep
                StaleDelegatedCandidate(2L, 80L, DocumentJobLane.BULK), // behind bulk cursor -> keep
            ),
        )
        val probe: DelegatedMessageProbe = mock()
        whenever(probe.snapshot()).thenReturn(DelegatedStreamSnapshot(deliveredStreamSeq = 10, interactiveDeliveredStreamSeq = 90, lastSeq = 100))
        whenever(probe.exists(80L)).thenReturn(true)

        val reset = coordinator(ops, probe).resetStale(2)

        assertEquals(0, reset)
        verify(ops, never()).applyResets(any())
    }
}
