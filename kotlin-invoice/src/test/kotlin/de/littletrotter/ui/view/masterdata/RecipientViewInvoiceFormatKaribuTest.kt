package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.AbstractField
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.combobox.ComboBox
import de.littletrotter.application.dto.RecipientDetailDto
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
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.component.AddressFormSection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

/**
 * PO order 04.09.2026 — the customer's "deutsche oder internationale Rechnung" choice: preselected
 * from the billing country, never forced. These cases pin the three states the PO named — the
 * preselection itself, a deliberate deviation surviving a later country edit, and a stored customer
 * never being re-derived — plus the write-back onto the entity the save path uses.
 */
class RecipientViewInvoiceFormatKaribuTest {

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val vatIdVerificationPort: VerifyVatIdUseCase = mock()
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

    private fun formatField(view: RecipientView): ComboBox<InvoiceFormat> = view.invoiceFormatField

    /**
     * What Vaadin does when the user picks an item: set the value, then deliver a value-change
     * event carrying `isFromClient = true` — the one bit [RecipientView] reads to tell a real
     * decision apart from its own country preselection. Karibu's `_setValue` cannot stand in here:
     * the field sits on a tab of the (closed) editor dialog and is therefore detached, and Karibu
     * refuses to drive a detached component.
     */
    private fun userPicks(field: ComboBox<InvoiceFormat>, value: InvoiceFormat) {
        val previous = field.value
        field.value = value
        ComponentUtil.fireEvent(field, AbstractField.ComponentValueChangeEvent(field, field, previous, true))
    }

    private fun addressFormSection(view: RecipientView): AddressFormSection {
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

    private fun populateForm(view: RecipientView, recipient: Recipient) {
        val m = RecipientView::class.java.getDeclaredMethod("populateForm", RecipientDetailDto::class.java)
        m.isAccessible = true
        m.invoke(view, recipient.toDetailDto())
    }

    private fun recipient(format: InvoiceFormat): Recipient = aRecipient {
        id = 1L
        name = "Acme"
        customerNumber = CustomerNumber("KD-1")
        invoiceFormat = format
    }

    @Test
    fun `a German billing country preselects the German invoice`() {
        val view = newView()
        addressFormSection(view).country.value = Country.DE

        assertEquals(InvoiceFormat.GERMAN, formatField(view).value)
    }

    @Test
    fun `Austria and Switzerland preselect the German invoice too`() {
        listOf(Country.AT, Country.CH).forEach { country ->
            val view = newView()
            addressFormSection(view).country.value = country
            assertEquals(InvoiceFormat.GERMAN, formatField(view).value, "$country")
        }
    }

    @Test
    fun `any other billing country preselects the international invoice`() {
        val view = newView()
        addressFormSection(view).country.value = Country.FR

        assertEquals(InvoiceFormat.INTERNATIONAL, formatField(view).value)
    }

    @Test
    fun `a deliberate deviation survives a later country change`() {
        val view = newView()
        addressFormSection(view).country.value = Country.FR

        userPicks(formatField(view), InvoiceFormat.GERMAN)
        addressFormSection(view).country.value = Country.BE

        assertEquals(InvoiceFormat.GERMAN, formatField(view).value, "die bewusste Abweichung bleibt")
    }

    @Test
    fun `a server-side preselection does not count as the user deciding`() {
        val view = newView()
        addressFormSection(view).country.value = Country.FR
        addressFormSection(view).country.value = Country.DE

        assertEquals(InvoiceFormat.GERMAN, formatField(view).value, "ohne eigene Wahl folgt das Feld dem Land")
    }

    @Test
    fun `a stored customer keeps its saved choice, never re-derived from the country`() {
        val view = newView()
        // A German address with an INTERNATIONAL customer — the combination the preselection would
        // overwrite if loading a stored customer went through it.
        populateForm(view, recipient(InvoiceFormat.INTERNATIONAL))
        addressFormSection(view).country.value = Country.DE

        assertEquals(InvoiceFormat.INTERNATIONAL, formatField(view).value)
    }

    @Test
    fun `the chosen format is written onto the draft`() {
        val view = newView()
        addressFormSection(view).country.value = Country.FR

        val draft = view.buildDraft()

        assertEquals(InvoiceFormat.INTERNATIONAL, draft.invoiceFormat)
    }

    @Test
    fun `a cleared field falls back to the German invoice instead of a null violation`() {
        val view = newView()
        formatField(view).clear()

        val draft = view.buildDraft()

        assertEquals(InvoiceFormat.GERMAN, draft.invoiceFormat)
    }

    @Test
    fun `the document language is preselected from the country until the user decides`() {
        val view = newView()
        addressFormSection(view).country.value = Country.FR
        assertEquals(DocumentLanguage.EN, view.documentLanguageField.value)

        addressFormSection(view).country.value = Country.AT
        assertEquals(DocumentLanguage.DE, view.documentLanguageField.value)

        val previous = view.documentLanguageField.value
        view.documentLanguageField.value = DocumentLanguage.EN
        ComponentUtil.fireEvent(
            view.documentLanguageField,
            AbstractField.ComponentValueChangeEvent(view.documentLanguageField, view.documentLanguageField, previous, true),
        )
        addressFormSection(view).country.value = Country.DE

        assertEquals(DocumentLanguage.EN, view.documentLanguageField.value, "die bewusste Wahl bleibt")
        assertEquals("en", view.buildDraft().documentLanguage)
    }

    @Test
    fun `a stored recipient keeps its saved document language and a cleared field falls back to German`() {
        val view = newView()
        populateForm(view, recipient(InvoiceFormat.GERMAN).edit { documentLanguage = "en" })
        addressFormSection(view).country.value = Country.DE

        assertEquals(DocumentLanguage.EN, view.documentLanguageField.value)

        view.documentLanguageField.clear()
        assertEquals("de", view.buildDraft().documentLanguage)
    }
}
