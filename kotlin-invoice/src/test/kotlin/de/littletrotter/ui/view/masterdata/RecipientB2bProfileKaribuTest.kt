package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._findAll
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.ui.view.invoice.toRecipientSummaryDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

class RecipientB2bProfileKaribuTest {

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val vatIdVerificationPort: VerifyVatIdUseCase = mock()
    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()

    private val activeProfile1 = B2bProfileDto(id = 1L, name = "Standard Profil", active = true)
    private val activeProfile2 = B2bProfileDto(id = 2L, name = "Erweitertes Profil", active = true)
    private val inactiveProfile = B2bProfileDto(id = 3L, name = "Altes Profil", active = false)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(activeProfile1, activeProfile2))
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(anyOrNull())).thenReturn(emptyList())
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenAnswer { invocation ->
            val draft = invocation.arguments[0] as RecipientDraft
            draft.toDetailDto(draft.id ?: 100L)
        }
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

    private fun b2bProfileCombo(view: RecipientView): ComboBox<B2bProfileDto> = view.b2bProfileCombo

    private fun textField(view: RecipientView, name: String): TextField {
        val f = RecipientView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
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

    /** The saved detail a save of [this] draft would produce, for the mocked
     *  [ManageRecipientUseCase.saveRecipientWithAddresses] to return. */
    private fun RecipientDraft.toDetailDto(id: Long): RecipientDetailDto = RecipientDetailDto(
        id = id, version = version, customerNumber = customerNumber, shortHash = null,
        name = name, vatId = vatId, routingId = routingId, email = email, phone = phone,
        contactPerson = contactPerson, iban = iban, customerType = customerType, invoiceFormat = invoiceFormat, documentLanguage = documentLanguage,
        reverseChargePreference = reverseChargePreference, buyerIdentifierValue = buyerIdentifierValue,
        buyerIdentifierSchemeId = buyerIdentifierSchemeId, legalRegistrationValue = legalRegistrationValue,
        legalRegistrationSchemeId = legalRegistrationSchemeId, b2bProfileId = b2bProfileId,
        partyPairs = partyPairs, billingAddress = null, deliveryAddress = null,
        vatIdVerificationStatus = null, vatIdVerifiedAt = null, vatIdVerificationNote = vatIdValidationNote,
    )

    private fun openEditor(view: RecipientView, entity: RecipientSummaryDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(view, entity)
    }

    private fun fillValidForm(view: RecipientView) {
        textField(view, "nameField").value = "Kunde GmbH"
        textField(view, "customerNumberField").value = "KD-1001"
        val addressFormSectionField = RecipientView::class.java.getDeclaredField("addressFormSection")
        addressFormSectionField.isAccessible = true
        val addressFormSection = addressFormSectionField.get(view) as de.littletrotter.ui.component.AddressFormSection
        addressFormSection.street.value = "Hauptstr. 1"
        addressFormSection.postalCode.value = "10115"
        addressFormSection.city.value = "Berlin"
        textField(view, "emailField").value = "kunde@example.org"
        view.customerTypeField.value = CustomerType.CONSUMER
    }

    @Test
    fun `creating a new customer loads active B2B profiles dynamically and allows assignment`() {
        val view = newView()
        openEditor(view, null)

        val combo = b2bProfileCombo(view)
        assertNull(combo.value)

        val items = combo.dataProvider._findAll()
        assertEquals(listOf(activeProfile1, activeProfile2), items)

        combo.value = activeProfile2

        fillValidForm(view)
        val saveMethod = AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        saveMethod.isAccessible = true
        saveMethod.invoke(view)

        val captor = argumentCaptor<RecipientDraft>()
        verify(manageRecipient).saveRecipientWithAddresses(captor.capture())
        assertEquals(activeProfile2.id, captor.firstValue.b2bProfileId)
    }

    @Test
    fun `editing an existing customer with inactive B2B profile includes it in combo items and keeps selection`() {
        val existingRecipient = aRecipient {
            id = 42L
            customerNumber = CustomerNumber("KD-0042")
            name = "Bestehender Kunde"
            b2bProfile = B2bProfile().edit { id = inactiveProfile.id; name = inactiveProfile.name; active = false }
        }
        whenever(recipientQuery.findRecipientById(42L)).thenReturn(existingRecipient.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(42L)).thenReturn(existingRecipient.toDetailDto())
        whenever(b2bProfileQueries.findB2bProfileById(3L)).thenReturn(inactiveProfile)

        val view = newView()
        openEditor(view, existingRecipient.toRecipientSummaryDto())

        val combo = b2bProfileCombo(view)
        assertEquals(inactiveProfile, combo.value)

        val items = combo.dataProvider._findAll()
        assertTrue(items.contains(inactiveProfile))
    }

    @Test
    fun `re-opening form reloads newly added active B2B profile`() {
        val view = newView()
        openEditor(view, null)
        assertEquals(2, b2bProfileCombo(view).dataProvider._findAll().size)

        val newActiveProfile = B2bProfileDto(id = 4L, name = "Neu Angelegtes Profil", active = true)
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(activeProfile1, activeProfile2, newActiveProfile))

        openEditor(view, null)
        val items = b2bProfileCombo(view).dataProvider._findAll()
        assertEquals(3, items.size)
        assertTrue(items.contains(newActiveProfile))
    }
}
