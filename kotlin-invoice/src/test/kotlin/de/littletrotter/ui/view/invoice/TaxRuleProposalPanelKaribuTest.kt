package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.application.invoice.ItemEntryQueries
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TODAY

/**
 * [TaxRuleProposalPanel] beyond what [GoodsItemDialogKaribuTest] already exercises: the
 * resolver-driven Proposed/Blocked hint (both need a recipient with real master data —
 * `recipientQueryPort.findRecipientById` — which the existing suite never mocks,
 * so `resolution` was always `null` there), the deviation line and Reverse-Charge disclosure block
 * that only appear when a manually picked rate disagrees with the resolver's own proposal, and the
 * two asymmetric halves of [TaxRuleProposalPanel.validate]'s invalid-field marking.
 *
 * Uses [ServiceItemDialog] rather than [GoodsItemDialog] for the resolution-driven hint and the
 * deviation/disclosure tests: a goods position's [InvoiceItemCategory.PRODUCT] category maps to
 * [ServiceType.UNSUPPORTED] ([toServiceType]), so the resolver always blocks it outright
 * ("Warenfall ... niemals stiller Default") regardless of recipient master data —
 * a service position is what actually exercises the resolver's country/customer-type decision
 * table. A PRODUCT position's tax ComboBox also excludes the Reverse-Charge rule code entirely
 * (`selectableTaxRatesFor`'s `SERVICE_ONLY_RULE_CODES` filter), so there would be nothing to
 * select a deviating rate from either way. [GoodsItemDialog] is still used for the two
 * [TaxRuleProposalPanel.validate] tests below — the exemption-details guard is category-agnostic.
 */
class TaxRuleProposalPanelKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun rate(
        code: String,
        category: String,
        percent: String = "19.00",
        country: Country = Country.DE,
        exemptionReasonText: String? = null,
    ) = TaxRateDto(
        id = code.hashCode().toLong(),
        code = code,
        category = category,
        rate = TaxRatePercent(BigDecimal(percent)),
        country = country,
        name = code,
        isActive = true,
        isSelectable = true,
        exemptionReasonText = exemptionReasonText,
    )

    private val de19 = rate("DE_STANDARD_19", "S")
    private val rcAe = rate("EU_B2B_RC_SERVICE", "AE", "0.00")

    private fun invoice(recipient: PartyDto?) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = recipient, items = emptyList(), latestGenerationJob = null,
    )

    private fun stubRecipient(
        recipientId: Long,
        customerType: CustomerType,
        billingCountry: Country,
        vatId: String? = null,
        vatIdVerificationStatus: VatIdVerificationStatus? = null,
    ) {
        val recipient = RecipientSummaryDto(
            id = recipientId, name = "Kunde", customerNumber = null, shortHash = null, contactPerson = null,
            routingId = null, vatId = vatId, customerType = customerType, vatIdVerificationStatus = vatIdVerificationStatus,
            reverseChargePreference = ReverseChargePreference.ASK, billingCity = null, billingCountryAlpha2 = billingCountry.alpha2,
            deliveryCountryAlpha2 = null,
        )
        whenever(recipientQueryPort.findRecipientById(recipientId)).thenReturn(recipient)
    }

    private fun dialog(): Dialog = _get<Dialog>()

    private fun formLayout(dialog: Dialog): com.vaadin.flow.component.formlayout.FormLayout =
        dialog.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>().first()

    private fun panel(dialog: Dialog): TaxRuleProposalPanel =
        dialog.children.toList().filterIsInstance<TaxRuleProposalPanel>().first()

    private fun hintLine(dialog: Dialog): Span = panel(dialog).children.toList()[0] as Span
    private fun deviationLine(dialog: Dialog): Span = panel(dialog).children.toList()[1] as Span
    private fun rcBlock(dialog: Dialog): Div = panel(dialog).children.toList()[2] as Div

    private fun saveButton(dialog: Dialog): com.vaadin.flow.component.button.Button =
        dialog._get<com.vaadin.flow.component.button.Button> { text = UiTranslations.tr("app.save") }

    // ── Goods dialog: only used by the validate() tests below (the exemption guard is category-agnostic) ──

    private fun goodsDescField(dialog: Dialog): com.vaadin.flow.component.textfield.TextArea =
        formLayout(dialog).children.toList().filterIsInstance<com.vaadin.flow.component.textfield.TextArea>()[0]

    // ── renderHint: a resolvable recipient produces a Proposed hint, not the "no data" empty state ──

    @Test
    fun `a domestic recipient resolves a Proposed rate and renders the proposed-styled hint`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        stubRecipient(
            recipientId = 3L, customerType = CustomerType.CONSUMER, billingCountry = Country.DE,
            vatId = "DE333333333",
        )
        val dlg = openService(invoice(recipient = PartyDto(id = 3L, name = "Kunde")))

        val hint = hintLine(dlg)
        assertTrue(hint.isVisible, "a resolved Proposed case must show the hint line")
        assertTrue(hint.hasClassName("taxrule-hint--proposed"), "classNames were: ${hint.classNames}")
        assertFalse(hint.hasClassName("taxrule-hint--blocked"))
    }

    @Test
    fun `a recipient in an unsupported third country blocks resolution and renders the blocked-styled hint`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        // Switzerland: not an EU member and not on the approved B2B target list -> Blocked(UNSUPPORTED_TAX_CASE).
        stubRecipient(
            recipientId = 4L, customerType = CustomerType.BUSINESS, billingCountry = Country.CH,
            vatId = "CH444444444",
        )
        val dlg = openService(invoice(recipient = PartyDto(id = 4L, name = "Kunde CH")))

        val hint = hintLine(dlg)
        assertTrue(hint.isVisible, "a Blocked case still shows the hint line (it explains why nothing was proposed)")
        assertTrue(hint.hasClassName("taxrule-hint--blocked"), "classNames were: ${hint.classNames}")
    }

    // ── Service dialog: deviation + Reverse-Charge disclosure, only reachable via a manual pick ──

    private fun taxGroupCombo(dialog: Dialog): ComboBox<TaxRateDto> =
        formLayout(dialog).children.toList().filterIsInstance<ComboBox<*>>()
            .first { it.label == UiTranslations.tr("invoiceDetail.itemDialog.taxRate") }.requireAs()

    private fun openService(invoiceDto: InvoiceDetailDto): Dialog {
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort,
            itemEntryQueryPort = ItemEntryQueries(mock(), mock(), mock(), mock(), taxRateQueries),
            invoice = invoiceDto,
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        return dialog()
    }

    @Test
    fun `manually picking a Reverse-Charge rate against a domestic-standard proposal shows the deviation line and the RC disclosure`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, rcAe))
        stubRecipient(
            recipientId = 5L, customerType = CustomerType.CONSUMER, billingCountry = Country.DE,
            vatId = "DE999999999", vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
        )
        val dlg = openService(invoice(recipient = PartyDto(id = 5L, name = "Kunde")))

        taxGroupCombo(dlg).value = rcAe

        assertTrue(deviationLine(dlg).isVisible, "AE selected against a STANDARD proposal must deviate")
        val block = rcBlock(dlg)
        assertTrue(block.isVisible, "picking an AE rate must show the Reverse-Charge disclosure regardless of the invoice-level decision")
        val texts = block.children.toList().map { (it as Div).children.toList().first().let { span -> (span as Span).text } }
        assertTrue(texts.any { it.contains("DE111111111") }, "seller VAT id must be disclosed: $texts")
        assertTrue(texts.any { it.contains("DE999999999") }, "buyer VAT id must be disclosed: $texts")
    }

    @Test
    fun `the RC disclosure states a VERIFIED buyer VAT-id verification status`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, rcAe))
        stubRecipient(
            recipientId = 6L, customerType = CustomerType.CONSUMER, billingCountry = Country.DE,
            vatId = "DE888888888", vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
        )
        val dlg = openService(invoice(recipient = PartyDto(id = 6L, name = "Kunde")))
        taxGroupCombo(dlg).value = rcAe

        val verificationText = (rcBlock(dlg).children.toList()[2] as Div).children.toList().first() as Span
        assertTrue(
            verificationText.text.endsWith(UiTranslations.tr("invoiceDetail.taxRule.verificationStatus.verified")),
            verificationText.text,
        )
    }

    @Test
    fun `the RC disclosure states a FAILED buyer VAT-id verification status`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, rcAe))
        stubRecipient(
            recipientId = 7L, customerType = CustomerType.CONSUMER, billingCountry = Country.DE,
            vatId = "DE777777777", vatIdVerificationStatus = VatIdVerificationStatus.FAILED,
        )
        val dlg = openService(invoice(recipient = PartyDto(id = 7L, name = "Kunde")))
        taxGroupCombo(dlg).value = rcAe

        val verificationText = (rcBlock(dlg).children.toList()[2] as Div).children.toList().first() as Span
        assertTrue(
            verificationText.text.endsWith(UiTranslations.tr("invoiceDetail.taxRule.verificationStatus.failed")),
            verificationText.text,
        )
    }

    @Test
    fun `the RC disclosure stays hidden when the buyer VAT country is unknown`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, rcAe))
        stubRecipient(
            recipientId = 8L, customerType = CustomerType.CONSUMER, billingCountry = Country.DE,
            vatId = null, vatIdVerificationStatus = null,
        )
        val dlg = openService(invoice(recipient = PartyDto(id = 8L, name = "Kunde")))
        taxGroupCombo(dlg).value = rcAe

        assertFalse(rcBlock(dlg).isVisible)
    }

    // ── validate(): the two exemption fields are flagged invalid independently ──────────────────

    @Test
    fun `validate blocks save and flags only the legal-basis field when the reason is already filled in`() {
        val exemptE = rate("DE_EXEMPT_SERVICE", "E", "0.00", exemptionReasonText = "Reason")
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, exemptE))
        var saveNewCalled = false
        GoodsItemDialog(
            recipientQueryPort = recipientQueryPort,
            itemEntryQueryPort = ItemEntryQueries(mock(), mock(), mock(), mock(), taxRateQueries),
            invoice = invoice(recipient = null),
            defaultTaxRate = { exemptE },
            onSaveNew = { saveNewCalled = true; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()
        goodsDescField(dlg).value = "Heilbehandlung"

        val panelChildren = panel(dlg).children.toList()
        (panelChildren[3] as TextField).value = "Grund vorhanden"
        // legal basis field (index 4) stays blank

        saveButton(dlg).click()

        assertFalse(saveNewCalled, "legal basis is still blank -> save must stay blocked")
        assertFalse((panelChildren[3] as TextField).isInvalid, "the filled-in reason field must not be flagged invalid")
        assertTrue((panelChildren[4] as TextField).isInvalid, "the still-blank legal-basis field must be flagged invalid")
    }

    @Test
    fun `validate blocks save and flags only the reason field when the legal basis is already filled in`() {
        val exemptE = rate("DE_EXEMPT_SERVICE", "E", "0.00", exemptionReasonText = "Reason")
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, exemptE))
        var saveNewCalled = false
        GoodsItemDialog(
            recipientQueryPort = recipientQueryPort,
            itemEntryQueryPort = ItemEntryQueries(mock(), mock(), mock(), mock(), taxRateQueries),
            invoice = invoice(recipient = null),
            defaultTaxRate = { exemptE },
            onSaveNew = { saveNewCalled = true; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()
        goodsDescField(dlg).value = "Heilbehandlung"

        val panelChildren = panel(dlg).children.toList()
        // reason field (index 3) stays blank
        (panelChildren[4] as TextField).value = "§ 4 Nr. 14 UStG"

        saveButton(dlg).click()

        assertFalse(saveNewCalled, "the reason is still blank -> save must stay blocked")
        assertTrue((panelChildren[3] as TextField).isInvalid, "the still-blank reason field must be flagged invalid")
        assertFalse((panelChildren[4] as TextField).isInvalid, "the filled-in legal-basis field must not be flagged invalid")
    }
}
