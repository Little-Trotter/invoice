package de.littletrotter.application.dto

/**
 * Read model for one completeness check of an invoice, satisfied or not: the detail view's
 * checklist shows every check, including the ones already fulfilled.
 */
data class CompletenessCheckDto(
    val ruleId: String,
    val satisfied: Boolean,
)
