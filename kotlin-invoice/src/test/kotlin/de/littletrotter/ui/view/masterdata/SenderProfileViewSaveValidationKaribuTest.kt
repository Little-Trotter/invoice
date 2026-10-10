package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [SenderProfileView.writeValidatedForm] (and the binder validators it drives)
 * had no test at all: neither [SenderProfileViewKaribuTest] nor
 * [SenderProfileViewCoverageKaribuTest] ever calls it, so the IBAN/email `withValidator` branches
 * (lines 346/364 — `IbanValidator.validate(value) == null` / `EmailValidator.validate(value) ==
 * null`) stayed at 0% branch coverage.
 */
class SenderProfileViewSaveValidationKaribuTest {

    private val sellerProfileService: SellerProfileService = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val b2bSectionComposer = SenderB2bSectionComposer(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue)
    private val paymentTermQueries: PaymentTermQueryPort = mock()
    private val languageQueries: LanguageQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val defaultsComposer = SenderProfileDefaultsComposer(paymentTermQueries)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): SenderProfileView = SenderProfileView(
        sellerProfileService,
        b2bSectionComposer,
        defaultsComposer,
        de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(languageQueries, mock(), mock()),
        numberRangeQueries,
        PartyFormExtensionRegistry(emptyList()),
        SellerVatRegistrationsExtensionRegistry(emptyList()),
    )

    private fun field(view: SenderProfileView, name: String): TextField {
        val f = SenderProfileView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun writeValidatedForm(view: SenderProfileView, formData: SenderProfileFormData): Boolean {
        val m = SenderProfileView::class.java.getDeclaredMethod("writeValidatedForm", SenderProfileFormData::class.java)
        m.isAccessible = true
        return m.invoke(view, formData) as Boolean
    }

    /** Fills every `asRequired` binder field with a value that passes validation, so a single
     *  field can be broken per test without the others tripping the same assertion. */
    private fun fillValidForm(view: SenderProfileView) {
        field(view, "nameField").value = "Anbieter GmbH"
        field(view, "taxNumberField").value = "181/815/08155"
        field(view, "vatIdField").value = "DE123456789"
        field(view, "ibanField").value = "DE89370400440532013000"
        field(view, "emailField").value = "firma@example.org"
        // AddressFormSection's own country combo already defaults to Country.DE; postalCode must
        // be a well-formed German one since validateGermanPostalCode runs whenever the address is
        // (still, by default) German.
        val addressFormSectionField = SenderProfileView::class.java.getDeclaredField("addressFormSection")
        addressFormSectionField.isAccessible = true
        val addressFormSection = addressFormSectionField.get(view) as de.littletrotter.ui.component.AddressFormSection
        addressFormSection.street.value = "Weg 1"
        addressFormSection.postalCode.value = "10115"
        addressFormSection.city.value = "Berlin"
    }

    @Test
    fun `writeValidatedForm succeeds when every field is well-formed`() {
        val view = newView()
        fillValidForm(view)

        assertTrue(writeValidatedForm(view, SenderProfileFormData()))
    }

    @Test
    fun `saving an old sender dialog rejects a newer loaded revision before mutation`() {
        val view = newView()
        val opened = SenderProfile().apply { id = 5L; name = "Opened"; version = 7L }
        whenever(sellerProfileService.findByIdForEdit(5L)).thenReturn(opened)
        whenever(sellerProfileService.findById(5L)).thenReturn(opened)
        AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
            .also { it.isAccessible = true }.invoke(view, opened)
        fillValidForm(view)
        val latest = SenderProfile().apply { id = 5L; name = "Concurrent edit"; version = 8L }
        whenever(sellerProfileService.findById(5L)).thenReturn(latest)

        (AbstractCrudView::class.java.getDeclaredField("saveButton")
            .also { it.isAccessible = true }.get(view) as Button).click()

        verify(sellerProfileService, never()).save(any(), anyOrNull(), anyOrNull(), anyOrNull())
        assertEquals("Concurrent edit", latest.name)
        assertEquals(8L, latest.version)
        assertTrue((AbstractCrudView::class.java.getDeclaredField("editorDialog")
            .also { it.isAccessible = true }.get(view) as Dialog).isOpened)
    }

    @Test
    fun `writeValidatedForm fails for a malformed IBAN`() {
        val view = newView()
        fillValidForm(view)
        field(view, "ibanField").value = "not-an-iban"

        assertFalse(writeValidatedForm(view, SenderProfileFormData()))
    }

    @Test
    fun `writeValidatedForm fails for a malformed email address`() {
        val view = newView()
        fillValidForm(view)
        field(view, "emailField").value = "not-an-email"

        assertFalse(writeValidatedForm(view, SenderProfileFormData()))
    }
}
