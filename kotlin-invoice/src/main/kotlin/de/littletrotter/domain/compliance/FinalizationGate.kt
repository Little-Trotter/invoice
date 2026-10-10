package de.littletrotter.domain.compliance

import de.littletrotter.domain.shared.ValidationStatus
import java.time.OffsetDateTime
import de.littletrotter.shared.ValidationMessage

/** The three-stage rollout switch. Stage [OBSERVE] is the safe default: the gate computes and
 *  logs its verdict but never blocks. */
enum class GateStage { OBSERVE, WARN, BLOCK }

/** Why [FinalizationGate.evaluate] blocked a transition, distinguishable so the UI/logs can
 *  tell a non-compliant invoice (`VALIDATION_INVALID`) apart from one that simply hasn't run
 *  through validation yet (`VALIDATION_PENDING`). */
enum class GateBlockReason {
    VALIDATION_INVALID,
    VALIDATION_PENDING,
    VALIDATION_UNAVAILABLE,
    PROFILE_BLOCKED_OR_UNKNOWN,

    /**
     * Distinct from [VALIDATION_PENDING]: this invoice has never been near an e-invoice run at
     * all (`ValidationStatus.NOT_VALIDATED`), as opposed to a run that is genuinely in flight
     * right now. Kept separate rather than folded into `VALIDATION_PENDING` so logs/UI never
     * claim a process is running when none ever started. Not overridable, same as
     * `VALIDATION_PENDING`/`PROFILE_BLOCKED_OR_UNKNOWN` — the `UNAVAILABLE`-only override in
     * [de.littletrotter.application.invoice.FinalizationPolicy] stays untouched.
     */
    VALIDATION_NOT_RUN,

    /**
     * A blocking finding from rule evaluation ([ComplianceEvaluator]) — a fifth axis alongside
     * the other four. Unlike those, it describes the invoice itself, not a validation run's
     * state, so [FinalizationOverride] doesn't help: a missing mandatory field doesn't become
     * present just because someone records a justification.
     */
    COMPLIANCE_RULE_VIOLATION,
}

/**
 * A [ComplianceFinding] in the transport form [InvoiceFinalizationBlockedException] and the
 * invoice view already use.
 *
 * [ValidationMessage.message] carries the **key**, not resolved text — the domain never
 * generates text, and the UI resolves via `ruleId` and key anyway. [args] rides along as
 * [ValidationMessage.args] so a resolving UI has something to fill the key's placeholders with.
 */
fun ComplianceFinding.toValidationMessage(): ValidationMessage =
    ValidationMessage(
        severity = severity.name,
        message = messageKey,
        location = positionId?.let { "position:$it" },
        ruleId = ruleId,
        stage = "BUSINESS",
        validator = "compliance-rules",
        args = args.map { it?.toString() ?: "" },
    )

/** A `UNAVAILABLE`-only override: requires a justification and is meant to be logged by the
 *  caller. Never applies to `INVALID` or a blocked/unknown profile —
 *  [FinalizationGate.evaluate] ignores it for those. */
data class FinalizationOverride(
    val reason: String,
    val actor: String,
    val at: OffsetDateTime,
) {
    val isValid: Boolean get() = reason.isNotBlank() && actor.isNotBlank()
}

sealed class GateDecision {
    object Allow : GateDecision()
    data class AllowWithWarning(val warning: String) : GateDecision()
    data class Block(val reason: GateBlockReason, val findings: List<ValidationMessage> = emptyList()) : GateDecision()
}

/** F4/VALIDATION-01 — evaluates just the profile-state axis of the gate, kept
 *  separate from [FinalizationGate] so a profile-registry-only regression is visible without a
 *  [ValidationStatus] fixture in every test case. */
object ValidationProfileRegistry {
    fun evaluate(state: ValidationProfileState?): GateDecision = when (state) {
        null, ValidationProfileState.BLOCKED -> GateDecision.Block(GateBlockReason.PROFILE_BLOCKED_OR_UNKNOWN)
        ValidationProfileState.DEPRECATED -> GateDecision.AllowWithWarning("Profil ist veraltet (DEPRECATED).")
        ValidationProfileState.ACTIVE, ValidationProfileState.EXPERIMENTAL -> GateDecision.Allow
    }
}

/**
 * F4 — the finalization gate itself (Umsetzung §2/§3). Pure and stage-agnostic: always answers
 * "would Stage C block this?" — [GateStage] handling (log-only in `OBSERVE`, warn-but-allow in
 * `WARN`) is the caller's job ([de.littletrotter.application.invoice.InvoiceLifecycleService]),
 * since only the caller can log the decision (domain stays side-effect free).
 */
object FinalizationGate {
    fun evaluate(
        validationStatus: ValidationStatus,
        profileState: ValidationProfileState?,
        findings: List<ValidationMessage> = emptyList(),
        override: FinalizationOverride? = null,
    ): GateDecision {
        // The profile axis is checked first: an unregistered/blocked profile is just as
        // non-overridable as INVALID, and neither VALID nor an UNAVAILABLE-override should be
        // able to paper over it.
        val profileVerdict = ValidationProfileRegistry.evaluate(profileState)
        if (profileVerdict is GateDecision.Block) return profileVerdict

        val statusVerdict: GateDecision = when (validationStatus) {
            ValidationStatus.INVALID -> GateDecision.Block(GateBlockReason.VALIDATION_INVALID, findings)
            ValidationStatus.PENDING -> GateDecision.Block(GateBlockReason.VALIDATION_PENDING)
            ValidationStatus.NOT_VALIDATED -> GateDecision.Block(GateBlockReason.VALIDATION_NOT_RUN)
            ValidationStatus.UNAVAILABLE ->
                if (override?.isValid == true) GateDecision.Allow
                else GateDecision.Block(GateBlockReason.VALIDATION_UNAVAILABLE)
            ValidationStatus.VALID -> GateDecision.Allow
        }
        if (statusVerdict is GateDecision.Block) return statusVerdict

        return profileVerdict // Allow, or AllowWithWarning for a DEPRECATED profile
    }
}

/**
 * F4 — thrown by [de.littletrotter.application.invoice.InvoiceLifecycleService.transition] when
 * [FinalizationGate.evaluate] blocks a `→ SENT` transition. Carries the typed [reason] and
 * raw [findings] (rule id + message, F2) rather than pre-rendered text — resolving a correction
 * hint per finding is a UI-layer concern ([de.littletrotter.ui.view.invoice.ValidationHints]),
 * the same split [de.littletrotter.domain.exception.InvoiceValidationException] already uses.
 *
 * Extends [RuntimeException] directly rather than the sealed
 * [de.littletrotter.domain.exception.InvoiceDomainException] hierarchy — Kotlin only allows a
 * sealed class's subclasses in its own package, and this stays in `domain/validation/`
 * (F4's declared Schreibbereich) rather than reaching into `domain/exception/`.
 */
class InvoiceFinalizationBlockedException(
    val reason: GateBlockReason,
    val findings: List<ValidationMessage> = emptyList(),
) : RuntimeException(
    buildString {
        append("Finalization blocked: $reason")
        findings.take(MAX_FINDINGS_IN_MESSAGE).forEach { append("; ").append(it.ruleId ?: it.message) }
    }
) {
    private companion object {
        /** Findings beyond this count are omitted from the exception message (full list stays
         *  in [findings] for callers that need it). */
        const val MAX_FINDINGS_IN_MESSAGE = 3
    }
}
