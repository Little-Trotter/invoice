package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.NavigationTrigger
import com.vaadin.flow.router.RouteParameters
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.settings.InMemoryFeatureSettingRepository
import de.littletrotter.domain.settings.FeatureFlag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * SFS — hiding `Verpflegungspauschalen` in Referenzdaten must be consistent across every surface:
 * the sub-tab `Tabs` rail and the narrow-screen `Select` (same list, same index arithmetic), and
 * the deep-link resolution in `beforeEnter` including the MEN-3 last-tab memory. The pure layer
 * ([visibleTabsFor], [resolveReferenceDataDeepLink], [hiddenReferenceDataTabs]) carries the rules;
 * these Karibu rows pin that the view actually applies them.
 */
class ReferenceDataFeatureVisibilityTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    private fun perDiemDisabled(): FeatureSettingsGate =
        FeatureSettingsGate(
            InMemoryFeatureSettingRepository(mapOf(FeatureFlag.PER_DIEM_UI to false)), time = FIXED_TIME_SOURCE,
        )

    private fun <T> privateField(view: ReferenceDataView, name: String): T {
        val f = ReferenceDataView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as T
    }

    private fun beforeEnterFor(groupe: String?, tab: String? = null): BeforeEnterEvent {
        val ui = UI.getCurrent()!!
        val params = buildMap {
            groupe?.let { put("gruppe", it) }
            tab?.let { put("tab", it) }
        }
        val path = listOfNotNull(REFERENCE_DATA_ROUTE_BASE, groupe, tab).joinToString("/")
        return BeforeEnterEvent(
            ui.internals.router,
            NavigationTrigger.PROGRAMMATIC,
            Location(path),
            ReferenceDataView::class.java,
            RouteParameters(params),
            ui,
            emptyList(),
        )
    }

    @Nested
    inner class PureRules {

        @Test
        fun `visibleTabsFor removes exactly the hidden tab and keeps order`() {
            val all = visibleTabsFor(ReferenceDataGroup.REISEKOSTEN)
            assertEquals(ReferenceDataTab.forGroup(ReferenceDataGroup.REISEKOSTEN), all)

            val hidden = hiddenReferenceDataTabs(perDiemUiEnabled = false)
            assertEquals(listOf(ReferenceDataTab.HOTEL_RATES, ReferenceDataTab.MILEAGE_RATES), visibleTabsFor(ReferenceDataGroup.REISEKOSTEN, hidden))
            // untouched groups are untouched
            assertEquals(visibleTabsFor(ReferenceDataGroup.ABRECHNUNG), visibleTabsFor(ReferenceDataGroup.ABRECHNUNG, hidden))
        }

        @Test
        fun `deep link to a hidden tab resolves to the first visible one (drawer, links, bookmarks)`() {
            val hidden = hiddenReferenceDataTabs(perDiemUiEnabled = false)
            assertEquals(
                ReferenceDataTab.HOTEL_RATES,
                resolveReferenceDataDeepLink("reisekosten", "verpflegungspauschalen", hidden).second,
            )
            assertEquals(
                ReferenceDataTab.HOTEL_RATES,
                resolveReferenceDataDeepLink("reisekosten", null, hidden).second,
                "a bare group link lands on the first VISIBLE tab",
            )
        }

        @Test
        fun `last-tab memory cannot resurrect a hidden tab`() {
            val hidden = hiddenReferenceDataTabs(perDiemUiEnabled = false)
            val (_, tab) = resolveReferenceDataDeepLink("reisekosten", null, hidden) { ReferenceDataTab.PER_DIEM_RATES }
            assertEquals(ReferenceDataTab.HOTEL_RATES, tab)
        }

        @Test
        fun `with the flag on nothing changes`() {
            val none = emptySet<ReferenceDataTab>()
            assertEquals(ReferenceDataTab.PER_DIEM_RATES, resolveReferenceDataDeepLink("reisekosten", null, none).second)
            assertEquals(ReferenceDataTab.PER_DIEM_RATES, resolveReferenceDataDeepLink("reisekosten", "verpflegungspauschalen", none).second)
        }
    }

    @Nested
    inner class ViewSurfaces {

        @Test
        fun `a view without a gate keeps the full rail`() {
            val view = newReferenceDataView()
            view.beforeEnter(beforeEnterFor("reisekosten"))
            val tabs: Tabs = privateField(view, "subTabs")
            val select: Select<ReferenceDataTab> = privateField(view, "subTabSelect")

            assertEquals(3, tabs.tabCount)
            assertEquals(ReferenceDataTab.forGroup(ReferenceDataGroup.REISEKOSTEN), select.listDataView.items.toList())
        }

        @Test
        fun `with per-diem switched off rail and select lose exactly that tab`() {
            val view = newReferenceDataView(featureSettingsGate = perDiemDisabled())
            view.beforeEnter(beforeEnterFor("reisekosten"))
            val tabs: Tabs = privateField(view, "subTabs")
            val select: Select<ReferenceDataTab> = privateField(view, "subTabSelect")

            assertEquals(2, tabs.tabCount, "nur die sichtbaren Reiter, none of the hidden ones")
            assertEquals(listOf(ReferenceDataTab.HOTEL_RATES, ReferenceDataTab.MILEAGE_RATES), select.listDataView.items.toList())
            assertEquals(ReferenceDataTab.HOTEL_RATES, privateField(view, "currentTab"), "default selection shifts to the first visible")
        }

        @Test
        fun `an explicit deep link to the hidden tab never renders its content without an error state`() {
            val view = newReferenceDataView(featureSettingsGate = perDiemDisabled())
            view.beforeEnter(beforeEnterFor("reisekosten", "verpflegungspauschalen"))

            assertTrue(privateField<Tabs>(view, "subTabs").tabCount == 2)
            // The remembered-per-group lookup runs through the same resolver — it must not put a
            // hidden tab on any second visit either:
            val second = newReferenceDataView(featureSettingsGate = perDiemDisabled())
            second.beforeEnter(beforeEnterFor("reisekosten", "kilometersaetze"))
            second.beforeEnter(beforeEnterFor("reisekosten"))
            assertEquals(ReferenceDataTab.MILEAGE_RATES, privateField(second, "currentTab"), "memory keeps working for visible tabs")
        }
    }
}
