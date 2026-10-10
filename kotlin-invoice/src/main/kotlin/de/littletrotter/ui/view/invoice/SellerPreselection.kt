package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.SenderSummaryDto

/**
 * Pure preselection decision for the "Neue Rechnung" dialog's seller ComboBox — extracted out of
 * [InvoiceListView] so the full branch matrix (default present / exactly one profile without a
 * default / several profiles without a default / no profiles at all) is exhaustively unit-testable
 * without driving any Vaadin component. PO-Auftrag 30.08.2026.
 *
 * - a profile with [SenderSummaryDto.isDefault] `== true` wins (the first such, if data ever
 *   carried several defaults);
 * - with no default and exactly one profile, that single profile wins;
 * - with no default and several profiles, `null` — the dialog must force an explicit choice, same
 *   as the recipient ComboBox already requires today;
 * - an empty list also yields `null`, though callers are expected to short-circuit that case with
 *   their own blocking message before ever building the ComboBox.
 */
fun resolvePreselectedSeller(profiles: List<SenderSummaryDto>): SenderSummaryDto? {
    val default = profiles.firstOrNull { it.isDefault }
    if (default != null) {
        return default
    }
    return if (profiles.size == 1) profiles[0] else null
}
