package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.view.invoice.componentFor
import de.littletrotter.ui.view.invoice.requireAs
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.github.mvysny.kaributesting.v10._setValue
import com.github.mvysny.kaributesting.v10.getNotifications
import com.github.mvysny.kaributesting.v10.getSuggestionItems
import com.github.mvysny.kaributesting.v10.getSuggestions
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.reference.port.inbound.ManageTaxRateUseCase
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

/** TaxRatesTab must never hard-delete a rule that's already in use on an invoice. */
class TaxRatesTabKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()

    private val manageTaxRate: ManageTaxRateUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(taxRateQueries.findTaxRates(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any()))
            .thenReturn(emptyList())
        whenever(taxRateQueries.countTaxRates(org.mockito.kotlin.anyOrNull())).thenReturn(0L)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun invokeDeleteEntityById(tab: TaxRatesTab, id: Long) {
        val m = TaxRatesTab::class.java.getDeclaredMethod("deleteEntityById", Long::class.java)
        m.isAccessible = true
        try {
            m.invoke(tab, id)
        } catch (ex: java.lang.reflect.InvocationTargetException) {
            throw ex.cause ?: ex
        }
    }

    @Test
    fun `deleteEntityById is blocked and never reaches the facade delete when the rate is in use`() {
        whenever(taxRateQueries.isTaxRateInUse(42L)).thenReturn(true)
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())

        assertThrows(InvalidParameterException::class.java) { invokeDeleteEntityById(tab, 42L) }
        verify(manageTaxRate, never()).deleteTaxRate(org.mockito.kotlin.any())
    }

    @Test
    fun `deleteEntityById delegates to the facade when the rate is not in use`() {
        whenever(taxRateQueries.isTaxRateInUse(7L)).thenReturn(false)
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())

        invokeDeleteEntityById(tab, 7L)
        verify(manageTaxRate).deleteTaxRate(7L)
    }

    // ── "Verwendung" column + deactivate confirmation ──────

    private fun invokeUsageCountLabel(tab: TaxRatesTab, rate: TaxRateDto): String {
        val m = TaxRatesTab::class.java.getDeclaredMethod("usageCountLabel", TaxRateDto::class.java)
        m.isAccessible = true
        return m.invoke(tab, rate) as String
    }

    private fun invokeConfirmDeactivate(tab: TaxRatesTab, id: Long, onConfirmed: () -> Unit, onCancelled: () -> Unit) {
        val m = TaxRatesTab::class.java.getDeclaredMethod(
            "confirmDeactivateTaxRate", Long::class.java, Function0::class.java, Function0::class.java,
        )
        m.isAccessible = true
        m.invoke(tab, id, onConfirmed, onCancelled)
    }

    private fun invokeRefreshTaxRateGrid(tab: TaxRatesTab) {
        val m = TaxRatesTab::class.java.getDeclaredMethod("refreshTaxRateGrid")
        m.isAccessible = true
        m.invoke(tab)
    }

    @Test
    fun `the grid's Verwendung column reads the pre-fetched usage-counts map, not a per-row facade call`() {
        whenever(taxRateQueries.findTaxRateUsageCounts()).thenReturn(mapOf(1L to 3L))
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        invokeRefreshTaxRateGrid(tab)

        assertEquals("3", invokeUsageCountLabel(tab, TaxRateDto(id = 1L)))
        assertEquals("0", invokeUsageCountLabel(tab, TaxRateDto(id = 2L)), "a rate absent from the map is unused")
        // The map is fetched once per grid refresh, never per row — no per-row facade call exists
        // in usageCountLabel at all (it only reads the field), so this simply documents the design.
        verify(taxRateQueries, org.mockito.kotlin.atLeastOnce()).findTaxRateUsageCounts()
    }

    @Test
    fun `deactivating an unused rate proceeds without asking`() {
        whenever(taxRateQueries.findTaxRateUsageCounts()).thenReturn(emptyMap())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        invokeRefreshTaxRateGrid(tab)
        var confirmed = false
        var cancelled = false

        invokeConfirmDeactivate(tab, 5L, onConfirmed = { confirmed = true }, onCancelled = { cancelled = true })

        assertTrue(confirmed, "an unused rate must deactivate immediately, no dialog")
        assertTrue(!cancelled)
    }

    @Test
    fun `deactivating a rate that is in use does not apply until confirmed`() {
        whenever(taxRateQueries.findTaxRateUsageCounts()).thenReturn(mapOf(5L to 4L))
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        invokeRefreshTaxRateGrid(tab)
        var confirmed = false

        invokeConfirmDeactivate(tab, 5L, onConfirmed = { confirmed = true }, onCancelled = {})

        assertTrue(!confirmed, "an in-use rate must wait for the confirm dialog, not deactivate synchronously")
    }

    @Test
    fun `toDto carries the tax rule fields`() {
        val formData = TaxRateFormData(
            name = "Reverse Charge", rate = BigDecimal.ZERO, country = "DE",
            code = "EU_B2B_RC_SERVICE", category = "AE",
            exemptionReasonCode = "VATEX-EU-AE", exemptionReasonText = "RC-Text",
            legalBasis = "§ 3a UStG", isActive = true, isSelectable = true,
        )
        val target = formData.toDto(version = null)

        org.junit.jupiter.api.Assertions.assertEquals("EU_B2B_RC_SERVICE", target.code)
        org.junit.jupiter.api.Assertions.assertEquals("AE", target.category)
        org.junit.jupiter.api.Assertions.assertEquals("VATEX-EU-AE", target.exemptionReasonCode)
        org.junit.jupiter.api.Assertions.assertEquals("RC-Text", target.exemptionReasonText)
        org.junit.jupiter.api.Assertions.assertEquals("§ 3a UStG", target.legalBasis)
    }

    @Test
    fun `form data round-trips the reference flag independently of the default flag`() {
        val entity = TaxRateDto(isDefault = false, isReference = true)

        val formData = TaxRateFormData.from(entity)
        val target = formData.toDto(version = null)

        assertTrue(formData.isReference)
        assertFalse(formData.isDefault)
        assertTrue(target.isReference)
        assertFalse(target.isDefault)
    }

    // ── Status column's default badge picked up the shared star icon automatically ──

    private fun taxRateGrid(tab: TaxRatesTab): com.vaadin.flow.component.grid.Grid<TaxRateDto> {
        val gridField = TaxRatesTab::class.java.getDeclaredField("taxRateGrid")
        gridField.isAccessible = true
        return gridField.get(tab).requireAs<com.vaadin.flow.component.grid.Grid<TaxRateDto>>()
    }

    // The validFrom/validTo column value providers (a plain
    // `?.format(dateFmt) ?: "—"` each) had never actually been invoked: every prior test either
    // checks column headers/existence or drives the *component*-rendered status column, never a
    // plain text column's own ValueProvider against a real row.
    @Test
    fun `the validFrom and validTo columns format a present date and fall back to the em dash when null`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val withDates = TaxRateDto(validFrom = LocalDate.of(2026, 1, 1), validTo = LocalDate.of(2026, 12, 31))
        val withoutDates = TaxRateDto(validFrom = null, validTo = null)
        val grid = taxRateGrid(tab)
        val validFromIndex = grid.columns.indexOfFirst { it.headerText == UiTranslations.tr(tab, "referenceData.validFrom") }
        val validToIndex = grid.columns.indexOfFirst { it.headerText == UiTranslations.tr(tab, "referenceData.validTo") }

        val withDatesRow = grid._getFormattedRow(withDates)
        val withoutDatesRow = grid._getFormattedRow(withoutDates)

        assertTrue(withDatesRow[validFromIndex].contains("2026"), withDatesRow[validFromIndex])
        assertTrue(withDatesRow[validToIndex].contains("2026"), withDatesRow[validToIndex])
        assertEquals("—", withoutDatesRow[validFromIndex])
        assertEquals("—", withoutDatesRow[validToIndex])
    }

    private fun fetchAll(grid: com.vaadin.flow.component.grid.Grid<TaxRateDto>): List<TaxRateDto> {
        val provider = grid.dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<TaxRateDto, Void>>()
        return provider.fetch(com.vaadin.flow.data.provider.Query(0, 50, emptyList(), null, null)).toList()
    }

    private fun activeOnlyCheckbox(tab: TaxRatesTab): Checkbox {
        val f = TaxRatesTab::class.java.getDeclaredField("trActiveOnly")
        f.isAccessible = true
        return f.get(tab) as Checkbox
    }

    // refreshTaxRateGrid's fetch lambda applies a client-side `!activeOnly ||
    // isTaxRateActive(rate)` filter on top of the facade's own page; "Nur aktive" had never
    // actually been checked in any prior test, so neither arm of that filter had run.
    // isTaxRateActive gates on the validFrom/validTo *date window*, not the separate `isActive`
    // flag (that one only drives the status column's own badge) — both rates here are isActive,
    // only the second has an expired validTo.
    @Test
    fun `checking Nur aktive filters date-expired rates out of the fetched page`() {
        val current = TaxRateDto(id = 1L, isActive = true)
        val expired = TaxRateDto(id = 2L, isActive = true, validTo = LocalDate.now().minusDays(1))
        whenever(taxRateQueries.findTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())).thenReturn(listOf(current, expired))
        whenever(taxRateQueries.findTaxRateUsageCounts()).thenReturn(emptyMap())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val grid = taxRateGrid(tab)

        activeOnlyCheckbox(tab).value = true

        val fetched = fetchAll(grid)
        assertEquals(listOf(current), fetched)
    }

    @Test
    fun `leaving Nur aktive unchecked keeps both current and date-expired rates in the fetched page`() {
        val current = TaxRateDto(id = 1L, isActive = true)
        val expired = TaxRateDto(id = 2L, isActive = true, validTo = LocalDate.now().minusDays(1))
        whenever(taxRateQueries.findTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())).thenReturn(listOf(current, expired))
        whenever(taxRateQueries.findTaxRateUsageCounts()).thenReturn(emptyMap())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val grid = taxRateGrid(tab)

        val fetched = fetchAll(grid)
        assertEquals(listOf(current, expired), fetched)
    }

    private fun statusColumnComponent(tab: TaxRatesTab, rate: TaxRateDto): HorizontalLayout {
        val gridField = TaxRatesTab::class.java.getDeclaredField("taxRateGrid")
        gridField.isAccessible = true
        val grid = gridField.get(tab).requireAs<com.vaadin.flow.component.grid.Grid<TaxRateDto>>()
        val column = grid.columns.first { it.headerText == UiTranslations.tr(tab, "referenceData.status") }
        return column.componentFor(rate) as HorizontalLayout
    }

    @Test
    fun `a default tax rate renders the shared star icon as the first child of the status column`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())

        val row = statusColumnComponent(tab, TaxRateDto(isDefault = true))

        val badge = row.children.findFirst().orElseThrow()
        assertTrue(badge is Icon, "expected the shared star icon, got ${badge::class}")
        assertEquals(UiTranslations.tr(tab, "referenceData.defaultBadge"), badge.element.getAttribute("aria-label"))
    }

    @Test
    fun `a non-default tax rate renders no icon and no text for its default marker`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())

        val row = statusColumnComponent(tab, TaxRateDto(isDefault = false))

        val badge = row.children.findFirst().orElseThrow()
        assertFalse(badge is Icon, "a non-default rate must not render any icon")
        assertEquals("true", badge.element.getAttribute("aria-hidden"))
    }

    // ── the status column adds a hotel/expired/inactive/not-selectable badge each on
    // its own condition; a hotel + expired + selectable-but-inactive row exercises all four. ────

    @Test
    fun `status column adds a hotel-rate badge only for hotel rates`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())

        val hotel = statusColumnComponent(tab, TaxRateDto(isHotelRate = true))
        val nonHotel = statusColumnComponent(tab, TaxRateDto(isHotelRate = false))

        assertTrue(hotel.children.anyMatch { it is com.vaadin.flow.component.html.Span && it.text == UiTranslations.tr(tab, "referenceData.hotelRate") })
        assertFalse(nonHotel.children.anyMatch { it is com.vaadin.flow.component.html.Span && it.text == UiTranslations.tr(tab, "referenceData.hotelRate") })
    }

    @Test
    fun `status column labels only marked reference tax rates`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())

        val reference = statusColumnComponent(tab, TaxRateDto(isReference = true))
        val ordinary = statusColumnComponent(tab, TaxRateDto(isReference = false))
        val label = UiTranslations.tr(tab, "referenceData.taxRate.isReference")

        assertTrue(reference.children.anyMatch { it is Span && it.text == label })
        assertFalse(ordinary.children.anyMatch { it is Span && it.text == label })
    }

    @Test
    fun `status column adds an expired badge once validTo is in the past`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val expired = statusColumnComponent(tab, TaxRateDto(validTo = LocalDate.now().minusDays(1)))
        val current = statusColumnComponent(tab, TaxRateDto(validTo = null))

        assertTrue(expired.children.anyMatch { it is com.vaadin.flow.component.html.Span && it.text == UiTranslations.tr(tab, "referenceData.status.expired") })
        assertFalse(current.children.anyMatch { it is com.vaadin.flow.component.html.Span && it.text == UiTranslations.tr(tab, "referenceData.status.expired") })
    }

    @Test
    fun `status column adds the inactive badge for an inactive rate, and not-selectable only when active`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val inactive = statusColumnComponent(tab, TaxRateDto(isActive = false, isSelectable = false))
        val activeNotSelectable = statusColumnComponent(tab, TaxRateDto(isActive = true, isSelectable = false))
        val activeSelectable = statusColumnComponent(tab, TaxRateDto(isActive = true, isSelectable = true))

        assertTrue(inactive.children.anyMatch { it is com.vaadin.flow.component.html.Span && it.text == UiTranslations.tr(tab, "referenceData.taxRate.status.inactive") })
        assertFalse(
            inactive.children.anyMatch { it is com.vaadin.flow.component.html.Span && it.text == UiTranslations.tr(tab, "referenceData.taxRate.status.notSelectable") },
            "an inactive rate shows only the inactive badge, not the not-selectable one (else-branch)",
        )
        assertTrue(
            activeNotSelectable.children.anyMatch { it is com.vaadin.flow.component.html.Span && it.text == UiTranslations.tr(tab, "referenceData.taxRate.status.notSelectable") },
        )
        assertFalse(
            activeSelectable.children.anyMatch {
                it is com.vaadin.flow.component.html.Span &&
                    (it.text == UiTranslations.tr(tab, "referenceData.taxRate.status.notSelectable") || it.text == UiTranslations.tr(tab, "referenceData.taxRate.status.inactive"))
            },
        )
    }

    // ── isTaxRateActive is the private predicate behind the active-only toggle and the
    // status column's expired badge: both validFrom and validTo each gate independently. ────────

    private fun invokeIsTaxRateActive(tab: TaxRatesTab, rate: TaxRateDto): Boolean {
        val m = TaxRatesTab::class.java.getDeclaredMethod("isTaxRateActive", TaxRateDto::class.java)
        m.isAccessible = true
        return m.invoke(tab, rate) as Boolean
    }

    @Test
    fun `isTaxRateActive is true with no validity bounds at all`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        assertTrue(invokeIsTaxRateActive(tab, TaxRateDto()))
    }

    @Test
    fun `isTaxRateActive is false once validFrom lies in the future`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val rate = TaxRateDto(validFrom = LocalDate.now().plusDays(1))
        assertFalse(invokeIsTaxRateActive(tab, rate))
    }

    @Test
    fun `isTaxRateActive is false once validTo lies in the past`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val rate = TaxRateDto(validTo = LocalDate.now().minusDays(1))
        assertFalse(invokeIsTaxRateActive(tab, rate))
    }

    @Test
    fun `boundary - isTaxRateActive is true once validFrom lies in the past, with no validTo bound`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val rate = TaxRateDto(validFrom = LocalDate.now().minusDays(1))
        assertTrue(invokeIsTaxRateActive(tab, rate), "a validFrom that has already passed must not disqualify the rate")
    }

    @Test
    fun `boundary - isTaxRateActive is true once validTo lies in the future, with no validFrom bound`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val rate = TaxRateDto(validTo = LocalDate.now().plusDays(1))
        assertTrue(invokeIsTaxRateActive(tab, rate), "a validTo that has not yet passed must not disqualify the rate")
    }

    // ── openTaxRateEditor makes the technical code read-only once the rate is in use;
    // dialog CRUD flow for New/Save/Delete, sharing the reflection-based field access already
    // established for the reference-data tabs elsewhere in this package. ──────────────────────

    private inline fun <reified T> declaredField(clazz: Class<*>, tab: Any, name: String): T {
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        return f.get(tab) as T
    }

    private fun openEditor(tab: TaxRatesTab, entity: TaxRateDto?) {
        val m = TaxRatesTab::class.java.getDeclaredMethod("openTaxRateEditor", TaxRateDto::class.java)
        m.isAccessible = true
        m.invoke(tab, entity)
    }

    private fun newButton(tab: TaxRatesTab): Button =
        tab._get<Button> { text = UiTranslations.tr(tab, "referenceData.new") }

    private fun saveButton(tab: TaxRatesTab): Button = declaredField(AbstractCrudView::class.java, tab, "saveButton")
    private fun deleteButton(tab: TaxRatesTab): Button = declaredField(AbstractCrudView::class.java, tab, "deleteButton")
    private fun deactivateButton(tab: TaxRatesTab): Button = declaredField(TaxRatesTab::class.java, tab, "deactivateButton")
    private fun trNameField(tab: TaxRatesTab): TextField = declaredField(TaxRatesTab::class.java, tab, "trNameField")
    private fun trRateField(tab: TaxRatesTab): BigDecimalField = declaredField(TaxRatesTab::class.java, tab, "trRateField")
    private fun trCodeField(tab: TaxRatesTab): TextField = declaredField(TaxRatesTab::class.java, tab, "trCodeField")
    private fun trActiveField(tab: TaxRatesTab): Checkbox = declaredField(TaxRatesTab::class.java, tab, "trActiveField")
    private fun trSelectableField(tab: TaxRatesTab): Checkbox = declaredField(TaxRatesTab::class.java, tab, "trSelectableField")
    private fun trReferenceField(tab: TaxRatesTab): Checkbox = declaredField(TaxRatesTab::class.java, tab, "trReferenceField")

    @Test
    fun `opening the editor for a rate already in use makes the technical code read-only`() {
        whenever(taxRateQueries.isTaxRateInUse(9L)).thenReturn(true)
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val existing = TaxRateDto(id = 9L, name = "Standard")

        openEditor(tab, existing)

        assertTrue(trCodeField(tab).isReadOnly)
    }

    @Test
    fun `opening the editor for an unused rate leaves the technical code editable`() {
        whenever(taxRateQueries.isTaxRateInUse(9L)).thenReturn(false)
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val existing = TaxRateDto(id = 9L, name = "Standard")

        openEditor(tab, existing)

        assertFalse(trCodeField(tab).isReadOnly)
    }

    @Test
    fun `clicking New opens an empty dialog with the delete button disabled`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())

        newButton(tab)._click()

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertFalse(deleteButton(tab).isEnabled)
    }

    @Test
    fun `saving without a name shows a validation error and keeps the dialog open`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageTaxRate, never()).saveTaxRate(any())
    }

    @Test
    fun `saving with a rate above 100 percent shows a validation error and keeps the dialog open`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        trNameField(tab).value = "Ungueltig"
        trRateField(tab).value = BigDecimal("150")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageTaxRate, never()).saveTaxRate(any())
    }

    @Test
    fun `saving a valid new tax rate persists it and closes the dialog`() {
        whenever(manageTaxRate.saveTaxRate(any())).thenAnswer { it.arguments[0] }
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        trNameField(tab).value = "Regelsatz"
        trRateField(tab).value = BigDecimal("19")
        // Code is now required (uq_tax_rate_code / chk_tax_rate_code_not_blank) and a
        // selectable rate (the default) now needs a category (chk_tax_rate_selectable_needs_category).
        trCodeField(tab).value = "DE_STANDARD_19"
        trCategoryField(tab).value = "S"
        trReferenceField(tab).value = true
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageTaxRate).saveTaxRate(org.mockito.kotlin.argThat { name == "Regelsatz" && isReference })
    }

    @Test
    fun `editor reads and saves the reference tax rate flag`() {
        whenever(manageTaxRate.saveTaxRate(any())).thenAnswer { it.arguments[0] }
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val existing = TaxRateDto(
            id = 11L,
            name = "Standard",
            rate = TaxRatePercent(BigDecimal("19")),
            code = "DE_STANDARD_19",
            category = "S",
            isReference = true,
        )
        whenever(taxRateQueries.findTaxRateById(11L)).thenReturn(existing)

        openEditor(tab, existing)

        assertEquals(UiTranslations.tr(tab, "referenceData.taxRate.isReference"), trReferenceField(tab).label)
        assertTrue(trReferenceField(tab).value)
        trReferenceField(tab).value = false
        saveButton(tab)._click()

        verify(manageTaxRate).saveTaxRate(org.mockito.kotlin.argThat { id == 11L && !isReference })
    }

    // ── Client-side validators mirroring DB CHECK constraints that were previously only
    // enforced server-side, surfacing as a raw SQL error instead of a friendly message. ────────

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun notificationText(notification: Notification): String =
        de.littletrotter.ui.toastText(notification)

    @Test
    fun `saving with an empty code is rejected with the required-field message, mirroring uq_tax_rate_code and chk_tax_rate_code_not_blank`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        trNameField(tab).value = "Regelsatz"
        trRateField(tab).value = BigDecimal("19")
        trCategoryField(tab).value = "S"
        // trCodeField is deliberately left empty.
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        assertTrue(trCodeField(tab).isInvalid, "the code field itself must carry the validation error")
        assertEquals(UiTranslations.tr(tab, "referenceData.taxRate.required"), trCodeField(tab).errorMessage)
        verify(manageTaxRate, never()).saveTaxRate(any())
    }

    @Test
    fun `saving selectable with no category is rejected, mirroring chk_tax_rate_selectable_needs_category`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        trNameField(tab).value = "Reduziert"
        trRateField(tab).value = BigDecimal("7")
        trCodeField(tab).value = "DE_REDUCED_7"
        // trSelectableField keeps its dialog default (checked/true); trCategoryField is left
        // unset (null) — exactly the guaranteed-violation state the cross-field check guards against.
        assertTrue(trSelectableField(tab).value, "isSelectable must default to checked for this to reproduce the finding")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        assertTrue(trCategoryField(tab).isInvalid)
        assertEquals(UiTranslations.tr(tab, "referenceData.taxRate.selectableRequiresCategory"), trCategoryField(tab).errorMessage)
        assertTrue(getNotifications().isEmpty())
        verify(manageTaxRate, never()).saveTaxRate(any())
    }

    @Test
    fun `saving selectable with a category chosen is a valid combination`() {
        whenever(manageTaxRate.saveTaxRate(any())).thenAnswer { it.arguments[0] }
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        trNameField(tab).value = "Reduziert"
        trRateField(tab).value = BigDecimal("7")
        trCodeField(tab).value = "DE_REDUCED_7"
        trCategoryField(tab).value = "S"
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        // The saved-success toast is expected here — only the selectable-needs-category error toast must be absent.
        assertTrue(getNotifications().none { notificationText(it) == UiTranslations.tr(tab, "referenceData.taxRate.selectableRequiresCategory") })
        verify(manageTaxRate).saveTaxRate(any())
    }

    @Test
    fun `unchecking selectable with no category is a valid combination (legacy row)`() {
        whenever(manageTaxRate.saveTaxRate(any())).thenAnswer { it.arguments[0] }
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        trNameField(tab).value = "Legacy"
        trRateField(tab).value = BigDecimal("16")
        trCodeField(tab).value = "DE_LEGACY_16"
        trSelectableField(tab).value = false

        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageTaxRate).saveTaxRate(any())
    }

    @Test
    fun `clicking deactivate on a brand-new rate applies immediately without a confirm dialog`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        trActiveField(tab).value = true

        deactivateButton(tab)._click()

        assertFalse(trActiveField(tab).value)
    }

    @Test
    fun `clicking deactivate on an unused existing rate applies immediately without a confirm dialog`() {
        whenever(taxRateQueries.findTaxRateUsageCounts()).thenReturn(emptyMap())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val existing = TaxRateDto(id = 3L, name = "Standard", isActive = true, isSelectable = true)
        openEditor(tab, existing)

        deactivateButton(tab)._click()

        assertFalse(trActiveField(tab).value)
    }

    // ── The Steuerkategorie combo opened with all five options present but blank and
    // unclickable in the browser; getSuggestions()/getSuggestionItems() drive the exact same
    // DataCommunicator fetch path the real client uses (unlike a bare itemLabelGenerator.apply
    // call), so this is the seam that actually reproduces the bug rather than just guessing at it.

    private fun trCategoryField(tab: TaxRatesTab): ComboBox<String> = declaredField<ComboBox<String>>(TaxRatesTab::class.java, tab, "trCategoryField")

    @Test
    fun `the category combo offers a real, non-blank, non-raw-code label for every suggested item`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val combo = trCategoryField(tab)

        val suggestions = combo.getSuggestions()

        assertEquals(4, suggestions.size, "S, AE, E, O - no null sentinel item any more")
        suggestions.forEach { label ->
            assertTrue(label.isNotBlank(), "every suggested label must be non-blank, got: $suggestions")
        }
        assertTrue(
            suggestions.none { it in setOf("S", "AE", "E", "O") },
            "the raw UNTDID 5305 code must never leak as the visible label - i18n keys are missing: $suggestions",
        )
    }

    @Test
    fun `the category combo's item list must not contain a null entry - it corrupts the whole suggestion list`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val combo = trCategoryField(tab)

        val items = combo.getSuggestionItems()

        assertFalse(items.any { it == null }, "a null item mixed into a String-typed ComboBox's items breaks Vaadin's default in-memory item filter (ComboBoxDataController.setDataProvider), which calls itemLabelGenerator.apply(item) unconditionally for every item - unlike generateLabel(), it has no null-guard")
    }

    @Test
    fun `confirming delete on an existing tax rate calls deleteTaxRate and closes the dialog`() {
        whenever(taxRateQueries.isTaxRateInUse(3L)).thenReturn(false)
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val existing = TaxRateDto(id = 3L, name = "Standard")
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        deleteButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageTaxRate).deleteTaxRate(eq(3L))
    }

    // ── trActiveField's own isFromClient-gated listener (deactivateButton's click tests
    // above only ever set .value programmatically) and the grid sort listener (never driven by
    // any test above). ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `boundary - a real client uncheck of active on an in-use rate opens the deactivate-confirm dialog and reverts on cancel`() {
        whenever(taxRateQueries.findTaxRateUsageCounts()).thenReturn(mapOf(7L to 2L))
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        invokeRefreshTaxRateGrid(tab)
        val existing = TaxRateDto(id = 7L, name = "Standard", isActive = true)
        openEditor(tab, existing)

        trActiveField(tab)._setValue(false)

        val dlg = _get<com.vaadin.flow.component.confirmdialog.ConfirmDialog>()
        assertTrue(dlg.isOpened)
        com.vaadin.flow.component.ComponentUtil.fireEvent(
            dlg,
            com.vaadin.flow.component.confirmdialog.ConfirmDialog.CancelEvent(dlg, false),
        )
        assertTrue(trActiveField(tab).value, "cancelling must revert the checkbox back to active")
    }

    @Test
    fun `sorting the grid by name descending re-fetches with the descending sort direction`() {
        whenever(taxRateQueries.findTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())).thenReturn(emptyList())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val grid = declaredField<com.vaadin.flow.component.grid.Grid<TaxRateDto>>(TaxRatesTab::class.java, tab, "taxRateGrid")
        val column = grid.getColumnByKey("name")

        grid.sort(com.vaadin.flow.component.grid.GridSortOrder.desc(column).build())
        grid._fetch(0, 10)

        verify(taxRateQueries, org.mockito.kotlin.atLeastOnce())
            .findTaxRates(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.application.dto.SortDirection.DESC))
    }

    @Test
    fun `boundary - clearing the grid sort resets to the default name-ascending order`() {
        whenever(taxRateQueries.findTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())).thenReturn(emptyList())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val grid = declaredField<com.vaadin.flow.component.grid.Grid<TaxRateDto>>(TaxRatesTab::class.java, tab, "taxRateGrid")
        val column = grid.getColumnByKey("name")
        grid.sort(com.vaadin.flow.component.grid.GridSortOrder.desc(column).build())

        grid.sort(emptyList())
        grid._fetch(0, 10)

        verify(taxRateQueries, org.mockito.kotlin.atLeastOnce())
            .findTaxRates(any(), any(), anyOrNull(), eq(null), eq(de.littletrotter.application.dto.SortDirection.ASC))
    }

    @Test
    fun `sorting the grid by name ascending re-fetches with the ascending sort direction`() {
        whenever(taxRateQueries.findTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())).thenReturn(emptyList())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val grid = declaredField<com.vaadin.flow.component.grid.Grid<TaxRateDto>>(TaxRatesTab::class.java, tab, "taxRateGrid")
        val column = grid.getColumnByKey("name")

        grid.sort(com.vaadin.flow.component.grid.GridSortOrder.asc(column).build())
        grid._fetch(0, 10)

        verify(taxRateQueries, org.mockito.kotlin.atLeastOnce())
            .findTaxRates(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.application.dto.SortDirection.ASC))
    }

    @Test
    fun `boundary - a negative rate fails the rate validator, not just the above-100 boundary`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        trNameField(tab).value = "Ungueltig"
        trRateField(tab).value = BigDecimal("-1")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageTaxRate, never()).saveTaxRate(any())
    }

    @Test
    fun `boundary - a real client uncheck of active on a brand-new, not-yet-saved rate is a no-op`() {
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        openEditor(tab, null)

        trActiveField(tab)._setValue(false)

        assertTrue(_find<com.vaadin.flow.component.confirmdialog.ConfirmDialog>().isEmpty(), "an unsaved rate has no id to deactivate, so no confirm dialog opens")
    }

    @Test
    fun `boundary - installHeaderActions wires the search placeholder`() {
        whenever(taxRateQueries.findTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())).thenReturn(emptyList())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val actionsSlot = com.vaadin.flow.component.html.Div()

        tab.installHeaderActions(actionsSlot)

        val search = actionsSlot.children.toList().filterIsInstance<TextField>().single()
        assertEquals(UiTranslations.tr("referenceData.taxRate.placeholder"), search.placeholder)
    }

    @Test
    fun `boundary - the grid data provider's own size query reaches countTaxRates`() {
        whenever(taxRateQueries.findTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())).thenReturn(emptyList())
        val tab = TaxRatesTab(taxRateQueries, manageTaxRate, java.time.LocalDate.now())
        val grid = declaredField<com.vaadin.flow.component.grid.Grid<TaxRateDto>>(TaxRatesTab::class.java, tab, "taxRateGrid")
        val provider = grid.dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<TaxRateDto, String>>()

        provider.size(com.vaadin.flow.data.provider.Query(0, 10, emptyList(), null, null))

        verify(taxRateQueries, org.mockito.kotlin.atLeastOnce()).countTaxRates(anyOrNull())
    }
}
