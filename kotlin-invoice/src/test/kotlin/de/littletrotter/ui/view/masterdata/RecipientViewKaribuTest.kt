package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._size
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.textfield.TextField
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
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.NumberCollisionException
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.ui.view.invoice.toRecipientSummaryDto
import de.littletrotter.ui.view.invoice.toSenderSummaryDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

class RecipientViewKaribuTest {

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val vatIdVerificationPort: VerifyVatIdUseCase = mock()
    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()

    /** No PartyFormExtension registered, so the forms are extension-free. */
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

    @Test
    fun `grid data provider invokes facade`() {
        val sample = de.littletrotter.application.dto.RecipientSummaryDto(
            id = 7L,
            name = "Acme",
            customerNumber = "C-7",
            shortHash = null,
            contactPerson = null,
            routingId = null,
            vatId = null,
            customerType = null,
            vatIdVerificationStatus = null,
            reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
            billingCity = null,
            billingCountryAlpha2 = null,
            deliveryCountryAlpha2 = null,
        )

        whenever(recipientQuery.findRecipients(0, 50, null, "name", SortDirection.ASC)).thenReturn(listOf(sample))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val view = newView()
        val grid = view.grid
        grid._fetch(0, 50)
        grid._size()

        verify(recipientQuery).findRecipients(0, 50, null, "name", SortDirection.ASC)
        verify(recipientQuery).countRecipients(null)
    }

    private fun field(view: RecipientView, name: String): TextField {
        val f = RecipientView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun checkboxField(view: RecipientView, name: String): com.vaadin.flow.component.checkbox.Checkbox {
        val f = RecipientView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as com.vaadin.flow.component.checkbox.Checkbox
    }

    /** The customer has a single billing address. Fields (street, postalCode, city, country) live in
     *  [de.littletrotter.ui.component.AddressFormSection]; this reaches that section to access them directly. */
    private fun billingAddressSection(view: RecipientView): de.littletrotter.ui.component.AddressFormSection {
        val f = RecipientView::class.java.getDeclaredField("addressFormSection")
        f.isAccessible = true
        return f.get(view) as de.littletrotter.ui.component.AddressFormSection
    }

    /** B2B profile selector on the customer relationship. */
    private fun b2bProfileCombo(view: RecipientView): com.vaadin.flow.component.combobox.ComboBox<de.littletrotter.application.dto.B2bProfileDto> =
        view.b2bProfileCombo

    private fun currentFormData(view: RecipientView): de.littletrotter.application.dto.RecipientDraft {
        val f = RecipientView::class.java.getDeclaredField("currentFormData")
        f.isAccessible = true
        return f.get(view) as de.littletrotter.application.dto.RecipientDraft
    }

    /** [RecipientView.populateForm] now reads off a [RecipientDetailDto], not off
     *  [Recipient] directly; [toDetailDto] mirrors [de.littletrotter.application.masterdata
     *  .RecipientQueries.findRecipientDetail]'s field mapping so every existing fixture (built as
     *  a plain [Recipient]) keeps driving the same assertions unchanged. `partyPairs` is always
     *  empty here — every `newView()` in this file registers no [de.littletrotter.spi.party
     *  .PartyFormExtension], so the SPI pairs are covered separately (`PartyExtensionsSectionTest`). */
    private fun Recipient.toDetailDto(): RecipientDetailDto = RecipientDetailDto(
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

    private fun populate(view: RecipientView, recipient: Recipient) {
        // populateForm resolves the standing B2B profile off the DTO's b2bProfileId via
        // b2bProfileQueries.findB2bProfileById; every fixture here that sets Recipient.b2bProfile
        // directly needs that same id to resolve back to it (see RecipientQueries.findRecipientDetail).
        recipient.b2bProfile?.let { profile -> whenever(b2bProfileQueries.findB2bProfileById(profile.id!!)).thenReturn(profile.toDto()) }
        val m = RecipientView::class.java.getDeclaredMethod("populateForm", RecipientDetailDto::class.java)
        m.isAccessible = true
        m.invoke(view, recipient.toDetailDto())
    }

    private fun validateForm(view: RecipientView): Boolean {
        val m = RecipientView::class.java.getDeclaredMethod("validateForm")
        m.isAccessible = true
        return m.invoke(view) as Boolean
    }

    private fun clearForm(view: RecipientView) {
        val m = RecipientView::class.java.getDeclaredMethod("clearForm")
        m.isAccessible = true
        m.invoke(view)
    }

    /** Saves the draft built off the widgets' current state, as a new recipient — the same path
     *  `AbstractCrudView.saveCurrentForm` takes. */
    private fun persistForm(view: RecipientView) {
        val m = RecipientView::class.java.getDeclaredMethod("persistForm", de.littletrotter.application.dto.RecipientDraft::class.java, RecipientSummaryDto::class.java)
        m.isAccessible = true
        m.invoke(view, currentFormData(view), null)
    }

    private fun proposedCustomerNumber(view: RecipientView): String? {
        val f = RecipientView::class.java.getDeclaredField("proposedCustomerNumber")
        f.isAccessible = true
        return f.get(view) as String?
    }

    /** Minimal set of widget values `validateForm()` requires, so `persistForm` runs cleanly. */
    private fun fillMinimalValid(view: RecipientView, name: String) {
        field(view, "nameField").value = name
        field(view, "customerNumberField").value = field(view, "customerNumberField").value.ifBlank { "KD-1" }
        billingAddressSection(view).street.value = "Str 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        field(view, "emailField").value = "person@example.org"
    }

    /** [RecipientDetailDto] [ManageRecipientUseCase.saveRecipientWithAddresses] would return for a
     *  save that succeeded — everything not asserted on stays at a neutral default. */
    private fun savedDetail(id: Long): RecipientDetailDto = RecipientDetailDto(
        id = id, version = 1L, customerNumber = null, shortHash = null, name = "Saved", vatId = null,
        routingId = null, email = null, phone = null, contactPerson = null, iban = null, customerType = null,
        invoiceFormat = de.littletrotter.domain.shared.InvoiceFormat.GERMAN, documentLanguage = "de",
        reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
        buyerIdentifierValue = null, buyerIdentifierSchemeId = null, legalRegistrationValue = null,
        legalRegistrationSchemeId = null, b2bProfileId = null, partyPairs = emptyMap(),
        billingAddress = null, deliveryAddress = null, vatIdVerificationStatus = null, vatIdVerifiedAt = null,
        vatIdVerificationNote = null,
    )

    // ─── The customer-number proposal shown at dialog-open reaches the save call unchanged,
    // and an edited field is distinguishable from an accepted one downstream ───

    @Test
    fun `N3 - opening a new recipient dialog pre-fills the customer number with the proposal`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn("KD-00042")

        clearForm(view)

        assertEquals("KD-00042", field(view, "customerNumberField").value)
        assertEquals("KD-00042", proposedCustomerNumber(view))
    }

    @Test
    fun `N3 - no active range leaves the field blank without failing`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn(null)

        clearForm(view)

        assertEquals("", field(view, "customerNumberField").value)
        assertNull(proposedCustomerNumber(view))
    }

    @Test
    fun `N3-12 - saving the unchanged proposal passes it through to the draft`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn("KD-00042")
        clearForm(view)
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail(1L))
        fillMinimalValid(view, "Neu GmbH")

        persistForm(view)

        val captor = argumentCaptor<de.littletrotter.application.dto.RecipientDraft>()
        verify(manageRecipient).saveRecipientWithAddresses(captor.capture())
        assertEquals("KD-00042", captor.firstValue.proposedCustomerNumber)
    }

    /** Regression (02.09.): the delivery country combo defaults to DE, which used to make
     *  `hasDelivery` true on every untouched form — the save then carried an empty delivery
     *  address that Address.validate rejects, so no new recipient could be created at all. */
    @Test
    fun `an untouched delivery section saves with null delivery arguments despite the DE country default`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn(null)
        clearForm(view)
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail(1L))
        fillMinimalValid(view, "Ohne Lieferadresse GmbH")

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
    fun `N3-11 - editing the field before saving leaves the proposal unequal to what is stored`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn("KD-00042")
        clearForm(view)

        field(view, "customerNumberField").value = "KD-EIGENE-WAHL"
        val target = aRecipient { name = "Neu GmbH"; customerNumber = CustomerNumber.ofNullable(field(view, "customerNumberField").value) }

        assertEquals("KD-00042", proposedCustomerNumber(view))
        assertEquals("KD-EIGENE-WAHL", target.customerNumber?.value)
    }

    // N3-09: clearForm peeks with a nameless Recipient (name still empty at open); without
    // refreshing the proposal on name entry, the erroneous initial proposal carries through to save.
    @Test
    fun `N3-09 - typing a name after opening refreshes the proposal with that name`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn("KD-00001")
        clearForm(view)
        assertEquals("KD-00001", field(view, "customerNumberField").value)

        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn("MUE-00001")
        field(view, "nameField").value = "Müller GmbH"

        assertEquals("MUE-00001", field(view, "customerNumberField").value, "die Vorschau muss den neu abgeleiteten Präfix zeigen")
        assertEquals("MUE-00001", proposedCustomerNumber(view))
        // argThat (not check): Each recorded call is tested; check assertions fail on the first
        // (clearForm with empty name), Mockito treats that as no-match, and test passes with logged
        // stacktraces (reported as flaky). argThat returns boolean instead, no spurious log noise.
        verify(recipientQuery).proposeCustomerNumber(org.mockito.kotlin.argThat { this == "Müller GmbH" })
    }

    @Test
    fun `N3-09 - once the field is edited by hand, further name changes no longer overwrite it`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn("KD-00001")
        clearForm(view)

        field(view, "customerNumberField").value = "KD-EIGENE-WAHL"
        field(view, "nameField").value = "Müller GmbH"

        assertEquals("KD-EIGENE-WAHL", field(view, "customerNumberField").value, "ein von Hand eingetragener Wert darf nicht überschrieben werden")
    }

    @Test
    fun `N3 - editing an existing recipient never proposes a number`() {
        val view = newView()
        val existing = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Bestand GmbH" }

        populate(view, existing)

        verify(recipientQuery, org.mockito.kotlin.never()).proposeCustomerNumber(anyOrNull())
        assertNull(proposedCustomerNumber(view))
    }

    @Test
    fun `a NumberCollisionException from the save call is surfaced without crashing the UI`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn("KD-00042")
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenThrow(NumberCollisionException("KD-00042"))

        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Kollision GmbH"
            customerType = CustomerType.CONSUMER
        }
        populate(view, recipient)
        field(view, "nameField").value = "Kollision GmbH"
        field(view, "customerNumberField").value = "KD-00042"
        billingAddressSection(view).street.value = "Str 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        field(view, "emailField").value = "person@example.org"

        val saveCurrentForm = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        saveCurrentForm.isAccessible = true
        // Does not throw: AbstractCrudView.saveCurrentForm catches all exceptions from persistForm,
        // shows a Notification instead of propagating; dialog stays open (close/refreshGridData unreached).
        saveCurrentForm.invoke(view)
    }

    // ── A duplicate-customer-number save must show the typed domain exception's message,
    // never a raw ConstraintViolationException, and must not crash the UI. Mirrors the
    // NumberCollisionException test above exactly; the DB-level "second insert really throws it"
    // proof lives in UniqueConstraintDomainExceptionIT (real Postgres), this is the UI wiring.

    private fun flatten(component: com.vaadin.flow.component.Component): List<com.vaadin.flow.component.Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun notificationText(notification: com.vaadin.flow.component.notification.Notification): String =
        flatten(notification).filterIsInstance<com.vaadin.flow.component.html.Span>().joinToString(" ") { it.text ?: "" }

    @Test
    fun `a DuplicateCustomerNumberException from the save call shows its message without crashing the UI`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn("KD-00042")
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenThrow(de.littletrotter.domain.exception.DuplicateCustomerNumberException("KD-00042"))

        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Kollision GmbH"
            customerType = CustomerType.CONSUMER
        }
        populate(view, recipient)
        field(view, "nameField").value = "Kollision GmbH"
        field(view, "customerNumberField").value = "KD-00042"
        billingAddressSection(view).street.value = "Str 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        field(view, "emailField").value = "person@example.org"

        val saveCurrentForm = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        saveCurrentForm.isAccessible = true
        saveCurrentForm.invoke(view)

        val notification = com.github.mvysny.kaributesting.v10.getNotifications().single()
        assertTrue(
            notificationText(notification).contains("KD-00042"),
            "expected the duplicate customer number in the notification, got: ${notificationText(notification)}",
        )
    }

    @Test
    fun `scheme id fields stop typing at the VARCHAR(10) column limit`() {
        val view = newView()

        assertEquals(10, field(view, "buyerIdentifierSchemeField").maxLength)
        assertEquals(10, field(view, "legalRegistrationSchemeField").maxLength)
    }

    @Test
    fun `populateForm shows buyerIdentifier and legalRegistration as separate fields`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"
            buyerIdentifier = PartyIdentifier.of("123", "0088")
            electronicAddress = ElectronicAddress.of("buyer@example.org", "EM")
        }

        populate(view, recipient)

        assertEquals("123", field(view, "buyerIdentifierValueField").value)
        assertEquals("0088", field(view, "buyerIdentifierSchemeField").value)
    }

    @Test
    fun `the electronic address pair is not a hand-coded field on RecipientView`() {
        val view = newView()
        // The electronic address (BT-49) is contributed by a PartyFormExtension bean, rendered
        // generically via partySection; it must be absent from the class entirely, not merely
        // hidden.
        assertThrows(NoSuchFieldException::class.java) {
            RecipientView::class.java.getDeclaredField("electronicAddressValueField")
        }
        assertThrows(NoSuchFieldException::class.java) {
            RecipientView::class.java.getDeclaredField("electronicAddressSchemeField")
        }
    }

    @Test
    fun `a CONSUMER without vatId or iban passes validation`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Private Person"
            customerType = CustomerType.CONSUMER
        }
        populate(view, recipient)

        field(view, "nameField").value = "Private Person"
        field(view, "customerNumberField").value = "KD-1"
        billingAddressSection(view).street.value = "Str 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        field(view, "emailField").value = "person@example.org"

        assertTrue(validateForm(view))
    }

    // The port adapter always invokes (format check is the UI layer's job); this test ensures
    // formally invalid values never reach the wire.

    // verifyVatId's logic moved into RecipientVatIdVerificationComposer; `internal`
    // visibility lets this same-module test call its public verify() directly, no method
    // reflection needed (only the field holding it is private).
    private fun verifyVatId(view: RecipientView) {
        val f = RecipientView::class.java.getDeclaredField("vatIdVerification")
        f.isAccessible = true
        (f.get(view) as RecipientVatIdVerificationComposer).verify()
    }

    @Test
    fun `a formally invalid VAT id never reaches the VIES port`() {
        val view = newView()
        field(view, "vatIdField").value = "DE-nonsense-42"

        verifyVatId(view)

        org.mockito.kotlin.verifyNoInteractions(vatIdVerificationPort)
    }

    @Test
    fun `a blank VAT id never reaches the VIES port`() {
        val view = newView()
        field(view, "vatIdField").value = "   "

        verifyVatId(view)

        org.mockito.kotlin.verifyNoInteractions(vatIdVerificationPort)
    }

    @Test
    fun `an unreachable register leaves the captured status untouched`() {
        val view = newView()
        val recipient = aRecipient {
            name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
        }
        populate(view, recipient)
        whenever(vatIdVerificationPort.verify(any(), any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.UNREACHABLE(null))

        verifyVatId(view)

        assertEquals(
            de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED,
            recipient.vatIdVerificationStatus,
            "Unerreichbarkeit ist keine Aussage ueber die USt-IdNr. — der erfasste Status bleibt",
        )
    }

    // Bugfix: a VIES verification recorded during the dialog session must
    // reach the entity AbstractCrudView.saveCurrentForm actually persists (a freshly reloaded
    // row, never `currentRecipient`, the UI's in-memory copy). buildDraft is exercised
    // directly against a `target` object that stands in for that freshly loaded row/new entity —
    // the same reflection pattern the buildDraft tests above already use — so a passing
    // test here is only possible if the reconciliation in RecipientView.buildDraft actually
    // carries the verification onto the object being saved, not merely onto currentRecipient. ──

    @Test
    fun `bugfix - verify-then-save carries a pending VERIFIED verification onto the draft`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
        }
        populate(view, recipient)
        whenever(vatIdVerificationPort.verify(any(), any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.VERIFIED(null, null))

        verifyVatId(view)
        val draft = view.buildDraft()

        assertEquals("DE123456789", draft.pendingVatIdVerification?.vatId)
        assertEquals(de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED, draft.pendingVatIdVerification?.status)
        assertTrue(draft.vatIdValidatedByUser)
    }

    @Test
    fun `bugfix - verify on a new recipient then save carries the pending verification`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn(null)
        clearForm(view)
        field(view, "vatIdField").value = "DE123456789"
        whenever(vatIdVerificationPort.verify(any(), any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.VERIFIED(null, null))

        verifyVatId(view)
        val draft = view.buildDraft()

        assertEquals(de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED, draft.pendingVatIdVerification?.status)
    }

    @Test
    fun `bugfix - verifying, then changing the VAT id, leaves the pending verification for the old id`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
        }
        populate(view, recipient)
        whenever(vatIdVerificationPort.verify(any(), any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.VERIFIED(null, null))
        verifyVatId(view)

        // The user edits the VAT id after verifying it — the proof captured above no longer
        // belongs to the id now on the form (an unverified id must never coast on a
        // stale proof for a different id); ManageRecipient's reconciliation is what actually
        // detects the mismatch and resets to UNVERIFIED (ManageRecipientTest covers that).
        field(view, "vatIdField").value = "DE987654321"

        val draft = view.buildDraft()

        assertEquals("DE987654321", draft.vatId)
        assertEquals("DE123456789", draft.pendingVatIdVerification?.vatId)
    }

    @Test
    fun `bugfix - a FAILED VIES outcome carries a pending FAILED verification onto the draft`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
        }
        populate(view, recipient)
        whenever(vatIdVerificationPort.verify(any(), any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.FAILED)

        verifyVatId(view)
        val draft = view.buildDraft()

        assertEquals(de.littletrotter.shared.tax.VatIdVerificationStatus.FAILED, draft.pendingVatIdVerification?.status)
    }

    // B2B profile selector on the customer relationship.

    @Test
    fun `populateForm shows the recipient's standing B2B profile default`() {
        val profile = de.littletrotter.domain.reference.B2bProfile().edit { id = 5L; name = "Premium" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"; b2bProfile = profile }

        populate(view, recipient)

        assertEquals(profile.toDto(), b2bProfileCombo(view).value)
    }

    @Test
    fun `populateForm with inactive B2B profile includes it in combo items and selects it`() {
        val activeProfile = de.littletrotter.domain.reference.B2bProfile().edit { id = 5L; name = "Active Profile" }
        val inactiveProfile = de.littletrotter.domain.reference.B2bProfile().edit { id = 99L; name = "Inactive Profile" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(activeProfile.toDto()))
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"; b2bProfile = inactiveProfile }

        populate(view, recipient)

        val combo = b2bProfileCombo(view)
        assertEquals(inactiveProfile.toDto(), combo.value)
        val items = (combo.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<de.littletrotter.application.dto.B2bProfileDto>).items
        assertTrue(items.contains(inactiveProfile.toDto()))
    }

    @Test
    fun `a new recipient's B2B profile combo starts empty`() {
        val profile = de.littletrotter.domain.reference.B2bProfile().edit { id = 5L; name = "Premium" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn(null)
        val view = newView()

        clearForm(view)

        assertNull(b2bProfileCombo(view).value)
    }

    @Test
    fun `buildDraft writes the selected B2B profile id onto the draft`() {
        val profile = de.littletrotter.domain.reference.B2bProfile().edit { id = 7L; name = "Standard" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        val view = newView()
        b2bProfileCombo(view).value = profile.toDto()

        val draft = view.buildDraft()

        assertEquals(7L, draft.b2bProfileId)
    }

    @Test
    fun `buildDraft writes null when no B2B profile is selected (Community behavior)`() {
        val view = newView()

        val draft = view.buildDraft()

        assertNull(draft.b2bProfileId, "eine leere Auswahl muss ein zuvor gesetztes Profil wieder auf null zurücksetzen")
    }

    // Customer form B2B fields section (per-recipient RECIPIENT-placed values).

    private fun b2bFieldsSection(view: RecipientView): de.littletrotter.ui.component.B2bProfileFieldsSection {
        val f = RecipientView::class.java.getDeclaredField("b2bFieldsSection")
        f.isAccessible = true
        return f.get(view) as de.littletrotter.ui.component.B2bProfileFieldsSection
    }

    private fun b2bFieldsContainer(view: RecipientView): com.vaadin.flow.component.html.Div {
        val f = RecipientView::class.java.getDeclaredField("b2bFieldsContainer")
        f.isAccessible = true
        return f.get(view) as com.vaadin.flow.component.html.Div
    }

    private fun recipientPlacedField(): de.littletrotter.application.dto.B2bProfileFieldDto =
        de.littletrotter.application.dto.B2bProfileFieldDto(
            id = 77L,
            term = de.littletrotter.shared.b2b.B2bTerm.BT_58,
            businessDefinition = "Käufer-Kontakt-E-Mail",
            placement = de.littletrotter.domain.reference.B2bPlacement.RECIPIENT,
        )

    @Test
    fun `the B2B-Felder section renders once the assigned profile has a RECIPIENT-placed field`() {
        val profile = de.littletrotter.domain.reference.B2bProfile().edit { id = 5L; name = "Nekar" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(5L)).thenReturn(listOf(recipientPlacedField()))
        whenever(b2bFieldValueQueries.findB2bRecipientFieldValues(1L, 5L)).thenReturn(mapOf("BT-58" to "kunde@example.com"))
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"; b2bProfile = profile }

        populate(view, recipient)

        assertTrue(b2bFieldsContainer(view).isVisible)
        assertTrue(b2bFieldsSection(view).hasContent)
        assertEquals(mapOf("BT-58" to "kunde@example.com"), b2bFieldsSection(view).currentValues())
    }

    @Test
    fun `the B2B-Felder section stays hidden when the assigned profile has no RECIPIENT-placed field`() {
        val profile = de.littletrotter.domain.reference.B2bProfile().edit { id = 5L; name = "Standard" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(5L)).thenReturn(emptyList())
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"; b2bProfile = profile }

        populate(view, recipient)

        assertTrue(!b2bFieldsContainer(view).isVisible)
        assertTrue(!b2bFieldsSection(view).hasContent)
    }

    @Test
    fun `the section stays empty for a not-yet-persisted recipient even with a profile assigned`() {
        // clearForm's "peek" recipient has no id yet -- there is nothing to load
        // recipient_b2b_field_value rows against.
        val profile = de.littletrotter.domain.reference.B2bProfile().edit { id = 5L; name = "Nekar" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(5L)).thenReturn(listOf(recipientPlacedField()))
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn(null)
        val view = newView()
        clearForm(view)

        b2bProfileCombo(view).value = profile.toDto()

        verify(b2bFieldValueQueries, org.mockito.kotlin.never()).findB2bRecipientFieldValues(any(), any())
        // The section still renders the (now blank) widget for the profile's RECIPIENT-placed
        // field — nothing to load recipient_b2b_field_value rows against yet, not "no field at all".
        assertEquals(mapOf("BT-58" to null), b2bFieldsSection(view).currentValues())
    }

    @Test
    fun `persistForm saves the entered per-customer field values after the recipient itself is saved`() {
        val profile = de.littletrotter.domain.reference.B2bProfile().edit { id = 5L; name = "Nekar" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(5L)).thenReturn(listOf(recipientPlacedField()))
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"; b2bProfile = profile }
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail(1L))
        populate(view, recipient)
        b2bFieldsSection(view).readFrom(mapOf("BT-58" to "kunde@example.com"))

        persistForm(view)

        verify(manageB2bFieldValue).saveB2bRecipientFieldValues(1L, 5L, mapOf("BT-58" to "kunde@example.com"))
    }

    @Test
    fun `persistForm never calls the facade when the section carries no RECIPIENT-placed field`() {
        val profile = de.littletrotter.domain.reference.B2bProfile().edit { id = 5L; name = "Standard" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(5L)).thenReturn(emptyList())
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"; b2bProfile = profile }
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(savedDetail(1L))
        populate(view, recipient)

        persistForm(view)

        verify(manageB2bFieldValue, org.mockito.kotlin.never()).saveB2bRecipientFieldValues(any(), any(), any())
    }

    // Read-only shortHash field (mirrors ArticleView pattern).

    @Test
    fun `shortHash field is read-only with the article-mirroring label and helper text`() {
        val view = newView()
        val shortHashField = field(view, "shortHashField")

        assertTrue(shortHashField.isReadOnly, "short_hash is never user-editable on Recipient")
        assertEquals("Kennung (technisch)", shortHashField.label)
        assertEquals(
            "Eindeutige technische Kennung für interne Referenzen — erscheint nicht auf der " +
                "Rechnung. Wird beim Speichern automatisch vergeben.",
            shortHashField.helperText,
        )
    }

    @Test
    fun `shortHash field is blank for a brand-new recipient`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn(null)

        clearForm(view)

        assertEquals("", field(view, "shortHashField").value)
    }

    @Test
    fun `shortHash field shows the persisted value for an existing recipient`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"
            shortHash = "aBcDeFgHiJ"
        }

        populate(view, recipient)

        assertEquals("aBcDeFgHiJ", field(view, "shortHashField").value)
    }

    @Test
    fun `RecipientDraft carries no shortHash field to write back at all`() {
        // Display-only; unlike ArticleView's shortHashField, Recipient's stays never-user-editable:
        // ensureShortHash() is the only writer. Structurally guaranteed —
        // RecipientDraft simply has no shortHash property buildDraft could populate.
        assertThrows(NoSuchFieldException::class.java) {
            de.littletrotter.application.dto.RecipientDraft::class.java.getDeclaredField("shortHash")
        }
    }

    private fun spanField(view: RecipientView, name: String): com.vaadin.flow.component.html.Span {
        val f = RecipientView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as com.vaadin.flow.component.html.Span
    }

    @Test
    fun `the read-only vat id status field is gone -- the checkbox is the sole surface`() {
        assertThrows(NoSuchFieldException::class.java) {
            RecipientView::class.java.getDeclaredField("vatIdVerificationStatusField")
        }
    }

    @Test
    fun `populateForm shows the FAILED badge and hides it for VERIFIED or UNVERIFIED`() {
        val view = newView()
        val failedAt = java.time.OffsetDateTime.now().minusDays(2)

        populate(
            view,
            aRecipient {
                id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"; vatId = VatId("DE123456789")
                vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.FAILED
                vatIdVerifiedAt = failedAt
            },
        )
        val badge = spanField(view, "vatIdFailedBadge")
        assertTrue(badge.isVisible, "FAILED must render as a visible badge")
        assertTrue(badge.text.contains("fehlgeschlagen"), badge.text)
        assertTrue(!checkboxField(view, "vatIdValidatedCheckbox").value, "FAILED must not read as checked")

        populate(
            view,
            aRecipient {
                id = 2L; customerNumber = CustomerNumber("KD-2"); name = "Beta"; vatId = VatId("DE987654321")
                vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
                vatIdVerifiedAt = java.time.OffsetDateTime.now()
            },
        )
        assertTrue(!badge.isVisible, "VERIFIED must hide the FAILED badge")

        populate(
            view,
            aRecipient {
                id = 3L; customerNumber = CustomerNumber("KD-3"); name = "Gamma"; vatId = VatId("DE111111111")
                vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
            },
        )
        assertTrue(!badge.isVisible, "UNVERIFIED must hide the FAILED badge")
    }

    @Test
    fun `a VERIFIED status loaded from a previous session shows a timestamp-only helper text`() {
        val view = newView()
        val verifiedAt = java.time.OffsetDateTime.of(2026, 8, 1, 10, 0, 0, 0, java.time.ZoneOffset.UTC)

        populate(
            view,
            aRecipient {
                id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"; vatId = VatId("DE123456789")
                vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
                vatIdVerifiedAt = verifiedAt
            },
        )

        val helper = checkboxField(view, "vatIdValidatedCheckbox").helperText
        assertTrue(helper.contains("01.08.2026"), helper)
        assertTrue(
            !helper.contains("VIES") && !helper.contains("manuell"),
            "no provenance can be claimed for a status loaded from a previous session (no persisted column for it): $helper",
        )
    }

    @Test
    fun `a fresh VIES success shows a via-VIES helper text`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
        }
        populate(view, recipient)
        whenever(vatIdVerificationPort.verify(any(), any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.VERIFIED(null, null))

        verifyVatId(view)

        val helper = checkboxField(view, "vatIdValidatedCheckbox").helperText
        assertTrue(helper.contains("VIES"), helper)
    }

    @Test
    fun `checking the box by hand shows a manual helper text`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
        }
        populate(view, recipient)

        checkboxField(view, "vatIdValidatedCheckbox").value = true

        val helper = checkboxField(view, "vatIdValidatedCheckbox").helperText
        assertTrue(helper.contains("manuell"), helper)
    }

    @Test
    fun `populateForm checks the box for a VERIFIED recipient and unchecks it otherwise`() {
        val view = newView()

        populate(view, aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"; vatId = VatId("DE123456789"); vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED })
        assertTrue(checkboxField(view, "vatIdValidatedCheckbox").value)

        populate(view, aRecipient { id = 2L; customerNumber = CustomerNumber("KD-2"); name = "Beta"; vatId = VatId("DE987654321"); vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED })
        assertTrue(!checkboxField(view, "vatIdValidatedCheckbox").value)

        populate(view, aRecipient { id = 3L; customerNumber = CustomerNumber("KD-3"); name = "Gamma"; vatId = VatId("DE111111111"); vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.FAILED })
        assertTrue(!checkboxField(view, "vatIdValidatedCheckbox").value, "FAILED must not read as checked")
    }

    @Test
    fun `checking the box carries vatIdValidatedByUser=true onto the draft`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
        }
        populate(view, recipient)

        checkboxField(view, "vatIdValidatedCheckbox").value = true
        val draft = view.buildDraft()

        assertTrue(draft.vatIdValidatedByUser)
    }

    @Test
    fun `unchecking the box carries vatIdValidatedByUser=false onto the draft`() {
        val view = newView()
        val verifiedAt = java.time.OffsetDateTime.now().minusDays(1)
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
            vatIdVerifiedAt = verifiedAt
        }
        populate(view, recipient)
        assertTrue(checkboxField(view, "vatIdValidatedCheckbox").value)

        checkboxField(view, "vatIdValidatedCheckbox").value = false
        val draft = view.buildDraft()

        assertFalse(draft.vatIdValidatedByUser)
    }

    @Test
    fun `a brand-new recipient can be manually marked as validated without an automated check`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(anyOrNull())).thenReturn(null)
        clearForm(view)
        field(view, "vatIdField").value = "DE123456789"
        checkboxField(view, "vatIdValidatedCheckbox").value = true

        val draft = view.buildDraft()

        assertTrue(draft.vatIdValidatedByUser)
        assertNull(draft.pendingVatIdVerification, "no automated check ran this session")
    }

    @Test
    fun `verifyVatId syncs the checkbox to checked on a VERIFIED result`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
        }
        populate(view, recipient)
        assertTrue(!checkboxField(view, "vatIdValidatedCheckbox").value)
        whenever(vatIdVerificationPort.verify(any(), any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.VERIFIED(null, null))

        verifyVatId(view)

        assertTrue(checkboxField(view, "vatIdValidatedCheckbox").value)
    }

    @Test
    fun `verifyVatId syncs the checkbox to unchecked on a FAILED result even if it was checked before`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
        }
        populate(view, recipient)
        assertTrue(checkboxField(view, "vatIdValidatedCheckbox").value)
        whenever(vatIdVerificationPort.verify(any(), any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.FAILED)

        verifyVatId(view)

        assertTrue(!checkboxField(view, "vatIdValidatedCheckbox").value)
    }

    @Test
    fun `the validation note field round-trips through populateForm and buildDraft`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationNote = "Bestätigt am 01.01.2026 per qualifizierter Bestätigungsabfrage."
        }

        populate(view, recipient)

        assertEquals("Bestätigt am 01.01.2026 per qualifizierter Bestätigungsabfrage.", field(view, "vatIdValidationNoteField").value)

        field(view, "vatIdValidationNoteField").value = "Geändert."
        val draft = view.buildDraft()

        assertEquals("Geändert.", draft.vatIdValidationNote)
    }

    @Test
    fun `a blank validation note is persisted as null`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatIdVerificationNote = "Notiz"
        }
        populate(view, recipient)

        field(view, "vatIdValidationNoteField").value = ""
        val draft = view.buildDraft()

        assertNull(draft.vatIdValidationNote)
    }

    @Test
    fun `the validation note is saved independently of the checkbox state`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"
            vatId = VatId("DE123456789")
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED
        }
        populate(view, recipient)
        field(view, "vatIdValidationNoteField").value = "Telefonisch bestätigt."
        // Checkbox stays unchecked -- the note must still be written.

        val draft = view.buildDraft()

        assertEquals("Telefonisch bestätigt.", draft.vatIdValidationNote)
        assertFalse(draft.vatIdValidatedByUser)
    }

    // ── invoice-from-customer-row — the per-row "Rechnung erstellen" action column ───────────────

    private fun grid(view: RecipientView): com.vaadin.flow.component.grid.Grid<RecipientSummaryDto> = view.grid

    /** The action column's rendered [NativeButton] for [recipient] — reached through the
     *  column's own component renderer via reflection (its `createComponent` takes the item's
     *  real type, which the column's already-checked `Renderer<T>` property does not carry as a
     *  [ComponentRenderer] without an unchecked cast); [recipient] need not be a row currently
     *  attached to the grid. */
    private fun createInvoiceButton(view: RecipientView, recipient: RecipientSummaryDto): NativeButton {
        val column = grid(view).columns.last()
        return column.renderer.javaClass.getMethod("createComponent", Any::class.java)
            .invoke(column.renderer, recipient) as NativeButton
    }

    private fun editorDialog(view: RecipientView): Dialog {
        val f = AbstractCrudView::class.java.getDeclaredField("editorDialog")
        f.isAccessible = true
        return f.get(view) as Dialog
    }

    private fun preselectedRecipientIn(dialog: Dialog): RecipientSummaryDto? {
        val form = dialog.children.toList().filterIsInstance<FormLayout>().first()
        val combo = form.children.toList().filterIsInstance<ComboBox<*>>()[1]
        return combo.value as RecipientSummaryDto?
    }

    @Test
    fun `every row's create-invoice action carries the shared aria-label and tooltip`() {
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme" }.toRecipientSummaryDto()

        val button = createInvoiceButton(view, recipient)

        val expected = UiTranslations.tr("recipient.grid.createInvoice")
        assertEquals(expected, button.element.getAttribute("aria-label"))
        assertEquals(expected, button.element.getAttribute("title"))
    }

    @Test
    fun `clicking the create-invoice action opens InvoiceCreateDialog preselected for that row without opening the editor`() {
        whenever(senderQuery.findAllSenders()).thenReturn(
            listOf(aSenderProfile { id = 1L; name = "Anbieter GmbH"; isDefault = true }.toSenderSummaryDto()),
        )
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        val view = newView()
        val recipient = aRecipient { id = 5L; customerNumber = CustomerNumber("KD-5"); name = "Beta AG" }.toRecipientSummaryDto()

        createInvoiceButton(view, recipient)._click()

        val dialog = _get<Dialog>()
        assertEquals(UiTranslations.tr("invoice.dialog.title"), dialog.headerTitle)
        assertEquals(recipient, preselectedRecipientIn(dialog))
        assertFalse(editorDialog(view).isOpened, "the grid's own row-select editor must not also open for this click")
    }

    @Test
    fun `a plain row selection still opens the editor`() {
        val recipient = aRecipient { id = 5L; customerNumber = CustomerNumber("KD-5"); name = "Beta AG" }
        whenever(recipientQuery.findRecipientById(5L)).thenReturn(recipient.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(5L)).thenReturn(recipient.toDetailDto())
        val view = newView()

        grid(view).asSingleSelect().value = recipient.toRecipientSummaryDto()

        assertTrue(editorDialog(view).isOpened, "selecting a row must still open the editor dialog")
    }
}
