package de.littletrotter.application.dto

/**
 * Read model for one missing-mandatory-field finding of the seller's B2B profile, as the detail
 * view's checklist and the e-invoice review step render it.
 *
 * @param ruleId Stable rule identifier; the last-resort label when neither a definition nor a
 *   business term is known.
 * @param businessTermCodes Official codes (`BT-nn`) of the affected business terms: the first is
 *   the missing field, a second one is the condition field of a "required if empty" rule.
 * @param definition The profile's own user-defined wording for the field, when it has one.
 * @param positionId Business position id of the invoice line the finding hangs off; `null` for a
 *   header-level finding.
 */
data class B2bFindingDto(
    val ruleId: String,
    val businessTermCodes: List<String>,
    val definition: String?,
    val positionId: String?,
)
