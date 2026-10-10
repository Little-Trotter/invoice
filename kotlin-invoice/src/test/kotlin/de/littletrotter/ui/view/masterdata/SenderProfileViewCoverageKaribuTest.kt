package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.PostalCodeValidator
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Closes branch-coverage gaps in
 * [SenderProfileView] that [SenderProfileViewKaribuTest] does not reach: the tax-number/VAT-id
 * helper-text listeners, the hero name/avatar listener's `ifBlank`/`ifEmpty` branches, the
 * "reload for edit" and "brand-new profile" branches of [SenderProfileView.formDataFrom] /
 * [SenderProfileView.createWritableForm], [SenderProfileView.persistForm]'s id/country elvis
 * branches, and the "PDF template tab only when a format has a real choice" branch.
 */
class SenderProfileViewCoverageKaribuTest {

    private val sellerProfileService: SellerProfileService = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val b2bSectionComposer = SenderB2bSectionComposer(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue)
    private val paymentTermQueries: PaymentTermQueryPort = mock()
    private val languageQueries: LanguageQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val defaultsComposer = SenderProfileDefaultsComposer(paymentTermQueries)

    private fun newView(): SenderProfileView = SenderProfileView(
        sellerProfileService,
        b2bSectionComposer,
        defaultsComposer,
        de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(languageQueries, mock(), mock()),
        numberRangeQueries,
        PartyFormExtensionRegistry(emptyList()),
        SellerVatRegistrationsExtensionRegistry(emptyList()),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion()).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun <T : Any> fieldOf(view: SenderProfileView, name: String, type: kotlin.reflect.KClass<T>): T =
        type.java.cast(SenderProfileView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(view))

    private fun addressFormSection(view: SenderProfileView): AddressFormSection =
        fieldOf(view, "addressFormSection", AddressFormSection::class)

    /** The hero-card spans moved to [SenderProfileDialogSections], built by the view as
     *  its private `sections` field. */
    private fun sections(view: SenderProfileView): SenderProfileDialogSections =
        fieldOf(view, "sections", SenderProfileDialogSections::class)

    /** Field-label/Binder wiring, incl. [SenderProfileFormBinder]'s private
     *  `postalCodeViolation`, moved to the view's private `formBinder` field. */
    private fun formBinder(view: SenderProfileView): SenderProfileFormBinder =
        fieldOf(view, "formBinder", SenderProfileFormBinder::class)

    private fun formDataFrom(view: SenderProfileView, entity: SenderProfile?): SenderProfileFormData {
        val m = SenderProfileView::class.java.getDeclaredMethod("formDataFrom", SenderProfile::class.java)
        m.isAccessible = true
        try {
            return m.invoke(view, entity) as SenderProfileFormData
        } catch (ex: java.lang.reflect.InvocationTargetException) {
            throw ex.targetException
        }
    }

    private fun createWritableForm(view: SenderProfileView, id: Long?): SenderProfileFormData {
        val m = SenderProfileView::class.java.getDeclaredMethod("createWritableForm", java.lang.Long::class.java)
        m.isAccessible = true
        return m.invoke(view, id) as SenderProfileFormData
    }

    /** Saves an untouched form onto [target] — the edit path of `AbstractCrudView.saveCurrentForm`. */
    private fun persistForm(view: SenderProfileView, target: SenderProfile) {
        val m = SenderProfileView::class.java.getDeclaredMethod("persistForm", SenderProfileFormData::class.java, SenderProfile::class.java)
        m.isAccessible = true
        try {
            m.invoke(view, SenderProfileFormData(), target)
        } catch (ex: java.lang.reflect.InvocationTargetException) {
            throw ex.targetException
        }
    }

    // ── taxNumberField / vatIdField listeners — the helper-text hint/clear branches ──

    @Test
    fun `an invalid tax number sets a helper hint, a blank one clears it`() {
        val view = newView()
        val taxNumberField: TextField = fieldOf(view, "taxNumberField", TextField::class)

        taxNumberField.value = "not-a-number"
        assertTrue(taxNumberField.helperText.isNotBlank(), "an invalid Steuernummer must set a hint")

        taxNumberField.value = ""
        assertEquals("", taxNumberField.helperText, "a blank Steuernummer must clear the hint again")
    }

    @Test
    fun `an unrecognised VAT id prefix marks the field invalid, a blank one clears it`() {
        val view = newView()
        val vatIdField: de.littletrotter.ui.component.VatIdField = fieldOf(view, "vatIdField", de.littletrotter.ui.component.VatIdField::class)

        vatIdField.value = "XX123456789"
        assertTrue(vatIdField.validateNow().not(), "an unrecognised VAT-id prefix must be invalid")
        assertTrue(vatIdField.isInvalid)
        assertTrue(vatIdField.errorMessage.orEmpty().isNotBlank(), "an unrecognised VAT-id prefix must set an error message")

        vatIdField.value = ""
        assertTrue(vatIdField.validateNow(), "a blank VAT id is valid")
        assertFalse(vatIdField.isInvalid, "a blank VAT id must clear the invalid state again")
    }

    // ── nameField listener — the hero name/avatar `ifBlank`/`ifEmpty` branches ──

    @Test
    fun `typing a name updates the hero name and avatar initial`() {
        val view = newView()
        val nameField: TextField = fieldOf(view, "nameField", TextField::class)
        val heroNameSpan: Span = sections(view).heroNameSpan
        val heroAvatarSpan: Span = sections(view).heroAvatarSpan

        nameField.value = "Acme GmbH"

        assertEquals("Acme GmbH", heroNameSpan.text)
        assertEquals("A", heroAvatarSpan.text)
    }

    @Test
    fun `clearing the name falls back to the placeholder name and a plus avatar`() {
        val view = newView()
        val nameField: TextField = fieldOf(view, "nameField", TextField::class)
        val heroNameSpan: Span = sections(view).heroNameSpan
        val heroAvatarSpan: Span = sections(view).heroAvatarSpan

        nameField.value = "Acme GmbH"
        nameField.value = ""

        assertEquals(UiTranslations.tr("senderProfile.dialog.new"), heroNameSpan.text)
        assertEquals("+", heroAvatarSpan.text)
    }

    // ── formDataFrom — brand-new vs. reload-for-edit vs. reload failure ──

    @Test
    fun `formDataFrom returns a blank form for a brand-new profile without touching the service`() {
        val view = newView()

        val result = formDataFrom(view, null)

        assertNull(result.id)
        assertEquals("", result.name)
        verify(sellerProfileService, org.mockito.kotlin.never()).findByIdForEdit(any())
    }

    @Test
    fun `formDataFrom reloads the full entity via findByIdForEdit for an existing profile`() {
        whenever(sellerProfileService.findByIdForEdit(5L)).thenReturn(
            SenderProfile().apply { id = 5L; name = "Bestand" },
        )
        val view = newView()

        val result = formDataFrom(view, SenderProfile().apply { id = 5L })

        assertEquals(5L, result.id)
        assertEquals("Bestand", result.name)
    }

    @Test
    fun `formDataFrom throws when the profile fails to reload`() {
        whenever(sellerProfileService.findByIdForEdit(5L)).thenReturn(null)
        val view = newView()

        assertThrows(IllegalStateException::class.java) {
            formDataFrom(view, SenderProfile().apply { id = 5L })
        }
    }

    // ── createWritableForm — the zero-day payment-term preselection, new vs. existing ──

    @Test
    fun `createWritableForm preselects the zero-day payment term for a brand-new profile`() {
        val dueImmediately = PaymentTerm().apply { setName(germanFallbackLanguage(), "Sofort"); isActive = true; dueInDays = 0 }
        val netTerm = PaymentTerm().apply { setName(germanFallbackLanguage(), "14 Tage netto"); isActive = true; dueInDays = 14 }
        whenever(paymentTermQueries.findAllPaymentTerms()).thenReturn(listOf(netTerm, dueImmediately).map { it.toDto() })
        val view = newView()

        val result = createWritableForm(view, null)

        assertEquals(dueImmediately.toDto(), result.defaultPaymentTerm)
    }

    @Test
    fun `createWritableForm leaves the payment term unset for an existing profile`() {
        val view = newView()

        val result = createWritableForm(view, 5L)

        assertNull(result.defaultPaymentTerm)
        assertEquals(5L, result.id)
    }

    // ── persistForm — the id-after-save elvis throw and the country elvis default ──

    @Test
    fun `the default-payment-term combo labels a term in German, falling back to English, falling back to blank`() {
        val view = newView()
        val combo = view.defaultPaymentTermField
        val german = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }
        val english = de.littletrotter.domain.reference.Language().apply { id = 2; code = "en"; isFallback = false }
        val withGerman = PaymentTerm().apply { setName(german, "Sofort fällig") }.toDto()
        val englishOnly = PaymentTerm().apply { setName(english, "Due immediately") }.toDto()
        val noNames = PaymentTerm().toDto()

        assertEquals("Sofort fällig", combo.itemLabelGenerator.apply(withGerman))
        assertEquals("Due immediately", combo.itemLabelGenerator.apply(englishOnly))
        assertEquals("", combo.itemLabelGenerator.apply(noNames))
    }

    @Test
    fun `persistForm throws when the saved entity carries no id`() {
        val view = newView()
        val target = SenderProfile()

        assertThrows(IllegalStateException::class.java) { persistForm(view, target) }
    }

    @Test
    fun `persistForm defaults the billing address country to DE when no country is selected`() {
        val view = newView()
        val target = SenderProfile().apply { id = 9L }

        persistForm(view, target)

        verify(sellerProfileService).save(target, null, emptyMap(), SellerDefaultSelections())
        verify(sellerProfileService).saveAddress(eq(9L), eq(Address.AddressType.BILLING), any(), any(), any(), eq("DE"))
    }

    @Test
    fun `persistForm writes the address country from the selected combo value`() {
        val view = newView()
        addressFormSection(view).country.value = Country.AT
        val target = SenderProfile().apply { id = 9L }

        persistForm(view, target)

        verify(sellerProfileService).saveAddress(eq(9L), eq(Address.AddressType.BILLING), any(), any(), any(), eq("AT"))
    }

    // ── postalCodeViolation: the view's country-gated PLZ rule, reading
    //    the country combo the binder validators cannot see on their own. ─────────────────────

    private fun postalCodeViolation(view: SenderProfileView, value: String?): PostalCodeValidator.Violation? {
        val m = SenderProfileFormBinder::class.java.getDeclaredMethod("postalCodeViolation", String::class.java)
        m.isAccessible = true
        return m.invoke(formBinder(view), value) as PostalCodeValidator.Violation?
    }

    @Test
    fun `no selected country defaults to the German rule - blank counts as German`() {
        val view = newView()
        addressFormSection(view).country.value = null

        assertTrue(postalCodeViolation(view, "AB123") == PostalCodeValidator.Violation.GERMAN_FORMAT)
        assertTrue(postalCodeViolation(view, "70173") == null)
    }

    @Test
    fun `a non-German address accepts a real foreign postal code but not an absurd length`() {
        val view = newView()
        addressFormSection(view).country.value = Country.AT

        assertTrue(postalCodeViolation(view, "1010") == null, "the German five-digit shape is only enforced for a German address")
        assertTrue(
            postalCodeViolation(view, "not-a-postal-code-at-all") == PostalCodeValidator.Violation.TOO_LONG,
            "a foreign address still rejects values beyond the lenient length cap",
        )
    }

    @Test
    fun `a German address enforces the 5-digit shape`() {
        val view = newView()
        addressFormSection(view).country.value = Country.DE

        assertTrue(postalCodeViolation(view, "70173") == null)
        assertTrue(
            postalCodeViolation(view, "AB123") == PostalCodeValidator.Violation.GERMAN_FORMAT,
            "a German address must reject a non-numeric postal code",
        )
    }

    // ── readFormData: hero avatar initial from a real (non-blank) loaded name ────────────────────
    // (the existing hero-avatar tests above drive the *live-typing* listener; readFormData's own
    //  copy of the same ifEmpty logic, run once per dialog open, was never separately exercised
    //  with a non-blank name — every readFormData(...) call elsewhere in this suite uses the
    //  formData's default blank name.)

    private fun readFormData(view: SenderProfileView, formData: SenderProfileFormData) {
        val m = SenderProfileView::class.java.getDeclaredMethod(
            "readFormData", SenderProfileFormData::class.java,
        )
        m.isAccessible = true
        m.invoke(view, formData)
    }

    @Test
    fun `readFormData sets the hero avatar initial from a non-blank loaded name`() {
        whenever(sellerProfileService.findById(21L)).thenReturn(
            SenderProfile().apply { id = 21L; name = "Muster GmbH" },
        )
        val view = newView()

        readFormData(view, SenderProfileFormData(id = 21L, name = "Muster GmbH"))

        val heroAvatarSpan: Span = sections(view).heroAvatarSpan
        assertEquals("M", heroAvatarSpan.text, "a real name must show its own initial, not the placeholder plus")
    }

    // ── readFormData: sellerNumber field shows a real proposal for a brand-new profile ───────────
    // (the existing "stays visible" test above only exercises the unstubbed-facade -> null case)

    @Test
    fun `readFormData fills the sellerNumber field with the facade's proposal for a brand-new profile`() {
        whenever(numberRangeQueries.proposeSellerNumber(org.mockito.kotlin.any())).thenReturn("VK-00099")
        val view = newView()

        readFormData(view, SenderProfileFormData())

        val sellerNumberField: TextField = fieldOf(view, "sellerNumberField", TextField::class)
        assertEquals("VK-00099", sellerNumberField.value)
    }

    // ── createWritableForm: the isActive && dueInDays == 0 preselection never sees a false first
    //    operand elsewhere — an inactive zero-day term must not be preselected ────────────────────

    @Test
    fun `createWritableForm never preselects an inactive zero-day payment term`() {
        val inactiveZeroDay = PaymentTerm().apply { setName(germanFallbackLanguage(), "Alt Sofort"); isActive = false; dueInDays = 0 }
        whenever(paymentTermQueries.findAllPaymentTerms()).thenReturn(listOf(inactiveZeroDay).map { it.toDto() })
        val view = newView()

        val result = createWritableForm(view, null)

        assertNull(result.defaultPaymentTerm, "an inactive term must never be preselected, even at zero days")
    }

    // ── buildEinvoiceIdentityContent / configureDialog: sellerVatSection and partySection each
    //    render nothing when no extension is registered ([newView] always passes empty lists) —
    //    their `hasContent` true direction needs an actual registered extension ──────────────────

    private class FakeVatExtension : de.littletrotter.spi.party.SellerVatRegistrationsExtension {
        override val id: String = "fakeVat"
        override val supportedCountries: List<String> = listOf("LU")
        override fun read(senderProfile: Any): List<de.littletrotter.spi.party.SellerVatRegistrationRow> = emptyList()
        override fun write(senderProfile: Any, rows: List<de.littletrotter.spi.party.SellerVatRegistrationRow>) { }
    }

    private class FakeSellerPairExtension : de.littletrotter.spi.party.PartyFormExtension {
        override val id: String = "fakeSellerPair"
        override fun pairs(kind: de.littletrotter.spi.party.PartyKind): List<de.littletrotter.spi.party.PartyIdentifierSpec> =
            if (kind == de.littletrotter.spi.party.PartyKind.SELLER) {
                listOf(de.littletrotter.spi.party.PartyIdentifierSpec(id = "fakeBt34", i18nKeyPrefix = "recipient.electronicAddress"))
            } else {
                emptyList()
            }
        override fun read(
            party: Any,
            kind: de.littletrotter.spi.party.PartyKind,
            spec: de.littletrotter.spi.party.PartyIdentifierSpec,
        ): de.littletrotter.spi.party.PartyPairState = de.littletrotter.spi.party.PartyPairState()
        override fun write(
            party: Any,
            kind: de.littletrotter.spi.party.PartyKind,
            spec: de.littletrotter.spi.party.PartyIdentifierSpec,
            state: de.littletrotter.spi.party.PartyPairState,
        ) { }
    }

    // Note: TabSheet only keeps the *currently selected* tab's content attached to the component
    // tree in this Karibu/Vaadin version (verified empirically — selecting the tab first still
    // did not surface the nested Section in a plain child-recursion), so this stops at asserting
    // hasContent itself: a real, construction-time-computed property (not a call counter) that is
    // exactly the condition [SenderProfileView.buildEinvoiceIdentityContent] /
    // [SenderProfileView.configureDialog]'s own `if` branches switch on.

    @Test
    fun `sellerVatSection has content once a SellerVatRegistrationsExtension is registered`() {
        val view = SenderProfileView(
            sellerProfileService,
            b2bSectionComposer,
            defaultsComposer,
            de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(languageQueries, mock(), mock()),
            numberRangeQueries,
            PartyFormExtensionRegistry(emptyList()),
            SellerVatRegistrationsExtensionRegistry(listOf(FakeVatExtension())),
        )
        val sellerVatSection: de.littletrotter.ui.component.SellerVatRegistrationsSection = fieldOf(view, "sellerVatSection", de.littletrotter.ui.component.SellerVatRegistrationsSection::class)

        assertTrue(sellerVatSection.hasContent)
    }

    @Test
    fun `sellerVatSection has no content without a registered extension`() {
        val view = newView()
        val sellerVatSection: de.littletrotter.ui.component.SellerVatRegistrationsSection = fieldOf(view, "sellerVatSection", de.littletrotter.ui.component.SellerVatRegistrationsSection::class)

        assertFalse(sellerVatSection.hasContent)
    }

    @Test
    fun `partySection has content once a seller-scoped PartyFormExtension is registered`() {
        val view = SenderProfileView(
            sellerProfileService,
            b2bSectionComposer,
            defaultsComposer,
            de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(languageQueries, mock(), mock()),
            numberRangeQueries,
            PartyFormExtensionRegistry(listOf(FakeSellerPairExtension())),
            SellerVatRegistrationsExtensionRegistry(emptyList()),
        )
        val partySection: de.littletrotter.ui.component.PartyExtensionsSection = fieldOf(view, "partySection", de.littletrotter.ui.component.PartyExtensionsSection::class)

        assertTrue(partySection.hasContent)
    }
}
