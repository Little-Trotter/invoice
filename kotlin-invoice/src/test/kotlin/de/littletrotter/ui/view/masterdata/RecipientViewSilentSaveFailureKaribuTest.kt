package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
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
import de.littletrotter.fixture.aRecipient
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.view.invoice.toRecipientSummaryDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

/**
 * PO report 04.09.2026, narrowed: "Änderung von Kundendaten wird nicht angenommen" /
 * "Speichern-Knopf tut sichtbar nichts", "Keine Meldung" — specifically editing an EXISTING
 * recipient.
 *
 * Root cause: [RecipientView.writeValidatedForm] used to be exactly `validateForm()` — no
 * notification on `false`. Every other [de.littletrotter.ui.view.AbstractCrudView] subclass
 * notifies on its own validation failure (see that class's `writeValidated` Binder helper and its
 * KDoc on `writeValidatedForm`); `RecipientView` hand-validates without a `Binder` and used to skip
 * this. Compounding it, the dialog has six tabs and `validateForm` marks fields invalid wherever
 * they happen to live — a field on a tab that is not the one currently shown renders no visible
 * error at all. An existing recipient that predates a since-added mandatory field (email became
 * mandatory after some records were created) hits this on every edit: the save button visibly does
 * nothing, forever, for that record.
 *
 * These tests pin the fix: [RecipientView.writeValidatedForm] must notify AND reveal (switch to
 * the tab holding, then focus) the first invalid field.
 */
class RecipientViewSilentSaveFailureKaribuTest {

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

    private fun tabs(view: RecipientView): TabSheet {
        val f = RecipientView::class.java.getDeclaredField("tabs")
        f.isAccessible = true
        return f.get(view) as TabSheet
    }

    private fun fieldTabs(view: RecipientView): Map<Component, Tab> = view.fieldTabs

    /** Marks [view] as editing an existing (already-persisted) recipient — the shape
     *  [AbstractCrudView.saveCurrentForm] needs to take the `findEntityById` branch instead of
     *  `newEntity()`, matching the PO's exact "editing an existing customer" scenario.
     *  `currentFormData` stays `protected` on the override, hence reflection. */
    private fun markAsExisting(view: RecipientView, id: Long) {
        val f = RecipientView::class.java.getDeclaredField("currentFormData")
        f.isAccessible = true
        val blank = f.get(view) as de.littletrotter.application.dto.RecipientDraft
        // Recipient.version defaults to 0 (not null); match that here so the optimistic-lock
        // compare against findEntityById's freshly loaded (also default-0) entity agrees.
        f.set(view, blank.copy(id = id, version = 0L))
    }

    private fun saveCurrentForm(view: RecipientView) {
        val m = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        m.isAccessible = true
        m.invoke(view)
    }

    /** Selects the "B2B" tab (index 4) — none of the fields under test live there, so a passing
     *  assertion that the identity/address/tax/delivery tab is selected afterwards proves an
     *  actual switch happened, not merely that the dialog's default tab already matched. */
    private fun selectSomeOtherTab(view: RecipientView) {
        tabs(view).selectedIndex = 4
    }

    @Test
    fun `an existing recipient missing the mandatory email notifies instead of doing nothing`() {
        val view = newView()
        markAsExisting(view, 5L)
        selectSomeOtherTab(view)

        // A legacy record: complete except for email, which became mandatory after it was created.
        textField(view, "nameField").value = "Alt-Kunde GmbH"
        textField(view, "customerNumberField").value = "KD-LEGACY-1"
        billingAddressSection(view).street.value = "Altstraße 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "vatIdField").value = "DE136695976"
        // emailField deliberately left blank.

        saveCurrentForm(view)

        assertTrue(
            textField(view, "emailField").isInvalid,
            "a validation failure that blocks the save must tell the user why — before the fix this was silent",
        )
        verify(manageRecipient, never()).saveRecipientWithAddresses(any())
    }

    @Test
    fun `the missing email is marked invalid and its tab is revealed`() {
        val view = newView()
        markAsExisting(view, 5L)
        selectSomeOtherTab(view)

        textField(view, "nameField").value = "Alt-Kunde GmbH"
        textField(view, "customerNumberField").value = "KD-LEGACY-1"
        billingAddressSection(view).street.value = "Altstraße 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "vatIdField").value = "DE136695976"

        saveCurrentForm(view)

        val emailField = textField(view, "emailField")
        assertTrue(emailField.isInvalid, "the offending field must be marked, not just refused silently")
        val expectedTab = fieldTabs(view)[emailField]
        assertEquals(expectedTab, tabs(view).selectedTab, "the tab hosting the invalid field must be the one shown")
    }

    @Test
    fun `a non-CONSUMER existing recipient with a rejected VAT id format is revealed on the tax tab`() {
        val view = newView()
        markAsExisting(view, 6L)
        selectSomeOtherTab(view)

        textField(view, "nameField").value = "Format-Fehler GmbH"
        textField(view, "customerNumberField").value = "KD-LEGACY-2"
        billingAddressSection(view).street.value = "Straße 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "emailField").value = "kunde@example.org"
        textField(view, "vatIdField").value = "NOT-A-VAT-ID"

        saveCurrentForm(view)

        val vatIdField = textField(view, "vatIdField")
        assertTrue(vatIdField.isInvalid)
        val expectedTab = fieldTabs(view)[vatIdField]
        assertEquals(expectedTab, tabs(view).selectedTab)
    }

    @Test
    fun `an incomplete billing address on an existing recipient is revealed on the address tab`() {
        val view = newView()
        markAsExisting(view, 7L)
        selectSomeOtherTab(view)

        textField(view, "nameField").value = "Unvollständig GmbH"
        textField(view, "customerNumberField").value = "KD-LEGACY-3"
        textField(view, "emailField").value = "kunde@example.org"
        textField(view, "vatIdField").value = "DE136695976"
        // Billing address left entirely blank.

        saveCurrentForm(view)

        assertTrue(billingAddressSection(view).street.isInvalid)
        val expectedTab = fieldTabs(view)[billingAddressSection(view).street]
        assertEquals(expectedTab, tabs(view).selectedTab)
    }

    @Test
    fun `a fully valid edit of an existing recipient saves without any notification about validation`() {
        val view = newView()
        markAsExisting(view, 8L)
        whenever(recipientQuery.findRecipientById(8L)).thenReturn(aRecipient { id = 8L }.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(8L)).thenReturn(
            de.littletrotter.application.dto.RecipientDetailDto(
                id = 8L, version = 0L, customerNumber = null, shortHash = null, name = "Vollständig GmbH", vatId = null,
                routingId = null, email = null, phone = null, contactPerson = null, iban = null, customerType = null,
                invoiceFormat = de.littletrotter.domain.shared.InvoiceFormat.GERMAN, documentLanguage = "de",
                reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
                buyerIdentifierValue = null, buyerIdentifierSchemeId = null, legalRegistrationValue = null,
                legalRegistrationSchemeId = null, b2bProfileId = null, partyPairs = emptyMap(),
                billingAddress = null, deliveryAddress = null, vatIdVerificationStatus = null, vatIdVerifiedAt = null,
                vatIdVerificationNote = null,
            ),
        )
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(
            de.littletrotter.application.dto.RecipientDetailDto(
                id = 8L, version = 1L, customerNumber = null, shortHash = null, name = "Vollständig GmbH", vatId = null,
                routingId = null, email = null, phone = null, contactPerson = null, iban = null, customerType = null,
                invoiceFormat = de.littletrotter.domain.shared.InvoiceFormat.GERMAN, documentLanguage = "de",
                reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
                buyerIdentifierValue = null, buyerIdentifierSchemeId = null, legalRegistrationValue = null,
                legalRegistrationSchemeId = null, b2bProfileId = null, partyPairs = emptyMap(),
                billingAddress = null, deliveryAddress = null, vatIdVerificationStatus = null, vatIdVerifiedAt = null,
                vatIdVerificationNote = null,
            ),
        )

        textField(view, "nameField").value = "Vollständig GmbH"
        textField(view, "customerNumberField").value = "KD-OK-1"
        billingAddressSection(view).street.value = "Musterstraße 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "emailField").value = "kunde@example.org"
        textField(view, "vatIdField").value = "DE136695976"

        saveCurrentForm(view)

        assertFalse(
            emailFieldOrAnyInvalid(view),
            "a fully valid form must not have any field marked invalid",
        )
        verify(manageRecipient).saveRecipientWithAddresses(any())
    }

    private fun emailFieldOrAnyInvalid(view: RecipientView): Boolean =
        listOf("nameField", "customerNumberField", "vatIdField", "ibanField", "emailField", "phoneField")
            .map { textField(view, it) }
            .any { it.isInvalid } || billingAddressSection(view).street.isInvalid
}
