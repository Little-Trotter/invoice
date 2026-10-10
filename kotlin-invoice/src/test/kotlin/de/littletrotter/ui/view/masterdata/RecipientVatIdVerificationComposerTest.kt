package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.masterdata.VatIdVerificationPort
import de.littletrotter.application.masterdata.VatIdVerificationResult
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.ui.component.VatIdField
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [RecipientVatIdVerificationComposer] in isolation, mocking the [VatIdVerificationPort]
 * and passing plain Vaadin components (no [RecipientView] involved). The end-to-end wiring through
 * the view stays covered by `RecipientViewVatIdVerificationKaribuTest` and friends; the actual
 * reconciliation onto the persisted recipient is
 * [de.littletrotter.application.masterdata.ManageRecipientTest]'s job now.
 */
class RecipientVatIdVerificationComposerTest {

    private val port: VatIdVerificationPort = mock()
    private lateinit var vatIdField: VatIdField
    private lateinit var vatIdVerifyButton: Button
    private lateinit var checkbox: Checkbox
    private lateinit var failedBadge: Span
    private lateinit var noteField: TextField

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        vatIdField = VatIdField({ it }, { null }, mismatchIsError = false)
        vatIdVerifyButton = Button()
        checkbox = Checkbox()
        failedBadge = Span()
        noteField = TextField()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun composer() = RecipientVatIdVerificationComposer(
        VatIdFormFields(vatIdField, vatIdVerifyButton, checkbox, failedBadge, noteField),
        port,
        de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        { key, params -> if (params.isEmpty()) key else "$key(${params.joinToString(",")})" },
    )

    private fun detail(status: VatIdVerificationStatus?, verifiedAt: java.time.OffsetDateTime?, note: String?): RecipientDetailDto =
        RecipientDetailDto(
            id = 1L, version = 0L, customerNumber = null, shortHash = null, name = "Acme",
            vatId = null, routingId = null, email = null, phone = null, contactPerson = null, iban = null,
            customerType = null, invoiceFormat = InvoiceFormat.GERMAN, documentLanguage = "de", reverseChargePreference = ReverseChargePreference.ASK,
            buyerIdentifierValue = null, buyerIdentifierSchemeId = null,
            legalRegistrationValue = null, legalRegistrationSchemeId = null,
            b2bProfileId = null, partyPairs = emptyMap(), billingAddress = null, deliveryAddress = null,
            vatIdVerificationStatus = status, vatIdVerifiedAt = verifiedAt, vatIdVerificationNote = note,
        )

    // ── updateVatIdVerifyButtonState ──────────────────────────────────────────

    @Test
    fun `the verify button is enabled for a valid EU VAT id`() {
        val c = composer()
        vatIdField.value = "DE123456789"

        c.updateVatIdVerifyButtonState()

        assertTrue(vatIdVerifyButton.isEnabled)
    }

    @Test
    fun `the verify button is disabled for a blank field`() {
        val c = composer()
        vatIdField.value = ""

        c.updateVatIdVerifyButtonState()

        assertFalse(vatIdVerifyButton.isEnabled)
    }

    @Test
    fun `the verify button is disabled for a formally invalid value`() {
        val c = composer()
        vatIdField.value = "DE12"

        c.updateVatIdVerifyButtonState()

        assertFalse(vatIdVerifyButton.isEnabled)
    }

    @Test
    fun `the verify button is disabled for a valid but non-EU value`() {
        val c = composer()
        vatIdField.value = "GB123456789"

        c.updateVatIdVerifyButtonState()

        assertFalse(vatIdVerifyButton.isEnabled)
    }

    // ── verify() ───────────────────────────────────────────────────────────────

    @Test
    fun `verify sends the normalised country and number to the port`() {
        val c = composer()
        vatIdField.value = "de 136 695 976"
        whenever(port.verify("DE", "136695976")).thenReturn(VatIdVerificationResult.VERIFIED(null, null))

        c.verify()

        assertEquals("DE136695976", vatIdField.value)
    }

    @Test
    fun `verify on a blank field never calls the port`() {
        val c = composer()
        vatIdField.value = "   "

        c.verify()

        org.mockito.kotlin.verify(port, org.mockito.kotlin.never()).verify(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `verify on a non-EU id never calls the port`() {
        val c = composer()
        vatIdField.value = "GB123456789"

        c.verify()

        org.mockito.kotlin.verify(port, org.mockito.kotlin.never()).verify(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `a VERIFIED result checks the box and records a pending verification for that vat id`() {
        val c = composer()
        vatIdField.value = "DE136695976"
        whenever(port.verify("DE", "136695976")).thenReturn(VatIdVerificationResult.VERIFIED("Example GmbH", "Musterstr. 1"))

        c.verify()

        assertTrue(checkbox.value)
        val pending = c.pendingVerification()
        assertEquals("DE136695976", pending?.vatId)
        assertEquals(VatIdVerificationStatus.VERIFIED, pending?.status)
    }

    @Test
    fun `handleVerified builds the detail from the address alone when VIES returns no name`() {
        val c = composer()
        vatIdField.value = "DE136695976"
        whenever(port.verify("DE", "136695976")).thenReturn(VatIdVerificationResult.VERIFIED(null, "Musterstr. 1"))

        c.verify()

        assertTrue(checkbox.helperText.contains("Musterstr. 1"), checkbox.helperText)
    }

    @Test
    fun `a FAILED result unchecks the box, shows the badge and records a pending FAILED verification`() {
        val c = composer()
        vatIdField.value = "DE136695976"
        whenever(port.verify("DE", "136695976")).thenReturn(VatIdVerificationResult.FAILED)

        c.verify()

        assertFalse(checkbox.value)
        assertTrue(failedBadge.isVisible)
        assertEquals(VatIdVerificationStatus.FAILED, c.pendingVerification()?.status)
    }

    @Test
    fun `an UNREACHABLE result leaves the checkbox untouched and records no pending verification`() {
        val c = composer()
        vatIdField.value = "DE136695976"
        checkbox.value = true

        whenever(port.verify("DE", "136695976")).thenReturn(VatIdVerificationResult.UNREACHABLE("TIMEOUT"))

        c.verify()

        assertTrue(checkbox.value)
        assertNull(c.pendingVerification())
    }

    // ── populate/clear ───────────────────────────────────────────────────────

    @Test
    fun `populate mirrors the stored verification state off RecipientDetailDto`() {
        val c = composer()
        val verifiedAt = java.time.OffsetDateTime.now()

        c.populate(detail(VatIdVerificationStatus.VERIFIED, verifiedAt, "Bestehende Notiz"))

        assertTrue(checkbox.value)
        assertEquals("Bestehende Notiz", noteField.value)
        assertNull(c.pendingVerification(), "loading a stored state is not a fresh in-session verification")
    }

    @Test
    fun `clear resets the checkbox and badge to a fresh state`() {
        val c = composer()
        checkbox.value = true
        failedBadge.isVisible = true

        c.clear()

        assertFalse(checkbox.value)
        assertFalse(failedBadge.isVisible)
    }

    // ── validatedByUser/pendingVerification accessors ───────────────────────────

    @Test
    fun `validatedByUser reflects the checkbox`() {
        val c = composer()

        assertFalse(c.validatedByUser())
        checkbox.value = true
        assertTrue(c.validatedByUser())
    }

    @Test
    fun `pendingVerification is reset by populate and clear`() {
        val c = composer()
        vatIdField.value = "DE136695976"
        whenever(port.verify("DE", "136695976")).thenReturn(VatIdVerificationResult.VERIFIED(null, null))
        c.verify()
        assertEquals("DE136695976", c.pendingVerification()?.vatId)

        c.clear()
        assertNull(c.pendingVerification())
    }
}
