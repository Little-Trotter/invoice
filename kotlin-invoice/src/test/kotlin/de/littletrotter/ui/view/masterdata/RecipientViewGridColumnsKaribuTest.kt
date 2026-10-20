package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getFormattedRow
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
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.view.invoice.toRecipientSummaryDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

/**
 * [RecipientView.configureGrid]'s city/country and shortHash columns are plain
 * `ValueProvider` lambdas, never invoked by Grid unless a row is actually formatted — same "0%
 * branch coverage despite the column-header assertions" gap
 * [ArticleViewCoverageKaribuTest] already found and fixed for
 * [ArticleView]. Neither [RecipientViewKaribuTest] nor [RecipientViewCoverageKaribuTest] ever
 * calls `_getFormattedRow` on this grid.
 */
class RecipientViewGridColumnsKaribuTest {

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

    private fun grid(view: RecipientView): de.littletrotter.ui.component.DataTable<RecipientSummaryDto> = view.grid

    /** Mirrors [de.littletrotter.application.masterdata.RecipientQueries
     *  .findRecipientDetail]'s mapping so existing [Recipient] fixtures keep driving
     *  [RecipientView.populateForm]/`readFormData` unchanged. */
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

    // ── city/country column (line 497-499): billing address present vs. absent ─────────────────

    @Test
    fun `the city-country column renders blank when the recipient has no billing address`() {
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme" }

        val row = grid(view)._getFormattedRow(recipient.toRecipientSummaryDto())

        assertEquals("", row[2], "cityCountry column with no billing address")
    }

    @Test
    fun `the city-country column renders city and country when a billing address is present`() {
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme" }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Weg 1", "12345", "Berlin", Country.DE))

        val row = grid(view)._getFormattedRow(recipient.toRecipientSummaryDto())

        assertEquals("Berlin, Deutschland", row[2], "cityCountry column with a billing address")
    }

    // ── shortHash column (line 504): the em-dash / real-value split ────────────────────────────

    @Test
    fun `the shortHash column renders the em dash placeholder when absent`() {
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"; shortHash = null }

        val row = grid(view)._getFormattedRow(recipient.toRecipientSummaryDto())

        assertEquals("—", row[4], "shortHash column")
    }

    @Test
    fun `the shortHash column renders the real value when present`() {
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme"; shortHash = "AB12" }

        val row = grid(view)._getFormattedRow(recipient.toRecipientSummaryDto())

        assertEquals("AB12", row[4], "shortHash column")
    }

    // ── formDataFrom (lines 790-793): never exercised directly — every existing test bypasses it
    // by calling populateForm/clearForm through reflection instead of the openEditor(entity) path
    // that actually calls it. ───────────────────────────────────────────────────────────────────

    private fun formDataFrom(view: RecipientView, entity: RecipientSummaryDto?): de.littletrotter.application.dto.RecipientDraft {
        val m = RecipientView::class.java.getDeclaredMethod("formDataFrom", RecipientSummaryDto::class.java)
        m.isAccessible = true
        return m.invoke(view, entity) as de.littletrotter.application.dto.RecipientDraft
    }

    @Test
    fun `formDataFrom with a null entity returns a blank draft`() {
        val view = newView()

        val result = formDataFrom(view, null)

        assertNull(result.id)
    }

    // "formDataFrom with an entity that has no id yet" (a transient Recipient, id == null) is
    // gone: RecipientSummaryDto.id is a non-null Long, so that state is no longer
    // representable by the grid/entity type — formDataFrom(entity) is only ever called with
    // either null (the "new recipient" dialog) or a real, persisted row's summary.

    @Test
    fun `formDataFrom with a persisted id re-loads the draft through the facade`() {
        val view = newView()
        val reloaded = aRecipient { id = 5L; name = "Reloaded" }
        whenever(recipientQuery.findRecipientById(5L)).thenReturn(reloaded.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(5L)).thenReturn(reloaded.toDetailDto())

        val result = formDataFrom(view, reloaded.toRecipientSummaryDto())

        assertEquals("Reloaded", result.name)
    }

    @Test
    fun `formDataFrom throws when the facade can no longer find the persisted id`() {
        val view = newView()
        whenever(recipientQuery.findRecipientDetail(9L)).thenReturn(null)

        val ex = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            formDataFrom(view, aRecipient { id = 9L }.toRecipientSummaryDto())
        }
        assertEquals(IllegalStateException::class.java, ex.cause?.javaClass)
    }

    // ── populateForm's UiNotifications.orEmpty(x?.value) chains: every existing fixture leaves
    // iban/email/legalRegistrationIdentifier null, so only their null branch was ever exercised. ──

    private fun populateForm(view: RecipientView, recipient: Recipient) {
        val m = RecipientView::class.java.getDeclaredMethod("populateForm", RecipientDetailDto::class.java)
        m.isAccessible = true
        m.invoke(view, recipient.toDetailDto())
    }

    private fun textField(view: RecipientView, name: String): com.vaadin.flow.component.textfield.TextField {
        val f = RecipientView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as com.vaadin.flow.component.textfield.TextField
    }

    @Test
    fun `populateForm shows the iban, email and legal registration identifier when the recipient carries them`() {
        val view = newView()
        val recipient = aRecipient {
            id = 1L
            customerNumber = CustomerNumber("KD-1")
            name = "Acme"
            iban = de.littletrotter.domain.value.Iban("DE89370400440532013000")
            email = de.littletrotter.domain.value.Email("kunde@example.org")
            legalRegistrationIdentifier = de.littletrotter.domain.masterdata.LegalRegistrationIdentifier.of("HRB12345", "0002")
        }

        populateForm(view, recipient)

        assertEquals("DE89370400440532013000", textField(view, "ibanField").value)
        assertEquals("kunde@example.org", textField(view, "emailField").value)
        assertEquals("HRB12345", textField(view, "legalRegistrationValueField").value)
        assertEquals("0002", textField(view, "legalRegistrationSchemeField").value)
    }

    // ── readFormData's own id-null dispatch (line 802): every existing test calls
    // populateForm/clearForm directly, bypassing the AbstractCrudView-facing wrapper entirely. ────

    private fun readFormData(view: RecipientView, formData: de.littletrotter.application.dto.RecipientDraft) {
        val m = RecipientView::class.java.getDeclaredMethod("readFormData", de.littletrotter.application.dto.RecipientDraft::class.java)
        m.isAccessible = true
        m.invoke(view, formData)
    }

    private fun blankDraft(view: RecipientView): de.littletrotter.application.dto.RecipientDraft {
        val f = RecipientView::class.java.getDeclaredField("currentFormData")
        f.isAccessible = true
        return f.get(view) as de.littletrotter.application.dto.RecipientDraft
    }

    @Test
    fun `readFormData with no id clears the form to its blank new-recipient state`() {
        val view = newView()
        whenever(recipientQuery.proposeCustomerNumber(org.mockito.kotlin.anyOrNull())).thenReturn("KD-1")
        // Dirty the name field first so clearForm's effect is actually observable.
        textField(view, "nameField").value = "Stale"

        readFormData(view, blankDraft(view))

        assertEquals("", textField(view, "nameField").value)
    }

    @Test
    fun `readFormData with an id populates the form from that recipient`() {
        val view = newView()
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Acme" }
        // readFormData reads currentRecipientDetail (normally set by formDataFrom, which
        // this test bypasses via direct reflection).
        val detailField = RecipientView::class.java.getDeclaredField("currentRecipientDetail")
        detailField.isAccessible = true
        detailField.set(view, recipient.toDetailDto())

        readFormData(view, blankDraft(view).copy(id = 1L))

        assertEquals("Acme", textField(view, "nameField").value)
    }

    // ── buildDraft's ifBlank chains (lines 820/821/823/824): every existing buildDraft
    // fixture leaves iban/routingId/email/phone blank, so only their null branch was exercised. ──

    @Test
    fun `buildDraft writes non-blank iban, routingId, email, phone and contact person onto the draft`() {
        val view = newView()
        textField(view, "nameField").value = "Acme"
        textField(view, "customerNumberField").value = "KD-1"
        textField(view, "ibanField").value = "DE89370400440532013000"
        textField(view, "routingIdField").value = "991-12345-67"
        textField(view, "emailField").value = "kunde@example.org"
        textField(view, "phoneField").value = "+49 30 1234567"
        textField(view, "contactPersonField").value = "Frau Muster"

        val draft = view.buildDraft(blankDraft(view))

        assertEquals("DE89370400440532013000", draft.iban)
        assertEquals("991-12345-67", draft.routingId)
        assertEquals("kunde@example.org", draft.email)
        assertEquals("+49 30 1234567", draft.phone)
        assertEquals("Frau Muster", draft.contactPerson)
    }

    // ── verifyVatId's currentRecipient?.-assignments (lines 988/989 and their VERIFIED-branch
    // twins): every existing verifyVatId test calls populateForm first, so currentRecipient is
    // never null at the point of a VIES result. A brand-new (never-saved) recipient reaches
    // verifyVatId too — the safe-call chain must not throw when there is nothing to mirror onto. ──

    // verifyVatId's logic moved into RecipientVatIdVerificationComposer; `internal`
    // visibility lets this same-module test call its public verify() directly, no method
    // reflection needed (only the field holding it is private).
    private fun verifyVatId(view: RecipientView) {
        val f = RecipientView::class.java.getDeclaredField("vatIdVerification")
        f.isAccessible = true
        (f.get(view) as RecipientVatIdVerificationComposer).verify()
    }

    @Test
    fun `verifyVatId on a brand-new, never-saved recipient does not throw for a FAILED result`() {
        val view = newView()
        val clearForm = RecipientView::class.java.getDeclaredMethod("clearForm").also { it.isAccessible = true }
        clearForm.invoke(view)
        textField(view, "vatIdField").value = "DE123456789"
        whenever(vatIdVerificationPort.verify(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenReturn(de.littletrotter.application.dto.VatIdVerificationResult.FAILED)

        // currentRecipient is null here (no populateForm ever ran) — the `currentRecipient?.…`
        // assignments must be no-ops, not throw. The outcome must still be recorded in
        // pendingVerification, since that (not currentRecipient) is what buildDraft/
        // reconcileVatIdVerification actually reads at save time for a brand-new recipient.
        verifyVatId(view)

        // pendingVerification moved into RecipientVatIdVerificationComposer; retarget the
        // reflection through the composer field the view now holds.
        val composerField = RecipientView::class.java.getDeclaredField("vatIdVerification").also { it.isAccessible = true }
        val composer = composerField.get(view)
        val pendingField = composer.javaClass.getDeclaredField("pendingVerification").also { it.isAccessible = true }
        val pending = pendingField.get(composer)
        assertEquals(
            de.littletrotter.shared.tax.VatIdVerificationStatus.FAILED,
            pending?.javaClass?.getDeclaredMethod("getStatus")?.invoke(pending),
        )
    }
}
