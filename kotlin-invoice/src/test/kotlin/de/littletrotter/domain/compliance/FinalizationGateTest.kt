package de.littletrotter.domain.compliance

import de.littletrotter.shared.ValidationMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** F4 — [FinalizationOverride.isValid] and the exception message [InvoiceFinalizationBlockedException]
 *  builds from a list of findings. Neither had its own domain-level test yet: [FinalizationGate]
 *  itself is only exercised via `FinalizationGateIT` (Quarkus), which never isolates these two. */
class FinalizationGateTest {

    private val fixedAt = java.time.OffsetDateTime.parse("2026-06-15T10:00:00Z")

    // ── FinalizationOverride.isValid ────────────────────────────────────────────

    @Test
    fun `an override with both a reason and an actor is valid`() {
        assertTrue(FinalizationOverride(reason = "VIES nicht erreichbar", actor = "buero@example.test", at = fixedAt).isValid)
    }

    @Test
    fun `an override with a blank reason is invalid, even with an actor set`() {
        assertFalse(FinalizationOverride(reason = "  ", actor = "buero@example.test", at = fixedAt).isValid)
    }

    @Test
    fun `an override with a blank actor is invalid, even with a reason set`() {
        assertFalse(FinalizationOverride(reason = "VIES nicht erreichbar", actor = "  ", at = fixedAt).isValid)
    }

    @Test
    fun `an override backs its VALIDATION_UNAVAILABLE allowance from isValid`() {
        val allowed = FinalizationGate.evaluate(
            validationStatus = de.littletrotter.domain.shared.ValidationStatus.UNAVAILABLE,
            profileState = ValidationProfileState.ACTIVE,
            override = FinalizationOverride(reason = "VIES nicht erreichbar", actor = "buero@example.test", at = fixedAt),
        )
        assertTrue(allowed is GateDecision.Allow)

        val stillBlocked = FinalizationGate.evaluate(
            validationStatus = de.littletrotter.domain.shared.ValidationStatus.UNAVAILABLE,
            profileState = ValidationProfileState.ACTIVE,
            override = FinalizationOverride(reason = "", actor = "buero@example.test", at = fixedAt),
        )
        assertTrue(stillBlocked is GateDecision.Block)
    }

    // ── validation-status-truthful-pending.md R4/R7 — NOT_VALIDATED vs. genuine PENDING ────────

    @Test
    fun `NOT_VALIDATED blocks with VALIDATION_NOT_RUN, distinguishable from a genuinely running PENDING job`() {
        val decision = FinalizationGate.evaluate(
            validationStatus = de.littletrotter.domain.shared.ValidationStatus.NOT_VALIDATED,
            profileState = ValidationProfileState.ACTIVE,
        )
        assertTrue(decision is GateDecision.Block)
        assertEquals(GateBlockReason.VALIDATION_NOT_RUN, (decision as GateDecision.Block).reason)
    }

    @Test
    fun `NOT_VALIDATED is not overridable, unlike UNAVAILABLE`() {
        val decision = FinalizationGate.evaluate(
            validationStatus = de.littletrotter.domain.shared.ValidationStatus.NOT_VALIDATED,
            profileState = ValidationProfileState.ACTIVE,
            override = FinalizationOverride(reason = "irrelevant", actor = "buero@example.test", at = fixedAt),
        )
        assertTrue(decision is GateDecision.Block)
        assertEquals(GateBlockReason.VALIDATION_NOT_RUN, (decision as GateDecision.Block).reason)
    }

    @Test
    fun `PENDING still blocks with VALIDATION_PENDING, unaffected by the NOT_VALIDATED addition`() {
        val decision = FinalizationGate.evaluate(
            validationStatus = de.littletrotter.domain.shared.ValidationStatus.PENDING,
            profileState = ValidationProfileState.ACTIVE,
        )
        assertTrue(decision is GateDecision.Block)
        assertEquals(GateBlockReason.VALIDATION_PENDING, (decision as GateDecision.Block).reason)
    }

    // ── InvoiceFinalizationBlockedException's message ───────────────────────────

    @Test
    fun `the exception message names a finding by its ruleId when present`() {
        val ex = InvoiceFinalizationBlockedException(
            GateBlockReason.COMPLIANCE_RULE_VIOLATION,
            listOf(ValidationMessage(severity = "ERROR", message = "Käuferreferenz fehlt", ruleId = "BR-DE-15")),
        )
        assertTrue(ex.message!!.contains("BR-DE-15"), ex.message!!)
    }

    @Test
    fun `the exception message falls back to the plain message text when a finding has no ruleId`() {
        val ex = InvoiceFinalizationBlockedException(
            GateBlockReason.VALIDATION_INVALID,
            listOf(ValidationMessage(severity = "ERROR", message = "Rechnungsnummer fehlt", ruleId = null)),
        )
        assertTrue(ex.message!!.contains("Rechnungsnummer fehlt"), ex.message!!)
    }
}
