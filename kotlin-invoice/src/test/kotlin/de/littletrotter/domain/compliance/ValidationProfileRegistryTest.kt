package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The profile-state axis of the finalization gate, in isolation. */
class ValidationProfileRegistryTest {

    @Test
    fun `ACTIVE allows finalization`() {
        assertEquals(GateDecision.Allow, ValidationProfileRegistry.evaluate(ValidationProfileState.ACTIVE))
    }

    @Test
    fun `BLOCKED blocks, not overridable`() {
        val decision = ValidationProfileRegistry.evaluate(ValidationProfileState.BLOCKED)
        assertTrue(decision is GateDecision.Block)
        assertEquals(GateBlockReason.PROFILE_BLOCKED_OR_UNKNOWN, (decision as GateDecision.Block).reason)
    }

    @Test
    fun `an unknown (null) profile blocks the same way as BLOCKED`() {
        val decision = ValidationProfileRegistry.evaluate(null)
        assertTrue(decision is GateDecision.Block)
        assertEquals(GateBlockReason.PROFILE_BLOCKED_OR_UNKNOWN, (decision as GateDecision.Block).reason)
    }

    @Test
    fun `DEPRECATED allows finalization with a warning`() {
        val decision = ValidationProfileRegistry.evaluate(ValidationProfileState.DEPRECATED)
        assertTrue(decision is GateDecision.AllowWithWarning)
    }

    @Test
    fun `EXPERIMENTAL allows finalization without a warning`() {
        assertEquals(GateDecision.Allow, ValidationProfileRegistry.evaluate(ValidationProfileState.EXPERIMENTAL))
    }
}
