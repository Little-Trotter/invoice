package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.invoice
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.rate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TODAY

/**
 * [TaxRuleProposalPanel] accept/deviate arms and disclosure edge cases: the proposal
 * accepted without deviation, a deviation against a proposal whose rate row is missing (named by
 * category code), the reverse-charge decision suppressing deviation, an unverified buyer VAT id
 * and a missing mandatory text, and the exemption guard flagging both blank fields.
 */
class TaxRuleProposalPanelBranchKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val de19 = rate("DE_STANDARD_19", "S", "19.00")
    private val rcAe = rate("EU_B2B_RC_SERVICE", "AE", "0.00")
    private val exemptE = rate("DE_EXEMPT_SERVICE", "E", "0.00")

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun domesticConsumer(id: Long, vatId: String? = null) {
        val recipient = RecipientSummaryDto(
            id = id, name = "Kunde", customerNumber = null, shortHash = null, contactPerson = null,
            routingId = null, vatId = vatId, customerType = CustomerType.CONSUMER, vatIdVerificationStatus = null,
            reverseChargePreference = ReverseChargePreference.ASK, billingCity = null, billingCountryAlpha2 = null,
            deliveryCountryAlpha2 = null,
        )
        whenever(recipientQueryPort.findRecipientById(id)).thenReturn(recipient)
    }

    private fun panel(recipientId: Long? = 5L, reverseCharge: Boolean = false): TaxRuleProposalPanel =
        TaxRuleProposalPanel(
            recipientQueryPort,
            taxRateQueries.findAllTaxRates(),
            invoice(reverseCharge = reverseCharge, recipient = recipientId?.let { PartyDto(id = it, name = "Kunde") }),
            InvoiceItemCategory.SERVICE, today = FIXED_TODAY,
        )

    private fun TaxRuleProposalPanel.line(index: Int) = children.toList()[index]

    @Test
    fun `happy path - accepting the proposed standard rate shows no deviation and no reverse-charge block`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, rcAe))
        domesticConsumer(5L)
        val p = panel()

        p.onSelectedRateChanged(de19)

        assertFalse(p.line(1).isVisible, "accepting the proposal is no deviation")
        assertFalse(p.line(2).isVisible)
        assertTrue(p.validate(de19), "a standard rate needs no exemption details")
    }

    @Test
    fun `boundary - a deviation from a proposal without a matching rate row names the proposed category code`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rcAe))
        domesticConsumer(5L, vatId = "DE999999999")
        val p = panel()

        p.onSelectedRateChanged(rcAe)

        assertNull(p.proposedRate)
        val deviation = p.line(1) as Span
        assertTrue(deviation.isVisible)
        assertTrue(deviation.text.contains("S"), deviation.text)
        val texts = (p.line(2) as Div).children.toList().map { ((it as Div).children.toList().first() as Span).text }
        assertTrue(texts[2].endsWith(UiTranslations.tr("invoiceDetail.taxRule.verificationStatus.unverified")), texts[2])
        assertTrue(texts[3].endsWith("—"), "no mandatory text on the rate: ${texts[3]}")
    }

    @Test
    fun `boundary - no rate or the reverse-charge decision never count as deviation`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, rcAe))
        domesticConsumer(5L)
        val p = panel()
        p.onSelectedRateChanged(null)
        assertFalse(p.line(1).isVisible)

        val decided = panel(reverseCharge = true)
        decided.onSelectedRateChanged(de19)
        assertFalse(decided.line(1).isVisible, "a fixed reverse-charge decision is stated, not contradicted")
        assertEquals(UiTranslations.tr("invoiceDetail.taxRule.reverseChargeDecision"), (decided.line(0) as Span).text)
    }

    @Test
    fun `boundary - without a recipient there is no resolution and no reverse-charge disclosure`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, rcAe))
        val p = panel(recipientId = null)

        p.onSelectedRateChanged(rcAe)

        assertNull(p.resolution)
        assertFalse(p.line(0).isVisible)
        assertFalse(p.line(2).isVisible)
    }

    @Test
    fun `boundary - an exempt rate with both details blank flags both fields and blocks`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, exemptE))
        domesticConsumer(5L)
        val p = panel()
        com.vaadin.flow.component.UI.getCurrent().add(p)
        p.onSelectedRateChanged(exemptE)

        assertFalse(p.validate(exemptE))
        assertTrue(p.exemptionReasonField.isInvalid)
        assertTrue(p.exemptionLegalBasisField.isInvalid)
        assertEquals(UiTranslations.tr("invoiceDetail.taxRule.exemption.required"), p.exemptionReasonField.errorMessage)
        assertEquals(UiTranslations.tr("invoiceDetail.taxRule.exemption.required"), p.exemptionLegalBasisField.errorMessage)
        assertTrue(getNotifications().isEmpty())

        p.exemptionReasonField.value = "Heilbehandlung"
        p.exemptionLegalBasisField.value = "§ 4 Nr. 14 UStG"
        assertTrue(p.validate(exemptE))
    }

    @Test
    fun `boundary - zero-rate labels name the category`() {
        assertEquals("DE 19 %", selectedTaxRateLabel(de19))
        assertNotEquals("0 %", selectedTaxRateLabel(rcAe))
    }
}
