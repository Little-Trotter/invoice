package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.view.invoice.componentFor
import de.littletrotter.ui.view.invoice.requireAs
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getColumnByHeader
import com.github.mvysny.kaributesting.v10._getFormatted
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.dto.MileageRateDto
import de.littletrotter.application.reference.port.inbound.MileageRateQueryPort
import de.littletrotter.application.reference.port.inbound.ManageMileageRateUseCase
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.VehicleType
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
 * [MileageRatesTab] had no test file at all, measured at 0 % branch coverage. Same
 * CRUD-dialog shape as [HotelRatesTabKaribuTest], plus dedicated coverage for the vehicle-type
 * column's three-way `when` and the grid sort listener's branches (both entirely specific to
 * this tab).
 */
class MileageRatesTabKaribuTest {

    private val mileageRateQueries: MileageRateQueryPort = mock()

    private val manageMileageRate: ManageMileageRateUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(mileageRateQueries.findMileageRates(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        whenever(mileageRateQueries.countMileageRates(anyOrNull())).thenReturn(0L)
        whenever(mileageRateQueries.countInactiveMileageRates(anyOrNull())).thenReturn(0L)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newTab(): MileageRatesTab = MileageRatesTab(mileageRateQueries, manageMileageRate)

    private inline fun <reified T> declaredField(clazz: Class<*>, tab: Any, name: String): T {
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        return f.get(tab) as T
    }

    private fun grid(tab: MileageRatesTab): Grid<MileageRateDto> =
        declaredField(AbstractReferencedataCrudTab::class.java, tab, "grid")

    private fun openEditor(tab: MileageRatesTab, entity: MileageRateDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(tab, entity)
    }

    private fun newButton(tab: MileageRatesTab): Button =
        tab._get<Button> { text = UiTranslations.tr("referenceData.new") }

    private fun saveButton(tab: MileageRatesTab): Button = declaredField(AbstractCrudView::class.java, tab, "saveButton")
    private fun deleteButton(tab: MileageRatesTab): Button = declaredField(AbstractCrudView::class.java, tab, "deleteButton")

    private fun vehicleTypeField(tab: MileageRatesTab): ComboBox<VehicleType> =
        declaredField(MileageRatesTab::class.java, tab, "vehicleTypeField")
    private fun rateField(tab: MileageRatesTab): BigDecimalField = declaredField(MileageRatesTab::class.java, tab, "rateField")
    private fun validFromPicker(tab: MileageRatesTab): DatePicker = declaredField(MileageRatesTab::class.java, tab, "validFromPicker")
    private fun activeField(tab: MileageRatesTab): Checkbox = declaredField(MileageRatesTab::class.java, tab, "activeField")
    private fun sourceField(tab: MileageRatesTab): TextArea = declaredField(MileageRatesTab::class.java, tab, "sourceField")

    private fun fillValidForm(tab: MileageRatesTab) {
        vehicleTypeField(tab).value = VehicleType.CAR
        rateField(tab).value = BigDecimal("0.30")
        validFromPicker(tab).value = LocalDate.of(2026, 1, 1)
        activeField(tab).value = true
        sourceField(tab).value = "Paragraph 9"
    }

    // ── grid columns ─────────────────────────────────────────────────────────

    @Test
    fun `vehicle-type column labels a car`() {
        val tab = newTab()
        val rate = MileageRateDto(vehicleType = VehicleType.CAR)

        val text = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.mileage.vehicleType"))._getFormatted(rate)

        assertEquals(UiTranslations.tr("invoiceDetail.travel.mileage.vehicleType.car"), text)
    }

    @Test
    fun `vehicle-type column labels another motor vehicle`() {
        val tab = newTab()
        val rate = MileageRateDto(vehicleType = VehicleType.OTHER_MOTOR_VEHICLE)

        val text = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.mileage.vehicleType"))._getFormatted(rate)

        assertEquals(UiTranslations.tr("invoiceDetail.travel.mileage.vehicleType.other"), text)
    }

    @Test
    fun `vehicle-type column falls back to the legacy country label when null`() {
        val tab = newTab()
        val rate = MileageRateDto(vehicleType = null, country = Country.DE)

        val text = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.mileage.vehicleType"))._getFormatted(rate)

        assertEquals("DE (${UiTranslations.tr("referenceData.mileage.legacy")})", text)
    }

    @Test
    fun `vehicle-type column falls back to the em dash when both vehicleType and country are null`() {
        val tab = newTab()
        val rate = MileageRateDto(vehicleType = null, country = null)

        val text = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.mileage.vehicleType"))._getFormatted(rate)

        assertEquals("— (${UiTranslations.tr("referenceData.mileage.legacy")})", text)
    }

    @Test
    fun `validFrom and validTo columns fall back to the em dash when null`() {
        val tab = newTab()
        val rate = MileageRateDto(validFrom = null, validTo = null)

        val from = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.validFrom"))._getFormatted(rate)
        val to = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.validTo"))._getFormatted(rate)

        assertEquals("—", from)
        assertEquals("—", to)
    }

    @Test
    fun `validFrom and validTo columns render the actual value when present`() {
        val tab = newTab()
        val rate = MileageRateDto(
            validFrom = LocalDate.of(2026, 1, 1),
            validTo = LocalDate.of(2026, 12, 31),
        )

        val from = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.validFrom"))._getFormatted(rate)
        val to = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.validTo"))._getFormatted(rate)

        assertEquals("01.01.2026", from)
        assertEquals("31.12.2026", to)
    }

    // ── ratePerKm used stripTrailingZeros().toPlainString() with a hardcoded "€", so 1.50
    //    rendered as "1.5 €" instead of the German "1,50 €" every other grid shows. ─────────────

    @Test
    fun `the ratePerKm column renders the German comma, not a stripped decimal`() {
        val tab = newTab()
        val rate = MileageRateDto(vehicleType = VehicleType.CAR, ratePerKm = BigDecimal("1.50"))

        val text = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.ratePerKm"))._getFormatted(rate)

        assertEquals(MoneyFormatter.format(BigDecimal("1.50")), text)
    }

    // ── MileageFormData ─────────────────────────────────────────────────────

    @Test
    fun `toDto defaults a blank ratePerKm and blank source, and falls back to DE for an unresolvable country`() {
        val formData = MileageFormData(country = "??", vehicleType = VehicleType.CAR, ratePerKm = null, source = "   ")

        val target = formData.toDto(version = 2L)

        assertEquals(2L, target.version)

        assertEquals(BigDecimal.ZERO, target.ratePerKm)
        assertEquals(Country.DE, target.country)
        assertEquals(null, target.source)
    }

    @Test
    fun `toDto keeps a resolvable country and a non-blank rate and source`() {
        val formData = MileageFormData(country = "FR", vehicleType = VehicleType.CAR, ratePerKm = BigDecimal("0.42"), source = "BMF-Schreiben")

        val target = formData.toDto(version = null)

        assertEquals(Country.FR, target.country)
        assertEquals(BigDecimal("0.42"), target.ratePerKm)
        assertEquals("BMF-Schreiben", target.source)
    }

    @Test
    fun `MileageFormData from entity defaults a null source to empty`() {
        val rate = MileageRateDto(id = 5L, country = Country.DE, source = null)

        val formData = MileageFormData.from(rate)

        assertEquals("", formData.source)
    }

    @Test
    fun `MileageFormData from entity keeps a non-null source`() {
        val rate = MileageRateDto(id = 6L, country = Country.DE, source = "Paragraph 9")

        val formData = MileageFormData.from(rate)

        assertEquals("Paragraph 9", formData.source)
    }

    @Test
    fun `status column shows the inactive badge only for inactive rows`() {
        val tab = newTab()
        val active = MileageRateDto(isActive = true)
        val inactive = MileageRateDto(isActive = false)

        val activeSpan = componentColumn(tab, UiTranslations.tr("referenceData.status"), active) as com.vaadin.flow.component.html.Span
        val inactiveSpan = componentColumn(tab, UiTranslations.tr("referenceData.status"), inactive) as com.vaadin.flow.component.html.Span

        assertTrue(activeSpan.text.isNullOrEmpty())
        assertEquals(UiTranslations.tr("referenceData.taxRate.status.inactive"), inactiveSpan.text)
    }

    private fun componentColumn(tab: MileageRatesTab, header: String, rate: MileageRateDto): com.vaadin.flow.component.Component {
        val column = grid(tab).columns.first { it.headerText == header }
        return column.componentFor(rate)
    }

    // ── sort listener ────────────────────────────────────────────────────────

    @Test
    fun `sorting by rate-per-km ascending refetches with the mapped sort field`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("vehicleType")!!

        grid(tab).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))
        grid(tab)._fetch(0, 10)

        verify(mileageRateQueries, org.mockito.kotlin.atLeastOnce())
            .findMileageRates(any(), any(), anyOrNull(), eq("vehicleType"), eq(de.littletrotter.application.dto.SortDirection.ASC))
    }

    @Test
    fun `sorting by validFrom descending refetches with the mapped sort field`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("vehicleType")!!

        grid(tab).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))
        grid(tab)._fetch(0, 10)

        verify(mileageRateQueries, org.mockito.kotlin.atLeastOnce())
            .findMileageRates(any(), any(), anyOrNull(), eq("vehicleType"), eq(de.littletrotter.application.dto.SortDirection.DESC))
    }

    @Test
    fun `clearing the sort order resets to country ascending`() {
        val tab = newTab()
        val column = grid(tab).getColumnByKey("vehicleType")!!
        grid(tab).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        grid(tab).sort(emptyList())
        grid(tab)._fetch(0, 10)

        verify(mileageRateQueries, org.mockito.kotlin.atLeastOnce())
            .findMileageRates(any(), any(), anyOrNull(), eq("country"), eq(de.littletrotter.application.dto.SortDirection.ASC))
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
    fun `saving without a vehicle type shows a validation error and keeps the dialog open`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        rateField(tab).value = BigDecimal("0.30")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageMileageRate, never()).saveMileageRate(any())
    }

    @Test
    fun `saving with a negative rate shows a validation error and keeps the dialog open`() {
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        vehicleTypeField(tab).value = VehicleType.CAR
        rateField(tab).value = BigDecimal("-0.10")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageMileageRate, never()).saveMileageRate(any())
    }

    @Test
    fun `saving a valid new mileage rate persists it and closes the dialog`() {
        whenever(manageMileageRate.saveMileageRate(any())).thenAnswer { it.arguments[0] }
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        fillValidForm(tab)
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageMileageRate).saveMileageRate(
            org.mockito.kotlin.argThat { vehicleType == VehicleType.CAR && ratePerKm == BigDecimal("0.30") }
        )
    }

    // ── edit / delete ────────────────────────────────────────────────────────

    @Test
    fun `opening an existing legacy mileage rate enables delete and keeps its country`() {
        val existing = MileageRateDto(
            id = 11L,
            country = Country.FR,
            vehicleType = null,
            ratePerKm = BigDecimal("0.25"),
        )
        val tab = newTab()

        openEditor(tab, existing)

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertTrue(deleteButton(tab).isEnabled)
    }

    @Test
    fun `confirming delete on an existing mileage rate calls deleteMileageRate and closes the dialog`() {
        val existing = MileageRateDto(id = 11L, vehicleType = VehicleType.CAR)
        val tab = newTab()
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        deleteButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageMileageRate).deleteMileageRate(eq(11L))
    }

    // ── the editor combo's own item label generator (distinct from the grid column's
    // vehicleTypeColumnLabel above, never invoked with OTHER_MOTOR_VEHICLE by any test), the
    // edit-existing save flow (findEntityById, no test above ever saves an edited row), the
    // DataProvider's own size-query count callback, and installHeaderActions/getSearchPlaceholder
    // — none wired by any test above that constructs the tab standalone. ──────────────────────

    @Test
    fun `vehicle-type editor combo labels another motor vehicle`() {
        val tab = newTab()

        val label = vehicleTypeField(tab).itemLabelGenerator.apply(VehicleType.OTHER_MOTOR_VEHICLE)

        assertEquals(UiTranslations.tr("invoiceDetail.travel.mileage.vehicleType.other"), label)
    }

    @Test
    fun `saving an edited existing mileage rate reloads it through findEntityById`() {
        val existing = MileageRateDto(
            id = 12L,
            vehicleType = VehicleType.CAR,
            ratePerKm = BigDecimal("0.30"),
            isActive = true,
            source = "BMF-Schreiben",
        )
        whenever(mileageRateQueries.findMileageRateById(12L)).thenReturn(existing)
        whenever(manageMileageRate.saveMileageRate(any())).thenAnswer { it.arguments[0] }
        val tab = newTab()
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(mileageRateQueries).findMileageRateById(12L)
        verify(manageMileageRate).saveMileageRate(any())
    }

    @Test
    fun `the grid data provider's own size query reaches countMileageRates`() {
        val tab = newTab()
        val provider = grid(tab).dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<MileageRateDto, String>>()

        provider.size(com.vaadin.flow.data.provider.Query(0, 10, emptyList(), null, null))

        verify(mileageRateQueries, org.mockito.kotlin.atLeastOnce()).countMileageRates(anyOrNull())
    }

    @Test
    fun `installHeaderActions wires the search placeholder and filters the grid on typing`() {
        whenever(mileageRateQueries.findMileageRates(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        val tab = newTab()
        val actionsSlot = com.vaadin.flow.component.html.Div()

        tab.installHeaderActions(actionsSlot)
        val search = actionsSlot.children.toList().filterIsInstance<com.vaadin.flow.component.textfield.TextField>().single()
        assertEquals(UiTranslations.tr("referenceData.mileage.placeholder"), search.placeholder)

        search.value = "pkw"
        grid(tab)._fetch(0, 10)

        verify(mileageRateQueries, org.mockito.kotlin.atLeastOnce())
            .findMileageRates(any(), any(), eq("pkw"), any(), any())
    }
}
