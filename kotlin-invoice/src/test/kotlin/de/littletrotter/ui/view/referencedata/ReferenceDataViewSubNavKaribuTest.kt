package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.NavigationTrigger
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.server.VaadinSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * MEN-3 — [ReferenceDataView] no longer has a group tab bar (that's the drawer's job now, see
 * `MainLayoutDrawerKaribuTest`): exactly one `Tabs` remains, for the sub-tabs of the current
 * group, mirrored by a `Select` for narrow viewports (both bound to the same selection, see the
 * class kdoc). This also covers the last-tab-per-group memory surviving across separate
 * [ReferenceDataView] instances (routed views are recreated on every navigation — see
 * [ReferenceDataLastTabState]'s kdoc), which needs a live [VaadinSession], hence Karibu here
 * rather than the plain-construction tests in `ReferenceDataViewTest`.
 */
class ReferenceDataViewSubNavKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun allDescendants(root: Component): List<Component> {
        val result = mutableListOf<Component>()
        root.children.forEach { child ->
            result.add(child)
            result.addAll(allDescendants(child))
        }
        return result
    }

    private inline fun <reified T> privateField(view: ReferenceDataView, name: String): T {
        val f = ReferenceDataView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as T
    }

    private fun invokeApplySelection(view: ReferenceDataView, group: ReferenceDataGroup, tab: ReferenceDataTab) {
        val m = ReferenceDataView::class.java.getDeclaredMethod(
            "applySelection", ReferenceDataGroup::class.java, ReferenceDataTab::class.java, Boolean::class.java,
        )
        m.isAccessible = true
        m.invoke(view, group, tab, true)
    }

    private fun beforeEnterEventFor(gruppe: String?, tab: String? = null): BeforeEnterEvent {
        val ui = UI.getCurrent()!!
        val paramMap = buildMap {
            gruppe?.let { put("gruppe", it) }
            tab?.let { put("tab", it) }
        }
        val path = listOfNotNull(REFERENCE_DATA_ROUTE_BASE, gruppe, tab).joinToString("/")
        return BeforeEnterEvent(
            ui.internals.router,
            NavigationTrigger.PROGRAMMATIC,
            Location(path),
            ReferenceDataView::class.java,
            RouteParameters(paramMap),
            ui,
            emptyList(),
        )
    }

    // ── The group tab bar is gone (MEN-3) ─────────────────────────────────────

    @Test
    fun `exactly one Tabs remains - the sub-tab bar, no group tab bar`() {
        val view = newReferenceDataView()

        val tabsComponents = allDescendants(view).filterIsInstance<Tabs>()

        assertEquals(1, tabsComponents.size, "the group tab bar must be gone, only the sub-tab bar remains")
        assertEquals(ReferenceDataTab.forGroup(ReferenceDataGroup.entries.first()).size, tabsComponents.first().tabCount)
    }

    @Test
    fun `the sub-tabs and sub-tab select carry their stable CSS class names`() {
        val view = newReferenceDataView()

        val subTabs: Tabs = privateField(view, "subTabs")
        val subTabSelect: Select<ReferenceDataTab> = privateField(view, "subTabSelect")

        assertTrue(subTabs.classNames.contains("reference-data-subtabs"))
        assertTrue(subTabSelect.classNames.contains("reference-data-subtab-select"))
    }

    // ── One selection model, two projections (MEN-3) ──────────────────────────

    @Test
    fun `the select mirrors the tabs' selection after a tab switch`() {
        val view = newReferenceDataView()
        invokeApplySelection(view, ReferenceDataGroup.E_RECHNUNG, ReferenceDataTab.VALIDATION)

        val subTabSelect: Select<ReferenceDataTab> = privateField(view, "subTabSelect")

        assertEquals(ReferenceDataTab.VALIDATION, subTabSelect.value)
    }

    @Test
    fun `the select offers exactly the current group's tabs, in the same order as the tabs bar`() {
        val view = newReferenceDataView()
        invokeApplySelection(view, ReferenceDataGroup.REISEKOSTEN, ReferenceDataTab.HOTEL_RATES)

        val subTabSelect: Select<ReferenceDataTab> = privateField(view, "subTabSelect")
        val offered = subTabSelect.listDataView.items.toList()

        assertEquals(ReferenceDataTab.forGroup(ReferenceDataGroup.REISEKOSTEN), offered)
    }

    // ── Last-tab-per-group memory survives across view instances (MEN-3) ─────

    @Test
    fun `returning to a previously visited group restores its last sub-tab, across view instances`() {
        val firstVisit = newReferenceDataView()
        firstVisit.beforeEnter(beforeEnterEventFor("e-rechnung", "validierung"))

        // A brand-new instance, as routing actually recreates on every navigation — the only way
        // this could remember anything is the session-held ReferenceDataLastTabState, not a field.
        val returningVisit = newReferenceDataView()
        returningVisit.beforeEnter(beforeEnterEventFor("e-rechnung", null))

        assertEquals(ReferenceDataGroup.E_RECHNUNG, privateField(returningVisit, "currentGroup"))
        assertEquals(ReferenceDataTab.VALIDATION, privateField(returningVisit, "currentTab"))
    }

    @Test
    fun `the session carries no memory for a group never visited`() {
        // Construction alone only ever touches the default group (ABRECHNUNG, its first tab) —
        // E_RECHNUNG and REISEKOSTEN are never selected here, so they must have no memory yet.
        newReferenceDataView()
        val state = VaadinSession.getCurrent()?.getAttribute(ReferenceDataLastTabState::class.java)
            ?: ReferenceDataLastTabState()

        assertNull(state.tabFor(ReferenceDataGroup.E_RECHNUNG))
        assertNull(state.tabFor(ReferenceDataGroup.REISEKOSTEN))
    }

    // ── User-driven sub-tab switches through both projections ────────

    @Test
    fun `happy path - a user switch on the sub-tab bar selects that tab and mirrors it into the select`() {
        val view = newReferenceDataView()
        val subTabs: Tabs = privateField(view, "subTabs")
        val subTabSelect: Select<ReferenceDataTab> = privateField(view, "subTabSelect")
        val groupTabs = ReferenceDataTab.forGroup(ReferenceDataGroup.entries.first())

        subTabs.selectedIndex = 1

        assertEquals(groupTabs[1], subTabSelect.value)
    }

    @Test
    fun `boundary - a user pick in the narrow-viewport select moves the sub-tab bar to the same tab`() {
        val view = newReferenceDataView()
        val subTabs: Tabs = privateField(view, "subTabs")
        val subTabSelect: Select<ReferenceDataTab> = privateField(view, "subTabSelect")
        val groupTabs = ReferenceDataTab.forGroup(ReferenceDataGroup.entries.first())

        subTabSelect.value = groupTabs.last()

        assertEquals(groupTabs.size - 1, subTabs.selectedIndex)
    }

    @Test
    fun `boundary - a cleared select value keeps the current sub-tab instead of failing`() {
        val view = newReferenceDataView()
        val subTabs: Tabs = privateField(view, "subTabs")
        val subTabSelect: Select<ReferenceDataTab> = privateField(view, "subTabSelect")

        subTabSelect.value = null

        assertEquals(0, subTabs.selectedIndex)
    }

    @Test
    fun `boundary - a URL-updating selection outside any UI skips the history update without failing`() {
        val view = newReferenceDataView()
        UI.setCurrent(null)

        invokeApplySelection(view, ReferenceDataGroup.E_RECHNUNG, ReferenceDataTab.VALIDATION)

        val subTabSelect: Select<ReferenceDataTab> = privateField(view, "subTabSelect")
        assertEquals(ReferenceDataTab.VALIDATION, subTabSelect.value)
    }
}
