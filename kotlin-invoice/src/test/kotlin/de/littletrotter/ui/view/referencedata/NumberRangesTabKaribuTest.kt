package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.view.invoice.componentFor
import de.littletrotter.ui.view.invoice.requireAs
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getColumnByHeader
import com.github.mvysny.kaributesting.v10._getFormatted
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.ManageNumberRangeUseCase
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
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

/**
 * RDS-1 — the Standard column (`createDefaultBadge` shared helper) renders the star icon for the
 * default row and nothing for every other row. No test file existed for this tab before this task.
 */
class NumberRangesTabKaribuTest {

    private val numberRangeQueries: NumberRangeQueryPort = mock()

    private val manageNumberRange: ManageNumberRangeUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(numberRangeQueries.findNumberRanges(any(), any(), anyOrNull())).thenReturn(emptyList())
        whenever(numberRangeQueries.countNumberRanges(anyOrNull())).thenReturn(0L)
        whenever(numberRangeQueries.countInactiveNumberRanges(anyOrNull())).thenReturn(0L)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun standardColumnComponent(tab: NumberRangesTab, range: NumberRangeDto): Component {
        val gridField = AbstractReferencedataCrudTab::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(tab).requireAs<com.vaadin.flow.component.grid.Grid<NumberRangeDto>>()
        val column = grid.columns.first { it.headerText == UiTranslations.tr("referenceData.standard") }
        return column.componentFor(range)
    }

    @Test
    fun `the default number range renders the shared star icon in the Standard column`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))

        val component = standardColumnComponent(tab, NumberRangeDto(isDefault = true))

        assertTrue(component is Icon, "expected the shared star icon, got ${component::class}")
        assertEquals(UiTranslations.tr("referenceData.defaultBadge"), component.element.getAttribute("aria-label"))
    }

    @Test
    fun `a non-default number range renders no icon and no text in the Standard column`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))

        val component = standardColumnComponent(tab, NumberRangeDto(isDefault = false))

        assertFalse(component is Icon, "a non-default range must not render any icon")
        assertEquals("true", component.element.getAttribute("aria-hidden"))
    }

    @Test
    fun `number range dropdown uses catalogue wording instead of a profile product noun`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        assertEquals("Leistungsverzeichnis", enumLabel(tab, NumberRangeSubject.CATALOGUE))
        assertEquals("Positionskategorie", enumLabel(tab, PrefixDataField.CATALOGUE_CATEGORY))
    }

    // ── enumLabel is one exhaustive `when` per enum; every constant is exercised once
    // via reflection instead of driving each ComboBox's item-label generator through the UI. ────

    private fun <E : Enum<E>> enumLabel(tab: NumberRangesTab, value: E): String {
        val m = NumberRangesTab::class.java.getDeclaredMethod("enumLabel", value.javaClass.let {
            // the declaring enum class, not the anonymous subclass Kotlin may generate for entries with bodies
            var c: Class<*> = it
            while (!c.isEnum) c = c.superclass
            c
        })
        m.isAccessible = true
        return m.invoke(tab, value) as String
    }

    @Test
    fun `enumLabel returns a non-blank translation for every NumberRangeSubject`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        NumberRangeSubject.entries.forEach { assertFalse(enumLabel(tab, it).isBlank(), "no label for $it") }
    }

    @Test
    fun `enumLabel returns a non-blank translation for every PrefixSource`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        PrefixSource.entries.forEach { assertFalse(enumLabel(tab, it).isBlank(), "no label for $it") }
    }

    @Test
    fun `enumLabel returns a non-blank translation for every PrefixDataField`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        PrefixDataField.entries.forEach { assertFalse(enumLabel(tab, it).isBlank(), "no label for $it") }
    }

    @Test
    fun `enumLabel returns a non-blank translation for every DatePart`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        DatePart.entries.forEach { assertFalse(enumLabel(tab, it).isBlank(), "no label for $it") }
    }

    @Test
    fun `enumLabel returns a non-blank translation for every ResetCycle`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        ResetCycle.entries.forEach { assertFalse(enumLabel(tab, it).isBlank(), "no label for $it") }
    }

    @Test
    fun `enumLabel returns a non-blank translation for every CounterScope`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        CounterScope.entries.forEach { assertFalse(enumLabel(tab, it).isBlank(), "no label for $it") }
    }

    @Test
    fun `enumLabel returns a non-blank translation for every Continuity`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        Continuity.entries.forEach { assertFalse(enumLabel(tab, it).isBlank(), "no label for $it") }
    }

    // ── exampleNumber — one branch per PrefixSource ─────────────────────────────

    private fun exampleNumber(tab: NumberRangesTab, range: NumberRangeDto): String = tab.exampleNumber(range)

    @Test
    fun `exampleNumber with no prefix source has no prefix segment`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val range = NumberRangeDto(prefixSource = PrefixSource.NONE, datePart = DatePart.NONE, startValue = 7, counterWidth = 4)

        assertEquals("0007", exampleNumber(tab, range))
    }

    @Test
    fun `exampleNumber with a fixed prefix source prepends the configured prefix`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val range = NumberRangeDto(
            prefixSource = PrefixSource.FIXED,
            fixedPrefix = "INV",
            datePart = DatePart.NONE,
            startValue = 7,
            counterWidth = 4,
        )

        assertEquals("INV-0007", exampleNumber(tab, range))
    }

    @Test
    fun `exampleNumber with a seller-profile prefix source uses the placeholder translation`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val range = NumberRangeDto(
            prefixSource = PrefixSource.SELLER_PROFILE,
            datePart = DatePart.NONE,
            startValue = 1,
            counterWidth = 3,
        )

        assertTrue(exampleNumber(tab, range).startsWith(UiTranslations.tr("referenceData.numberRange.exampleSellerPrefix")))
    }

    @Test
    fun `exampleNumber with a from-data prefix source uses the placeholder translation`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val range = NumberRangeDto(
            prefixSource = PrefixSource.FROM_DATA,
            datePart = DatePart.YEAR,
            startValue = 1,
            counterWidth = 3,
        )

        assertTrue(exampleNumber(tab, range).startsWith(UiTranslations.tr("referenceData.numberRange.exampleDataPrefix")))
    }

    // ── grid columns ─────────────────────────────────────────────────────────

    @Test
    fun `counter column shows the current value when the facade has one`() {
        whenever(numberRangeQueries.numberRangeCurrentValue(NumberRangeSubject.INVOICE)).thenReturn(42)
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val range = NumberRangeDto(subject = NumberRangeSubject.INVOICE)

        val text = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.numberRange.counter"))._getFormatted(range)

        assertEquals("42", text)
    }

    @Test
    fun `counter column falls back to the em dash when the facade has no current value`() {
        whenever(numberRangeQueries.numberRangeCurrentValue(NumberRangeSubject.CUSTOMER)).thenReturn(null)
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val range = NumberRangeDto(subject = NumberRangeSubject.CUSTOMER)

        val text = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.numberRange.counter"))._getFormatted(range)

        assertEquals("—", text)
    }

    @Test
    fun `active column shows the localized active or inactive label`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val active = NumberRangeDto(isActive = true)
        val inactive = NumberRangeDto(isActive = false)

        val activeText = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.paymentTerm.activeColumn"))._getFormatted(active)
        val inactiveText = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.paymentTerm.activeColumn"))._getFormatted(inactive)

        assertEquals(UiTranslations.tr("referenceData.paymentTerm.active"), activeText)
        assertEquals(UiTranslations.tr("referenceData.paymentTerm.inactive"), inactiveText)
    }

    private fun grid(tab: NumberRangesTab): com.vaadin.flow.component.grid.Grid<NumberRangeDto> {
        val gridField = AbstractReferencedataCrudTab::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        return gridField.get(tab).requireAs<com.vaadin.flow.component.grid.Grid<NumberRangeDto>>()
    }

    // ── new / save / delete ──────────────────────────────────────────────────

    private inline fun <reified T> declaredField(clazz: Class<*>, tab: Any, name: String): T {
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        return f.get(tab) as T
    }

    private fun openEditor(tab: NumberRangesTab, entity: NumberRangeDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(tab, entity)
    }

    private fun newButton(tab: NumberRangesTab): Button =
        tab._get<Button> { text = UiTranslations.tr("referenceData.new") }

    private fun saveButton(tab: NumberRangesTab): Button = declaredField(AbstractCrudView::class.java, tab, "saveButton")
    private fun deleteButton(tab: NumberRangesTab): Button = declaredField(AbstractCrudView::class.java, tab, "deleteButton")
    private fun codeField(tab: NumberRangesTab): TextField = declaredField(NumberRangesTab::class.java, tab, "codeField")
    private fun displayNameField(tab: NumberRangesTab): TextField = declaredField(NumberRangesTab::class.java, tab, "displayNameField")

    private fun prefixSourceField(tab: NumberRangesTab): com.vaadin.flow.component.combobox.ComboBox<PrefixSource> =
        declaredField(NumberRangesTab::class.java, tab, "prefixSourceField")

    private fun fixedPrefixField(tab: NumberRangesTab): TextField =
        declaredField(NumberRangesTab::class.java, tab, "fixedPrefixField")

    private fun prefixDataField(tab: NumberRangesTab): com.vaadin.flow.component.combobox.ComboBox<PrefixDataField> =
        declaredField(NumberRangesTab::class.java, tab, "prefixDataFieldField")

    private fun prefixDataFieldsFor(tab: NumberRangesTab, subject: NumberRangeSubject): List<PrefixDataField> {
        val method = NumberRangesTab::class.java.getDeclaredMethod("prefixDataFieldsFor", NumberRangeSubject::class.java)
        method.isAccessible = true
        return method.invoke(tab, subject).requireAs<List<PrefixDataField>>()
    }

    @Test
    fun `number range editor limits prefix data choices to the selected subject`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))

        assertEquals(listOf(PrefixDataField.CATALOGUE_CATEGORY), prefixDataFieldsFor(tab, NumberRangeSubject.CATALOGUE))
        assertEquals(
            listOf(PrefixDataField.NAME_INITIALS, PrefixDataField.COUNTRY_CODE),
            prefixDataFieldsFor(tab, NumberRangeSubject.CUSTOMER),
        )
        assertEquals(PrefixDataField.entries.toList(), prefixDataFieldsFor(tab, NumberRangeSubject.INVOICE))
    }

    @Test
    fun `number range editor only shows the input belonging to the selected prefix source`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))

        prefixSourceField(tab).value = PrefixSource.FIXED
        assertTrue(fixedPrefixField(tab).isVisible)
        assertFalse(prefixDataField(tab).isVisible)

        prefixSourceField(tab).value = PrefixSource.FROM_DATA
        assertFalse(fixedPrefixField(tab).isVisible)
        assertTrue(prefixDataField(tab).isVisible)

        prefixSourceField(tab).value = PrefixSource.NONE
        assertFalse(fixedPrefixField(tab).isVisible)
        assertFalse(prefixDataField(tab).isVisible)
    }

    private fun subjectField(tab: NumberRangesTab): com.vaadin.flow.component.combobox.ComboBox<NumberRangeSubject> =
        declaredField(NumberRangesTab::class.java, tab, "subjectField")

    private fun scopeField(tab: NumberRangesTab): com.vaadin.flow.component.combobox.ComboBox<CounterScope> =
        declaredField(NumberRangesTab::class.java, tab, "scopeField")

    private fun continuityField(tab: NumberRangesTab): com.vaadin.flow.component.combobox.ComboBox<Continuity> =
        declaredField(NumberRangesTab::class.java, tab, "continuityField")

    private fun quoteHint(tab: NumberRangesTab): com.vaadin.flow.component.html.Span =
        declaredField(NumberRangesTab::class.java, tab, "quoteCounterHint")

    private fun subjectItems(tab: NumberRangesTab): List<NumberRangeSubject> =
        subjectField(tab).listDataView.items.toList()

    @Test
    fun `the quote subject is offered only while the offer module is enabled`() {
        assertFalse(NumberRangeSubject.QUOTE in subjectItems(NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))))
        assertTrue(NumberRangeSubject.QUOTE in subjectItems(NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(true))))
    }

    @Test
    fun `selecting the quote subject restricts prefix source, fixes scope and continuity and shows the hint`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(true))
        prefixSourceField(tab).value = PrefixSource.FROM_DATA

        subjectField(tab).value = NumberRangeSubject.QUOTE

        assertEquals(listOf(PrefixSource.NONE, PrefixSource.FIXED), prefixSourceField(tab).listDataView.items.toList())
        assertEquals(PrefixSource.FIXED, prefixSourceField(tab).value)
        assertEquals(CounterScope.GLOBAL, scopeField(tab).value)
        assertEquals(Continuity.GAPLESS, continuityField(tab).value)
        assertFalse(scopeField(tab).isEnabled)
        assertFalse(continuityField(tab).isEnabled)
        assertTrue(quoteHint(tab).isVisible)
        assertEquals(UiTranslations.tr("referenceData.numberRange.quoteCounterHint"), quoteHint(tab).text)
    }

    @Test
    fun `leaving the quote subject restores the unrestricted fields`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(true))
        subjectField(tab).value = NumberRangeSubject.QUOTE

        subjectField(tab).value = NumberRangeSubject.CUSTOMER

        assertEquals(PrefixSource.entries.toList(), prefixSourceField(tab).listDataView.items.toList())
        assertTrue(scopeField(tab).isEnabled)
        assertTrue(continuityField(tab).isEnabled)
        assertFalse(quoteHint(tab).isVisible)
    }

    @Test
    fun `clicking New opens an empty dialog with the delete button disabled`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))

        newButton(tab)._click()

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertFalse(deleteButton(tab).isEnabled)
    }

    @Test
    fun `saving without a code shows a validation error and keeps the dialog open`() {
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        displayNameField(tab).value = "Rechnung Standard"
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(manageNumberRange, never()).saveNumberRange(any())
    }

    @Test
    fun `saving a valid new number range persists it and closes the dialog`() {
        whenever(manageNumberRange.saveNumberRange(any())).thenAnswer { it.arguments[0] }
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        codeField(tab).value = "INVOICE_DEFAULT"
        displayNameField(tab).value = "Rechnung Standard"
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageNumberRange).saveNumberRange(org.mockito.kotlin.argThat { code == "INVOICE_DEFAULT" && displayName == "Rechnung Standard" })
    }

    @Test
    fun `opening an existing number range enables the delete button and populates the form`() {
        val existing = NumberRangeDto(id = 5L, code = "CUSTOMER_DEFAULT", displayName = "Kunde Standard")
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))

        openEditor(tab, existing)

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertTrue(deleteButton(tab).isEnabled)
        assertEquals("CUSTOMER_DEFAULT", codeField(tab).value)
    }

    @Test
    fun `confirming delete on an existing number range calls deleteNumberRange and closes the dialog`() {
        val existing = NumberRangeDto(id = 5L, code = "CUSTOMER_DEFAULT", displayName = "Kunde Standard")
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        deleteButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(manageNumberRange).deleteNumberRange(eq(5L))
    }

    // ── No test above ever calls _fetch/size on this tab's grid (it has no sortable
    // column to drive one), so the DataProvider's fetch/count/filterSupplier lambdas, the
    // edit-existing save flow (findEntityById) and installHeaderActions/getSearchPlaceholder are
    // all unexercised by every test above. ────────────────────────────────────────────────────

    @Test
    fun `saving an edited existing number range reloads it through findEntityById`() {
        val existing = NumberRangeDto(id = 6L, code = "INV", displayName = "Rechnungen")
        whenever(numberRangeQueries.findNumberRangeById(6L)).thenReturn(existing)
        whenever(manageNumberRange.saveNumberRange(any())).thenAnswer { it.arguments[0] }
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(numberRangeQueries).findNumberRangeById(6L)
        verify(manageNumberRange).saveNumberRange(any())
    }

    @Test
    fun `the grid data provider's own fetch and size queries reach the facade`() {
        whenever(numberRangeQueries.findNumberRanges(any(), any(), anyOrNull())).thenReturn(emptyList())
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val provider = grid(tab).dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<NumberRangeDto, String>>()
        val query = com.vaadin.flow.data.provider.Query<NumberRangeDto, String>(0, 10, emptyList(), null, null)

        provider.fetch(query).toList()
        provider.size(query)

        verify(numberRangeQueries, org.mockito.kotlin.atLeastOnce()).findNumberRanges(any(), any(), anyOrNull())
        verify(numberRangeQueries, org.mockito.kotlin.atLeastOnce()).countNumberRanges(anyOrNull())
    }

    @Test
    fun `installHeaderActions wires the search placeholder and filters the grid`() {
        whenever(numberRangeQueries.findNumberRanges(any(), any(), anyOrNull())).thenReturn(emptyList())
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))
        val actionsSlot = com.vaadin.flow.component.html.Div()

        tab.installHeaderActions(actionsSlot)
        val search = actionsSlot.children.toList().filterIsInstance<TextField>().single()
        assertEquals(UiTranslations.tr("referenceData.numberRange.placeholder"), search.placeholder)

        search.value = "rechnung"
        val provider = grid(tab).dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<NumberRangeDto, String>>()
        provider.fetch(com.vaadin.flow.data.provider.Query(0, 10, emptyList(), null, null)).toList()

        verify(numberRangeQueries, org.mockito.kotlin.atLeastOnce()).findNumberRanges(any(), any(), eq("rechnung"))
    }

    private fun invokeDelete(tab: NumberRangesTab, id: Long) {
        val m = NumberRangesTab::class.java.getDeclaredMethod("deleteEntityById", Long::class.javaPrimitiveType)
        m.isAccessible = true
        try {
            m.invoke(tab, id)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `delete of a range that issued numbers is blocked with the defined message and skips the manage port`() {
        whenever(numberRangeQueries.isNumberRangeInUse(4L)).thenReturn(true)
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))

        val ex = org.junit.jupiter.api.assertThrows<de.littletrotter.domain.exception.InvalidParameterException> { invokeDelete(tab, 4L) }

        assertEquals(UiTranslations.tr(tab, "referenceData.numberRange.deleteBlocked"), ex.message)
        verify(manageNumberRange, never()).deleteNumberRange(any())
    }

    @Test
    fun `delete of an unused range calls the manage port`() {
        whenever(numberRangeQueries.isNumberRangeInUse(5L)).thenReturn(false)
        val tab = NumberRangesTab(numberRangeQueries, manageNumberRange, QuoteAvailability(false))

        invokeDelete(tab, 5L)

        verify(manageNumberRange).deleteNumberRange(5L)
    }
}
