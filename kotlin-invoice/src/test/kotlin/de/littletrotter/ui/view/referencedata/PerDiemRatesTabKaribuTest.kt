package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.view.invoice.componentFor
import de.littletrotter.ui.view.invoice.requireAs
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
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.reference.port.inbound.ManagePerDiemRateUseCase
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.PerDiemRateDto
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
 * Exercises [PerDiemRatesTab]'s CRUD dialog and column-rendering branches. Same shape as
 * [HotelRatesTabKaribuTest]; see there for the reasoning behind the reflection-based field
 * access instead of Karibu's label lookup.
 */
class PerDiemRatesTabKaribuTest {

    private val perDiemRateQueries: PerDiemRateQueryPort = mock()

    private val managePerDiemRate: ManagePerDiemRateUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(perDiemRateQueries.findPerDiemRates(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        whenever(perDiemRateQueries.countPerDiemRates(anyOrNull())).thenReturn(0L)
        whenever(perDiemRateQueries.countInactivePerDiemRates(anyOrNull())).thenReturn(0L)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newTab(): PerDiemRatesTab = PerDiemRatesTab(perDiemRateQueries, managePerDiemRate)

    private inline fun <reified T> declaredField(clazz: Class<*>, tab: Any, name: String): T {
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        return f.get(tab) as T
    }

    private fun grid(tab: PerDiemRatesTab): Grid<PerDiemRateDto> =
        declaredField(AbstractReferencedataCrudTab::class.java, tab, "grid")

    private fun componentColumn(tab: PerDiemRatesTab, header: String, rate: PerDiemRateDto): Component {
        val column = grid(tab).columns.first { it.headerText == header }
        return column.componentFor(rate)
    }

    private fun openEditor(tab: PerDiemRatesTab, entity: PerDiemRateDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(tab, entity)
    }

    private fun newButton(tab: PerDiemRatesTab): Button =
        tab._get<Button> { text = UiTranslations.tr("referenceData.new") }

    private fun saveButton(tab: PerDiemRatesTab): Button = declaredField(AbstractCrudView::class.java, tab, "saveButton")
    private fun deleteButton(tab: PerDiemRatesTab): Button = declaredField(AbstractCrudView::class.java, tab, "deleteButton")

    private fun countryField(tab: PerDiemRatesTab): TextField = declaredField(PerDiemRatesTab::class.java, tab, "countryField")
    private fun regionField(tab: PerDiemRatesTab): TextField = declaredField(PerDiemRatesTab::class.java, tab, "regionField")
    private fun fullDayRateField(tab: PerDiemRatesTab): BigDecimalField = declaredField(PerDiemRatesTab::class.java, tab, "fullDayRateField")
    private fun arrivalDepartureRateField(tab: PerDiemRatesTab): BigDecimalField =
        declaredField(PerDiemRatesTab::class.java, tab, "arrivalDepartureRateField")
    private fun validFromPicker(tab: PerDiemRatesTab): DatePicker = declaredField(PerDiemRatesTab::class.java, tab, "validFromPicker")
    private fun activeField(tab: PerDiemRatesTab): Checkbox = declaredField(PerDiemRatesTab::class.java, tab, "activeField")
    private fun sourceField(tab: PerDiemRatesTab): TextArea = declaredField(PerDiemRatesTab::class.java, tab, "sourceField")
    private fun inlandOnlyField(tab: PerDiemRatesTab): Checkbox = declaredField(PerDiemRatesTab::class.java, tab, "inlandOnlyField")

    private fun fillValidForm(tab: PerDiemRatesTab) {
        countryField(tab).value = "DE"
        regionField(tab).value = "Berlin"
        fullDayRateField(tab).value = BigDecimal("28.00")
        arrivalDepartureRateField(tab).value = BigDecimal("14.00")
        validFromPicker(tab).value = LocalDate.of(2026, 1, 1)
        activeField(tab).value = true
        sourceField(tab).value = "BMF-Schreiben"
    }

    // ── grid columns ─────────────────────────────────────────────────────────

    @Test
    fun `region, validFrom and validTo columns fall back to the em dash when null`() {
        val tab = newTab()
        val rate = PerDiemRateDto(country = Country.DE, region = null, validFrom = null, validTo = null)

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
        val rate = PerDiemRateDto(
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

    // ── fullDayRate/arrivalDepartureRate used stripTrailingZeros().toPlainString() with a
    //    hardcoded "€", so 1.50 rendered as "1.5 €" instead of the German "1,50 €". ─────────────

    @Test
    fun `the fullDayRate and arrivalDepartureRate columns render the German comma, not a stripped decimal`() {
        val tab = newTab()
        val rate = PerDiemRateDto(
            country = Country.DE,
            fullDayRate = Money.of(BigDecimal("1.50")),
            arrivalDepartureRate = Money.of(BigDecimal("1.50")),
        )

        val fullDayRate = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.fullDayRate"))._getFormatted(rate)
        val arrivalDepartureRate = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.arrivalDepartureRate"))._getFormatted(rate)

        assertEquals(MoneyFormatter.format(Money.of("1.50")), fullDayRate)
        assertEquals(MoneyFormatter.format(Money.of("1.50")), arrivalDepartureRate)
    }

    @Test
    fun `status column shows the inactive badge only for inactive rows`() {
        val tab = newTab()
        val active = PerDiemRateDto(isActive = true)
        val inactive = PerDiemRateDto(isActive = false)

        val activeSpan = componentColumn(tab, UiTranslations.tr("referenceData.status"), active) as com.vaadin.flow.component.html.Span
        val inactiveSpan = componentColumn(tab, UiTranslations.tr("referenceData.status"), inactive) as com.vaadin.flow.component.html.Span

        assertTrue(activeSpan.text.isNullOrEmpty())
        assertEquals(UiTranslations.tr("referenceData.taxRate.status.inactive"), inactiveSpan.text)
    }

    @Test
    fun `a rate that is both inland and default renders the home icon and the star icon`() {
        val tab = newTab()
        val rate = PerDiemRateDto(inlandRules = true, isDefault = true)

        val inlandBadge = componentColumn(tab, UiTranslations.tr("referenceData.inland"), rate)
        val standardBadge = componentColumn(tab, UiTranslations.tr("referenceData.perDiem.isDefault"), rate)

        assertTrue(inlandBadge is Icon, "expected the shared home icon, got ${inlandBadge::class}")
        assertEquals(UiTranslations.tr("referenceData.inlandBadge"), inlandBadge.element.getAttribute("aria-label"))
        assertTrue(standardBadge is Icon, "expected the shared star icon, got ${standardBadge::class}")
        assertEquals(UiTranslations.tr("referenceData.defaultBadge"), standardBadge.element.getAttribute("aria-label"))
    }

    @Test
    fun `a rate that is neither inland nor default renders no icon in either column`() {
        val tab = newTab()
        val rate = PerDiemRateDto(inlandRules = false, isDefault = false)

        val inlandBadge = componentColumn(tab, UiTranslations.tr("referenceData.inland"), rate)
        val standardBadge = componentColumn(tab, UiTranslations.tr("referenceData.perDiem.isDefault"), rate)

        assertFalse(inlandBadge is Icon, "a non-inland rate must not render any icon")
        assertEquals("true", inlandBadge.element.getAttribute("aria-hidden"))
        assertFalse(standardBadge is Icon, "a non-default rate must not render any icon")
        assertEquals("true", standardBadge.element.getAttribute("aria-hidden"))
    }

    @Test
    fun `toggling inland-only refreshes the grid data`() {
        val tab = newTab()

        inlandOnlyField(tab).value = true

        verify(perDiemRateQueries, org.mockito.kotlin.atLeast(2)).countPerDiemRates(anyOrNull())
    }

    @Test
    fun `inland-only checked filters out rows without inland rules after fetch`() {
        val inland = PerDiemRateDto(id = 1L, country = Country.DE, inlandRules = true)
        val notInland = PerDiemRateDto(id = 2L, country = Country.FR, inlandRules = false)
        whenever(perDiemRateQueries.findPerDiemRates(any(), any(), anyOrNull(), any(), any())).thenReturn(listOf(inland, notInland))
        val tab = newTab()

        inlandOnlyField(tab).value = true
        val fetched = grid(tab)._fetch(0, 10)

        assertEquals(listOf(inland), fetched)
    }

    @Test
    fun `inland-only unchecked returns every fetched row regardless of inland rules`() {
        val inland = PerDiemRateDto(id = 1L, country = Country.DE, inlandRules = true)
        val notInland = PerDiemRateDto(id = 2L, country = Country.FR, inlandRules = false)
        whenever(perDiemRateQueries.findPerDiemRates(any(), any(), anyOrNull(), any(), any())).thenReturn(listOf(inland, notInland))
        val tab = newTab()

        val fetched = grid(tab)._fetch(0, 10)

        assertEquals(2, fetched.size)
    }

    // ── new / save ───────────────────────────────────────────────────────────

    @Test
    fun `clicking New opens an empty dialog with the delete button disabled`() {
        val tab = newTab()

        newButton(tab)._click()

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertFalse(deleteButton(tab).isEnabled)
    }

    @Test
    fun `saving without a country shows a validation error and keeps the dialog open`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        fullDayRateField(tab).value = BigDecimal("28.00")
        arrivalDepartureRateField(tab).value = BigDecimal("14.00")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePerDiemRate, never()).savePerDiemRate(any())
    }

    @Test
    fun `saving with a negative arrival-departure rate shows a validation error`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        fullDayRateField(tab).value = BigDecimal("28.00")
        arrivalDepartureRateField(tab).value = BigDecimal("-1.00")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePerDiemRate, never()).savePerDiemRate(any())
    }

    @Test
    fun `saving with a negative full-day rate shows a validation error`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        fullDayRateField(tab).value = BigDecimal("-1.00")
        arrivalDepartureRateField(tab).value = BigDecimal("14.00")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePerDiemRate, never()).savePerDiemRate(any())
    }

    // Each rate field's own asRequired() is a separate binding-chain link ahead of the
    // withValidator null/negative check; a blank field must still be rejected (never silently
    // treated as zero), same gap the negative-rate tests above do not close for either field.
    @Test
    fun `saving with a blank full-day rate shows a validation error`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        fullDayRateField(tab).clear()
        arrivalDepartureRateField(tab).value = BigDecimal("14.00")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePerDiemRate, never()).savePerDiemRate(any())
    }

    @Test
    fun `saving with a blank arrival-departure rate shows a validation error`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        fullDayRateField(tab).value = BigDecimal("28.00")
        arrivalDepartureRateField(tab).clear()
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePerDiemRate, never()).savePerDiemRate(any())
    }

    @Test
    fun `saving a valid new per-diem rate persists it and closes the dialog`() {
        whenever(managePerDiemRate.savePerDiemRate(any())).thenAnswer { it.arguments[0] }
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        fillValidForm(tab)
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(managePerDiemRate).savePerDiemRate(
            org.mockito.kotlin.argThat { country == Country.DE && fullDayRate == Money.of(BigDecimal("28.00")) }
        )
    }

    // ── Active-needs-source cross-field validation, mirrors
    // chk_per_diem_rate_active_needs_source (NOT is_active OR (source IS NOT NULL AND
    // LENGTH(TRIM(source)) > 0)) client-side. isActive defaults true with no required validator
    // on source ("Rechtsgrundlage"), a guaranteed constraint violation on save without this. ────

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun notificationText(notification: Notification): String =
        de.littletrotter.ui.toastText(notification)

    @Test
    fun `saving active with no legal basis is rejected, mirroring chk_per_diem_rate_active_needs_source`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        fullDayRateField(tab).value = BigDecimal("28.00")
        arrivalDepartureRateField(tab).value = BigDecimal("14.00")
        // activeField keeps its dialog default (checked/true); sourceField is left blank —
        // exactly the guaranteed-violation state described in finding L6.
        assertTrue(activeField(tab).value, "isActive must default to checked for this to reproduce the finding")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        assertTrue(sourceField(tab).isInvalid)
        assertEquals(UiTranslations.tr("referenceData.perDiem.activeRequiresSource"), sourceField(tab).errorMessage)
        assertTrue(getNotifications().isEmpty())
        verify(managePerDiemRate, never()).savePerDiemRate(any())
    }

    @Test
    fun `unchecking active with no legal basis is a valid combination (superseded rate)`() {
        whenever(managePerDiemRate.savePerDiemRate(any())).thenAnswer { it.arguments[0] }
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        countryField(tab).value = "DE"
        fullDayRateField(tab).value = BigDecimal("28.00")
        arrivalDepartureRateField(tab).value = BigDecimal("14.00")
        activeField(tab).value = false

        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        // The saved-success toast is expected here — only the L6 validation-error toast must be absent.
        assertTrue(getNotifications().none { notificationText(it) == UiTranslations.tr("referenceData.perDiem.activeRequiresSource") })
        verify(managePerDiemRate).savePerDiemRate(any())
    }

    // ── edit / delete ────────────────────────────────────────────────────────

    @Test
    fun `opening an existing per-diem rate enables the delete button and populates the form`() {
        val existing = PerDiemRateDto(
            id = 9L,
            country = Country.DE,
            region = "Hamburg",
            fullDayRate = Money.of(BigDecimal("28.00")),
        )
        val tab = newTab()

        openEditor(tab, existing)

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertTrue(deleteButton(tab).isEnabled)
        assertEquals("Hamburg", regionField(tab).value)
    }

    @Test
    fun `confirming delete on an existing per-diem rate calls deletePerDiemRate and closes the dialog`() {
        val existing = PerDiemRateDto(id = 9L, country = Country.DE)
        val tab = newTab()
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        deleteButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(managePerDiemRate).deletePerDiemRate(eq(9L))
    }

    // ── header actions / counter ────────────────────────────────────────────

    @Test
    fun `searching after the header actions are installed updates the counter text`() {
        whenever(perDiemRateQueries.countPerDiemRates(anyOrNull())).thenReturn(3L)
        whenever(perDiemRateQueries.countInactivePerDiemRates(anyOrNull())).thenReturn(1L)
        val tab = newTab()
        val actionsSlot = Div()
        tab.installHeaderActions(actionsSlot)
        val counter = actionsSlot.children.filter { it is com.vaadin.flow.component.html.Span }
            .findFirst().get() as com.vaadin.flow.component.html.Span
        val search = actionsSlot.children.filter { it is TextField }.findFirst().get() as TextField

        search.value = "berlin"

        assertEquals(UiTranslations.tr("referenceData.counter.withInactive", 3L, 1L), counter.text)
    }

    // ── Edit-existing save flow (findEntityById) and the grid sort listener, neither
    // driven by any test above: every save test opens via newButton (id == null → newEntity, not
    // findEntityById), and no test ever sorts the grid at all. ─────────────────────────────────

    @Test
    fun `editing an existing per-diem rate and saving loads it via findEntityById and persists the change`() {
        val existing = PerDiemRateDto(
            id = 9L,
            country = Country.DE,
            fullDayRate = Money.of(BigDecimal("28.00")),
            arrivalDepartureRate = Money.of(BigDecimal("14.00")),
            validFrom = LocalDate.of(2025, 1, 1),
            isActive = true,
            source = "BMF-Schreiben",
        )
        whenever(perDiemRateQueries.findPerDiemRateById(9L)).thenReturn(existing)
        whenever(managePerDiemRate.savePerDiemRate(any())).thenAnswer { it.arguments[0] }
        val tab = newTab()
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        regionField(tab).value = "München"
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(perDiemRateQueries).findPerDiemRateById(9L)
        verify(managePerDiemRate).savePerDiemRate(org.mockito.kotlin.argThat { region == "München" })
    }

    @Test
    fun `sorting the grid by country descending re-fetches with the descending sort direction`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("country")

        grid(tab).sort(com.vaadin.flow.component.grid.GridSortOrder.desc(column).build())
        grid(tab)._fetch(0, 10)

        verify(perDiemRateQueries, org.mockito.kotlin.atLeastOnce())
            .findPerDiemRates(any(), any(), anyOrNull(), eq("country"), eq(SortDirection.DESC))
    }

    // ── The sort listener's ASC/null-order arm (only the DESC test above exists so far)
    // and its own DataProvider `count` callback (used by Vaadin's lazy row-count query, distinct
    // from the header counter's countTotal/countInactive), plus installHeaderActions/
    // getSearchPlaceholder — none wired by any test above that constructs the tab standalone. ──

    @Test
    fun `boundary - sorting country ascending refetches with the ASC direction`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("country")

        grid(tab).sort(com.vaadin.flow.component.grid.GridSortOrder.asc(column).build())
        grid(tab)._fetch(0, 10)

        verify(perDiemRateQueries, org.mockito.kotlin.atLeastOnce())
            .findPerDiemRates(any(), any(), anyOrNull(), eq("country"), eq(SortDirection.ASC))
    }

    @Test
    fun `boundary - clearing the sort order resets to ascending`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("country")
        grid(tab).sort(com.vaadin.flow.component.grid.GridSortOrder.desc(column).build())

        grid(tab).sort(emptyList())
        grid(tab)._fetch(0, 10)

        verify(perDiemRateQueries, org.mockito.kotlin.atLeastOnce())
            .findPerDiemRates(any(), any(), anyOrNull(), eq("country"), eq(SortDirection.ASC))
    }

    @Test
    fun `the grid data provider's own size query reaches countPerDiemRates`() {
        val tab = newTab()
        val provider = grid(tab).dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<PerDiemRateDto, String>>()

        provider.size(com.vaadin.flow.data.provider.Query(0, 10, emptyList(), null, null))

        verify(perDiemRateQueries, org.mockito.kotlin.atLeastOnce()).countPerDiemRates(anyOrNull())
    }

    @Test
    fun `installHeaderActions wires the search placeholder and filters the grid on typing`() {
        whenever(perDiemRateQueries.findPerDiemRates(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        val tab = newTab()
        val actionsSlot = Div()

        tab.installHeaderActions(actionsSlot)
        val search = actionsSlot.children.toList().filterIsInstance<TextField>().single()
        assertEquals(UiTranslations.tr("referenceData.perDiem.placeholder"), search.placeholder)

        search.value = "bayern"
        grid(tab)._fetch(0, 10)

        verify(perDiemRateQueries, org.mockito.kotlin.atLeastOnce())
            .findPerDiemRates(any(), any(), eq("bayern"), any(), any())
    }

    // ── The two rate binders' extracted null arms: never reachable through either field
    // itself (asRequired's own required-check in the same binding chain always rejects a blank
    // field first, both for the custom validator and, on the write side, its converter), but real
    // production code a differently-constructed PerDiemRateFormData could still hand a null to. ──

    @Test
    fun `boundary - isNonNegativeRate rejects a null value`() {
        assertFalse(PerDiemRatesTab.isNonNegativeRate(null))
    }

    @Test
    fun `boundary - isNonNegativeRate rejects a negative value and accepts zero and positive`() {
        assertFalse(PerDiemRatesTab.isNonNegativeRate(BigDecimal("-0.01")))
        assertTrue(PerDiemRatesTab.isNonNegativeRate(BigDecimal.ZERO))
        assertTrue(PerDiemRatesTab.isNonNegativeRate(BigDecimal("28.00")))
    }

    @Test
    fun `boundary - toMoneyOrZero defaults a null amount to zero and keeps a real one`() {
        assertEquals(Money.ZERO, PerDiemRatesTab.toMoneyOrZero(null))
        assertEquals(Money.of(BigDecimal("14.00")), PerDiemRatesTab.toMoneyOrZero(BigDecimal("14.00")))
    }

    @Test
    fun `boundary - toBigDecimalOrZero defaults a null Money to zero and keeps a real one`() {
        assertEquals(BigDecimal.ZERO, PerDiemRatesTab.toBigDecimalOrZero(null))
        assertEquals(Money.of(BigDecimal("14.00")).amount, PerDiemRatesTab.toBigDecimalOrZero(Money.of(BigDecimal("14.00"))))
    }
}
