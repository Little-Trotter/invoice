package de.littletrotter.domain.compliance

import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.shared.b2b.B2bTerm

/**
 * One B2B profile field's mandatory-ness resolved against a concrete invoice (or one of its
 * lines): whether *some* value is present for it, right now, on this document.
 *
 * Building this list is the only place that touches
 * [de.littletrotter.domain.reference.B2bProfileField] and the entity/`b2b_field_value` lookups
 * ([de.littletrotter.application.invoice.B2bProfileRuleSet]) — [B2bProfileRules] itself only ever
 * sees the already-resolved shape below, so the actual mandatory-field *logic* is testable without
 * a profile or a database at all.
 *
 * @param btCode kept only so a [ComplianceFinding] can name the business term in
 *   [ComplianceFinding.businessTerms]/[ComplianceFinding.args] — the rule logic in
 *   [B2bProfileRules.evaluate] never branches on its *identity*, only compares it for equality.
 *   Typed as [B2bTerm] like [ComplianceFinding.businessTerms] itself; the display string is
 *   [B2bTerm.officialCode], read explicitly wherever this travels into [ComplianceFinding.args] so
 *   rendering stays byte-identical.
 * @param conditionBtCode the other field's [btCode] that makes this one mandatory, set exactly
 *   when [requirement] is [B2bFieldRequirement.CONDITIONAL] or
 *   [B2bFieldRequirement.CONDITIONAL_IF_EMPTY] (mirrors
 *   [de.littletrotter.domain.reference.B2bProfileField.conditionTerm]).
 * @param businessDefinition the user-defined explanation from the profile — carried through to a
 *   missing-field [ComplianceFinding] as the actual value-add over a bare "BT-19 missing".
 * @param hasValue whether the term already carries a value on this document (resolved by the
 *   adapter — bound property or `b2b_field_value` row, see [de.littletrotter.application.invoice
 *   .B2bProfileRuleSet]).
 * @param target [FindingTarget.Header]/[FindingTarget.Seller]/[FindingTarget.Buyer] for one
 *   invoice-wide field, [FindingTarget.Position] for a per-line one.
 */
data class B2bFieldState(
    val btCode: B2bTerm,
    val requirement: B2bFieldRequirement,
    val conditionBtCode: B2bTerm?,
    val businessDefinition: String,
    val hasValue: Boolean,
    val target: FindingTarget,
)

/**
 * The B2B profile's mandatory-field check as a compliance rule set (`B2B.*` prefix). Reads
 * whatever the profile/catalog say about a term (via [B2bFieldState]), decides nothing on its
 * own: a field is required because its [B2bFieldState.requirement] says so, never because of
 * which business term it happens to carry.
 *
 * [B2bFieldState.target] tells this function whether a field is invoice-wide or per-position; a
 * `CONDITIONAL`/`CONDITIONAL_IF_EMPTY` field's trigger is looked up **in scope**: every
 * non-position field is visible to every field (a header/seller/buyer term can gate a line term,
 * matching the seeded standard profile's BT-155/BT-156 ← BT-13), while a position's own fields are
 * visible only to that same position's siblings — a line-level trigger belonging to a *different*
 * position must not silently satisfy this one's condition.
 *
 * [B2bFieldRequirement.CONDITIONAL_IF_EMPTY] adds one more wrinkle: a **non-position** field's
 * trigger can itself be a LINE-level term, which never appears in-scope for a non-position field
 * (LINE fields only ever populate `lineByPosition`, never the shared header/seller/buyer scope
 * every field sees). In that case "empty" is defined as **absent on at least one line**
 * (`emptyOnAnyLine`, below) — a header requirement kicks in as soon as a single line is incomplete,
 * matching how a per-line CONDITIONAL field, from the other side, is already checked per line. When
 * no line carries the trigger term at all (no items, or no profile field for it placed on any
 * line), that also counts as empty — there is nothing to point to as "filled". A trigger visible
 * directly in scope (the common case: same level, or a LINE field's own condition on a
 * header/seller/buyer term) is read directly, exactly like [B2bFieldRequirement.CONDITIONAL] does,
 * just inverted.
 */
object B2bProfileRules {

    const val MANDATORY_FIELD = "B2B.MANDATORY_FIELD"

    fun evaluate(fields: List<B2bFieldState>): List<ComplianceFinding> {
        val globalByCode = fields.filterNot { it.target is FindingTarget.Position }.associateBy { it.btCode }
        val lineStatesByCode: Map<B2bTerm, List<B2bFieldState>> = fields
            .filter { it.target is FindingTarget.Position }
            .groupBy { it.btCode }
        val lineByPosition: Map<String, Map<B2bTerm, B2bFieldState>> = fields
            .mapNotNull { field -> (field.target as? FindingTarget.Position)?.let { it.positionId to field } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, siblings) -> siblings.associateBy { it.btCode } }

        return fields.mapNotNull { field ->
            val triggerScope = triggerScopeFor(field, globalByCode, lineByPosition)
            val required = isRequired(field, triggerScope, lineStatesByCode)
            if (!required || field.hasValue) return@mapNotNull null
            findingFor(field)
        }
    }

    // Each helper below is one piece of evaluate()'s per-field decision, extracted so that
    // method's own cognitive complexity stays low.
    private fun triggerScopeFor(
        field: B2bFieldState,
        globalByCode: Map<B2bTerm, B2bFieldState>,
        lineByPosition: Map<String, Map<B2bTerm, B2bFieldState>>,
    ): Map<B2bTerm, B2bFieldState> = when (val target = field.target) {
        is FindingTarget.Position -> globalByCode + lineByPosition[target.positionId].orEmpty()
        else -> globalByCode
    }

    private fun isRequired(
        field: B2bFieldState,
        triggerScope: Map<B2bTerm, B2bFieldState>,
        lineStatesByCode: Map<B2bTerm, List<B2bFieldState>>,
    ): Boolean = when (field.requirement) {
        B2bFieldRequirement.MANDATORY -> true
        B2bFieldRequirement.OPTIONAL -> false
        B2bFieldRequirement.CONDITIONAL -> triggerScope[field.conditionBtCode]?.hasValue ?: false
        B2bFieldRequirement.CONDITIONAL_IF_EMPTY -> isRequiredIfEmpty(field, triggerScope, lineStatesByCode)
    }

    private fun isRequiredIfEmpty(
        field: B2bFieldState,
        triggerScope: Map<B2bTerm, B2bFieldState>,
        lineStatesByCode: Map<B2bTerm, List<B2bFieldState>>,
    ): Boolean {
        val trigger = field.conditionBtCode ?: return false
        val inScope = triggerScope[trigger]
        return if (inScope != null) !inScope.hasValue else emptyOnAnyLine(trigger, lineStatesByCode)
    }

    // args stays [btCode.officialCode, businessDefinition] regardless of kind — index 1
    // is load-bearing (the detail view's B2B finding read model takes the definition from
    // it). The trigger term rides along as businessTerms[1] instead, so it names
    // both terms without disturbing that contract; absent for every other requirement
    // kind. args stays String-shaped (not the typed B2bTerm) so the rendered text is
    // unaffected by the typed term constants — a raw B2bTerm.toString() would print "BT_19", not "BT-19".
    private fun findingFor(field: B2bFieldState): ComplianceFinding {
        val ifEmpty = field.requirement == B2bFieldRequirement.CONDITIONAL_IF_EMPTY
        return ComplianceFinding(
            ruleId = MANDATORY_FIELD,
            severity = ComplianceSeverity.ERROR,
            messageKey = if (ifEmpty) "compliance.b2b.mandatoryFieldMissingIfEmpty" else "compliance.b2b.mandatoryFieldMissing",
            businessTerms = if (ifEmpty && field.conditionBtCode != null) listOf(field.btCode, field.conditionBtCode) else listOf(field.btCode),
            target = field.target,
            args = listOf(field.btCode.officialCode, field.businessDefinition),
        )
    }

    /** True unless [triggerBtCode] has a value on **every** line ([lineStatesByCode]'s per-code
     *  list) — i.e. it is missing on at least one line, or there is no line carrying it at all. */
    private fun emptyOnAnyLine(triggerBtCode: B2bTerm, lineStatesByCode: Map<B2bTerm, List<B2bFieldState>>): Boolean {
        val lineStates = lineStatesByCode[triggerBtCode]
        return lineStates.isNullOrEmpty() || lineStates.any { !it.hasValue }
    }
}
