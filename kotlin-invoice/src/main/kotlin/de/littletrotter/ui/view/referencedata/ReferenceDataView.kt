package de.littletrotter.ui.view.referencedata

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinSession
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.application.settings.port.inbound.FeatureSettingsQueryPort
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.navigation.ReferenceDataSelectionEvent
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * Grouped navigation for reference data: three groups (Abrechnung,
 * Reisekosten-Sätze, E-Rechnung), rendered here as a single sub-tab bar (MEN-3) with a uniform
 * header frame ([ReferenceDataSubTabHeader]) above every sub-tab's content. Deep-linkable via
 * `referenzdaten/:gruppe?/:tab?` ([resolveReferenceDataDeepLink]).
 *
 * MEN-3 — group selection no longer has a tab bar of its own here: it lives in the drawer
 * (`MainLayout`'s `REFERENCE_DATA` [com.vaadin.flow.component.sidenav.SideNav] node, one child
 * per [ReferenceDataGroup]) and flows in exclusively through the route via `beforeEnter`. The
 * drawer's group links are stateless (`referenzdaten/<gruppe>`, no tab segment); returning to a
 * group restores its last sub-tab via [resolveReferenceDataDeepLink]'s `lastTabForGroup` lookup,
 * backed by the session-held [ReferenceDataLastTabState] (see [sessionLastTabState]/
 * [rememberLastTab] — a view instance is recreated on every navigation, so this can't be a plain
 * field the way it was before MEN-3).
 *
 * Sub-tab content is genuinely lazy: the ten tab components ([PaymentTermsTab] etc.) are only
 * constructed — and only then do their own port/DB round-trips in `init` — the first time
 * their tab is actually selected, and the built component is cached afterwards ([tabContentCache]).
 * The sub-tab `Tabs` bar itself (just labels) is rebuilt eagerly on group change, which is cheap.
 *
 * MEN-3 — below the narrow-viewport breakpoint the `Tabs` bar is replaced by a full-width
 * `Select` ([subTabSelect]) driven by the exact same selection model: both are kept in lockstep
 * by [applySelection]/[rebuildSubTabs], so there's exactly one source of truth for "which sub-tab
 * is selected", just two visual projections of it. Which one is visible is a pure CSS media query
 * on stable class names (`.reference-data-subtabs` / `.reference-data-subtab-select`, see
 * `styles.css`) — no JS/resize listeners.
 */
@Route(value = "$REFERENCE_DATA_ROUTE_BASE/:gruppe?/:tab?", layout = MainLayout::class)
@PageTitle("Referenzdaten")
class ReferenceDataView
@Inject
constructor(
    // the view no longer receives every Referenzdaten
    // port just to hand them to its tabs; each ReferenceDataGroup (three groups, see this
    // class's own KDoc) has its own narrow CDI factory ([AbrechnungTabFactory],
    // [ReisekostenTabFactory], [ERechnungTabFactory]) that injects only that group's own ports
    // and builds that group's tabs. This view only injects the three factories.
    private val abrechnungTabFactory: AbrechnungTabFactory,
    private val reisekostenTabFactory: ReisekostenTabFactory,
    private val eRechnungTabFactory: ERechnungTabFactory,
    private val selectionEvent: Event<ReferenceDataSelectionEvent>,
    // Defaulted (null): every existing test/helper caller keeps its prior behaviour; Arc
    // always injects the real bean. `null` reads "all flags on" (see hiddenTabs).
    private val featureSettingsGate: FeatureSettingsQueryPort? = null,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    /**
     * The reference-data tabs the current feature state hides: [ReferenceDataTab.PER_DIEM_RATES]
     * behind [de.littletrotter.domain.settings.FeatureFlag.PER_DIEM_UI].
     * A `null` gate (bare component tests) reads "on", so nothing is hidden.
     */
    private val hiddenTabs: Set<ReferenceDataTab>
        get() = hiddenReferenceDataTabs(
            perDiemUiEnabled = featureSettingsGate?.isEnabled(FeatureFlag.PER_DIEM_UI) ?: true,
        )

    private val tabContentCache = mutableMapOf<ReferenceDataTab, Component>()

    private var currentGroup: ReferenceDataGroup = ReferenceDataGroup.entries.first()
    private var currentTab: ReferenceDataTab = visibleTabsFor(currentGroup, hiddenTabs).first()

    /** Guards against re-entrant selection handling while we programmatically set the `Tabs`'/
     *  `Select`'s selection (group rebuild, deep-link apply) — only user interaction should
     *  update the URL. */
    private var applyingSelection = false

    private val subTabs = Tabs().apply {
        setWidthFull()
        addClassName("reference-data-subtabs")
    }

    /** MEN-3 — narrow-viewport projection of [subTabs]'s selection; see class kdoc. Items are
     *  the tabs of the current group, same order as [subTabs]; [tabLabel] doubles as the item
     *  label generator so the two bars can never show different text for the same tab. */
    private val subTabSelect = Select<ReferenceDataTab>().apply {
        setWidthFull()
        addClassName("reference-data-subtab-select")
        setItemLabelGenerator(::tabLabel)
        setAriaLabel(tr("referenceData.subTabSelect"))
    }

    private val contentSlot = Div().apply { setSizeFull() }

    // ── Init ───────────────────────────────────────────────────────────────────

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        // Reference data notice (demo/test only) is the page subtitle
        add(
            PageShell(tr("referenceData.title"), tr("info.referenceData.notice.text"))
                .content(subTabs, subTabSelect, contentSlot),
        )
        setFlexGrow(1.0, contentSlot)

        subTabs.addSelectedChangeListener {
            if (applyingSelection) return@addSelectedChangeListener
            val tab = visibleTabsFor(currentGroup, hiddenTabs)[subTabs.selectedIndex]
            applySelection(currentGroup, tab, updateUrl = true)
        }
        subTabSelect.addValueChangeListener { event ->
            if (applyingSelection) return@addValueChangeListener
            val tab = event.value ?: return@addValueChangeListener
            applySelection(currentGroup, tab, updateUrl = true)
        }

        // Default selection so the view is usable when constructed outside routing (tests) —
        // beforeEnter() below overrides this with the deep-linked group/tab in the real app.
        applySelection(currentGroup, currentTab, updateUrl = false)
        updatePageTitle()
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        val groupSlug = event.routeParameters.get("gruppe").orElse(null)
        val tabSlug = event.routeParameters.get("tab").orElse(null)
        val (group, tab) = resolveReferenceDataDeepLink(groupSlug, tabSlug, hiddenTabs) { g ->
            sessionLastTabState().tabFor(g)
        }
        applySelection(group, tab, updateUrl = false)
    }

    // ── Selection / navigation ────────────────────────────────────────────────

    private fun applySelection(group: ReferenceDataGroup, tab: ReferenceDataTab, updateUrl: Boolean) {
        val groupChanged = group != currentGroup || subTabs.tabCount == 0
        currentGroup = group
        currentTab = tab
        rememberLastTab(group, tab)

        applyingSelection = true
        if (groupChanged) rebuildSubTabs(group)
        subTabs.selectedIndex = visibleTabsFor(group, hiddenTabs).indexOf(tab)
        subTabSelect.value = tab
        applyingSelection = false

        contentSlot.removeAll()
        contentSlot.add(tabContentCache.getOrPut(tab) { buildTabPanel(tab) })

        updatePageTitle()
        if (updateUrl) updateUrlForSelection()

        // MEN-4 — notify MainLayout of the selection change so it can update the breadcrumb's
        // terminal segment (which changes without a full router event, just a history.replaceState).
        selectionEvent.fire(ReferenceDataSelectionEvent(group, tab))
    }

    private fun rebuildSubTabs(group: ReferenceDataGroup) {
        subTabs.removeAll()
        // Rail and Select render the same visibility-filtered list; the deep-link resolver
        // uses the very same filter, so selected-index arithmetic can never offset by a hidden tab.
        val tabs = visibleTabsFor(group, hiddenTabs)
        tabs.forEach { t -> subTabs.add(Tab(tabLabel(t))) }
        subTabSelect.setItems(tabs)
    }

    private fun updateUrlForSelection() {
        val ui = UI.getCurrent() ?: return
        ui.page.history.replaceState(null, referenceDataRoute(currentGroup, currentTab))
    }

    // ── Last-tab-per-group memory (session-scoped, MEN-3) ────────────────────

    private fun sessionLastTabState(): ReferenceDataLastTabState =
        VaadinSession.getCurrent()?.getAttribute(ReferenceDataLastTabState::class.java) ?: ReferenceDataLastTabState()

    private fun rememberLastTab(group: ReferenceDataGroup, tab: ReferenceDataTab) {
        VaadinSession.getCurrent()?.setAttribute(
            ReferenceDataLastTabState::class.java,
            sessionLastTabState().with(group, tab),
        )
    }

    // ── Lazy tab construction ─────────────────────────────────────────────────

    private fun buildTabPanel(tab: ReferenceDataTab): Component {
        val header = ReferenceDataSubTabHeader(tabLabel(tab), tabDescription(tab))
        val body = buildTabComponent(tab)
        // Tabs that carry search+counter populate the header's actionsSlot
        // themselves, right after construction; tabs that opt out (ValidationSettingsTab) simply
        // don't implement the interface and the slot stays empty (see ReferenceDataSubTabHeader).
        (body as? ReferenceDataHeaderActions)?.installHeaderActions(header.actionsSlot)
        return VerticalLayout(header, body).apply {
            setSizeFull()
            isPadding = false
            isSpacing = true
            setFlexGrow(1.0, body)
        }
    }

    private fun buildTabComponent(tab: ReferenceDataTab): Component = when (tab.group) {
        ReferenceDataGroup.ABRECHNUNG -> abrechnungTabFactory.build(tab)
        ReferenceDataGroup.REISEKOSTEN -> reisekostenTabFactory.build(tab)
        ReferenceDataGroup.E_RECHNUNG -> eRechnungTabFactory.build(tab)
    }

    // ── i18n lookups ───────────────────────────────────────────────────────────
    // Kept as explicit `when` blocks (literal keys per branch) rather than a key stored on the
    // enum — ReferenceDataTranslationsAuditTest finds `referenceData.*` usages by scanning for
    // literal string arguments to tr()/UiTranslations.tr(), so a key built from enum state at
    // runtime would look unused to it even though it's very much in use.

    private fun tabLabel(tab: ReferenceDataTab): String = when (tab) {
        ReferenceDataTab.PAYMENT_TERMS -> tr("referenceData.paymentTerms")
        ReferenceDataTab.TAX_RATES -> tr("referenceData.taxRates")
        ReferenceDataTab.UNITS -> tr("referenceData.units")
        ReferenceDataTab.NUMBER_RANGES -> tr("referenceData.numberRange.title")
        ReferenceDataTab.PER_DIEM_RATES -> tr("referenceData.perDiemRates")
        ReferenceDataTab.HOTEL_RATES -> tr("referenceData.hotelRates")
        ReferenceDataTab.MILEAGE_RATES -> tr("referenceData.mileageRates")
        ReferenceDataTab.B2B_PROFILES -> tr("referenceData.b2b.tabTitle")
        ReferenceDataTab.VALIDATION -> tr("referenceData.validations")
    }

    private fun tabDescription(tab: ReferenceDataTab): String = when (tab) {
        ReferenceDataTab.PAYMENT_TERMS -> tr("referenceData.paymentTerms.tabDescription")
        ReferenceDataTab.TAX_RATES -> tr("referenceData.taxRates.tabDescription")
        ReferenceDataTab.UNITS -> tr("referenceData.units.tabDescription")
        ReferenceDataTab.NUMBER_RANGES -> tr("referenceData.numberRange.tabDescription")
        ReferenceDataTab.PER_DIEM_RATES -> tr("referenceData.perDiemRates.tabDescription")
        ReferenceDataTab.HOTEL_RATES -> tr("referenceData.hotelRates.tabDescription")
        ReferenceDataTab.MILEAGE_RATES -> tr("referenceData.mileageRates.tabDescription")
        ReferenceDataTab.B2B_PROFILES -> tr("referenceData.b2b.tabDescription")
        ReferenceDataTab.VALIDATION -> tr("referenceData.validations.tabDescription")
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    override fun getComponent(): Component = this

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        ui.page.setTitle(
            tr("referenceData.title") + " / " + tabLabel(currentTab) + " | " + tr("app.title"),
        )
    }
}
