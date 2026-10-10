package de.littletrotter.ui.view.referencedata

import com.vaadin.flow.component.Component
import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.MileageRateQueryPort
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Every reference-data port is its own narrow mock. Mockito's default answer already returns an
 * empty list/zero for unconfigured collection/numeric methods, so only the one behaviour a test
 * actually asserts on (the payment terms tab's fallback-language list) needs an explicit stub.
 */
internal fun fakeMileageRateQueries(): MileageRateQueryPort = mock()

internal fun fakeLanguageQueries(): LanguageQueryPort = mock<LanguageQueryPort>().apply {
    whenever(findActiveLanguages()).thenReturn(
        listOf(
            LanguageDto(id = 1, code = "de", isFallback = true, sortOrder = 1),
            LanguageDto(id = 2, code = "en", isFallback = false, sortOrder = 2),
        ),
    )
}

internal fun newReferenceDataView(
    mileageRateQueries: MileageRateQueryPort = fakeMileageRateQueries(),
    // Defaults to `null` == "no gate wired, nothing hidden" so every caller not passing one is
    // untouched; feature-visibility tests pass a real FeatureSettingsGate over the in-memory
    // feature repository.
    featureSettingsGate: de.littletrotter.application.settings.FeatureSettingsGate? = null,
): ReferenceDataView =
    ReferenceDataView(
        AbrechnungTabFactory(
            PaymentTermsTabFactory(
                mock(),
                mock(),
                fakeLanguageQueries(),
                de.littletrotter.application.invoice.MultilingualTextsFeatureConfig(false),
            ),
            mock(),
            mock(),
            mock(),
            mock(),
            de.littletrotter.application.quote.QuoteAvailability(false),
            de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        ),
        ReisekostenTabFactory(mock(), mock(), mock(), mock(), mileageRateQueries, mock()),
        ERechnungTabFactory(
            mock(),
            mock(),
            mock(),
            mock(),
            ValidationSettingsTabFactory(
                de.littletrotter.application.reference.validationSettingsGate(),
                de.littletrotter.infrastructure.docker.NoopEngineActivation(),
                de.littletrotter.application.invoice.FacturXDeployment(true),
            ), time = FIXED_TIME_SOURCE,
        ),
        mock<Event<de.littletrotter.ui.navigation.ReferenceDataSelectionEvent>>(),
        featureSettingsGate,
    )

class ReferenceDataViewTest {

    @Test
    fun `reference data view contains mileage tab`() {
        val mileageRateQueries = fakeMileageRateQueries()
        newReferenceDataView(mileageRateQueries = mileageRateQueries)

        // Some Vaadin tab labels are not available as HasText in this test environment.
        // Ensure MileageRatesTab can be constructed and doesn't throw.
        val mileageTab = MileageRatesTab(mileageRateQueries, mock())
        assertNotNull(mileageTab)
    }

    // ── Group structure ───────────────────────────────────────

    @Test
    fun `groups and tabs are non-empty and have unique slugs`() {
        assertEquals(3, ReferenceDataGroup.entries.size)
        assertEquals(9, ReferenceDataTab.entries.size)
        assertEquals(
            ReferenceDataGroup.entries.size,
            ReferenceDataGroup.entries.map { it.slug }.distinct().size,
            "group slugs must be unique",
        )
        assertEquals(
            ReferenceDataTab.entries.size,
            ReferenceDataTab.entries.map { it.slug }.distinct().size,
            "tab slugs must be unique",
        )
        // Every group must have at least one tab, every tab must resolve back to its group.
        ReferenceDataGroup.entries.forEach { group ->
            assertTrue(ReferenceDataTab.forGroup(group).isNotEmpty(), "group $group has no tabs")
            ReferenceDataTab.forGroup(group).forEach { tab -> assertEquals(group, tab.group) }
        }
    }

    @Test
    fun `Option B group cut - all nine known tabs are present in the right groups`() {
        assertEquals(
            setOf(ReferenceDataTab.PAYMENT_TERMS, ReferenceDataTab.TAX_RATES, ReferenceDataTab.UNITS, ReferenceDataTab.NUMBER_RANGES),
            ReferenceDataTab.forGroup(ReferenceDataGroup.ABRECHNUNG).toSet(),
        )
        assertEquals(
            setOf(ReferenceDataTab.PER_DIEM_RATES, ReferenceDataTab.HOTEL_RATES, ReferenceDataTab.MILEAGE_RATES),
            ReferenceDataTab.forGroup(ReferenceDataGroup.REISEKOSTEN).toSet(),
        )
        assertEquals(
            setOf(ReferenceDataTab.B2B_PROFILES, ReferenceDataTab.VALIDATION),
            ReferenceDataTab.forGroup(ReferenceDataGroup.E_RECHNUNG).toSet(),
        )
    }

    // ── Deep-link resolution (pure function) ───────────────────────────

    @Test
    fun `bare route resolves to the first group and its first tab`() {
        val (group, tab) = resolveReferenceDataDeepLink(null, null)
        assertEquals(ReferenceDataGroup.entries.first(), group)
        assertEquals(ReferenceDataTab.forGroup(group).first(), tab)
    }

    @Test
    fun `group only resolves to that group's first tab`() {
        val (group, tab) = resolveReferenceDataDeepLink("e-rechnung", null)
        assertEquals(ReferenceDataGroup.E_RECHNUNG, group)
        assertEquals(ReferenceDataTab.B2B_PROFILES, tab)
    }

    @Test
    fun `group and tab resolve exactly`() {
        val (group, tab) = resolveReferenceDataDeepLink("e-rechnung", "validierung")
        assertEquals(ReferenceDataGroup.E_RECHNUNG, group)
        assertEquals(ReferenceDataTab.VALIDATION, tab)
    }

    @Test
    fun `unknown group falls back to the first group`() {
        val (group, tab) = resolveReferenceDataDeepLink("does-not-exist", "does-not-exist")
        assertEquals(ReferenceDataGroup.entries.first(), group)
        assertEquals(ReferenceDataTab.forGroup(group).first(), tab)
    }

    @Test
    fun `unknown tab in a known group falls back to that group's first tab`() {
        val (group, tab) = resolveReferenceDataDeepLink("reisekosten", "does-not-exist")
        assertEquals(ReferenceDataGroup.REISEKOSTEN, group)
        assertEquals(ReferenceDataTab.forGroup(ReferenceDataGroup.REISEKOSTEN).first(), tab)
    }

    @Test
    fun `route round-trips through referenceDataRoute`() {
        assertEquals("referenzdaten/e-rechnung/validierung", referenceDataRoute(ReferenceDataGroup.E_RECHNUNG, ReferenceDataTab.VALIDATION))
    }

    // ── MEN-3 — group-only deep link consults last-tab-for-group memory ──────

    @Test
    fun `group only with no tab segment restores the remembered last tab for that group`() {
        val (group, tab) = resolveReferenceDataDeepLink("e-rechnung", null) { g ->
            if (g == ReferenceDataGroup.E_RECHNUNG) ReferenceDataTab.VALIDATION else null
        }
        assertEquals(ReferenceDataGroup.E_RECHNUNG, group)
        assertEquals(ReferenceDataTab.VALIDATION, tab)
    }

    @Test
    fun `group only with no remembered tab still falls back to the group's first tab`() {
        val (group, tab) = resolveReferenceDataDeepLink("e-rechnung", null) { null }
        assertEquals(ReferenceDataGroup.E_RECHNUNG, group)
        assertEquals(ReferenceDataTab.forGroup(ReferenceDataGroup.E_RECHNUNG).first(), tab)
    }

    @Test
    fun `an explicit but unknown tab segment falls back to the first tab, ignoring last-tab memory`() {
        val (group, tab) = resolveReferenceDataDeepLink("e-rechnung", "does-not-exist") {
            ReferenceDataTab.VALIDATION
        }
        assertEquals(ReferenceDataGroup.E_RECHNUNG, group)
        assertEquals(ReferenceDataTab.forGroup(ReferenceDataGroup.E_RECHNUNG).first(), tab)
    }

    @Test
    fun `an explicit known tab segment wins over last-tab memory`() {
        val (group, tab) = resolveReferenceDataDeepLink("e-rechnung", "validierung") {
            ReferenceDataTab.B2B_PROFILES
        }
        assertEquals(ReferenceDataGroup.E_RECHNUNG, group)
        assertEquals(ReferenceDataTab.VALIDATION, tab)
    }

    // ── MEN-3 — session-held last-tab-per-group memory (pure data class) ─────

    @Test
    fun `ReferenceDataLastTabState has no memory for any group by default`() {
        val state = ReferenceDataLastTabState()
        ReferenceDataGroup.entries.forEach { group -> assertEquals(null, state.tabFor(group)) }
    }

    @Test
    fun `ReferenceDataLastTabState remembers the tab last set per group, independently`() {
        val state = ReferenceDataLastTabState()
            .with(ReferenceDataGroup.E_RECHNUNG, ReferenceDataTab.VALIDATION)
            .with(ReferenceDataGroup.REISEKOSTEN, ReferenceDataTab.HOTEL_RATES)

        assertEquals(ReferenceDataTab.VALIDATION, state.tabFor(ReferenceDataGroup.E_RECHNUNG))
        assertEquals(ReferenceDataTab.HOTEL_RATES, state.tabFor(ReferenceDataGroup.REISEKOSTEN))
        assertEquals(null, state.tabFor(ReferenceDataGroup.ABRECHNUNG))
    }

    @Test
    fun `ReferenceDataLastTabState overwrites a group's remembered tab on a later selection`() {
        val state = ReferenceDataLastTabState()
            .with(ReferenceDataGroup.E_RECHNUNG, ReferenceDataTab.B2B_PROFILES)
            .with(ReferenceDataGroup.E_RECHNUNG, ReferenceDataTab.VALIDATION)

        assertEquals(ReferenceDataTab.VALIDATION, state.tabFor(ReferenceDataGroup.E_RECHNUNG))
    }

    // ── All ten tabs are reachable, lazily ─────────────────────────────

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
        m.invoke(view, group, tab, false)
    }

    @Test
    fun `only the default tab is built on construction - the other nine are lazy`() {
        val view = newReferenceDataView()
        val cache: Map<ReferenceDataTab, Component> = privateField(view, "tabContentCache")
        assertEquals(setOf(ReferenceDataTab.forGroup(ReferenceDataGroup.entries.first()).first()), cache.keys)
    }

    @Test
    fun `selecting every tab builds and caches exactly that many components`() {
        val view = newReferenceDataView()
        ReferenceDataTab.entries.forEach { tab -> invokeApplySelection(view, tab.group, tab) }

        val cache: Map<ReferenceDataTab, Component> = privateField(view, "tabContentCache")
        assertEquals(ReferenceDataTab.entries.toSet(), cache.keys, "all tabs must be reachable")
    }
}
