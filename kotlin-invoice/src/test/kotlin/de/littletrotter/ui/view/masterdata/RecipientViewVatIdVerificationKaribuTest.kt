package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import de.littletrotter.application.dto.VatIdVerificationResult
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.component.AddressFormSection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

/**
 * PO report 04.09.2026 — the customer dialog's VIES button "did not work". It handed the RAW field
 * value to the port after splitting off the first two characters, so "DE 136 695 976" arrived as
 * country "DE" and number " 136 695 976". Those two become URL path segments in
 * [de.littletrotter.infrastructure.vies.ViesHttpClient]; `URI.create` rejects the space and the
 * catch there reported UNREACHABLE — the user saw "VIES is unreachable" for an input problem.
 *
 * These cases assert on what actually reaches the port, which is the only place the defect was
 * ever visible: the format check passed, the field looked fine, and the error message pointed at
 * the wrong culprit.
 */
class RecipientViewVatIdVerificationKaribuTest {

    /** Records the arguments instead of calling anything — the arguments ARE the regression. */
    private class RecordingPort : VerifyVatIdUseCase {
        var countryCode: String? = null
        var vatId: String? = null
        var calls = 0
        var answer: VatIdVerificationResult = VatIdVerificationResult.VERIFIED(null, null)

        override fun verify(countryCode: String, vatId: String): VatIdVerificationResult {
            this.countryCode = countryCode
            this.vatId = vatId
            calls++
            return answer
        }
    }

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val port = RecordingPort()

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
        port,
        RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue),
        InvoiceCreationDependencies(senderQuery, createInvoiceUseCase), time = FIXED_TIME_SOURCE,
    )

    private fun vatIdField(view: RecipientView): TextField {
        val f = RecipientView::class.java.getDeclaredField("vatIdField")
        f.isAccessible = true
        return f.get(view) as TextField
    }

    // verifyVatId's logic lives in RecipientVatIdVerificationComposer; `internal`
    // visibility lets this same-module test call its public verify() directly, no method
    // reflection needed (only the field holding it is private).
    private fun verifyVatId(view: RecipientView) {
        val f = RecipientView::class.java.getDeclaredField("vatIdVerification")
        f.isAccessible = true
        (f.get(view) as RecipientVatIdVerificationComposer).verify()
    }

    private fun verifyWith(input: String): Pair<RecipientView, RecordingPort> {
        val view = newView()
        vatIdField(view).value = input
        verifyVatId(view)
        return view to port
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

    private fun deliveryAddressSection(view: RecipientView): AddressFormSection {
        val f = RecipientView::class.java.getDeclaredField("deliveryAddressFormSection")
        f.isAccessible = true
        return f.get(view) as AddressFormSection
    }

    private fun clearForm(view: RecipientView) {
        val m = RecipientView::class.java.getDeclaredMethod("clearForm")
        m.isAccessible = true
        m.invoke(view)
    }

    private fun vatIdVerifyButton(view: RecipientView): Button {
        val f = RecipientView::class.java.getDeclaredField("vatIdVerifyButton")
        f.isAccessible = true
        return f.get(view) as Button
    }

    /** [Button.isEnabled] cascades through the component's ancestor chain: [TabSheet] keeps every
     *  tab's content mounted but disables the content of every tab except the selected one, so
     *  [vatIdVerifyButton] — which lives on the "tax" tab, `buildTabs`'s third tab, index 2 —
     *  reads as disabled while a different tab (identity, index 0) is selected, regardless of its
     *  own `isEnabled` flag. Attaching the view to a UI and opening the dialog is also required, as
     *  production routing does implicitly, but is not by itself sufficient — [RecipientViewSilentSaveFailureKaribuTest]
     *  selects a tab by index for the same reason before asserting on that tab's content. */
    private fun openEditorDialog(view: RecipientView) {
        com.vaadin.flow.component.UI.getCurrent().add(view)
        val f = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredField("editorDialog")
        f.isAccessible = true
        (f.get(view) as com.vaadin.flow.component.dialog.Dialog).open()
        val tabsField = RecipientView::class.java.getDeclaredField("tabs")
        tabsField.isAccessible = true
        (tabsField.get(view) as TabSheet).selectedIndex = 2
        com.github.mvysny.kaributesting.v10.MockVaadin.clientRoundtrip()
    }

    private fun vatIdValidatedCheckbox(view: RecipientView): Checkbox {
        val f = RecipientView::class.java.getDeclaredField("vatIdValidatedCheckbox")
        f.isAccessible = true
        return f.get(view) as Checkbox
    }

    private fun vatIdValidationNoteField(view: RecipientView): TextField {
        val f = RecipientView::class.java.getDeclaredField("vatIdValidationNoteField")
        f.isAccessible = true
        return f.get(view) as TextField
    }

    /** Same idiom as `ValidationSettingsTabTest.assertNotificationShown` — a plain
     *  `Notification.show(...)` (`UiNotifications.success`/`.info`) sets its text as a "text"
     *  element property with no child `Span`, while `UiNotifications.error`/`.warning` wrap the
     *  message in an actual `Span`. */
    private fun assertNotificationShown(expectedText: String) {
        com.github.mvysny.kaributesting.v10.MockVaadin.clientRoundtrip()
        val found = mutableListOf<com.vaadin.flow.component.Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(com.vaadin.flow.component.UI.getCurrent().element)
        val plainTextMatch = found.filterIsInstance<com.vaadin.flow.component.notification.Notification>()
            .any { de.littletrotter.ui.toastText(it) == expectedText }
        val spanMatch = found.filterIsInstance<com.vaadin.flow.component.html.Span>().any { it.text == expectedText }
        assertTrue(plainTextMatch || spanMatch, "expected an open notification with text: $expectedText")
    }

    /** Fills every field [de.littletrotter.ui.view.masterdata.RecipientView.validateForm] requires
     *  for a BUSINESS recipient, so a failing save can only be attributed to the VIES outcome under
     *  test — not to some unrelated missing field. */
    private fun fillMinimalValidForm(view: RecipientView) {
        clearForm(view)
        textField(view, "nameField").value = "Reproduktions GmbH"
        textField(view, "customerNumberField").value = "KD-REPRO-1"
        billingAddressSection(view).street.value = "Teststraße 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "emailField").value = "kunde@example.org"
    }

    private fun saveCurrentForm(view: RecipientView) {
        val m = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        m.isAccessible = true
        m.invoke(view)
    }

    /**
     * Reproduction, PO report 04.09.2026: a VIES [VatIdVerificationResult.FAILED] outcome must not
     * block the save. Before the fix in this task, [RecipientView.verifyVatId] reported the FAILED
     * outcome via [de.littletrotter.ui.notification.UiNotifications.error] — which is the wrong
     * severity (spec: it must be a warning) — but the save path itself was never gated on
     * `vatIdVerificationStatus` (see [de.littletrotter.domain.masterdata.Recipient.validate] and
     * `RecipientView.validateForm`, neither reads it). This test proves the save goes through
     * end-to-end (facade invoked, persisted status is FAILED, dialog closes) regardless of what the
     * VIES call answered.
     */
    @Test
    fun `reproduction - a VIES FAILED outcome does not block saving the customer`() {
        val view = newView()
        fillMinimalValidForm(view)
        textField(view, "vatIdField").value = "DE136695976"
        port.answer = VatIdVerificationResult.FAILED
        verifyVatId(view)

        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenAnswer { invocation ->
            val draft = invocation.getArgument<de.littletrotter.application.dto.RecipientDraft>(0)
            de.littletrotter.application.dto.RecipientDetailDto(
                id = 99L, version = 1L, customerNumber = null, shortHash = null, name = draft.name, vatId = draft.vatId,
                routingId = null, email = null, phone = null, contactPerson = null, iban = null, customerType = null,
                invoiceFormat = de.littletrotter.domain.shared.InvoiceFormat.GERMAN, documentLanguage = "de",
                reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
                buyerIdentifierValue = null, buyerIdentifierSchemeId = null, legalRegistrationValue = null,
                legalRegistrationSchemeId = null, b2bProfileId = null, partyPairs = emptyMap(),
                billingAddress = null, deliveryAddress = null,
                vatIdVerificationStatus = draft.pendingVatIdVerification?.status, vatIdVerifiedAt = null,
                vatIdVerificationNote = null,
            )
        }

        // Must not throw, and must reach the facade — this is the reproduction: it fails before the
        // fix (see the KDoc above) if the save is blocked anywhere on the FAILED status.
        saveCurrentForm(view)

        val captor = argumentCaptor<de.littletrotter.application.dto.RecipientDraft>()
        verify(manageRecipient).saveRecipientWithAddresses(captor.capture())
        assertEquals(VatIdVerificationStatus.FAILED, captor.firstValue.pendingVatIdVerification?.status)
        assertTrue(view.element.isEnabled, "sanity: the view itself must still be functional after save")
    }

    /**
     * Reproduction, PO report 04.09.2026, second candidate (the one that actually reproduces): the
     * delivery address lives on its own dialog tab ("Lieferadresse"), added last in [RecipientView]'s
     * `TabSheet` and therefore never the one shown when the dialog opens — "identity" is. If a
     * delivery postal code fails [de.littletrotter.domain.value.PostalCodeValidator] (the delivery
     * country combo defaults to DE, so a non-German-shaped value trips the strict five-digit rule),
     * `validateForm`'s `gateDeliveryPostalCode` marks that field invalid and returns `false` — with
     * no [de.littletrotter.ui.notification.UiNotifications] call anywhere in `validateForm`. The
     * save button click then does nothing visible at all: no toast, no dialog close, and the one
     * field carrying the error is on a tab the user is not looking at. That is indistinguishable
     * from a dead save button — exactly the PO's "Kunde lässt sich nicht speichern", independent of
     * whatever the VIES check answered.
     */
    @Test
    fun `reproduction - an invalid delivery postal code on the hidden delivery tab silently blocks the save`() {
        val view = newView()
        fillMinimalValidForm(view)
        textField(view, "vatIdField").value = "DE136695976"
        port.answer = VatIdVerificationResult.VERIFIED(null, null)
        verifyVatId(view)

        // Entered on the delivery-address tab, which is not the tab visible right after opening
        // the dialog. "ABC" is not a valid German postal code, and the delivery country combo
        // defaults to DE.
        deliveryAddressSection(view).street.value = "Lieferstraße 1"
        deliveryAddressSection(view).postalCode.value = "ABC"
        deliveryAddressSection(view).city.value = "Lieferort"

        saveCurrentForm(view)

        verify(manageRecipient, org.mockito.kotlin.never()).saveRecipientWithAddresses(any())
        assertTrue(
            deliveryAddressSection(view).postalCode.isInvalid,
            "the actual cause: the delivery postal code was rejected on a tab the user is not looking at",
        )
    }

    @Test
    fun `a VAT id typed with spaces reaches the port without them`() {
        verifyWith("DE 136 695 976")

        assertEquals("DE", port.countryCode)
        assertEquals("136695976", port.vatId, "the number must not carry the spaces into the URL path")
    }

    @Test
    fun `a lower-case VAT id reaches the port upper-cased`() {
        verifyWith("nl123456789b01")

        assertEquals("NL", port.countryCode)
        assertEquals("123456789B01", port.vatId)
    }

    @Test
    fun `the non-breaking space survives no better than a normal one`() {
        verifyWith("DE 136 695 976")

        assertEquals("136695976", port.vatId)
    }

    /** The country code is taken from the NUMBER, and Greece proves it: VAT prefix EL, ISO code GR. */
    @Test
    fun `the country code comes from the number, EL for Greece`() {
        verifyWith("EL 123 456 789")

        assertEquals("EL", port.countryCode)
        assertEquals("123456789", port.vatId)
    }

    @Test
    fun `the field shows the normalised value afterwards, so the user sees what was queried`() {
        val (view, _) = verifyWith("  de 136 695 976 ")

        assertEquals("DE136695976", vatIdField(view).value)
    }

    // ── plausibility: nothing pointless goes on the wire ─────────────────────────

    @Test
    fun `a non-EU VAT id is never sent to VIES`() {
        verifyWith("GB123456789")

        assertEquals(0, port.calls, "VIES has no entry for GB — asking would report a false outage")
        assertNull(port.countryCode)
    }

    @Test
    fun `a formally invalid VAT id is never sent to VIES`() {
        verifyWith("DE12")

        assertEquals(0, port.calls)
    }

    @Test
    fun `a blank field is never sent to VIES`() {
        verifyWith("   ")

        assertEquals(0, port.calls)
    }

    @Test
    fun `an EU VAT id is sent exactly once`() {
        verifyWith("LU26375245")

        assertEquals(1, port.calls)
        assertEquals("LU", port.countryCode)
        assertEquals("26375245", port.vatId)
    }

    // ── The VIES button's own enabled state ───────────────────────────────

    @Test
    fun `the VIES button is disabled for a blank field`() {
        val view = newView()
        openEditorDialog(view)
        vatIdField(view).value = ""
        assertFalse(vatIdVerifyButton(view).isEnabled)
    }

    @Test
    fun `the VIES button is disabled for a formally invalid value`() {
        val view = newView()
        openEditorDialog(view)
        vatIdField(view).value = "DE12"
        assertFalse(vatIdVerifyButton(view).isEnabled)
    }

    @Test
    fun `the VIES button is disabled for a formally valid but non-EU value`() {
        val view = newView()
        openEditorDialog(view)
        vatIdField(view).value = "GB123456789"
        assertFalse(vatIdVerifyButton(view).isEnabled)
    }

    @ParameterizedTest(name = "the VIES button is enabled for {0}")
    @MethodSource("validEuVatIds")
    fun `the VIES button is enabled for a valid EU VAT id`(vatId: String) {
        val view = newView()
        openEditorDialog(view)
        vatIdField(view).value = vatId
        assertTrue(vatIdVerifyButton(view).isEnabled, vatId)
    }

    // ── The VIES-reason-specific UNREACHABLE warnings ────────────────────────

    @Test
    fun `UNREACHABLE with MS_UNAVAILABLE names the country in the warning`() {
        val view = newView()
        vatIdField(view).value = "DE123456789"
        port.answer = VatIdVerificationResult.UNREACHABLE("MS_UNAVAILABLE")

        verifyVatId(view)

        assertNotificationShown(de.littletrotter.ui.i18n.UiTranslations.tr("recipient.vatId.verify.unreachable.msUnavailable", "DE"))
    }

    @Test
    fun `UNREACHABLE with an unknown reason falls back to the generic warning`() {
        val view = newView()
        vatIdField(view).value = "DE123456789"
        port.answer = VatIdVerificationResult.UNREACHABLE(null)

        verifyVatId(view)

        assertNotificationShown(de.littletrotter.ui.i18n.UiTranslations.tr("recipient.vatId.verify.unreachable"))
    }

    @Test
    fun `UNREACHABLE with MS_MAX_CONCURRENT_REQ shows the busy warning`() {
        val view = newView()
        vatIdField(view).value = "DE123456789"
        port.answer = VatIdVerificationResult.UNREACHABLE("MS_MAX_CONCURRENT_REQ")

        verifyVatId(view)

        assertNotificationShown(de.littletrotter.ui.i18n.UiTranslations.tr("recipient.vatId.verify.unreachable.busy"))
    }

    @Test
    fun `UNREACHABLE with GLOBAL_MAX_CONCURRENT_REQ shares the busy warning with MS_MAX_CONCURRENT_REQ`() {
        val view = newView()
        vatIdField(view).value = "DE123456789"
        port.answer = VatIdVerificationResult.UNREACHABLE("GLOBAL_MAX_CONCURRENT_REQ")

        verifyVatId(view)

        assertNotificationShown(de.littletrotter.ui.i18n.UiTranslations.tr("recipient.vatId.verify.unreachable.busy"))
    }

    @Test
    fun `UNREACHABLE with VAT_BLOCKED shows the blocked warning`() {
        val view = newView()
        vatIdField(view).value = "DE123456789"
        port.answer = VatIdVerificationResult.UNREACHABLE("VAT_BLOCKED")

        verifyVatId(view)

        assertNotificationShown(de.littletrotter.ui.i18n.UiTranslations.tr("recipient.vatId.verify.unreachable.blocked"))
    }

    @Test
    fun `UNREACHABLE with INVALID_REQUESTER_INFO shares the blocked warning with IP_BLOCKED and VAT_BLOCKED`() {
        val view = newView()
        vatIdField(view).value = "DE123456789"
        port.answer = VatIdVerificationResult.UNREACHABLE("INVALID_REQUESTER_INFO")

        verifyVatId(view)

        assertNotificationShown(de.littletrotter.ui.i18n.UiTranslations.tr("recipient.vatId.verify.unreachable.blocked"))
    }

    @Test
    fun `UNREACHABLE with INVALID_INPUT shows the bad-request warning`() {
        val view = newView()
        vatIdField(view).value = "DE123456789"
        port.answer = VatIdVerificationResult.UNREACHABLE("INVALID_INPUT")

        verifyVatId(view)

        assertNotificationShown(de.littletrotter.ui.i18n.UiTranslations.tr("recipient.vatId.verify.unreachable.badRequest"))
    }

    @Test
    fun `UNREACHABLE with SERVICE_UNAVAILABLE shares the timeout warning with TIMEOUT`() {
        val view = newView()
        vatIdField(view).value = "DE123456789"
        port.answer = VatIdVerificationResult.UNREACHABLE("SERVICE_UNAVAILABLE")

        verifyVatId(view)

        assertNotificationShown(de.littletrotter.ui.i18n.UiTranslations.tr("recipient.vatId.verify.unreachable.timeout"))
    }

    // ── P4 — the VIES-returned trader name/address ─────────────────────────────────

    @Test
    fun `VERIFIED with name and address shows them in the checkbox helper and prefills a blank note`() {
        val view = newView()
        fillMinimalValidForm(view)
        textField(view, "vatIdField").value = "DE136695976"
        port.answer = VatIdVerificationResult.VERIFIED("Example GmbH", "Musterstraße 1, 12345 Berlin")

        verifyVatId(view)

        val checkbox = vatIdValidatedCheckbox(view)
        assertTrue(checkbox.value, "a VERIFIED outcome must check the box")
        assertTrue(checkbox.helperText.contains("Example GmbH"), checkbox.helperText)
        assertTrue(checkbox.helperText.contains("Musterstraße 1, 12345 Berlin"), checkbox.helperText)
        assertTrue(vatIdValidationNoteField(view).value.contains("Example GmbH"), vatIdValidationNoteField(view).value)
    }

    @Test
    fun `VERIFIED with no name or address keeps today's plain helper text`() {
        val view = newView()
        fillMinimalValidForm(view)
        textField(view, "vatIdField").value = "DE136695976"
        port.answer = VatIdVerificationResult.VERIFIED(null, null)

        verifyVatId(view)

        val checkbox = vatIdValidatedCheckbox(view)
        assertFalse(checkbox.helperText.contains("·"), "no VIES detail delivered — the plain 'via VIES' text must be unchanged")
    }

    @Test
    fun `VERIFIED never overwrites a non-blank validation note`() {
        val view = newView()
        fillMinimalValidForm(view)
        textField(view, "vatIdField").value = "DE136695976"
        vatIdValidationNoteField(view).value = "Bestehende Notiz"
        port.answer = VatIdVerificationResult.VERIFIED("Example GmbH", null)

        verifyVatId(view)

        assertEquals("Bestehende Notiz", vatIdValidationNoteField(view).value)
    }

    companion object {
        @JvmStatic
        fun validEuVatIds(): List<String> = listOf("DE123456789", "ATU12345678", "LU26375245", "EL123456789")
    }
}
