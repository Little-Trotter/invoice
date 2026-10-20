package de.littletrotter.domain.compliance

import de.littletrotter.shared.b2b.B2bTerm

/**
 * The compliance subcontext: **one** place holding every finding about a subject, rather than
 * separate validation paths each with their own result shape.
 *
 * Deliberately **not** a throw like [de.littletrotter.domain.exception.InvoiceValidationException]:
 * an exception reports only the first violation and aborts. A standards check has to surface every
 * finding at once and must not prevent saving a draft — hence a return value, the same separation
 * `GateDecision` already applies.
 */
enum class ComplianceSeverity {
    /** A standards violation or a missing mandatory entry. Blocks finalization. */
    ERROR,

    /** Notable but permitted. Never blocking. */
    WARNING,

    /** Informational only — something that makes the invoice better, but without which it is
     *  still valid. Carries the invoice view's checklist, which shows more points than there are
     *  hard obligations. */
    INFO,
}

/**
 * What a finding refers to. "Header or line" is not enough once rule evaluation also judges master
 * data and articles: a finding about the seller has to appear at the seller, not at the invoice
 * header.
 */
sealed interface FindingTarget {
    /** The document as a whole. */
    data object Header : FindingTarget

    /** A single invoice line, addressed by its business
     *  [de.littletrotter.domain.invoice.InvoiceItem.positionId] — not the technical id, which an
     *  unsaved draft does not have yet. */
    data class Position(val positionId: String) : FindingTarget

    /** The seller (sender profile). */
    data object Seller : FindingTarget

    /** The buyer. */
    data object Buyer : FindingTarget

    /** An article master record, addressed by name: [de.littletrotter.domain.masterdata.Article
     *  .articleNumber] is nullable and may not be assigned yet, so the name is the only identifier
     *  always available here. */
    data class Article(val articleName: String) : FindingTarget
}

/**
 * A single finding. Carries rule id, affected business terms and parameters **raw**, never
 * finished text — resolving against `messages.properties` belongs to the UI layer, which is why
 * the domain carries no `translationKey`.
 *
 * @param ruleId Stable identifier. `BR-*` = a rule from EN 16931 itself, `LT-*` = a house rule
 *   going beyond the standard, `CORE.*` = completeness of the application itself, `B2B.*` = a rule
 *   from a user-defined profile. The prefix carries the distinction because only a `BR-*` finding
 *   can be justified to a recipient by citing the standard.
 * @param messageKey Key in `messages.properties`, not the text itself.
 * @param businessTerms Typed [B2bTerm] constants, not raw `"BT-nn"` strings. Rendering (the
 *   checklist, `InvoiceOverviewPanel`) reads [B2bTerm.officialCode] off these.
 */
data class ComplianceFinding(
    val ruleId: String,
    val severity: ComplianceSeverity,
    val messageKey: String,
    val businessTerms: List<B2bTerm> = emptyList(),
    val target: FindingTarget = FindingTarget.Header,
    val args: List<Any?> = emptyList(),
) {
    /** The affected line, if the finding hangs off one — otherwise `null`. */
    val positionId: String? get() = (target as? FindingTarget.Position)?.positionId

    /** True as soon as the finding blocks finalization. */
    val isBlocking: Boolean get() = severity == ComplianceSeverity.ERROR
}

/**
 * A single check **together with its result** — including the positive one.
 *
 * A finding only exists when something is missing, but the UI checklist also has to show the
 * satisfied points ("invoice number ✓"). Feeding those from two separate sources is what lets
 * display and blocking drift apart: here the rule set yields the checks, and findings are the
 * derived subset of the unsatisfied ones.
 */
data class ComplianceCheck(
    val ruleId: String,
    val severity: ComplianceSeverity,
    val messageKey: String,
    val target: FindingTarget = FindingTarget.Header,
    val businessTerms: List<B2bTerm> = emptyList(),
    val isSatisfied: Boolean,
) {
    fun findingOrNull(): ComplianceFinding? =
        if (isSatisfied) null
        else ComplianceFinding(ruleId, severity, messageKey, businessTerms, target)
}

/** The unsatisfied checks, as findings. */
fun List<ComplianceCheck>.findings(): List<ComplianceFinding> = mapNotNull { it.findingOrNull() }

/** Convenience for callers that only need to know whether anything blocks. */
fun List<ComplianceFinding>.hasBlocking(): Boolean = any { it.isBlocking }

/** Every finding that blocks finalization. */
fun List<ComplianceFinding>.blocking(): List<ComplianceFinding> = filter { it.isBlocking }

/** Every finding on one particular target, e.g. a single line. */
fun List<ComplianceFinding>.on(target: FindingTarget): List<ComplianceFinding> =
    filter { it.target == target }
