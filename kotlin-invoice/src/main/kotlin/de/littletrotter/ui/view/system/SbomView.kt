package de.littletrotter.ui.view.system

import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.ui.MainLayout

/**
 * TS-8 — gained `layout = MainLayout::class` (previously routed bare, with no parent layout at
 * all and no drawer entry): the route matrix requires `sbom` to be admin-gated exactly like
 * `system` (whose own SBOM tab shows the same data), and [MainLayout] is the single place that
 * enforcement lives (`MainLayout.beforeEnter`, `de.littletrotter.ui.security.NavigationAccessGuard`)
 * — a standalone route bypassed that entirely.
 */
@Route(value = "sbom", layout = MainLayout::class)
@PageTitle("SBOM – Software Bill of Materials")
class SbomView(
    componentsLoader: () -> List<SbomComponent> = SbomDataLoader::loadComponents,
) : VerticalLayout() {
    init {
        setSizeFull()
        add(SbomPanel(componentsLoader))
    }
}
