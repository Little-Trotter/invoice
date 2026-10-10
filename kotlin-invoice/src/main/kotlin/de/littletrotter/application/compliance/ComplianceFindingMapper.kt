package de.littletrotter.application.compliance

import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.compliance.ComplianceSeverity
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationStageResult

/**
 * The Anti-Corruption mapper at the boundary between the two validation bounded contexts:
 *
 * - Welt A, `de.littletrotter.domain.compliance` — the fachliche Wahrheit:
 *   [ComplianceFinding] with [ComplianceSeverity] and a typed [FindingTarget].
 * - Welt B, `de.littletrotter.shared` — the report/transport format: the deliberately
 *   string-tolerant [ValidationMessage]/[ValidationStageResult] that an old, already-persisted
 *   JSON report must stay decodable against.
 *
 * These two worlds are deliberately **not** to be merged — `domain/compliance` stays
 * the sole fachliche Wahrheit in `kotlin-invoice`, and `ValidationMessage`/`ValidationStageResult`
 * stay string-typed in `kotlin-shared` so the tolerant reader (see `KositReportPayload`'s own
 * KDoc) keeps working. This file is the one-way translation between them: it may import
 * `domain.compliance` (it lives in `kotlin-invoice`, which owns that package), but nothing under
 * `kotlin-shared` or `kotlin-xpdf` may ever import `domain.compliance` — the dependency runs
 * strictly invoice-application → shared, never the other way.
 *
 * The mapping itself is the following table:
 *
 * | Fachlich (Welt A)             | Bericht (Welt B)            |
 * |--------------------------------|------------------------------|
 * | [ComplianceSeverity.ERROR]     | `severity = "error"`         |
 * | [ComplianceSeverity.WARNING]   | `severity = "warning"`       |
 * | [ComplianceSeverity.INFO]      | `severity = "info"`          |
 * | [FindingTarget.Header]          | `location = "header"`        |
 * | [FindingTarget.Position]        | `location = "position:$id"`  |
 * | [FindingTarget.Seller]          | `location = "seller"`        |
 * | [FindingTarget.Buyer]           | `location = "buyer"`         |
 * | [FindingTarget.Article]         | `location = "article:$name"` |
 *
 * `ruleId` passes through unchanged; `stage` is always `"BUSINESS"`, `validator` always
 * `"compliance"`.
 */
object ComplianceFindingMapper {

    /**
     * One [ComplianceFinding] as a [ValidationMessage] on the `BUSINESS` report stage.
     *
     * [ValidationMessage.message] carries [ComplianceFinding.messageKey], not resolved text —
     * the same choice [de.littletrotter.domain.compliance.toValidationMessage] (the
     * `FinalizationGate` exception path) already makes: the domain never renders text, and
     * the UI resolves `messages.properties` from `ruleId`/the key
     * itself either way. [ComplianceFinding.args] carries through as [ValidationMessage.args]
     * (stringified) so that render-time resolution can actually fill the key's `{0}`/`{1}`
     * placeholders — without this, a resolving UI would still have nothing to substitute.
     */
    fun toValidationMessage(finding: ComplianceFinding): ValidationMessage = ValidationMessage(
        severity = finding.severity.toReportSeverity(),
        message = finding.messageKey,
        location = finding.target.toLocation(),
        ruleId = finding.ruleId,
        stage = "BUSINESS",
        validator = "compliance",
        args = finding.args.map { it?.toString() ?: "" },
    )

    /**
     * The full `BUSINESS` stage result for a report — the outcome rule: the stage is
     * [KositOutcome.INVALID] when at least one [ComplianceSeverity.ERROR] finding is present,
     * [KositOutcome.VALID] otherwise (an empty list, or only WARNING/INFO findings). Never
     * `skipped` — that placeholder was exactly the gap this mapper closes; from here on the stage
     * always reports honestly, independent of `compliance-rules.stage` (OBSERVE/WARN/BLOCK is a
     * [de.littletrotter.domain.compliance.FinalizationGate] concern, not a reporting one).
     */
    fun toValidationStageResult(findings: List<ComplianceFinding>): ValidationStageResult {
        val messages = findings.map(::toValidationMessage)
        val outcome = if (findings.any { it.severity == ComplianceSeverity.ERROR }) {
            KositOutcome.INVALID
        } else {
            KositOutcome.VALID
        }
        return ValidationStageResult(
            stage = "BUSINESS",
            outcome = outcome,
            findings = messages,
            skipped = false,
        )
    }

    private fun ComplianceSeverity.toReportSeverity(): String = when (this) {
        ComplianceSeverity.ERROR -> "error"
        ComplianceSeverity.WARNING -> "warning"
        ComplianceSeverity.INFO -> "info"
    }

    private fun FindingTarget.toLocation(): String = when (this) {
        is FindingTarget.Header -> "header"
        is FindingTarget.Position -> "position:$positionId"
        is FindingTarget.Seller -> "seller"
        is FindingTarget.Buyer -> "buyer"
        is FindingTarget.Article -> "article:$articleName"
    }
}
