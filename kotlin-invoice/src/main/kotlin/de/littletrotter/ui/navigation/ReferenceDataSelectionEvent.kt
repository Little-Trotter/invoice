package de.littletrotter.ui.navigation

import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import de.littletrotter.ui.view.referencedata.ReferenceDataTab

/**
 * MEN-4 — CDI event fired when the user selects a different reference-data sub-tab without
 * a full router navigation (via history.replaceState). Observed in MainLayout to update the
 * breadcrumb's terminal segment (the current tab label).
 *
 * This is a tiny payload record carrying only the group and tab selections; MainLayout combines
 * it with the current route path to rebuild the full breadcrumb.
 */
data class ReferenceDataSelectionEvent(
    val group: ReferenceDataGroup,
    val tab: ReferenceDataTab,
)
