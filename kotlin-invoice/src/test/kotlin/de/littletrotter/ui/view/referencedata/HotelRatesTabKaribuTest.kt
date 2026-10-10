package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.view.invoice.componentFor
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getColumnByHeader
import com.github.mvysny.kaributesting.v10._getFormatted
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.reference.port.inbound.HotelRateQueryPort
import de.littletrotter.application.reference.port.inbound.ManageHotelRateUseCase
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [HotelRatesTab] had no test coverage at all. Structure
 * mirrors [PerDiemRatesTab], so this test shape is reused (with small edits) for
 * [PerDiemRatesTabKaribuTest], [MileageRatesTabKaribuTest] and [NumberRangesTabKaribuTest].
 * Private form fields are reached via reflection instead of Karibu's label-based lookup, since
 * several fields of the same component type share a container and only differ by label.
 */
class HotelRatesTabKaribuTest {

    private val hotelRateQueries: HotelRateQueryPort = mock()

    private val manageHotelRate: ManageHotelRateUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(hotelRateQueries.findHotelRates(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        whenever(hotelRateQueries.countHotelRates(anyOrNull())).thenReturn(0L)
        whenever(hotelRateQueries.countInactiveHotelRates(anyOrNull())).thenReturn(0L)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newTab(): HotelRatesTab = HotelRatesTab(hotelRateQueries, manageHotelRate)

    private inline fun <reified T> declaredField(clazz: Class<*>, tab: Any, name: String): T {
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        return f.get(tab) as T
    }

    private fun grid(tab: HotelRatesTab): Grid<HotelRateDto> =
        declaredField(AbstractReferencedataCrudTab::class.java, tab, "grid")

    private fun columnComponent(tab: HotelRatesTab, header: String, rate: HotelRateDto): Component {
        val column = grid(tab).columns.first { it.headerText == header }
        return column.componentFor(rate)
    }

    private fun openEditor(tab: HotelRatesTab, entity: HotelRateDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(tab, entity)
    }

    private fun newButton(tab: HotelRatesTab): Button =
        tab._get<Button> { text = UiTranslations.tr("referenceData.new") }

    private fun saveButton(tab: HotelRatesTab): Button = declaredField(AbstractCrudView::class.java, tab, "saveButton")
    private fun deleteButton(tab: HotelRatesTab): Button = declaredField(AbstractCrudView::class.java, tab, "deleteButton")

    private fun countryField(tab: HotelRatesTab): TextField = declaredField(HotelRatesTab::class.java, tab, "countryField")
    private fun regionField(tab: HotelRatesTab): TextField = declaredField(HotelRatesTab::class.java, tab, "regionField")
    private fun ratePerNightField(tab: HotelRatesTab): BigDecimalField = declaredField(HotelRatesTab::class.java, tab, "ratePerNightField")
    private fun validFromPicker(tab: HotelRatesTab): DatePicker = declaredField(HotelRatesTab::class.java, tab, "validFromPicker")
    private fun activeField(tab: HotelRatesTab): Checkbox = declaredField(HotelRatesTab::class.java, tab, "activeField")
    private fun sourceField(tab: HotelRatesTab): TextArea = declaredField(HotelRatesTab::class.java, tab, "sourceField")

    private fun fillValidForm(tab: HotelRatesTab) {
        countryField(tab).value = "DE"
        regionField(tab).value = "Berlin"
        ratePerNightField(tab).value = BigDecimal("120.00")
        validFromPicker(tab).value = LocalDate.of(2026, 1, 1)
        activeField(tab).value = true
        sourceField(tab).value = "BMF-Schreiben"
    }

    // ── grid columns ─────────────────────────────────────────────────────────

    @Test
    fun `region, validFrom and validTo columns fall back to the em dash when null`() {
        val tab = newTab()
        val rate = HotelRateDto(country = Country.DE, region = null, validFrom = null, validTo = null)

        val region = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.region"))._getFormatted(rate)
        val validFrom = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.validFrom"))._getFormatted(rate)
        val validTo = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.validTo"))._getFormatted(rate)

        assertEquals("—", region)
        assertEquals("—", validFrom)
        assertEquals("—", validTo)
    }

    @Test
    fun `region, validFrom and validTo columns render the actual value when present`() {
        val tab = newTab()
        val rate = HotelRateDto(
            country = Country.DE,
            region = "Berlin",
            validFrom = LocalDate.of(2026, 1, 1),
            validTo = LocalDate.of(2026, 12, 31),
        )

        val region = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.region"))._getFormatted(rate)
        val validFrom = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.validFrom"))._getFormatted(rate)
        val validTo = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.validTo"))._getFormatted(rate)

        assertEquals("Berlin", region)
        assertEquals("01.01.2026", validFrom)
        assertEquals("31.12.2026", validTo)
    }

    // ── The ratePerNight column used stripTrailingZeros().toPlainString() with a hardcoded
    //    "€", so 1.50 rendered as "1.5 €" instead of the German "1,50 €" MoneyFormatter produces. ──

    @Test
    fun `the ratePerNight column renders the German comma, not a stripped decimal`() {
        val tab = newTab()
        val rate = HotelRateDto(country = Country.DE, ratePerNight = Money.of(BigDecimal("1.50")))

        val ratePerNight = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.ratePerNight"))._getFormatted(rate)

        assertEquals(MoneyFormatter.format(Money.of("1.50")), ratePerNight)
    }

    @Test
    fun `status column shows the inactive badge only for inactive rows`() {
        val tab = newTab()
        val active = HotelRateDto(isActive = true)
        val inactive = HotelRateDto(isActive = false)

        val activeSpan = columnComponent(tab, UiTranslations.tr("referenceData.status"), active) as com.vaadin.flow.component.html.Span
        val inactiveSpan = columnComponent(tab, UiTranslations.tr("referenceData.status"), inactive) as com.vaadin.flow.component.html.Span

        assertTrue(activeSpan.text.isNullOrEmpty())
        assertEquals(UiTranslations.tr("referenceData.taxRate.status.inactive"), inactiveSpan.text)
    }

    // ── sort listener ────────────────────────────────────────────────────────

    @Test
    fun `boundary - sorting country descending refetches with the DESC direction`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("country")!!

        grid(tab).sort(listOf(GridSortOrder(column, com.vaadin.flow.data.provider.SortDirection.DESCENDING)))
        grid(tab)._fetch(0, 10)

        verify(hotelRateQueries, org.mockito.kotlin.atLeastOnce())
            .findHotelRates(any(), any(), anyOrNull(), eq("country"), eq(SortDirection.DESC))
    }

    @Test
    fun `boundary - sorting country ascending refetches with the ASC direction`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("country")!!
        grid(tab).sort(listOf(GridSortOrder(column, com.vaadin.flow.data.provider.SortDirection.DESCENDING)))

        grid(tab).sort(listOf(GridSortOrder(column, com.vaadin.flow.data.provider.SortDirection.ASCENDING)))
        grid(tab)._fetch(0, 10)

        verify(hotelRateQueries, org.mockito.kotlin.atLeastOnce())
            .findHotelRates(any(), any(), anyOrNull(), eq("country"), eq(SortDirection.ASC))
    }

    @Test
    fun `boundary - clearing the sort order resets to ascending`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("country")!!
        grid(tab).sort(listOf(GridSortOrder(column, com.vaadin.flow.data.provider.SortDirection.DESCENDING)))

        grid(tab).sort(emptyList())
        grid(tab)._fetch(0, 10)

        verify(hotelRateQueries, org.mockito.kotlin.atLeastOnce())
            .findHotelRates(any(), any(), anyOrNull(), eq("country"), eq(SortDirection.ASC))
    }

    // ── new / save ───────────────────────────────────────────────────────────

    @Test
    fun `clicking New opens an empty dialog with the delete button disabled`() {
        val tab = newTab()

        newButton(tab)._click()

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertFalse(deleteButton(tab).isEnabled)
        assertEquals("", countryField(tab).value)
    }

    @Test
    fun `saving without a country shows a validation error and keeps the dialog open`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        ratePerNightField(tab).value = BigDecimal("50.00")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageHotelRate, never()).saveHotelRate(any())
    }

    @Test
    fun `saving with a negative rate shows a validation error and keeps the dialog open`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        ratePerNightField(tab).value = BigDecimal("-1.00")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageHotelRate, never()).saveHotelRate(any())
    }

    // The rate field's own asRequired() is a separate binding-chain link ahead of the
    // withValidator null/negative check; a blank field must still be rejected (never silently
    // treated as zero), and unlike the negative-rate case above nothing was pinning that the
    // custom validator's own null arm rejects it too, not just the required check.
    @Test
    fun `saving with a blank rate shows a validation error and keeps the dialog open`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        ratePerNightField(tab).clear()
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageHotelRate, never()).saveHotelRate(any())
    }

    @Test
    fun `saving a valid new hotel rate persists it and closes the dialog`() {
        whenever(manageHotelRate.saveHotelRate(any())).thenAnswer { it.arguments[0] }
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        fillValidForm(tab)
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageHotelRate).saveHotelRate(
            org.mockito.kotlin.argThat { country == Country.DE && region == "Berlin" && ratePerNight == Money.of(BigDecimal("120.00")) }
        )
    }

    // ── Active-needs-source cross-field validation, mirrors
    // chk_hotel_rate_active_needs_source (NOT is_active OR (source IS NOT NULL AND
    // LENGTH(TRIM(source)) > 0)) client-side. isActive defaults true with no required validator
    // on source ("Rechtsgrundlage"), a guaranteed constraint violation on save without this. ────

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun notificationText(notification: Notification): String =
        de.littletrotter.ui.toastText(notification)

    @Test
    fun `saving active with no legal basis is rejected, mirroring chk_hotel_rate_active_needs_source`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        ratePerNightField(tab).value = BigDecimal("120.00")
        // activeField keeps its dialog default (checked/true); sourceField is left blank —
        // exactly the guaranteed-violation state described in finding L6.
        assertTrue(activeField(tab).value, "isActive must default to checked for this to reproduce the finding")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        assertTrue(sourceField(tab).isInvalid)
        assertEquals(UiTranslations.tr("referenceData.hotel.activeRequiresSource"), sourceField(tab).errorMessage)
        assertTrue(getNotifications().isEmpty())
        verify(manageHotelRate, never()).saveHotelRate(any())
    }

    @Test
    fun `unchecking active with no legal basis is a valid combination (superseded rate)`() {
        whenever(manageHotelRate.saveHotelRate(any())).thenAnswer { it.arguments[0] }
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        ratePerNightField(tab).value = BigDecimal("120.00")
        activeField(tab).value = false

        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        // The saved-success toast is expected here — only the L6 validation-error toast must be absent.
        assertTrue(getNotifications().none { notificationText(it) == UiTranslations.tr("referenceData.hotel.activeRequiresSource") })
        verify(manageHotelRate).saveHotelRate(any())
    }

    // ── edit / delete ────────────────────────────────────────────────────────

    @Test
    fun `opening an existing hotel rate enables the delete button and populates the form`() {
        val existing = HotelRateDto(
            id = 7L,
            country = Country.DE,
            region = "Muenchen",
            ratePerNight = Money.of(BigDecimal("99.00")),
            isActive = true,
        )
        val tab = newTab()

        openEditor(tab, existing)

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertTrue(deleteButton(tab).isEnabled)
        assertEquals("DE", countryField(tab).value)
        assertEquals("Muenchen", regionField(tab).value)
    }

    @Test
    fun `confirming delete on an existing hotel rate calls deleteHotelRate and closes the dialog`() {
        val existing = HotelRateDto(id = 7L, country = Country.DE)
        val tab = newTab()
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        deleteButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageHotelRate).deleteHotelRate(eq(7L))
    }

    // ── The edit-existing save flow (findEntityById) that no test above exercises;
    // every existing save test only ever saves a brand-new row. Mirrors the equivalent test
    // PerDiemRatesTabKaribuTest already carries for the same gap. ─────────────────────────────

    @Test
    fun `saving an edited existing hotel rate reloads it through findEntityById`() {
        val existing = HotelRateDto(
            id = 9L,
            country = Country.DE,
            region = "Berlin",
            ratePerNight = Money.of(BigDecimal("120.00")),
            isActive = true,
            source = "BMF-Schreiben",
        )
        whenever(hotelRateQueries.findHotelRateById(9L)).thenReturn(existing)
        whenever(manageHotelRate.saveHotelRate(any())).thenAnswer { it.arguments[0] }
        val tab = newTab()
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        regionField(tab).value = "München"
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(hotelRateQueries).findHotelRateById(9L)
        verify(manageHotelRate).saveHotelRate(org.mockito.kotlin.argThat { region == "München" })
    }

    // ── installHeaderActions (search field + entry counter), only wired by the real
    // ReferenceDataView host, never called by any test above that constructs the tab standalone. ──

    @Test
    fun `installHeaderActions wires the search placeholder and filters the grid on typing`() {
        whenever(hotelRateQueries.countHotelRates(anyOrNull())).thenReturn(0L)
        whenever(hotelRateQueries.countInactiveHotelRates(anyOrNull())).thenReturn(0L)
        whenever(hotelRateQueries.findHotelRates(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        val tab = newTab()
        val actionsSlot = com.vaadin.flow.component.html.Div()

        tab.installHeaderActions(actionsSlot)
        val search = actionsSlot.children.toList().filterIsInstance<TextField>().single()
        assertEquals(UiTranslations.tr("referenceData.hotel.placeholder"), search.placeholder)

        search.value = "münchen"
        grid(tab)._fetch(0, 10)

        verify(hotelRateQueries, org.mockito.kotlin.atLeastOnce()).findHotelRates(any(), any(), eq("münchen"), any(), any())
    }

    // ── The rate binder's extracted null arms: never reachable through the field itself
    // (asRequired's own required-check in the same binding chain always rejects a blank field
    // first, both for the custom validator and, on the write side, its converter), but real
    // production code a differently-constructed HotelRateFormData could still hand a null to. ──

    @Test
    fun `boundary - isNonNegativeRate rejects a null value`() {
        assertFalse(HotelRatesTab.isNonNegativeRate(null))
    }

    @Test
    fun `boundary - isNonNegativeRate rejects a negative value and accepts zero and positive`() {
        assertFalse(HotelRatesTab.isNonNegativeRate(BigDecimal("-0.01")))
        assertTrue(HotelRatesTab.isNonNegativeRate(BigDecimal.ZERO))
        assertTrue(HotelRatesTab.isNonNegativeRate(BigDecimal("50.00")))
    }

    @Test
    fun `boundary - toMoneyOrZero defaults a null amount to zero and keeps a real one`() {
        assertEquals(Money.ZERO, HotelRatesTab.toMoneyOrZero(null))
        assertEquals(Money.of(BigDecimal("12.50")), HotelRatesTab.toMoneyOrZero(BigDecimal("12.50")))
    }

    @Test
    fun `boundary - toBigDecimalOrZero defaults a null Money to zero and keeps a real one`() {
        assertEquals(BigDecimal.ZERO, HotelRatesTab.toBigDecimalOrZero(null))
        assertEquals(Money.of(BigDecimal("12.50")).amount, HotelRatesTab.toBigDecimalOrZero(Money.of(BigDecimal("12.50"))))
    }
}
