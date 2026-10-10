package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.PaymentTermDto
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
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SenderProfilePaymentTermKaribuTest {

    private val sellerProfileService: SellerProfileService = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val b2bSectionComposer = SenderB2bSectionComposer(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue)
    private val paymentTermQueries: PaymentTermQueryPort = mock()
    private val languageQueries: LanguageQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val defaultsComposer = SenderProfileDefaultsComposer(paymentTermQueries)

    private val german = Language().apply { id = 1; code = "de"; isFallback = true }
    private val english = Language().apply { id = 2; code = "en"; isFallback = false }

    // Entities only exist here to build the DTOs the ports actually hand back (toDto() is the
    // same mapping ManagePaymentTerm/PaymentTermQueries use), and to give
    // existingSender.defaultPaymentTerm (a real JPA relation) something to point at.
    private val termImmediately = PaymentTerm().apply {
        id = 1L
        setName(german, "Sofort fällig")
        setName(english, "Due immediately")
        dueInDays = 0
        isActive = true
    }

    private val term14Days = PaymentTerm().apply {
        id = 2L
        setName(german, "14 Tage netto")
        setName(english, "14 days net")
        dueInDays = 14
        isActive = true
    }

    private val termInactive = PaymentTerm().apply {
        id = 3L
        setName(german, "Inaktives Ziel")
        setName(english, "Inactive term")
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
        whenever(sellerProfileService.save(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { invocation ->
            val sender = invocation.arguments[0] as SenderProfile
            if (sender.id == null) sender.id = 100L
            sender
        }
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) {}
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

    private fun openEditor(view: SenderProfileView, entity: SenderProfile?) {
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

        val idCaptor = argumentCaptor<SellerDefaultSelections>()
        verify(sellerProfileService).save(anyOrNull(), anyOrNull(), anyOrNull(), idCaptor.capture())
        assertEquals(term14Days.id, idCaptor.firstValue.paymentTermId, "the selected term's id is handed to SellerProfileService.save")
    }

    @Test
    fun `editing an existing sender with inactive payment term retains the term in combo items and selects it`() {
        val existingSender = SenderProfile().apply {
            id = 42L
            name = "Bestehender Anbieter"
            defaultPaymentTerm = termInactive
        }
        whenever(sellerProfileService.findById(42L)).thenReturn(existingSender)
        whenever(sellerProfileService.findByIdForEdit(42L)).thenReturn(existingSender)

        val view = newView()
        openEditor(view, existingSender)

        val combo = paymentTermField(view)
        assertEquals(termInactiveDto, combo.value, "Existing profile's payment term is loaded into combo")

        val items = (combo.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<PaymentTermDto>).items
        assertTrue(items.contains(termInactiveDto), "Inactive term is included in combo items for this sender")
    }

    @Test
    fun `editing an existing sender without a payment term leaves the combo clear and saveable`() {
        val existingSender = SenderProfile().apply {
            id = 43L
            name = "Anbieter ohne Zahlungsbedingung"
            defaultPaymentTerm = null
        }
        whenever(sellerProfileService.findById(43L)).thenReturn(existingSender)
        whenever(sellerProfileService.findByIdForEdit(43L)).thenReturn(existingSender)

        val view = newView()
        openEditor(view, existingSender)

        val combo = paymentTermField(view)
        assertEquals(null, combo.value, "A missing payment term must remain an explicit empty selection")
        fillValidForm(view)
        val saveMethod = AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        saveMethod.isAccessible = true
        saveMethod.invoke(view)

        val idCaptor = argumentCaptor<SellerDefaultSelections>()
        verify(sellerProfileService).save(anyOrNull(), anyOrNull(), anyOrNull(), idCaptor.capture())
        assertNull(idCaptor.firstValue.paymentTermId)
    }
}
