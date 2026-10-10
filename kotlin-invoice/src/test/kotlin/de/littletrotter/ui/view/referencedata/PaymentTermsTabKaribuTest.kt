package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.view.invoice.componentFor
import de.littletrotter.ui.view.invoice.requireAs
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getColumnByHeader
import com.github.mvysny.kaributesting.v10._getFormatted
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.application.reference.port.inbound.ManagePaymentTermUseCase
import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

/**
 * Covers the "Verwendung" grid column and the deactivate confirmation (the same pattern
 * generalized to payment terms) alongside the shared header search+counter.
 */
class PaymentTermsTabKaribuTest {

    private val paymentTermQueries: PaymentTermQueryPort = mock()

    private val managePaymentTerm: ManagePaymentTermUseCase = mock()
    private val languageQueries: LanguageQueryPort = mock()

    private fun language(id: Int, code: String, isFallback: Boolean) =
        LanguageDto(id = id, code = code, isFallback = isFallback, sortOrder = id)

    /** A [PaymentTermDto] with a German (fallback) name and, optionally, an English one — the
     *  same read row [de.littletrotter.application.reference.ManagePaymentTerm] hands back after
     *  resolving `PaymentTerm.setName` per language. */
    private fun paymentTermNamed(name: String, id: Long? = null, englishName: String? = null): PaymentTermDto =
        PaymentTermDto(
            id = id,
            namesByLanguageCode = buildMap {
                put("de", name)
                englishName?.let { put("en", it) }
            },
            fallbackLanguageCode = "de",
        )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(paymentTermQueries.findPaymentTerms(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        whenever(paymentTermQueries.countPaymentTerms(anyOrNull())).thenReturn(0L)
        whenever(paymentTermQueries.countInactivePaymentTerms(anyOrNull())).thenReturn(0L)
        // PaymentTermsTab's name field is built from the active-language set; every
        // pre-existing test here only exercises the mandatory fallback (German) field.
        whenever(languageQueries.findActiveLanguages()).thenReturn(listOf(language(1, "de", isFallback = true)))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun invokeUsageCountLabel(tab: PaymentTermsTab, term: PaymentTermDto): String {
        val m = PaymentTermsTab::class.java.getDeclaredMethod("usageCountLabel", PaymentTermDto::class.java)
        m.isAccessible = true
        return m.invoke(tab, term) as String
    }

    private fun invokeDeactivateConfirmCount(tab: PaymentTermsTab, id: Long?): Long? {
        val m = PaymentTermsTab::class.java.getDeclaredMethod("deactivateConfirmCount", Long::class.javaObjectType)
        m.isAccessible = true
        return m.invoke(tab, id) as Long?
    }

    @Test
    fun `the grid's Verwendung column reads the pre-fetched usage-counts map`() {
        whenever(paymentTermQueries.findPaymentTermUsageCounts()).thenReturn(mapOf(1L to 2L))
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        assertEquals("2", invokeUsageCountLabel(tab, PaymentTermDto(id = 1L)))
        assertEquals("0", invokeUsageCountLabel(tab, PaymentTermDto(id = 9L)), "a term absent from the map is unused")
    }

    @Test
    fun `deactivateConfirmCount is null for an unused term - no dialog needed`() {
        whenever(paymentTermQueries.findPaymentTermUsageCounts()).thenReturn(emptyMap())
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        assertNull(invokeDeactivateConfirmCount(tab, 3L))
    }

    @Test
    fun `deactivateConfirmCount is null for a term with no id yet (new, unsaved)`() {
        whenever(paymentTermQueries.findPaymentTermUsageCounts()).thenReturn(mapOf(3L to 5L))
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        assertNull(invokeDeactivateConfirmCount(tab, null))
    }

    @Test
    fun `deactivateConfirmCount names the actual usage count for a term in use`() {
        whenever(paymentTermQueries.findPaymentTermUsageCounts()).thenReturn(mapOf(3L to 5L))
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        assertEquals(5L, invokeDeactivateConfirmCount(tab, 3L))
    }

    // ── Standard column renders the shared star icon, not a text badge ─────────────

    private fun standardColumnComponent(tab: PaymentTermsTab, term: PaymentTermDto): com.vaadin.flow.component.Component {
        val gridField = AbstractReferencedataCrudTab::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(tab).requireAs<com.vaadin.flow.component.grid.Grid<PaymentTermDto>>()
        val column = grid.columns.first { it.headerText == UiTranslations.tr("referenceData.standard") }
        return column.componentFor(term)
    }

    @Test
    fun `the default payment term renders the shared star icon in the Standard column`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        val component = standardColumnComponent(tab, PaymentTermDto(isDefault = true))

        assertTrue(component is Icon, "expected the shared star icon, got ${component::class}")
        assertEquals(UiTranslations.tr("referenceData.defaultBadge"), component.element.getAttribute("aria-label"))
    }

    @Test
    fun `a non-default payment term renders no icon and no text in the Standard column`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        val component = standardColumnComponent(tab, PaymentTermDto(isDefault = false))

        assertFalse(component is Icon, "a non-default term must not render any icon")
        assertEquals("true", component.element.getAttribute("aria-hidden"))
    }

    // ── Grid columns beyond the Standard badge: englishName's orEmpty, formatDiscount's
    // two branches, and the active/inactive text column. ───────────────────────────────────────

    private fun grid(tab: PaymentTermsTab): Grid<PaymentTermDto> {
        val f = AbstractReferencedataCrudTab::class.java.getDeclaredField("grid")
        f.isAccessible = true
        return f.get(tab).requireAs<Grid<PaymentTermDto>>()
    }

    @Test
    fun `englishName column falls back to empty text when null`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val withName = paymentTermNamed("Netto 30", englishName = "Net 30")
        val withoutName = paymentTermNamed("Netto 30")

        assertEquals("Net 30", grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.paymentTerm.englishName"))._getFormatted(withName))
        assertEquals("", grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.paymentTerm.englishName"))._getFormatted(withoutName))
    }

    @Test
    fun `discount column falls back to the em dash unless both a percent and a day count are set`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val noDiscount = PaymentTermDto(discountDays = null, discountPercent = DiscountPercent(BigDecimal.ZERO))
        val zeroPercent = PaymentTermDto(discountDays = 10, discountPercent = DiscountPercent(BigDecimal.ZERO))
        val withDiscount = PaymentTermDto(discountDays = 10, discountPercent = DiscountPercent(BigDecimal("2.00")))

        val column = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.paymentDiscount"))
        assertEquals("—", column._getFormatted(noDiscount))
        assertEquals("—", column._getFormatted(zeroPercent), "a zero percent must not be shown as a real discount")
        assertEquals("2 % / 10 ${UiTranslations.tr("referenceData.days")}", column._getFormatted(withDiscount))
    }

    @Test
    fun `active column shows the localized active or inactive label`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val active = PaymentTermDto(isActive = true)
        val inactive = PaymentTermDto(isActive = false)

        val column = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.paymentTerm.activeColumn"))
        assertEquals(UiTranslations.tr("referenceData.paymentTerm.active"), column._getFormatted(active))
        assertEquals(UiTranslations.tr("referenceData.paymentTerm.inactive"), column._getFormatted(inactive))
    }

    @Test
    fun `name column reads the resolved German fallback name, empty when unset`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val named = paymentTermNamed("Netto 30")
        val unnamed = PaymentTermDto()

        val column = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.name"))
        assertEquals("Netto 30", column._getFormatted(named))
        assertEquals("", column._getFormatted(unnamed))
    }

    @Test
    fun `due-in-days column renders the day count with its localized unit label`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val term = PaymentTermDto(dueInDays = 14)

        val column = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.paymentDueDays"))
        assertEquals("14 ${UiTranslations.tr("referenceData.days")}", column._getFormatted(term))
    }

    @Test
    fun `usage-count column reads the pre-fetched usage-counts map for the row's own id`() {
        whenever(paymentTermQueries.findPaymentTermUsageCounts()).thenReturn(mapOf(7L to 3L))
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        grid(tab)._fetch(0, 10) // populates usageCounts from the mocked facade

        val column = grid(tab)._getColumnByHeader(UiTranslations.tr("referenceData.paymentTerm.usageCount"))
        assertEquals("3", column._getFormatted(PaymentTermDto(id = 7L)))
        assertEquals("0", column._getFormatted(PaymentTermDto(id = 99L)))
    }

    // ── The grid sort listener's three branches: mapped column, unmapped/name column,
    // and cleared sort order resetting to name ascending. ──────────────────────────────────────

    @Test
    fun `sorting by dueInDays refetches with the mapped sort field`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val column = grid(tab).getColumnByKey("name")!!

        grid(tab).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))
        grid(tab)._fetch(0, 10)

        verify(paymentTermQueries, org.mockito.kotlin.atLeastOnce())
            .findPaymentTerms(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.application.dto.SortDirection.DESC))
    }

    @Test
    fun `sorting by name refetches with the mapped sort field`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val column = grid(tab).getColumnByKey("name")!!

        grid(tab).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))
        grid(tab)._fetch(0, 10)

        verify(paymentTermQueries, org.mockito.kotlin.atLeastOnce())
            .findPaymentTerms(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.application.dto.SortDirection.ASC))
    }

    @Test
    fun `clearing the sort order resets to name ascending`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val column = grid(tab).getColumnByKey("name")!!
        grid(tab).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        grid(tab).sort(emptyList())
        grid(tab)._fetch(0, 10)

        verify(paymentTermQueries, org.mockito.kotlin.atLeastOnce())
            .findPaymentTerms(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.application.dto.SortDirection.ASC))
    }

    // ── New/Save/Delete dialog flow, plus validateDiscountCombination's two branches
    // (percent XOR days is invalid; neither or both is valid). ─────────────────────────────────

    private inline fun <reified T> declaredField(clazz: Class<*>, tab: Any, name: String): T {
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        return f.get(tab) as T
    }

    private fun openEditor(tab: PaymentTermsTab, entity: PaymentTermDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(tab, entity)
    }

    private fun newButton(tab: PaymentTermsTab): Button =
        tab._get<Button> { text = UiTranslations.tr("referenceData.new") }

    private fun saveButton(tab: PaymentTermsTab): Button = declaredField(AbstractCrudView::class.java, tab, "saveButton")
    private fun deleteButton(tab: PaymentTermsTab): Button = declaredField(AbstractCrudView::class.java, tab, "deleteButton")
    /** The former fixed `nameField` is now one [TextField] per active language
     *  ([PaymentTermsTab]'s `nameFieldsByLanguage`); every test here only ever needs the
     *  mandatory fallback (German) one. */
    private fun nameField(tab: PaymentTermsTab): TextField {
        val fieldsByLanguage: Map<LanguageDto, TextField> = declaredField(PaymentTermsTab::class.java, tab, "nameFieldsByLanguage")
        return fieldsByLanguage.entries.first { it.key.isFallback }.value
    }
    private fun discountPercentField(tab: PaymentTermsTab): com.vaadin.flow.component.textfield.BigDecimalField =
        declaredField(PaymentTermsTab::class.java, tab, "discountPercentField")
    private fun discountDaysField(tab: PaymentTermsTab): com.vaadin.flow.component.textfield.IntegerField =
        declaredField(PaymentTermsTab::class.java, tab, "discountDaysField")
    private fun dueInDaysField(tab: PaymentTermsTab): com.vaadin.flow.component.textfield.IntegerField =
        declaredField(PaymentTermsTab::class.java, tab, "dueInDaysField")
    private fun activeField(tab: PaymentTermsTab): Checkbox = declaredField(PaymentTermsTab::class.java, tab, "activeField")

    private fun setCurrentFormData(tab: PaymentTermsTab, formData: PaymentTermFormData) {
        val f = PaymentTermsTab::class.java.getDeclaredField("currentFormData")
        f.isAccessible = true
        f.set(tab, formData)
    }

    @Test
    fun `clicking New opens an empty dialog with the delete button disabled`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        newButton(tab)._click()

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertFalse(deleteButton(tab).isEnabled)
    }

    @Test
    fun `saving without a name shows a validation error and keeps the dialog open`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePaymentTerm, never()).savePaymentTerm(any())
    }

    @Test
    fun `saving with only a discount percent and no discount days is rejected as an invalid combination`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        nameField(tab).value = "10 Tage 2 Prozent"
        discountPercentField(tab).value = BigDecimal("2.00")
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePaymentTerm, never()).savePaymentTerm(any())
    }

    @Test
    fun `saving with a negative due-in-days value shows a validation error`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        nameField(tab).value = "Negativ"
        dueInDaysField(tab).value = -5
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePaymentTerm, never()).savePaymentTerm(any())
    }

    @Test
    fun `saving with a discount percent above 100 shows a validation error`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        nameField(tab).value = "Zu viel Skonto"
        discountPercentField(tab).value = BigDecimal("150.00")
        discountDaysField(tab).value = 10
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePaymentTerm, never()).savePaymentTerm(any())
    }

    @Test
    fun `saving with a negative discount days value shows a validation error`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        nameField(tab).value = "Negative Skontotage"
        discountPercentField(tab).value = BigDecimal("2.00")
        discountDaysField(tab).value = -3
        saveButton(tab)._click()

        assertTrue(dialog.isOpened)
        verify(managePaymentTerm, never()).savePaymentTerm(any())
    }

    @Test
    fun `saving a valid new payment term persists it and closes the dialog`() {
        whenever(managePaymentTerm.savePaymentTerm(any())).thenAnswer { it.arguments[0] }
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        nameField(tab).value = "Netto 30"
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(managePaymentTerm).savePaymentTerm(org.mockito.kotlin.argThat { nameFor("de") == "Netto 30" })
    }

    @Test
    fun `saving with both a discount percent and discount days set is a valid combination`() {
        whenever(managePaymentTerm.savePaymentTerm(any())).thenAnswer { it.arguments[0] }
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        nameField(tab).value = "Skonto"
        discountPercentField(tab).value = BigDecimal("2.00")
        discountDaysField(tab).value = 10
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(managePaymentTerm).savePaymentTerm(any())
    }

    @Test
    fun `opening an existing payment term enables the delete button and populates the form`() {
        val existing = paymentTermNamed("Netto 14", id = 4L)
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        openEditor(tab, existing)

        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertTrue(deleteButton(tab).isEnabled)
        assertEquals("Netto 14", nameField(tab).value)
    }

    @Test
    fun `confirming delete on an existing payment term calls deletePaymentTerm and closes the dialog`() {
        val existing = paymentTermNamed("Netto 14", id = 4L)
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        deleteButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(managePaymentTerm).deletePaymentTerm(eq(4L))
    }

    // ── active-checkbox deactivate-confirm listener (isFromClient guard, both early-return
    //    directions, and the confirm/cancel dialog) — same idiom as B2bProfilesTab ──────

    @Test
    fun `a programmatic active-checkbox change opens no confirm dialog - not from a real client`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        setCurrentFormData(tab, PaymentTermFormData(id = 40L))
        activeField(tab).value = true

        activeField(tab).value = false // plain server-side set, isFromClient = false

        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `re-checking active as a real client action opens no confirm dialog - only deactivation warns`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        setCurrentFormData(tab, PaymentTermFormData(id = 41L))
        activeField(tab).value = false

        activeField(tab)._setValue(true)

        assertTrue(_find<ConfirmDialog>().isEmpty(), "checking the box back on must never prompt")
    }

    @Test
    fun `deactivating a payment term with no id yet opens no confirm dialog`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        setCurrentFormData(tab, PaymentTermFormData(id = null))
        activeField(tab).value = true

        activeField(tab)._setValue(false)

        assertTrue(_find<ConfirmDialog>().isEmpty(), "a new, unsaved payment term has nothing to warn about")
    }

    @Test
    fun `deactivating a payment term nobody uses opens no confirm dialog`() {
        whenever(paymentTermQueries.findPaymentTermUsageCounts()).thenReturn(mapOf(42L to 0L))
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        setCurrentFormData(tab, PaymentTermFormData(id = 42L))
        activeField(tab).value = true

        activeField(tab)._setValue(false)

        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `deactivating a payment term in use warns with the usage count and reverts the checkbox on cancel`() {
        whenever(paymentTermQueries.findPaymentTermUsageCounts()).thenReturn(mapOf(43L to 5L))
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        setCurrentFormData(tab, PaymentTermFormData(id = 43L))
        activeField(tab).value = true

        activeField(tab)._setValue(false)

        val dlg = _find<ConfirmDialog>().single()
        assertEquals(
            UiTranslations.tr("referenceData.paymentTerm.deactivateConfirm.title"),
            dlg.element.getProperty("header"),
        )
        dlg.fireCancel()
        assertTrue(activeField(tab).value, "cancelling the confirm dialog must revert the checkbox")
    }

    /** [ConfirmDialog]'s cancel listener has no click-driven public API in a headless test — its
     *  own [ConfirmDialog.CancelEvent] constructor and [com.vaadin.flow.component.ComponentUtil
     *  .fireEvent] are both public, so no reflection is needed to dispatch it. Same idiom as
     *  B2bProfilesTabKaribuTest's identical helper. */
    private fun ConfirmDialog.fireCancel() {
        com.vaadin.flow.component.ComponentUtil.fireEvent(this, ConfirmDialog.CancelEvent(this, false))
    }

    // ── The name field, one per active language (same rigor as the seller-note tab) ──

    private fun newTab(multilingualTextsEnabled: Boolean = false): PaymentTermsTab =
        PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries, de.littletrotter.application.invoice.MultilingualTextsFeatureConfig(multilingualTextsEnabled))

    private fun nameFieldsOf(tab: PaymentTermsTab): Map<LanguageDto, TextField> {
        val f = PaymentTermsTab::class.java.getDeclaredField("nameFieldsByLanguage")
        f.isAccessible = true
        return f.get(tab).requireAs<Map<LanguageDto, TextField>>()
    }

    @Test
    fun `flag off - the English name field is absent from the component tree, not merely hidden`() {
        val tab = newTab(multilingualTextsEnabled = false)

        val fields = nameFieldsOf(tab)

        assertEquals(1, fields.size, "only the German (fallback) field must exist: $fields")
        assertTrue(fields.keys.single().isFallback)
        assertTrue(fields.keys.none { it.code == "en" }, "no English field, not even an invisible one")
    }

    @Test
    fun `flag on - the English name field is present`() {
        whenever(languageQueries.findActiveLanguages()).thenReturn(listOf(language(1, "de", true), language(2, "en", false)))
        val tab = newTab(multilingualTextsEnabled = true)

        val fields = nameFieldsOf(tab)

        assertEquals(2, fields.size)
        assertTrue(fields.keys.any { it.code == "en" })
    }

    @Test
    fun `the name fields carry the 150-character limit`() {
        whenever(languageQueries.findActiveLanguages()).thenReturn(listOf(language(1, "de", true), language(2, "en", false)))
        val tab = newTab(multilingualTextsEnabled = true)

        nameFieldsOf(tab).values.forEach { field -> assertEquals(150, field.maxLength) }
    }

    @Test
    fun `a third active language renders a third name field with no code change`() {
        // The test itself adds the language row — proof that "freely configurable" is real,
        // same idiom as SenderProfileViewKaribuTest's identical seller-note test.
        whenever(languageQueries.findActiveLanguages()).thenReturn(
            listOf(language(1, "de", isFallback = true), language(2, "en", isFallback = false), language(3, "fr", isFallback = false)),
        )

        val tab = newTab(multilingualTextsEnabled = true)

        assertEquals(3, nameFieldsOf(tab).size)
    }

    @Test
    fun `opening an existing bilingual payment term fills both name fields`() {
        whenever(languageQueries.findActiveLanguages()).thenReturn(listOf(language(1, "de", true), language(2, "en", false)))
        val tab = newTab(multilingualTextsEnabled = true)
        val existing = paymentTermNamed("Netto 14", id = 9L, englishName = "Net 14 days")

        openEditor(tab, existing)

        val fields = nameFieldsOf(tab)
        assertEquals("Netto 14", fields.entries.first { it.key.isFallback }.value.value)
        assertEquals("Net 14 days", fields.entries.first { it.key.code == "en" }.value.value)
    }

    @Test
    fun `saving a bilingual payment term persists both the German and English name`() {
        whenever(languageQueries.findActiveLanguages()).thenReturn(listOf(language(1, "de", true), language(2, "en", false)))
        whenever(managePaymentTerm.savePaymentTerm(any())).thenAnswer { it.arguments[0] }
        val tab = newTab(multilingualTextsEnabled = true)
        newButton(tab)._click()
        val dialog = _get<Dialog>()

        val fields = nameFieldsOf(tab)
        fields.entries.first { it.key.isFallback }.value.value = "Netto 30"
        fields.entries.first { it.key.code == "en" }.value.value = "Net 30 days"
        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(managePaymentTerm).savePaymentTerm(
            org.mockito.kotlin.argThat { nameFor("de") == "Netto 30" && nameFor("en") == "Net 30 days" },
        )
    }

    // ── installHeaderActions (search field + entry counter), only wired by the real
    // ReferenceDataView host, never called by any test above that constructs the tab standalone. ──

    @Test
    fun `installHeaderActions wires the search placeholder and filters the grid on typing`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val actionsSlot = com.vaadin.flow.component.html.Div()

        tab.installHeaderActions(actionsSlot)
        val search = actionsSlot.children.toList().filterIsInstance<TextField>().single()
        assertEquals(UiTranslations.tr("referenceData.paymentTerm.placeholder"), search.placeholder)

        search.value = "netto"
        grid(tab)._fetch(0, 10)

        verify(paymentTermQueries, org.mockito.kotlin.atLeastOnce()).findPaymentTerms(any(), any(), eq("netto"), any(), any())
    }

    @Test
    fun `the grid data provider's own size query reaches countPaymentTerms`() {
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        val provider = grid(tab).dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<PaymentTermDto, String>>()

        provider.size(com.vaadin.flow.data.provider.Query(0, 10, emptyList(), null, null))

        verify(paymentTermQueries, org.mockito.kotlin.atLeastOnce()).countPaymentTerms(anyOrNull())
    }

    @Test
    fun `boundary - saving an existing payment term re-fetches it by id before applying changes`() {
        val existing = paymentTermNamed("Netto 14", id = 12L)
        whenever(paymentTermQueries.findPaymentTermById(12L)).thenReturn(existing)
        whenever(managePaymentTerm.savePaymentTerm(any())).thenAnswer { it.arguments[0] }
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)
        openEditor(tab, existing)
        val dialog = _get<Dialog>()

        saveButton(tab)._click()

        assertFalse(dialog.isOpened)
        verify(paymentTermQueries).findPaymentTermById(12L)
        verify(managePaymentTerm).savePaymentTerm(any())
    }

    @Test
    fun `boundary - a non-fallback language field is not rendered when the multilingual feature flag is disabled`() {
        whenever(languageQueries.findActiveLanguages()).thenReturn(listOf(language(1, "de", true), language(2, "en", false)))
        val tab = newTab(multilingualTextsEnabled = false)

        assertEquals(1, nameFieldsOf(tab).size, "only the mandatory fallback field renders while the flag is disabled")
    }

    // ── usageCountLabel's term.id null branch, and writeValidatedForm's fallbackField
    // null branch (no active language at all, so nameFieldsByLanguage has no fallback entry). ────

    @Test
    fun `usage-count label reads zero for a term with no id yet`() {
        whenever(paymentTermQueries.findPaymentTermUsageCounts()).thenReturn(mapOf(1L to 2L))
        val tab = PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries)

        assertEquals("0", invokeUsageCountLabel(tab, PaymentTermDto()), "a term with no id yet can never be looked up in usageCounts")
    }

    @Test
    fun `saving is rejected with a required-name error when no active language exists`() {
        whenever(languageQueries.findActiveLanguages()).thenReturn(emptyList())
        val tab = newTab()
        newButton(tab)._click()
        val dialog = _get<Dialog>()
        dueInDaysField(tab).value = 5

        saveButton(tab)._click()

        assertTrue(dialog.isOpened, "no fallback name field can ever be filled, so save must be rejected")
        verify(managePaymentTerm, never()).savePaymentTerm(any())
    }
}
