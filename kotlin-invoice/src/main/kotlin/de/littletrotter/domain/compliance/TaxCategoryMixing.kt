package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.shared.tax.VatCategory

// ── Finalization invariant (Integration §5) ─────────────────────────────────

/** Reverse Charge (`AE`) and the domestic standard rate (`S`) can never coexist on
 *  one invoice: a single supply is either wholly reverse-charged to the buyer or wholly taxed
 *  by the seller, never split. Items without a snapshot yet (`taxCategoryEnum == null`) don't
 *  participate — that's R4's fallback-to-draft case, not a mixing violation. */
fun List<InvoiceItem>.hasMixedReverseChargeAndStandard(): Boolean {
    val categories = mapNotNull { it.taxCategoryEnum }.toSet()
    return VatCategory.REVERSE_CHARGE in categories && VatCategory.STANDARD in categories
}

/** R5 — thrown by [de.littletrotter.application.invoice.InvoiceLifecycleService.transition]
 *  when [hasMixedReverseChargeAndStandard] rejects a `→ SENT` transition. Extends
 *  [RuntimeException] directly for the same reason as
 *  [InvoiceFinalizationBlockedException] — a sealed hierarchy in `domain/exception/` can't be
 *  subclassed from this package. */
class MixedTaxCategoryException :
    RuntimeException("Rechnung enthält sowohl Reverse-Charge- (AE) als auch Regelsatz-Positionen (S) — das ist nicht zulässig.")
