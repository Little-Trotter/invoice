package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.dto.SaveSenderProfileCommand
import de.littletrotter.application.dto.SenderProfileRowDto
import de.littletrotter.application.masterdata.port.inbound.DeleteSenderProfileUseCase
import de.littletrotter.application.masterdata.port.inbound.SaveSenderProfileUseCase
import de.littletrotter.application.masterdata.port.inbound.SenderProfileQueryPort
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.reference.Language
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.edit
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SenderProfilePaymentTermKaribuTest {

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

    private val german = Language().edit { id = 1; code = "de"; isFallback = true }
    private val english = Language().edit { id = 2; code = "en"; isFallback = false }

    // Entities only exist here to build the DTOs the ports actually hand back (toDto() is the
    // same mapping ManagePaymentTerm/PaymentTermQueries use), and to give
    // existingSender.defaultPaymentTerm (a real JPA relation) something to point at.
    private val termImmediately = aPaymentTerm {
        id = 1L
        changeName(german, "Sofort fällig")
        changeName(english, "Due immediately")
        dueInDays = 0
        isActive = true
    }

    private val term14Days = aPaymentTerm {
        id = 2L
        changeName(german, "14 Tage netto")
        changeName(english, "14 days net")
        dueInDays = 14
        isActive = true
    }

    private val termInactive = aPaymentTerm {
        id = 3L
        changeName(german, "Inaktives Ziel")
        changeName(english, "Inactive term")
        dueInDays = 60
        isActive = false
    }

    private val termImmediatelyDto = termImmediately.toDto()
    private val term14DaysDto = term14Days.toDto()
    private val termInactiveDto = termInactive.toDto()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(languageQueries.findActiveLanguages()).thenReturn(listOf(german, english).map { it.toDto() })
        whenever(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion()).thenReturn(emptyList())
        whenever(paymentTermQueries.findAllPaymentTerms()).thenReturn(listOf(termImmediatelyDto, term14DaysDto, termInactiveDto))
        whenever(saveSenderProfile.saveSenderProfile(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as SaveSenderProfileCommand).id ?: 100L
        }
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) {}
    }

    private fun newView(): SenderProfileView = SenderProfileView(
        SenderProfilePorts(senderProfileQueries, saveSenderProfile, deleteSenderProfile),
        b2bSectionComposer,
        defaultsComposer,
        de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(languageQueries, mock(), mock()),
        numberRangeQueries,
        PartyFormExtensionRegistry(emptyList()),
        SellerVatRegistrationsExtensionRegistry(emptyList()),
    )

    private fun paymentTermField(view: SenderProfileView): ComboBox<PaymentTermDto> = view.defaultPaymentTermField

    private fun textField(view: SenderProfileView, name: String): TextField {
        val f = SenderProfileView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun fillValidForm(view: SenderProfileView) {
        textField(view, "nameField").value = "Test Anbieter GmbH"
        textField(view, "taxNumberField").value = "181/815/08155"
        textField(view, "vatIdField").value = "DE123456789"
        textField(view, "ibanField").value = "DE89370400440532013000"
        textField(view, "emailField").value = "firma@example.org"
        val addressFormSectionField = SenderProfileView::class.java.getDeclaredField("addressFormSection")
        addressFormSectionField.isAccessible = true
        val addressFormSection = addressFormSectionField.get(view) as de.littletrotter.ui.component.AddressFormSection
        addressFormSection.street.value = "Weg 1"
        addressFormSection.postalCode.value = "10115"
        addressFormSection.city.value = "Berlin"
    }

    private fun openEditor(view: SenderProfileView, entity: SenderProfileRowDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(view, entity)
    }

    @Test
    fun `creating a new sender preselects 0-days payment term and allows selecting another term without errors`() {
        val view = newView()
        openEditor(view, null)

        val combo = paymentTermField(view)
        assertEquals(termImmediatelyDto, combo.value, "Default 0-days term is pre-selected for new senders")

        // Item label generator renders properly in German
        assertEquals("Sofort fällig", combo.itemLabelGenerator.apply(termImmediatelyDto))
        assertEquals("14 Tage netto", combo.itemLabelGenerator.apply(term14DaysDto))

        // Selecting another payment term in the UI does not throw
        combo.value = term14DaysDto
        assertEquals(term14DaysDto, combo.value)

        // Save new sender
        fillValidForm(view)
        val saveMethod = AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        saveMethod.isAccessible = true
        saveMethod.invoke(view)

        val commandCaptor = argumentCaptor<SaveSenderProfileCommand>()
        verify(saveSenderProfile).saveSenderProfile(commandCaptor.capture())
        assertEquals(term14Days.id, commandCaptor.firstValue.defaultPaymentTermId, "the selected term's id is handed to the save command")
    }

    @Test
    fun `editing an existing sender with inactive payment term retains the term in combo items and selects it`() {
        val existingSender = senderRow(id = 42L, name = "Bestehender Anbieter")
        whenever(senderProfileQueries.findRowById(42L)).thenReturn(existingSender)
        whenever(senderProfileQueries.findForEdit(42L)).thenReturn(
            senderDetail(id = 42L, name = "Bestehender Anbieter", defaultPaymentTerm = termInactiveDto),
        )

        val view = newView()
        openEditor(view, existingSender)

        val combo = paymentTermField(view)
        assertEquals(termInactiveDto, combo.value, "Existing profile's payment term is loaded into combo")

        val items = (combo.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<PaymentTermDto>).items
        assertTrue(items.contains(termInactiveDto), "Inactive term is included in combo items for this sender")
    }

    @Test
    fun `editing an existing sender without a payment term leaves the combo clear and saveable`() {
        val existingSender = senderRow(id = 43L, name = "Anbieter ohne Zahlungsbedingung")
        whenever(senderProfileQueries.findRowById(43L)).thenReturn(existingSender)
        whenever(senderProfileQueries.findForEdit(43L)).thenReturn(
            senderDetail(id = 43L, name = "Anbieter ohne Zahlungsbedingung", defaultPaymentTerm = null),
        )

        val view = newView()
        openEditor(view, existingSender)

        val combo = paymentTermField(view)
        assertEquals(null, combo.value, "A missing payment term must remain an explicit empty selection")
        fillValidForm(view)
        val saveMethod = AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        saveMethod.isAccessible = true
        saveMethod.invoke(view)

        val commandCaptor = argumentCaptor<SaveSenderProfileCommand>()
        verify(saveSenderProfile).saveSenderProfile(commandCaptor.capture())
        assertNull(commandCaptor.firstValue.defaultPaymentTermId)
    }
}
