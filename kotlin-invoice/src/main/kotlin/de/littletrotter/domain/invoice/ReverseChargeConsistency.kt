package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.shared.tax.VatCategory

/**
 * Since [Invoice.reverseCharge] is now the leading, user-set decision instead of a
 * derivation of the items (see that field's own KDoc), positions must follow it: every item's
 * frozen tax category has to agree with the header decision before `→ ISSUED`. A position
 * without a snapshot yet ([InvoiceItem.taxCategory] `null`) doesn't participate — same
 * convention as [de.littletrotter.domain.compliance.hasMixedReverseChargeAndStandard] (R4's
 * fallback-to-draft case, not a mixing/consistency violation).
 *
 * Two distinct violations, both hard errors naming every offending position by its
 * [InvoiceItem.positionId]:
 * - decision ON, but at least one position doesn't carry [VatCategory.REVERSE_CHARGE].
 * - decision OFF, but at least one position does carry [VatCategory.REVERSE_CHARGE].
 *
 * Checked unconditionally in `InvoiceLifecycleService.transition`, right next to
 * [validateHeaderLinePeriodConsistency] — a structural data-entry defect, not a validation-run
 * outcome, so (like that check) it blocks regardless of the staged compliance-rules rollout.
 * [de.littletrotter.domain.compliance.assertReverseChargeRecipientEligibility] is the companion
 * check for the third violation (decision ON, but the recipient no longer qualifies) — it
 * lives in `domain.compliance` instead of here because it needs [de.littletrotter.domain
 * .compliance.TaxRuleResolver], and `domain.compliance` reads `domain.invoice`, never the other
 * way round.
 */
fun Invoice.validateReverseChargeItemConsistency() {
    val offenders = if (reverseCharge) {
        itemList.filter { it.taxCategory != null && it.taxCategory != VatCategory.REVERSE_CHARGE }
    } else {
        itemList.filter { it.taxCategory == VatCategory.REVERSE_CHARGE }
    }
    if (offenders.isEmpty()) return

    val positions = offenders.joinToString(", ") { it.positionId }
    val error = if (reverseCharge) {
        InvoiceValidationError.REVERSE_CHARGE_ITEM_NOT_AE
    } else {
        InvoiceValidationError.REVERSE_CHARGE_ITEM_UNEXPECTED_AE
    }
    throw InvoiceValidationException(error, listOf(positions))
}
