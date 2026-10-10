package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every branch of [StaleDelegatedResetDecider.decide], the pure decision at the heart of
 * the fix: "a `DELEGATED` job is re-published only when its message no longer exists in the
 * stream." [FailingProbe] proves the two "no broker call needed" branches ([StaleDelegatedResetReason
 * .MISSING_SEQ]/[StaleDelegatedResetReason.STREAM_RECREATED]/[StaleDelegatedResetReason
 * .STILL_QUEUED]) never actually reach [DelegatedMessageProbe.exists] at all.
 */
class StaleDelegatedResetDeciderTest {

    private class FailingProbe : DelegatedMessageProbe {
        override fun snapshot(): DelegatedStreamSnapshot = throw AssertionError("snapshot() must never be called by the decider itself")
        override fun exists(streamSeq: Long): Boolean = throw AssertionError("exists() must not be called for a decision that doesn't need it")
    }

    private class FixedProbe(private val result: Boolean) : DelegatedMessageProbe {
        var existsCalledWith: Long? = null
        override fun snapshot(): DelegatedStreamSnapshot = throw AssertionError("not used in these tests")
        override fun exists(streamSeq: Long): Boolean {
            existsCalledWith = streamSeq
            return result
        }
    }

    @Test
    fun `no recorded sequence resets outright without ever touching the probe`() {
        val reason = StaleDelegatedResetDecider.decide(null, DocumentJobLane.BULK, DelegatedStreamSnapshot(deliveredStreamSeq = 100, interactiveDeliveredStreamSeq = 100, lastSeq = 100), FailingProbe())

        assertEquals(StaleDelegatedResetReason.MISSING_SEQ, reason)
        assertTrue(reason.resets)
    }

    @Test
    fun `a sequence past the stream's last sequence means the stream was recreated -- resets without touching the probe`() {
        val reason = StaleDelegatedResetDecider.decide(150L, DocumentJobLane.BULK, DelegatedStreamSnapshot(deliveredStreamSeq = 50, interactiveDeliveredStreamSeq = 50, lastSeq = 100), FailingProbe())

        assertEquals(StaleDelegatedResetReason.STREAM_RECREATED, reason)
        assertTrue(reason.resets)
    }

    @Test
    fun `a sequence still ahead of the delivered cursor is still queued -- never resets, never touches the probe`() {
        val reason = StaleDelegatedResetDecider.decide(80L, DocumentJobLane.BULK, DelegatedStreamSnapshot(deliveredStreamSeq = 50, interactiveDeliveredStreamSeq = 50, lastSeq = 100), FailingProbe())

        assertEquals(StaleDelegatedResetReason.STILL_QUEUED, reason)
        assertFalse(reason.resets)
    }

    @Test
    fun `a delivered sequence whose message still exists is in flight -- never resets`() {
        val probe = FixedProbe(result = true)

        val reason = StaleDelegatedResetDecider.decide(30L, DocumentJobLane.BULK, DelegatedStreamSnapshot(deliveredStreamSeq = 50, interactiveDeliveredStreamSeq = 50, lastSeq = 100), probe)

        assertEquals(StaleDelegatedResetReason.IN_FLIGHT, reason)
        assertFalse(reason.resets)
        assertEquals(30L, probe.existsCalledWith)
    }

    @Test
    fun `a delivered sequence whose message is gone resets`() {
        val probe = FixedProbe(result = false)

        val reason = StaleDelegatedResetDecider.decide(30L, DocumentJobLane.BULK, DelegatedStreamSnapshot(deliveredStreamSeq = 50, interactiveDeliveredStreamSeq = 50, lastSeq = 100), probe)

        assertEquals(StaleDelegatedResetReason.MESSAGE_GONE, reason)
        assertTrue(reason.resets)
        assertEquals(30L, probe.existsCalledWith)
    }

    @Test
    fun `a sequence exactly equal to the delivered cursor still requires the existence check`() {
        val probe = FixedProbe(result = false)

        val reason = StaleDelegatedResetDecider.decide(50L, DocumentJobLane.BULK, DelegatedStreamSnapshot(deliveredStreamSeq = 50, interactiveDeliveredStreamSeq = 50, lastSeq = 100), probe)

        assertEquals(StaleDelegatedResetReason.MESSAGE_GONE, reason)
        assertEquals(50L, probe.existsCalledWith)
    }

    @Test
    fun `a sequence exactly equal to the stream's last sequence is not treated as stream-recreated`() {
        val probe = FixedProbe(result = true)

        val reason = StaleDelegatedResetDecider.decide(100L, DocumentJobLane.BULK, DelegatedStreamSnapshot(deliveredStreamSeq = 100, interactiveDeliveredStreamSeq = 100, lastSeq = 100), probe)

        assertEquals(StaleDelegatedResetReason.IN_FLIGHT, reason)
    }

    @Test
    fun `an interactive job already delivered on its own consumer is not queued while the bulk cursor is behind`() {
        val probe = FixedProbe(result = true)
        val snapshot = DelegatedStreamSnapshot(deliveredStreamSeq = 10, interactiveDeliveredStreamSeq = 90, lastSeq = 100)

        val reason = StaleDelegatedResetDecider.decide(80L, DocumentJobLane.INTERACTIVE, snapshot, probe)

        assertEquals(StaleDelegatedResetReason.IN_FLIGHT, reason)
        assertFalse(reason.resets)
    }

    @Test
    fun `a bulk job past the bulk cursor stays queued even when the interactive cursor is ahead`() {
        val snapshot = DelegatedStreamSnapshot(deliveredStreamSeq = 10, interactiveDeliveredStreamSeq = 90, lastSeq = 100)

        val reason = StaleDelegatedResetDecider.decide(80L, DocumentJobLane.BULK, snapshot, FailingProbe())

        assertEquals(StaleDelegatedResetReason.STILL_QUEUED, reason)
    }

    @Test
    fun `an interactive job past the interactive cursor is still queued even when the bulk cursor is ahead`() {
        val snapshot = DelegatedStreamSnapshot(deliveredStreamSeq = 90, interactiveDeliveredStreamSeq = 10, lastSeq = 100)

        val reason = StaleDelegatedResetDecider.decide(80L, DocumentJobLane.INTERACTIVE, snapshot, FailingProbe())

        assertEquals(StaleDelegatedResetReason.STILL_QUEUED, reason)
    }
}
