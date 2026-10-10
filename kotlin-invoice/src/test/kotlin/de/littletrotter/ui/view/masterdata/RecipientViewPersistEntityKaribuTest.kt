package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import org.mockito.kotlin.argThat
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.VatIdVerificationPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Two branch pairs in [RecipientView] that [RecipientViewKaribuTest] never
 * isolates: [RecipientView.persistForm]'s `hasDelivery` OR-chain (every existing test either
 * leaves [de.littletrotter.ui.component.AddressFormSection.clear]'s DE default in place — always
 * true, since `Country.DE.alpha2` is never blank — or fills a delivery street, never exercising
 * the all-false / null-country path), and [RecipientView.validateForm]'s `billingComplete`
 * three-term AND with only postal code missing (the existing coverage test only ever blanks the
 * street or the city).
 */
class RecipientViewPersistEntityKaribuTest {

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val vatIdVerificationPort: VatIdVerificationPort = mock()
    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): RecipientView = RecipientView(
        manageRecipient,
        recipientQuery,
        PartyFormExtensionRegistry(emptyList()),
        vatIdVerificationPort,
        RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue),
        InvoiceCreationDependencies(senderQuery, createInvoiceUseCase), time = FIXED_TIME_SOURCE,
    )

    private fun field(view: RecipientView, name: String): TextField {
        val f = RecipientView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun addressFormSection(view: RecipientView): AddressFormSection {
        val f = RecipientView::class.java.getDeclaredField("addressFormSection")
        f.isAccessible = true
        return f.get(view) as AddressFormSection
    }

    private fun deliveryAddressFormSection(view: RecipientView): AddressFormSection {
        val f = RecipientView::class.java.getDeclaredField("deliveryAddressFormSection")
        f.isAccessible = true
        return f.get(view) as AddressFormSection
    }

    /** Saves the draft built off the widgets' current state, as a new recipient — the same path
     *  `AbstractCrudView.saveCurrentForm` takes. */
    private fun persistForm(view: RecipientView) {
        val cf = RecipientView::class.java.getDeclaredField("currentFormData")
        cf.isAccessible = true
        val m = RecipientView::class.java.getDeclaredMethod("persistForm", de.littletrotter.application.dto.RecipientDraft::class.java, RecipientSummaryDto::class.java)
        m.isAccessible = true
        m.invoke(view, cf.get(view), null)
    }

    private fun savedDetail(): de.littletrotter.application.dto.RecipientDetailDto = de.littletrotter.application.dto.RecipientDetailDto(
        id = 1L, version = 1L, customerNumber = null, shortHash = null, name = "Acme", vatId = null,
        routingId = null, email = null, phone = null, contactPerson = null, iban = null, customerType = null,
        invoiceFormat = de.littletrotter.domain.shared.InvoiceFormat.GERMAN, documentLanguage = "de",
        reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
        buyerIdentifierValue = null, buyerIdentifierSchemeId = null, legalRegistrationValue = null,
        legalRegistrationSchemeId = null, b2bProfileId = null, partyPairs = emptyMap(),
        billingAddress = null, deliveryAddress = null, vatIdVerificationStatus = null, vatIdVerifiedAt = null,
        vatIdVerificationNote = null,
    )

    private fun validateForm(view: RecipientView): Boolean {
        val m = RecipientView::class.java.getDeclaredMethod("validateForm")
        m.isAccessible = true
        return m.invoke(view) as Boolean
    }

    private fun fillValidForm(view: RecipientView) {
        field(view, "nameField").value = "Acme GmbH"
        field(view, "customerNumberField").value = "KD-1"
        addressFormSection(view).street.value = "Str 1"
        addressFormSection(view).postalCode.value = "10115"
        addressFormSection(view).city.value = "Berlin"
        field(view, "emailField").value = "person@example.org"
        // Customer type defaults to BUSINESS, which makes the VAT id a required field.
        field(view, "vatIdField").value = "DE136695976"
    }

    // ── validateForm's billingComplete: postal code alone missing ───────────────────────────────

    @Test
    fun `validation fails when only the postal code is blank, the third billingComplete term this coverage never isolated`() {
        val view = newView()
        fillValidForm(view)
        addressFormSection(view).postalCode.value = ""

        // L20 follow-up: the gated PLZ check no longer clears a billing-incomplete mark on a
        // format-valid blank value (`clearWhenValid = false`), so all three fields of the group
        // keep the shared billing-incomplete message — including the blank PLZ itself.
        assertFalse(validateForm(view))
        assertTrue(addressFormSection(view).street.isInvalid, "the shared billing-incomplete message marks every field of the group")
        assertTrue(addressFormSection(view).city.isInvalid)
        assertTrue(addressFormSection(view).postalCode.isInvalid, "the blank PLZ keeps its billing-incomplete mark")
    }

    // ── persistForm's hasDelivery: every term false, including a cleared (null) country ───────

    @Test
    fun `an entirely blank delivery address with no country selected is never sent to the facade`() {
        val view = newView()
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail())
        // deliveryAddressFormSection defaults to Country.DE (never blank alpha2) — clearing it
        // explicitly is the only way to reach hasDelivery's all-false path.
        deliveryAddressFormSection(view).country.value = null

        persistForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(
            argThat {
                addresses.deliveryStreet == null &&
                    addresses.deliveryPostalCode == null &&
                    addresses.deliveryCity == null &&
                    addresses.deliveryCountry == null
            },
        )
    }

    @Test
    fun `a delivery address with only a street set is still sent as a real delivery address`() {
        val view = newView()
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail())
        deliveryAddressFormSection(view).country.value = null
        deliveryAddressFormSection(view).street.value = "Lieferweg 3"

        persistForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(
            // hasDelivery=true but the country combo itself was cleared -> falls back to billingCountry.
            argThat { addresses.deliveryStreet == "Lieferweg 3" },
        )
    }

    @Test
    fun `a delivery address with only a postal code set is still sent as a real delivery address`() {
        val view = newView()
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail())
        deliveryAddressFormSection(view).country.value = null
        deliveryAddressFormSection(view).postalCode.value = "54321"

        persistForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(argThat { addresses.deliveryPostalCode == "54321" })
    }

    @Test
    fun `a delivery address with only a city set is still sent as a real delivery address`() {
        val view = newView()
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail())
        deliveryAddressFormSection(view).country.value = null
        deliveryAddressFormSection(view).city.value = "Hamburg"

        persistForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(argThat { addresses.deliveryCity == "Hamburg" })
    }

    // ── Country-gated PLZ validation: German rule only for DE, lenient rule otherwise ────

    private fun editorDialog(view: RecipientView): Dialog {
        val f = AbstractCrudView::class.java.getDeclaredField("editorDialog")
        f.isAccessible = true
        return f.get(view) as Dialog
    }

    private fun saveButton(view: RecipientView): Button {
        val f = AbstractCrudView::class.java.getDeclaredField("saveButton")
        f.isAccessible = true
        return f.get(view) as Button
    }

    private fun openEditor(view: RecipientView) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(view, null)
    }

    @Test
    fun `an LU billing address with a real Luxembourg PLZ validates and persists`() {
        val view = newView()
        fillValidForm(view)
        addressFormSection(view).country.value = Country.LU
        addressFormSection(view).postalCode.value = "L-1611"
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail())

        assertTrue(validateForm(view), "a real foreign PLZ must not be rejected by the German five-digit rule")
        persistForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(
            argThat { addresses.billingPostalCode == "L-1611" && addresses.billingCountry == "LU" },
        )
    }

    @Test
    fun `a German billing address with a foreign-format PLZ fails inline on the PLZ field`() {
        val view = newView()
        fillValidForm(view)
        addressFormSection(view).postalCode.value = "L-1611"

        assertFalse(validateForm(view))
        assertTrue(addressFormSection(view).postalCode.isInvalid, "the rejection must be visible on the field itself")
        assertTrue(addressFormSection(view).postalCode.errorMessage.isNotBlank())
    }

    @Test
    fun `a foreign billing PLZ beyond the lenient length cap fails inline`() {
        val view = newView()
        fillValidForm(view)
        addressFormSection(view).country.value = Country.LU
        addressFormSection(view).postalCode.value = "L-161100000000"

        assertFalse(validateForm(view))
        assertTrue(addressFormSection(view).postalCode.isInvalid)
    }

    @Test
    fun `a DE-defaulted delivery address with a foreign PLZ marks the delivery PLZ inline`() {
        val view = newView()
        fillValidForm(view)
        // The delivery country combo defaults to DE — exactly the constellation of the old
        // silent close-after-save stall: a foreign delivery PLZ under a German delivery country.
        deliveryAddressFormSection(view).street.value = "Lieferweg 5"
        deliveryAddressFormSection(view).postalCode.value = "L-2222"
        deliveryAddressFormSection(view).city.value = "Luxembourg"

        assertFalse(validateForm(view))
        assertTrue(deliveryAddressFormSection(view).postalCode.isInvalid)
    }

    @Test
    fun `an LU delivery address with a real Luxembourg PLZ passes validation`() {
        val view = newView()
        fillValidForm(view)
        deliveryAddressFormSection(view).street.value = "Lieferweg 5"
        deliveryAddressFormSection(view).postalCode.value = "L-2222"
        deliveryAddressFormSection(view).city.value = "Luxembourg"
        deliveryAddressFormSection(view).country.value = Country.LU

        assertTrue(validateForm(view))
        assertFalse(deliveryAddressFormSection(view).postalCode.isInvalid)
    }

    @Test
    fun `a blank delivery address never triggers the delivery PLZ gate`() {
        val view = newView()
        fillValidForm(view)
        // A stale error from a previous attempt must clear once the delivery address is emptied.
        deliveryAddressFormSection(view).postalCode.isInvalid = true
        deliveryAddressFormSection(view).street.value = ""
        deliveryAddressFormSection(view).postalCode.value = ""
        deliveryAddressFormSection(view).city.value = ""

        assertTrue(validateForm(view))
        assertFalse(deliveryAddressFormSection(view).postalCode.isInvalid)
    }

    @Test
    fun `the editor dialog stays open on a rejected PLZ and closes once the LU recipient saves`() {
        val view = newView()
        openEditor(view)
        assertTrue(editorDialog(view).isOpened)
        fillValidForm(view)
        addressFormSection(view).country.value = Country.LU
        addressFormSection(view).postalCode.value = "L-161100000000"

        saveButton(view)._click()

        assertTrue(editorDialog(view).isOpened, "a rejected PLZ must keep the dialog open")
        assertTrue(addressFormSection(view).postalCode.isInvalid, "…with the rejection visible inline, never silently")

        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail())
        addressFormSection(view).postalCode.value = "L-1611"

        saveButton(view)._click()

        assertFalse(editorDialog(view).isOpened, "a real LU PLZ must save and close the dialog")
    }

    // ── persistForm's billing country elvis: a cleared billing country falls back to "DE" ────

    @Test
    fun `a cleared billing address country still saves with the DE fallback`() {
        val view = newView()
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail())
        addressFormSection(view).country.value = null

        persistForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(argThat { addresses.billingCountry == "DE" })
    }
}
