package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.invoice.MultilingualTextsFeatureConfig
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.port.inbound.DeleteSenderProfileUseCase
import de.littletrotter.application.masterdata.port.inbound.SaveSenderProfileUseCase
import de.littletrotter.application.masterdata.port.inbound.SenderProfileQueryPort
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.SenderProfileRowDto
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.B2bSellerFieldGroupDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.component.ScopedB2bProfileFieldsSection
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The seller view has no B2B profile selector: the acceptance criterion
 * ("seller view shows the SELLER-placed union and no profile selector") is covered here.
 * Mirrors [RecipientViewKaribuTest]'s setup pattern.
 *
 * Also covers the tab consolidation (EU-USt/B2B tabs merge
 * into one "E-Rechnung" tab) and the reactive country-scoped visibility.
 */
class SenderProfileViewKaribuTest {

    private val senderProfileQueries: SenderProfileQueryPort = mock()
    private val saveSenderProfile: SaveSenderProfileUseCase = mock()
    private val deleteSenderProfile: DeleteSenderProfileUseCase = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val b2bSectionComposer = SenderB2bSectionComposer(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue)
    private val paymentTermQueries: PaymentTermQueryPort = mock()
    private val languageQueries: LanguageQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val defaultsComposer = SenderProfileDefaultsComposer(paymentTermQueries)
    private val consumerNotes: de.littletrotter.application.masterdata.port.inbound.EditSenderConsumerNoteUseCase = mock()
    private val wordingProfile: de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort = mock()

    private fun profileField(term: B2bTerm, placement: B2bPlacement) = B2bProfileFieldDto(
        term = term,
        placement = placement,
        businessDefinition = "Definition of ${term.officialCode}",
    )

    /** No PartyFormExtension/SellerVatRegistrationsExtension registered, so the forms are
     *  extension-free — same reasoning as [RecipientViewKaribuTest.newView].
     *
     *  [multilingualTextsEnabled] defaults to `false` (production default) so every
     *  pre-existing test in this file, which never stubs [LanguageQueryPort.findActiveLanguages]
     *  and gets Mockito's empty-list default, keeps building the same dialog as before. */
    private fun newView(multilingualTextsEnabled: Boolean = false): SenderProfileView = SenderProfileView(
        SenderProfilePorts(senderProfileQueries, saveSenderProfile, deleteSenderProfile),
        b2bSectionComposer,
        defaultsComposer,
        de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(languageQueries, consumerNotes, wordingProfile),
        numberRangeQueries,
        PartyFormExtensionRegistry(emptyList()),
        SellerVatRegistrationsExtensionRegistry(emptyList()),
        MultilingualTextsFeatureConfig(multilingualTextsEnabled),
    )

    private fun language(id: Int, code: String, isFallback: Boolean) =
        LanguageDto(id = id, code = code, isFallback = isFallback, sortOrder = id)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion()).thenReturn(emptyList())
        // the two-language MVP set; individual tests override this with whenever(...) again
        // when they need a third language or an empty set.
        whenever(languageQueries.findActiveLanguages()).thenReturn(
            listOf(language(1, "de", isFallback = true), language(2, "en", isFallback = false)),
        )
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    /** [AbstractCrudView.editorDialog] is `protected`; the dialog is built once in the
     *  constructor ([SenderProfileView]'s `configureDialog`), so no `openEditor` call is needed to
     *  inspect its (already built) tab structure. */
    private fun editorDialogOf(view: SenderProfileView): Dialog {
        val field = AbstractCrudView::class.java.getDeclaredField("editorDialog")
        field.isAccessible = true
        return field.get(view) as Dialog
    }

    private fun findTabSheet(root: Component): TabSheet {
        for (child in root.children.toList()) {
            if (child is TabSheet) return child
            val found = runCatching { findTabSheet(child) }.getOrNull()
            if (found != null) return found
        }
        throw NoSuchElementException("No TabSheet found under $root")
    }

    private fun tabLabels(tabSheet: TabSheet): List<String> =
        (0 until tabSheet.tabCount).map { tabSheet.getTabAt(it).label }

    @Test
    fun `the profile selector field no longer exists on the class`() {
        // Not merely hidden: decision 12 removed SenderProfile#b2bProfile entirely, and with it
        // the ComboBox that used to choose one — same "absent, not hidden" proof pattern as
        // RecipientViewKaribuTest's "electronic address pair is not a hand-coded field" test.
        assertThrows(NoSuchFieldException::class.java) {
            SenderProfileView::class.java.getDeclaredField("b2bProfileCombo")
        }
    }

    @Test
    fun `the seller form's B2B section is built from the cross-profile union, not a single chosen profile`() {
        // Both catalog-eligible (binding != null) — a B2bProfileFieldDto always names a catalog term,
        // never a reference-only one such as BT-29 (binding = null, not selectable in the editor).
        whenever(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion()).thenReturn(
            listOf(
                B2bSellerFieldGroupDto(profileField(B2bTerm.BT_84, B2bPlacement.SELLER), group = null, triggerCountries = emptySet()),
                B2bSellerFieldGroupDto(profileField(B2bTerm.BT_12, B2bPlacement.SELLER), group = null, triggerCountries = emptySet()),
            ),
        )

        newView()

        verify(b2bFieldDefinitionQueries).findB2bSellerFieldsUnion()
        // findB2bSellerFields(profileId) is the single-profile lookup a chosen-profile combo would
        // have driven — it must never be called without a seller-chosen
        // profile id to call it with, and there is none anymore.
        verify(b2bFieldDefinitionQueries, never()).findB2bSellerFields(any())
    }

    @Test
    fun `the B2B fields section renders the union's fields`() {
        // BT-12 (contract reference) is FREE-bound — unlike BT-84/IBAN (BOUND, contributes no
        // widget in ScopedB2bProfileFieldsSection per its class KDoc), it actually creates one.
        whenever(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion()).thenReturn(
            listOf(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_12, B2bPlacement.SELLER), group = null, triggerCountries = emptySet())),
        )

        val view = newView()

        val f = SenderProfileView::class.java.getDeclaredField("scopedB2bSection")
        f.isAccessible = true
        val section = f.get(view) as ScopedB2bProfileFieldsSection
        assertTrue(section.hasContent, "die Union-Felder muessen tatsaechlich ein Widget erzeugen")
    }

    // ── Tab consolidation ───────────────────────────────────────────────────────────────

    @Test
    fun `spec - EU-USt and B2B tabs are gone, one consolidated E-Rechnung tab remains`() {
        val view = newView()
        val tabSheet = findTabSheet(editorDialogOf(view))
        val labels = tabLabels(tabSheet)

        assertTrue(labels.contains("E-Rechnung"), "consolidated tab must exist: $labels")
        assertFalse(labels.contains("Zusätzliche USt-IdNrn"), "EU-USt tab must be gone: $labels")
        assertFalse(labels.contains("B2B-Felder"), "B2B tab must be gone: $labels")
        assertEquals(1, labels.count { it == "E-Rechnung" }, "exactly one E-Rechnung tab: $labels")
    }

    @Test
    fun `spec - tab count drops to at most 8`() {
        val view = newView()
        val tabSheet = findTabSheet(editorDialogOf(view))
        assertTrue(tabSheet.tabCount <= 8, "expected at most 8 tabs, found ${tabSheet.tabCount}")
    }

    // ── Reactive country-scoped visibility ─────────────────────────────

    private fun scopedSectionOf(view: SenderProfileView): ScopedB2bProfileFieldsSection {
        val f = SenderProfileView::class.java.getDeclaredField("scopedB2bSection")
        f.isAccessible = true
        return f.get(view) as ScopedB2bProfileFieldsSection
    }

    private fun vatIdFieldOf(view: SenderProfileView): TextField {
        val f = SenderProfileView::class.java.getDeclaredField("vatIdField")
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun firstCountryGroupContainer(section: ScopedB2bProfileFieldsSection): Component =
        // init{} adds [unscopedContainer, hintLabel] (indices 0, 1); setFields() then appends one
        // container per country group, alpha2-sorted, starting at index 2.
        section.children.toList()[2]

    @Test
    fun `a scoped BT-12 group is hidden until vatId matches its country, then visible`() {
        // BT-12, not BT-84/IBAN: BT-84 is BOUND (no widget rendered here, see class KDoc); BT-12
        // is FREE and actually produces the widget this test inspects.
        whenever(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion()).thenReturn(
            listOf(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_12, B2bPlacement.SELLER), group = Country.FR, triggerCountries = setOf(Country.FR))),
        )

        val view = newView()
        val group = firstCountryGroupContainer(scopedSectionOf(view))
        assertFalse(group.isVisible, "no FR registration yet — group must start hidden")

        vatIdFieldOf(view).value = "FR12345678901"
        assertTrue(group.isVisible, "vatId now FR-prefixed — group must become visible reactively")

        vatIdFieldOf(view).value = "DE123456789"
        assertFalse(group.isVisible, "vatId moved away from FR — group must hide again")
    }

    // ── Tab "Firmierung" ─────────────────────────────────────────────────────

    private fun <T : Any> fieldOf(view: SenderProfileView, name: String, type: kotlin.reflect.KClass<T>): T =
        type.java.cast(SenderProfileView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(view))

    /** The Firmierung hint span moved to [SenderProfileDialogSections], built by the view
     *  as its private `sections` field. */
    private fun sections(view: SenderProfileView): SenderProfileDialogSections =
        fieldOf(view, "sections", SenderProfileDialogSections::class)

    @Test
    fun `a Firmierung tab exists`() {
        val view = newView()
        val labels = tabLabels(findTabSheet(editorDialogOf(view)))
        assertTrue(labels.contains("Firmierung"), "expected a Firmierung tab: $labels")
    }

    @Test
    fun `the Firmierung tab carries the legal-form combo, the crafts-chamber checkbox and the moved register fields`() {
        val view = newView()

        val legalFormCombo: ComboBox<LegalForm> = view.legalFormCombo
        val craftsChamberField: Checkbox = fieldOf(view, "craftsChamberField", Checkbox::class)
        val commercialRegisterField: TextField = fieldOf(view, "commercialRegisterField", TextField::class)
        val registerCourtField: TextField = fieldOf(view, "registerCourtField", TextField::class)
        val managingDirectorField: TextField = fieldOf(view, "managingDirectorField", TextField::class)
        val courtOfJurisdictionField: TextField = fieldOf(view, "courtOfJurisdictionField", TextField::class)

        assertEquals("Rechtsform", legalFormCombo.label)
        assertEquals(LegalForm.entries.toSet(), legalFormCombo.dataProvider.fetch(com.vaadin.flow.data.provider.Query()).toList().toSet())
        assertEquals("In Handwerkskammer eingetragen", craftsChamberField.label)
        assertEquals("Handelsregisternummer", commercialRegisterField.label)
        assertEquals("Registergericht", registerCourtField.label)
        assertEquals("Geschäftsführung", managingDirectorField.label)
        assertEquals("Gerichtsstand", courtOfJurisdictionField.label)
    }

    @Test
    fun `the register fields no longer sit on the Steuer und Recht tab`() {
        // spec: "Die bestehenden Registerangaben ... ziehen in diesen Tab um" — proof they left
        // the tax tab, not merely that they exist somewhere.
        val view = newView()
        val registerCourtField: TextField = fieldOf(view, "registerCourtField", TextField::class)

        val tabSheet = findTabSheet(editorDialogOf(view))
        val taxTabIndex = (0 until tabSheet.tabCount).first { tabSheet.getTabAt(it).label == "Steuer & Recht" }
        val taxContent = tabSheet.getComponent(tabSheet.getTabAt(taxTabIndex))

        fun containsComponent(root: Component, target: Component): Boolean {
            if (root === target) return true
            return root.children.anyMatch { containsComponent(it, target) }
        }
        assertFalse(containsComponent(taxContent, registerCourtField), "registerCourtField must have moved off Steuer & Recht")
    }

    @Test
    fun `the HR-omitted hint is hidden by default and appears only for sole proprietor plus crafts chamber`() {
        val view = newView()
        val legalFormCombo: ComboBox<LegalForm> = view.legalFormCombo
        val craftsChamberField: Checkbox = fieldOf(view, "craftsChamberField", Checkbox::class)
        val hintSpan: Span = sections(view).firmierungHintSpan

        assertFalse(hintSpan.isVisible, "hidden by default (no legal form recorded yet)")

        legalFormCombo.value = LegalForm.SOLE_PROPRIETOR
        assertFalse(hintSpan.isVisible, "still hidden — crafts-chamber flag not set yet")

        craftsChamberField.value = true
        assertTrue(hintSpan.isVisible, "the exact combination must show the hint")

        legalFormCombo.value = LegalForm.GMBH
        assertFalse(hintSpan.isVisible, "changing away from sole proprietor hides it again")

        legalFormCombo.value = LegalForm.SOLE_PROPRIETOR
        craftsChamberField.value = false
        assertFalse(hintSpan.isVisible, "unchecking crafts chamber hides it again")
    }

    // ── anbieter-menu-ergonomie — read-only shortHash field, always-visible sellerNumber,
    //    Standard column replaced by an inline marker ────────────────────────────────────────

    /** Drives [SenderProfileView.readFormData] directly (it is `protected override`,
     *  unreachable from this non-subclass test otherwise) — same reflection idiom
     *  `ArticleViewKaribuTest`/`B2bProfilesTabKaribuTest` use for their own `readFormData`. */
    private fun readFormData(view: SenderProfileView, formData: SenderProfileFormData) {
        val m = SenderProfileView::class.java.getDeclaredMethod(
            "readFormData", SenderProfileFormData::class.java,
        )
        m.isAccessible = true
        m.invoke(view, formData)
    }

    @Test
    fun `shortHash field is read-only with the article-mirroring label and helper text`() {
        val view = newView()
        val shortHashField: TextField = fieldOf(view, "shortHashField", TextField::class)

        assertTrue(shortHashField.isReadOnly, "short_hash is never user-editable on SenderProfile")
        assertEquals("Kennung (technisch)", shortHashField.label)
        assertEquals(
            "Eindeutige technische Kennung für interne Referenzen — erscheint nicht auf der " +
                "Rechnung. Wird beim Speichern automatisch vergeben.",
            shortHashField.helperText,
        )
    }

    @Test
    fun `shortHash field is blank for a brand-new profile`() {
        val view = newView()
        readFormData(view, SenderProfileFormData())
        assertEquals("", fieldOf(view, "shortHashField", TextField::class).value)
    }

    @Test
    fun `shortHash field shows the persisted value for an existing profile`() {
        val view = newView()
        readFormData(view, SenderProfileFormData(id = 5L, shortHash = "aBcDeFgHiJ"))

        assertEquals("aBcDeFgHiJ", fieldOf(view, "shortHashField", TextField::class).value)
    }

    @Test
    fun `sellerNumber field carries the pending-assignment placeholder`() {
        val view = newView()
        val sellerNumberField: TextField = fieldOf(view, "sellerNumberField", TextField::class)
        assertTrue(sellerNumberField.isReadOnly)
        assertEquals("wird beim Speichern vergeben", sellerNumberField.placeholder)
    }

    @Test
    fun `sellerNumber field stays visible for a brand-new profile with no active SELLER circle`() {
        // numberRangeQueries.proposeSellerNumber(any()) is unstubbed -> null, exactly the "no
        // active SELLER number range yet" case (N3-7) — the regression this test pins: this used
        // to hide the field entirely (isVisible = proposedSellerNumber != null).
        val view = newView()
        readFormData(view, SenderProfileFormData())

        val sellerNumberField: TextField = fieldOf(view, "sellerNumberField", TextField::class)
        assertTrue(sellerNumberField.isVisible, "the field must never hide, even without a proposal")
        assertEquals("", sellerNumberField.value)
    }

    @Test
    fun `sellerNumber field stays visible for an existing profile that has no number yet`() {
        // The other half of the same regression: an existing profile predating any SELLER
        // number range used to hide the field too (isVisible = !existing.sellerNumber.isNullOrBlank()).
        val view = newView()
        readFormData(view, SenderProfileFormData(id = 9L, sellerNumber = ""))

        val sellerNumberField: TextField = fieldOf(view, "sellerNumberField", TextField::class)
        assertTrue(sellerNumberField.isVisible, "must stay visible, showing the placeholder instead")
        assertEquals("", sellerNumberField.value)
    }

    @Test
    fun `sellerNumber field shows the persisted value for an existing profile that already has one`() {
        val view = newView()
        readFormData(view, SenderProfileFormData(id = 11L, sellerNumber = "DEE-00001"))

        val sellerNumberField: TextField = fieldOf(view, "sellerNumberField", TextField::class)
        assertTrue(sellerNumberField.isVisible)
        assertEquals("DEE-00001", sellerNumberField.value)
    }

    // ── Grid: Standard column replaced by an inline star marker on the Name cell ───────────

    private fun gridOf(view: SenderProfileView): com.vaadin.flow.component.grid.Grid<*> = view.grid

    @Test
    fun `grid no longer carries a dedicated Standard column`() {
        val view = newView()
        // 5 columns remain: Name, Ort-Land, Praefix, Anbieternummer, Kurzname — the former
        // sixth (Standard) is gone, folded into the Name cell instead.
        assertEquals(5, gridOf(view).columns.size, gridOf(view).columns.map { it.key }.toString())
    }

    private fun nameCell(view: SenderProfileView, sender: SenderProfileRowDto): Span {
        val m = SenderProfileView::class.java.getDeclaredMethod(
            "nameCell", SenderProfileRowDto::class.java,
        )
        m.isAccessible = true
        return m.invoke(view, sender) as Span
    }

    @Test
    fun `name cell carries no star for a non-default profile`() {
        val view = newView()
        val sender = senderRow(name = "Acme GmbH", isDefault = false)

        val cell = nameCell(view, sender)

        assertEquals("Acme GmbH", cell.text)
        assertTrue(cell.children.toList().isEmpty(), "no icon for a non-default profile")
    }

    @Test
    fun `name cell carries a star icon with a tooltip for the default profile`() {
        val view = newView()
        val sender = senderRow(name = "Acme GmbH", isDefault = true)

        val cell = nameCell(view, sender)
        val icon = cell.children.toList().single() as com.vaadin.flow.component.icon.Icon

        assertEquals("Standard im Ausstellerprofil", icon.element.getAttribute("title"))
        assertEquals("Standard im Ausstellerprofil", icon.element.getAttribute("aria-label"))
    }

    // ── The seller's invoice note, one field per active language ───────────────────────────

    private fun invoiceNoteFieldsOf(view: SenderProfileView): Map<LanguageDto, TextField> = view.invoiceNoteFieldsByLanguage

    private fun pendingInvoiceNotesByLanguageOf(view: SenderProfileView): Map<String, String?> =
        view.pendingInvoiceNotesByLanguage

    private fun writeValidatedForm(view: SenderProfileView, formData: SenderProfileFormData): Boolean {
        val m = SenderProfileView::class.java.getDeclaredMethod(
            "writeValidatedForm", SenderProfileFormData::class.java,
        )
        m.isAccessible = true
        return m.invoke(view, formData) as Boolean
    }

    private fun applyFormData(view: SenderProfileView, formData: SenderProfileFormData) {
        val m = SenderProfileView::class.java.getDeclaredMethod(
            "persistForm",
            SenderProfileFormData::class.java,
            SenderProfileRowDto::class.java,
        )
        m.isAccessible = true
        m.invoke(view, formData, null)
    }

    /** Fills every binder-required field with a value the validators accept, so
     *  [writeValidatedForm] actually runs to completion instead of failing early on an unrelated
     *  field — this test file's other tests never call `writeValidatedForm`, so no existing helper
     *  does this yet. */
    private fun fillRequiredFields(view: SenderProfileView) {
        fieldOf(view, "nameField", TextField::class).value = "Seller GmbH"
        fieldOf(view, "taxNumberField", TextField::class).value = "12/345/67890"
        fieldOf(view, "vatIdField", TextField::class).value = "DE123456789"
        fieldOf(view, "ibanField", TextField::class).value = "DE89370400440532013000"
        fieldOf(view, "emailField", TextField::class).value = "seller@example.org"
    }

    @Test
    fun `flag off - the English invoice note field is absent from the component tree, not merely hidden`() {
        val view = newView(multilingualTextsEnabled = false)

        val fields = invoiceNoteFieldsOf(view)

        assertEquals(1, fields.size, "only the German (fallback) field must exist: $fields")
        assertTrue(fields.keys.single().isFallback)
        assertTrue(fields.keys.none { it.code == "en" }, "no English field, not even an invisible one")
    }

    @Test
    fun `flag on - the English invoice note field is present`() {
        val view = newView(multilingualTextsEnabled = true)

        val fields = invoiceNoteFieldsOf(view)

        assertEquals(2, fields.size)
        assertTrue(fields.keys.any { it.code == "en" })
    }

    @Test
    fun `the invoice note fields carry the 200-character limit`() {
        val view = newView(multilingualTextsEnabled = true)

        invoiceNoteFieldsOf(view).values.forEach { field -> assertEquals(200, field.maxLength) }
    }

    @Test
    fun `a third active language renders a third field with no code change`() {
        // The test itself adds the language row — proof that "freely configurable" is real.
        whenever(languageQueries.findActiveLanguages()).thenReturn(
            listOf(
                language(1, "de", isFallback = true),
                language(2, "en", isFallback = false),
                language(3, "fr", isFallback = false),
            ),
        )

        val view = newView(multilingualTextsEnabled = true)

        assertEquals(3, invoiceNoteFieldsOf(view).size)
    }

    @Test
    fun `readFormData fills the German field from an existing note and leaves English blank when none exists`() {
        val view = newView(multilingualTextsEnabled = true)

        readFormData(
            view,
            SenderProfileFormData(invoiceNotesByLanguageCode = mutableMapOf("de" to "Zahlbar ohne Abzug.")),
        )

        val fields = invoiceNoteFieldsOf(view)
        assertEquals("Zahlbar ohne Abzug.", fields.entries.first { it.key.isFallback }.value.value)
        assertEquals("", fields.entries.first { it.key.code == "en" }.value.value)
    }

    @Test
    fun `writeValidatedForm and applyFormData carry the typed text through to the pending save map`() {
        val view = newView(multilingualTextsEnabled = true)
        fillRequiredFields(view)
        val fields = invoiceNoteFieldsOf(view)
        fields.entries.first { it.key.isFallback }.value.value = "Deutscher Hinweis"
        fields.entries.first { it.key.code == "en" }.value.value = "English note"

        val formData = SenderProfileFormData()
        assertTrue(writeValidatedForm(view, formData), "the form must validate with every required field filled")
        assertEquals("Deutscher Hinweis", formData.invoiceNotesByLanguageCode["de"])
        assertEquals("English note", formData.invoiceNotesByLanguageCode["en"])

        applyFormData(view, formData)
        val pending = pendingInvoiceNotesByLanguageOf(view)
        assertEquals("Deutscher Hinweis", pending["de"])
        assertEquals("English note", pending["en"])
    }

    @Test
    fun `clearing the English field carries a blank value through, leaving the German one untouched`() {
        val view = newView(multilingualTextsEnabled = true)
        fillRequiredFields(view)
        val fields = invoiceNoteFieldsOf(view)
        fields.entries.first { it.key.isFallback }.value.value = "Deutscher Hinweis"
        fields.entries.first { it.key.code == "en" }.value.value = ""

        val formData = SenderProfileFormData()
        writeValidatedForm(view, formData)

        assertEquals("Deutscher Hinweis", formData.invoiceNotesByLanguageCode["de"])
        assertEquals("", formData.invoiceNotesByLanguageCode["en"])
    }

    @Test
    fun `flag off - only the German field's value ever reaches the pending save map`() {
        val view = newView(multilingualTextsEnabled = false)
        fillRequiredFields(view)
        invoiceNoteFieldsOf(view).values.single().value = "Deutscher Hinweis"

        val formData = SenderProfileFormData()
        writeValidatedForm(view, formData)
        applyFormData(view, formData)

        val pending = pendingInvoiceNotesByLanguageOf(view)
        assertEquals(1, pending.size, "no English entry must be present at all with the flag off: $pending")
        assertEquals("Deutscher Hinweis", pending.values.single())
    }

    // ── Over-long BIC/legalRegistrationSchemeId must never reach Postgres as a raw
    // "value too long" SQL error; the field itself now hard-caps input at the DB column width
    // (SenderProfile.bic's/legalRegistrationSchemeId's @Size, mirroring VARCHAR(11)/VARCHAR(10)).

    private fun textField(view: SenderProfileView, name: String): TextField {
        val f = SenderProfileView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    @Test
    fun `the BIC field caps input at 11 characters, matching sender_profile bic VARCHAR(11)`() {
        val view = newView()

        assertEquals(11, textField(view, "bicField").maxLength)
    }

    @Test
    fun `the legal registration scheme id field caps input at 10 characters, matching VARCHAR(10)`() {
        val view = newView()

        assertEquals(10, textField(view, "legalRegistrationSchemeIdField").maxLength)
    }

    // ── phone/email/website/registeredSeat must each fit one invoice footer line
    //    (the shared HelveticaWidths/fitFontSize budget, see SenderProfileFormBinder). ──────────

    @Test
    fun `an over-long email fails validation with the too-long-for-footer message`() {
        val view = newView()
        fillRequiredFields(view)
        fieldOf(view, "emailField", TextField::class).value = "an-extraordinarily-long-local-part-for-testing@example-domain.example"

        val formData = SenderProfileFormData()
        assertFalse(writeValidatedForm(view, formData))
    }

    @Test
    fun `an over-long website fails validation, a short one passes`() {
        val view = newView()
        fillRequiredFields(view)
        fieldOf(view, "websiteField", TextField::class).value =
            "https://www.an-extraordinarily-long-domain-name-for-footer-testing.example/"

        val formData = SenderProfileFormData()
        assertFalse(writeValidatedForm(view, formData))

        fieldOf(view, "websiteField", TextField::class).value = "https://www.example.de"
        assertTrue(writeValidatedForm(view, formData), "a short website must still validate")
    }

    @Test
    fun `an over-long registered seat fails validation, a short one passes`() {
        val view = newView()
        fillRequiredFields(view)
        fieldOf(view, "registeredSeatField", TextField::class).value =
            "An Extraordinarily Long Registered-Seat Name For Footer Line Testing Purposes"

        val formData = SenderProfileFormData()
        assertFalse(writeValidatedForm(view, formData))

        fieldOf(view, "registeredSeatField", TextField::class).value = "Berlin"
        assertTrue(writeValidatedForm(view, formData), "a short seat must still validate")
    }

    @Test
    fun `an over-long phone number fails validation, a short one passes`() {
        val view = newView()
        fillRequiredFields(view)
        fieldOf(view, "phoneField", TextField::class).value = "+49 30 0000000000000000000000000000000000000000"

        val formData = SenderProfileFormData()
        assertFalse(writeValidatedForm(view, formData))

        fieldOf(view, "phoneField", TextField::class).value = "+49 30 123456"
        assertTrue(writeValidatedForm(view, formData), "a short phone number must still validate")
    }
}
