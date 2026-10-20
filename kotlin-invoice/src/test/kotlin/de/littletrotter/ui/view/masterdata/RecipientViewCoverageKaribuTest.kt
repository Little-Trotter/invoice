package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.RecipientDetailDto
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
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.fixture.aRecipient
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.ui.view.invoice.toRecipientSummaryDto
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

/**
 * Closes branch-coverage gaps in [RecipientView]
 * that [RecipientViewKaribuTest] does not reach: almost every failure branch of
 * `validateForm()` (only its all-valid CONSUMER path was tested before this) and the grid sort
 * listener's field/direction branches (mapped columns, unmapped/name fallback, cleared order,
 * both directions) — the same pattern already covered for [ArticleView] in
 * [ArticleViewCoverageKaribuTest].
 */
class RecipientViewCoverageKaribuTest {

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val vatIdVerificationPort: VerifyVatIdUseCase = mock()
    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()

    private fun newView(): RecipientView = RecipientView(
        manageRecipient,
        recipientQuery,
        PartyFormExtensionRegistry(emptyList()),
        vatIdVerificationPort,
        RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue),
        InvoiceCreationDependencies(senderQuery, createInvoiceUseCase), time = FIXED_TIME_SOURCE,
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

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

    private fun customerTypeField(view: RecipientView): com.vaadin.flow.component.combobox.ComboBox<CustomerType> = view.customerTypeField

    private fun validateForm(view: RecipientView): Boolean {
        val m = RecipientView::class.java.getDeclaredMethod("validateForm")
        m.isAccessible = true
        return m.invoke(view) as Boolean
    }

    private fun grid(view: RecipientView): com.vaadin.flow.component.grid.Grid<RecipientSummaryDto> = view.grid

    /** Mirrors [de.littletrotter.application.masterdata.RecipientQueries
     *  .findRecipientDetail]'s mapping so existing [Recipient] fixtures keep driving
     *  `openEditor`/[RecipientView.populateForm] unchanged. */
    private fun Recipient.toDetailDto(deliveryAddress: Address? = this.deliveryAddress): RecipientDetailDto = RecipientDetailDto(
        id = id ?: 0L,
        version = version,
        customerNumber = customerNumber?.value,
        shortHash = shortHash,
        name = name,
        vatId = vatId?.value,
        routingId = routingId,
        email = email?.value,
        phone = phone,
        contactPerson = contactPerson,
        iban = iban?.value,
        customerType = customerType,
        invoiceFormat = invoiceFormat, documentLanguage = documentLanguage,
        reverseChargePreference = reverseChargePreference,
        buyerIdentifierValue = buyerIdentifier?.value,
        buyerIdentifierSchemeId = buyerIdentifier?.schemeId,
        legalRegistrationValue = legalRegistrationIdentifier?.value,
        legalRegistrationSchemeId = legalRegistrationIdentifier?.schemeId,
        b2bProfileId = b2bProfile?.id,
        partyPairs = emptyMap(),
        billingAddress = billingAddress?.toAddressDto(),
        deliveryAddress = deliveryAddress?.toAddressDto(),
        vatIdVerificationStatus = vatIdVerificationStatus,
        vatIdVerifiedAt = vatIdVerifiedAt,
        vatIdVerificationNote = vatIdVerificationNote,
    )

    private fun Address.toAddressDto(): AddressDto = AddressDto(id, street, postalCode.value, city, country.alpha2)

    /** Fills every field `validateForm()` checks with values that pass, so a single field can be
     *  broken per test without the others tripping the same assertion. Mirrors the baseline
     *  "CONSUMER without vatId or iban" fixture in [RecipientViewKaribuTest]. */
    private fun fillValidForm(view: RecipientView) {
        field(view, "nameField").value = "Acme GmbH"
        field(view, "customerNumberField").value = "KD-1"
        addressFormSection(view).street.value = "Str 1"
        addressFormSection(view).postalCode.value = "10115"
        addressFormSection(view).city.value = "Berlin"
        field(view, "emailField").value = "person@example.org"
        customerTypeField(view).value = CustomerType.CONSUMER
    }

    // ── validateForm() — requireField branches (blank vs. filled) ──

    @Test
    fun `validation fails when the name is blank`() {
        val view = newView()
        fillValidForm(view)
        field(view, "nameField").value = ""

        assertFalse(validateForm(view))
        assertTrue(field(view, "nameField").isInvalid)
    }

    @Test
    fun `validation fails when the customer number is blank`() {
        val view = newView()
        fillValidForm(view)
        field(view, "customerNumberField").value = ""

        assertFalse(validateForm(view))
        assertTrue(field(view, "customerNumberField").isInvalid)
    }

    @Test
    fun `validation fails when the email is blank`() {
        val view = newView()
        fillValidForm(view)
        field(view, "emailField").value = ""

        assertFalse(validateForm(view))
        assertTrue(field(view, "emailField").isInvalid)
    }

    // ── billingComplete if/else ──

    @Test
    fun `validation fails and marks street and city invalid when the billing address is incomplete`() {
        val view = newView()
        fillValidForm(view)
        addressFormSection(view).city.value = ""

        // L20 follow-up: the country-gated PLZ check no longer clears a billing-incomplete
        // mark on a format-valid value, so the whole group keeps the shared message.
        assertFalse(validateForm(view))
        assertTrue(addressFormSection(view).street.isInvalid)
        assertTrue(addressFormSection(view).city.isInvalid)
        assertTrue(addressFormSection(view).postalCode.isInvalid)
    }

    // ── optionalValidated(postalCode) — the ifPresentOrElse present/absent split ──

    @Test
    fun `validation fails for a malformed postal code`() {
        val view = newView()
        fillValidForm(view)
        addressFormSection(view).postalCode.value = "AB"

        assertFalse(validateForm(view))
        assertTrue(addressFormSection(view).postalCode.isInvalid)
    }

    // ── optionalValidatedNullable(phone) — the null/non-null message branch ──

    @Test
    fun `validation fails for a malformed phone number`() {
        val view = newView()
        fillValidForm(view)
        field(view, "phoneField").value = "not-a-phone-number"

        assertFalse(validateForm(view))
        assertTrue(field(view, "phoneField").isInvalid)
    }

    @Test
    fun `validation passes for a blank phone number`() {
        val view = newView()
        fillValidForm(view)
        field(view, "phoneField").value = ""

        assertTrue(validateForm(view))
        assertFalse(field(view, "phoneField").isInvalid)
    }

    // ── isConsumer branch — BUSINESS requires vatId, CONSUMER does not ──

    @Test
    fun `validation requires a VAT id for a BUSINESS recipient`() {
        val view = newView()
        fillValidForm(view)
        customerTypeField(view).value = CustomerType.BUSINESS
        field(view, "vatIdField").value = ""

        assertFalse(validateForm(view))
        assertTrue(field(view, "vatIdField").isInvalid)
    }

    @Test
    fun `validation fails for a BUSINESS recipient with a malformed VAT id`() {
        val view = newView()
        fillValidForm(view)
        customerTypeField(view).value = CustomerType.BUSINESS
        field(view, "vatIdField").value = "XX123456789"

        assertFalse(validateForm(view))
        assertTrue(field(view, "vatIdField").isInvalid)
    }

    @Test
    fun `validation passes for a BUSINESS recipient with a well-formed VAT id`() {
        val view = newView()
        fillValidForm(view)
        customerTypeField(view).value = CustomerType.BUSINESS
        field(view, "vatIdField").value = "DE123456789"

        assertTrue(validateForm(view))
        assertFalse(field(view, "vatIdField").isInvalid)
    }

    // ── ibanField — the `?.let { } ?: run { }` invalid/valid split ──

    @Test
    fun `validation fails for a malformed IBAN`() {
        val view = newView()
        fillValidForm(view)
        field(view, "ibanField").value = "not-an-iban"

        assertFalse(validateForm(view))
        assertTrue(field(view, "ibanField").isInvalid)
    }

    @Test
    fun `validation passes for a well-formed IBAN`() {
        val view = newView()
        fillValidForm(view)
        field(view, "ibanField").value = "DE89370400440532013000"

        assertTrue(validateForm(view))
        assertFalse(field(view, "ibanField").isInvalid)
    }

    // ── emailField — the non-blank malformed-value branch (blank/valid already covered) ──

    @Test
    fun `validation fails for a malformed email address`() {
        val view = newView()
        fillValidForm(view)
        field(view, "emailField").value = "not-an-email"

        assertFalse(validateForm(view))
        assertTrue(field(view, "emailField").isInvalid)
    }

    // ── grid sort listener — mapped columns, unmapped/name fallback, cleared order, both directions ──

    private fun stubRecipientSearch() {
        whenever(recipientQuery.findRecipients(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        whenever(recipientQuery.countRecipients(anyOrNull())).thenReturn(0L)
    }

    @Test
    fun `sorting by customerNumber refetches descending with the mapped sort field`() {
        stubRecipientSearch()
        val view = newView()
        val column = grid(view).getColumnByKey("customerNumber")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))
        grid(view)._fetch(0, 10)

        verify(recipientQuery, atLeastOnce())
            .findRecipients(any(), any(), anyOrNull(), eq("customerNumber"), eq(de.littletrotter.domain.shared.SortDirection.DESC))
    }

    @Test
    fun `sorting by city refetches ascending with the mapped sort field`() {
        stubRecipientSearch()
        val view = newView()
        val column = grid(view).getColumnByKey("city")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))
        grid(view)._fetch(0, 10)

        verify(recipientQuery, atLeastOnce())
            .findRecipients(any(), any(), anyOrNull(), eq("city"), eq(de.littletrotter.domain.shared.SortDirection.ASC))
    }

    @Test
    fun `sorting by contactPerson refetches with the mapped sort field`() {
        stubRecipientSearch()
        val view = newView()
        val column = grid(view).getColumnByKey("contactPerson")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))
        grid(view)._fetch(0, 10)

        verify(recipientQuery, atLeastOnce())
            .findRecipients(any(), any(), anyOrNull(), eq("contactPerson"), eq(de.littletrotter.domain.shared.SortDirection.DESC))
    }

    @Test
    fun `sorting by name falls through to the else branch`() {
        stubRecipientSearch()
        val view = newView()
        val column = grid(view).getColumnByKey("name")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))
        grid(view)._fetch(0, 10)

        verify(recipientQuery, atLeastOnce())
            .findRecipients(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.domain.shared.SortDirection.DESC))
    }

    @Test
    fun `clearing the sort order resets to name ascending`() {
        stubRecipientSearch()
        val view = newView()
        val column = grid(view).getColumnByKey("customerNumber")!!
        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        grid(view).sort(emptyList())
        grid(view)._fetch(0, 10)

        verify(recipientQuery, atLeastOnce())
            .findRecipients(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.domain.shared.SortDirection.ASC))
    }

    // ── applyFilter / persistEntity / delete — the remaining fully-uncovered lines ──

    private fun deliveryAddressFormSection(view: RecipientView): AddressFormSection {
        val f = RecipientView::class.java.getDeclaredField("deliveryAddressFormSection")
        f.isAccessible = true
        return f.get(view) as AddressFormSection
    }

    private fun deleteButton(view: RecipientView): Button {
        val f = AbstractCrudView::class.java.getDeclaredField("deleteButton")
        f.isAccessible = true
        return f.get(view) as Button
    }

    private fun setCurrentFormData(view: RecipientView, id: Long, version: Long) {
        val f = RecipientView::class.java.getDeclaredField("currentFormData")
        f.isAccessible = true
        val blank = f.get(view) as de.littletrotter.application.dto.RecipientDraft
        f.set(view, blank.copy(id = id, version = version))
    }

    private fun saveCurrentForm(view: RecipientView) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        m.isAccessible = true
        m.invoke(view)
    }

    private fun openEditor(view: RecipientView, entity: RecipientSummaryDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(view, entity)
    }

    @Test
    fun `boundary - typing into the toolbar filter field refreshes the grid through the existing data provider`() {
        stubRecipientSearch()
        val view = newView()
        com.vaadin.flow.component.UI.getCurrent().add(view)
        val filterField = _find<TextField>().single { it.prefixComponent != null }

        filterField.value = "acme"
        grid(view)._fetch(0, 10)

        verify(recipientQuery, atLeastOnce()).findRecipients(any(), any(), eq("acme"), any(), any())
    }

    @Test
    fun `boundary - opening an existing recipient with a stored delivery address populates the delivery form`() {
        val existing = aRecipient { id = 7L; customerNumber = CustomerNumber("KD-7"); name = "Beta AG" }
        whenever(recipientQuery.findRecipientById(7L)).thenReturn(existing.toRecipientSummaryDto())
        val deliveryAddress = Address.forRecipient(existing, Address.AddressType.DELIVERY, "Lieferstr. 1", "54321", "Köln", Country.DE)
        whenever(recipientQuery.findRecipientDetail(7L)).thenReturn(existing.toDetailDto(deliveryAddress = deliveryAddress))
        val view = newView()

        openEditor(view, existing.toRecipientSummaryDto())

        assertEquals("Lieferstr. 1", deliveryAddressFormSection(view).street.value)
        assertEquals("54321", deliveryAddressFormSection(view).postalCode.value)
        assertEquals("Köln", deliveryAddressFormSection(view).city.value)
    }

    @Test
    fun `boundary - a database-level optimistic lock during save surfaces as an error notification`() {
        val view = newView()
        setCurrentFormData(view, id = 8L, version = 1L)
        val stored = aRecipient { id = 8L; version = 1L }
        whenever(recipientQuery.findRecipientById(8L)).thenReturn(stored.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(8L)).thenReturn(stored.toDetailDto())
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenThrow(OptimisticLockException("conflict"))
        fillValidForm(view)

        saveCurrentForm(view)

        assertTrue(getNotifications().isNotEmpty(), "a database-level optimistic lock must still notify the user")
    }

    @Test
    fun `boundary - deleting an existing recipient calls the facade and clears the form`() {
        val existing = aRecipient { id = 9L; customerNumber = CustomerNumber("KD-9"); name = "Gamma AG" }
        whenever(recipientQuery.findRecipientById(9L)).thenReturn(existing.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(9L)).thenReturn(existing.toDetailDto())
        stubRecipientSearch()
        val view = newView()
        openEditor(view, existing.toRecipientSummaryDto())
        val dialog = _get<Dialog>()

        deleteButton(view)._click()

        assertFalse(dialog.isOpened)
        verify(manageRecipient).deleteRecipient(9L, existing.version)
        assertEquals("", field(view, "nameField").value)
    }
}
