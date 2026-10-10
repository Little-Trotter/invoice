package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientSummaryDto
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
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.ui.view.invoice.toRecipientSummaryDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * The "Steuer & Recht" tab's [ReverseChargePreference] segmented control: defaulting,
 * persisting the selected value, restoring a persisted value on reopen, and resetting to the
 * class default between dialog openings. Pattern per [RecipientB2bProfileKaribuTest] (which
 * already establishes the `openEditor(view, entity)` reflection idiom this depends on) and
 * [RecipientViewVatIdVerificationKaribuTest] (facade/save reflection idiom).
 */
class RecipientViewReverseChargePreferenceKaribuTest {

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val port: VatIdVerificationPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(anyOrNull())).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): RecipientView = RecipientView(
        manageRecipient,
        recipientQuery,
        PartyFormExtensionRegistry(emptyList()),
        port,
        RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue),
        InvoiceCreationDependencies(senderQuery, createInvoiceUseCase), time = FIXED_TIME_SOURCE,
    )

    private fun reverseChargePreferenceField(view: RecipientView): RadioButtonGroup<ReverseChargePreference> =
        view.reverseChargePreferenceField

    private fun textField(view: RecipientView, name: String): TextField {
        val f = RecipientView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun billingAddressSection(view: RecipientView): AddressFormSection {
        val f = RecipientView::class.java.getDeclaredField("addressFormSection")
        f.isAccessible = true
        return f.get(view) as AddressFormSection
    }

    /** Mirrors [de.littletrotter.application.masterdata.RecipientQueries
     *  .findRecipientDetail]'s mapping (only the fields this file's fixtures ever set). */
    private fun Recipient.toDetailDto(): RecipientDetailDto = RecipientDetailDto(
        id = id ?: 0L, version = version, customerNumber = customerNumber?.value, shortHash = shortHash,
        name = name, vatId = vatId?.value, routingId = routingId, email = email?.value, phone = phone,
        contactPerson = contactPerson, iban = iban?.value, customerType = customerType, invoiceFormat = invoiceFormat, documentLanguage = documentLanguage,
        reverseChargePreference = reverseChargePreference, buyerIdentifierValue = buyerIdentifier?.value,
        buyerIdentifierSchemeId = buyerIdentifier?.schemeId, legalRegistrationValue = legalRegistrationIdentifier?.value,
        legalRegistrationSchemeId = legalRegistrationIdentifier?.schemeId, b2bProfileId = b2bProfile?.id,
        partyPairs = emptyMap(), billingAddress = null, deliveryAddress = null,
        vatIdVerificationStatus = vatIdVerificationStatus, vatIdVerifiedAt = vatIdVerifiedAt,
        vatIdVerificationNote = vatIdVerificationNote,
    )

    private fun openEditor(view: RecipientView, entity: RecipientSummaryDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(view, entity)
    }

    private fun saveCurrentForm(view: RecipientView) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        m.isAccessible = true
        m.invoke(view)
    }

    private fun fillMinimalValidForm(view: RecipientView) {
        textField(view, "nameField").value = "Reverse Charge Test GmbH"
        textField(view, "customerNumberField").value = "KD-RC-1"
        billingAddressSection(view).street.value = "Teststraße 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "emailField").value = "kunde@example.org"
        textField(view, "vatIdField").value = "DE136695976"
    }

    private fun stubSave(): org.mockito.kotlin.KArgumentCaptor<de.littletrotter.application.dto.RecipientDraft> {
        val captor = argumentCaptor<de.littletrotter.application.dto.RecipientDraft>()
        whenever(manageRecipient.saveRecipientWithAddresses(captor.capture())).thenAnswer { invocation ->
            val draft = invocation.getArgument<de.littletrotter.application.dto.RecipientDraft>(0)
            Recipient().apply {
                id = draft.id ?: 99L
                name = draft.name
                reverseChargePreference = draft.reverseChargePreference
            }.toDetailDto()
        }
        return captor
    }

    // ── happy path ──────────────────────────────────────────────────────────────

    @Test
    fun `a new recipient defaults to ASK and selecting ALWAYS persists it`() {
        val view = newView()
        openEditor(view, null)
        val field = reverseChargePreferenceField(view)
        assertEquals(ReverseChargePreference.ASK, field.value, "a brand-new recipient must default to ASK")

        fillMinimalValidForm(view)
        field.value = ReverseChargePreference.ALWAYS
        val captor = stubSave()

        saveCurrentForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(any())
        assertEquals(ReverseChargePreference.ALWAYS, captor.firstValue.reverseChargePreference)
    }

    // ── edge case 1: reopen shows the persisted value, not the class default ────

    @Test
    fun `an existing recipient whose persisted value is NEVER shows NEVER selected on reopen`() {
        val view = newView()
        val recipient = Recipient().apply {
            id = 1L
            customerNumber = CustomerNumber("KD-RC-2")
            name = "Never Reverse Charge GmbH"
            reverseChargePreference = ReverseChargePreference.NEVER
        }
        whenever(recipientQuery.findRecipientById(1L)).thenReturn(recipient.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(1L)).thenReturn(recipient.toDetailDto())

        openEditor(view, recipient.toRecipientSummaryDto())

        assertEquals(
            ReverseChargePreference.NEVER, reverseChargePreferenceField(view).value,
            "populateForm must read the entity's persisted value, not the field's own class default",
        )
    }

    // ── edge case 2: saving with the field left at ASK succeeds, no validation error ─

    @Test
    fun `saving with the field left at the default ASK succeeds with no validation error`() {
        val view = newView()
        openEditor(view, null)
        fillMinimalValidForm(view)
        val field = reverseChargePreferenceField(view)
        assertEquals(ReverseChargePreference.ASK, field.value)
        val captor = stubSave()

        saveCurrentForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(any())
        assertEquals(ReverseChargePreference.ASK, captor.firstValue.reverseChargePreference)
    }

    // ── edge case 3: clearForm resets to ASK between dialog openings ────────────

    @Test
    fun `a subsequent new-recipient dialog resets the control to ASK after a NEVER recipient was open`() {
        val view = newView()
        val recipient = Recipient().apply {
            id = 2L
            customerNumber = CustomerNumber("KD-RC-3")
            name = "Stale State GmbH"
            reverseChargePreference = ReverseChargePreference.NEVER
        }
        whenever(recipientQuery.findRecipientById(2L)).thenReturn(recipient.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(2L)).thenReturn(recipient.toDetailDto())
        openEditor(view, recipient.toRecipientSummaryDto())
        assertEquals(ReverseChargePreference.NEVER, reverseChargePreferenceField(view).value)

        openEditor(view, null)

        assertEquals(
            ReverseChargePreference.ASK, reverseChargePreferenceField(view).value,
            "a NEVER recipient's selection must not leak into a subsequent new-recipient dialog",
        )
    }
}
