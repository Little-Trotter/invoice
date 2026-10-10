package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.details.Details
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.compliance.CoreCompletenessRules
import de.littletrotter.domain.compliance.BuyerDataRules
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [InvoiceOverviewPanel] itself (the class, not the standalone functions covered by
 * [InvoiceOverviewPanelSupportTest]): the detail-view tests reach it only with a DRAFT invoice and
 * never populate the party cards' optional fields, non-empty line
 * advice, or a B2B compliance finding. This file constructs [InvoiceOverviewPanel] directly and drives
 * [InvoiceOverviewPanel.render] to cover both directions of every conditional: the Bearbeiten
 * button's canEdit guard, the omitted-when-empty party card fields, all five [InvoiceStatus] arms behind the total card's CSS class, the
 * Money-null-to-ZERO fallback, the line-advisor heading, and the B2B compliance finding row.
 */
class InvoiceOverviewPanelKaribuTest {

    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(invoiceDetailQueryPort.findCompletenessChecks(org.mockito.kotlin.any())).thenReturn(emptyList())
        whenever(invoiceDetailQueryPort.findB2bComplianceFindings(org.mockito.kotlin.any())).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { child -> flatten(child) }

    private fun panel(): InvoiceOverviewPanel = InvoiceOverviewPanel(invoiceDetailQueryPort, recipientQueryPort)

    /** Attaches the panel to the live UI and round-trips, as the detail view does. */
    private fun renderAttached(dto: InvoiceDetailDto, documentLanguage: String? = null): InvoiceOverviewPanel {
        val p = panel()
        p.render(if (documentLanguage == null) dto else dto.copy(documentLanguage = documentLanguage))
        com.vaadin.flow.component.UI.getCurrent().add(p)
        MockVaadin.clientRoundtrip()
        return p
    }

    private fun totalCard(root: Component): Div =
        flatten(root).filterIsInstance<Div>().first { it.hasClassName("invoice-total-hero") }

    private fun item(description: String) = ItemDto(
        id = 1L, positionId = "1", description = description, quantity = BigDecimal.ONE, unit = null,
        unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = Money.of("10.00"), vatAmount = null, grossAmount = null,
        category = InvoiceItemCategory.SERVICE.name,
    )

    private fun dto(
        status: InvoiceStatus = InvoiceStatus.DRAFT,
        recipient: PartyDto? = null,
        sender: PartyDto? = PartyDto(id = 9L, name = "Sender"),
        items: List<ItemDto> = emptyList(),
        netAmount: Money? = null,
        vatAmount: Money? = null,
        grossAmount: Money? = null,
        invoiceDate: LocalDate? = null,
        billingAddress: AddressDto? = null,
        reverseCharge: Boolean = false,
        buyerReference: String? = null,
        purchaseOrderReference: String? = null,
        orderReference: String? = null,
        freeText: String? = null,
        typeCode: Int? = null,
        dueDate: LocalDate? = null,
        paymentTermName: String? = null,
        currency: Int? = null,
        cashDiscount: de.littletrotter.shared.InvoiceCashDiscountDto? = null,
    ) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status.ordinal, invoiceDate = invoiceDate,
        netAmount = netAmount, vatAmount = vatAmount, grossAmount = grossAmount,
        sender = sender, recipient = recipient, items = items, latestGenerationJob = null,
        billingAddress = billingAddress, reverseCharge = reverseCharge,
        buyerReference = buyerReference, purchaseOrderReference = purchaseOrderReference,
        orderReference = orderReference, freeText = freeText,
        typeCode = typeCode, dueDate = dueDate, paymentTermName = paymentTermName, currency = currency,
        cashDiscount = cashDiscount,
    )

    // ── canEdit / Bearbeiten button ───────────────────────────────────────────

    @Test
    fun `an editable DRAFT invoice shows the Bearbeiten button`() {
        val p = renderAttached(dto(status = InvoiceStatus.DRAFT))
        assertTrue(flatten(p).filterIsInstance<Button>().isNotEmpty(), "DRAFT must be editable")
    }

    @Test
    fun `a read-only SENT invoice hides the Bearbeiten button`() {
        val p = renderAttached(dto(status = InvoiceStatus.SENT))
        assertTrue(flatten(p).filterIsInstance<Button>().isEmpty(), "SENT is read-only, no edit button")
    }

    // ── status CSS class — all five InvoiceStatus arms ───────────────────────

    @Test
    fun `SENT renders the sent total-card class`() {
        val p = renderAttached(dto(status = InvoiceStatus.SENT))
        assertTrue(totalCard(p).hasClassName("invoice-total-card--sent"))
    }

    @Test
    fun `CANCELLED renders the cancelled total-card class`() {
        val p = renderAttached(dto(status = InvoiceStatus.CANCELLED))
        assertTrue(totalCard(p).hasClassName("invoice-total-card--cancelled"))
    }

    @Test
    fun `VOIDED renders the voided total-card class`() {
        val p = renderAttached(dto(status = InvoiceStatus.VOIDED))
        assertTrue(totalCard(p).hasClassName("invoice-total-card--voided"))
    }

    @Test
    fun `IN_REVIEW renders the review total-card class`() {
        val p = renderAttached(dto(status = InvoiceStatus.IN_REVIEW))
        assertTrue(totalCard(p).hasClassName("invoice-total-card--review"))
    }

    @Test
    fun `DRAFT falls through to the draft total-card class`() {
        val p = renderAttached(dto(status = InvoiceStatus.DRAFT))
        assertTrue(totalCard(p).hasClassName("invoice-total-card--draft"))
    }

    // ── party cards: lead, address, fields omitted when empty ────────────────

    private fun spanTexts(p: InvoiceOverviewPanel): List<String> =
        flatten(p).filterIsInstance<Span>().map { it.text ?: "" }

    @Test
    fun `no recipient renders the noneAssigned lead and no recipient fields`() {
        val p = renderAttached(dto(recipient = null))
        val texts = spanTexts(p)
        assertTrue(texts.contains(tr("invoiceDetail.recipient.noneAssigned")))
        assertFalse(texts.contains(tr("recipient.vatId")))
    }

    @Test
    fun `a recipient without a customer number shows its name as lead and no customer number field`() {
        val p = renderAttached(dto(recipient = PartyDto(id = 3L, name = "Kunde Ohne Nummer", customerNumber = null)))
        val texts = spanTexts(p)
        assertTrue(texts.contains("Kunde Ohne Nummer"))
        assertFalse(texts.contains(tr("recipient.customerNumber")))
    }

    @Test
    fun `a recipient with a blank customer number is treated as having none`() {
        val p = renderAttached(dto(recipient = PartyDto(id = 3L, name = "Blanke Nummer", customerNumber = "   ")))
        val texts = spanTexts(p)
        assertTrue(texts.contains("Blanke Nummer"))
        assertFalse(texts.contains(tr("recipient.customerNumber")))
    }

    @Test
    fun `a recipient with a customer number shows name and number as separate texts`() {
        val p = renderAttached(dto(recipient = PartyDto(id = 3L, name = "Kunde Mit Nummer", customerNumber = "K-42")))
        val texts = spanTexts(p)
        assertTrue(texts.contains("Kunde Mit Nummer"))
        assertTrue(texts.contains(tr("recipient.customerNumber")))
        assertTrue(texts.contains("K-42"))
    }

    @Test
    fun `recipient fields without a value are omitted and set ones are labelled`() {
        val p = renderAttached(
            dto(recipient = PartyDto(id = 3L, name = "Kunde", vatId = null, email = "k@example.org", phone = " ")),
            documentLanguage = "en",
        )
        val texts = spanTexts(p)
        assertFalse(texts.contains(tr("recipient.vatId")))
        assertFalse(texts.contains(tr("recipient.phone")))
        assertTrue(texts.contains(tr("recipient.email")))
        assertTrue(texts.contains("k@example.org"))
        assertTrue(texts.contains(tr("recipient.documentLanguage.en")))
    }

    @Test
    fun `a delivery address equal to the billing address is not repeated`() {
        val same = AddressDto(id = 1L, street = "Gleichstr. 1", postalCode = "10115", city = "Berlin", countryAlpha2 = "DE")
        whenever(recipientQueryPort.findBillingAddress(3L)).thenReturn(same)
        whenever(recipientQueryPort.findDeliveryAddress(3L)).thenReturn(same.copy(id = 2L))
        val p = renderAttached(dto(recipient = PartyDto(id = 3L, name = "Kunde")))
        assertFalse(spanTexts(p).contains(tr("invoiceDetail.tab.deliveryAddress")))
    }

    @Test
    fun `a differing delivery address is shown as a labelled field`() {
        whenever(recipientQueryPort.findBillingAddress(3L))
            .thenReturn(AddressDto(id = 1L, street = "Rechnungsstr. 1", postalCode = "10115", city = "Berlin", countryAlpha2 = "DE"))
        whenever(recipientQueryPort.findDeliveryAddress(3L))
            .thenReturn(AddressDto(id = 2L, street = "Lieferstr. 2", postalCode = "10117", city = "Berlin", countryAlpha2 = "DE"))
        val p = renderAttached(dto(recipient = PartyDto(id = 3L, name = "Kunde")))
        val texts = spanTexts(p)
        assertTrue(texts.contains(tr("invoiceDetail.tab.deliveryAddress")))
        assertTrue(texts.contains("Lieferstr. 2, 10117, Berlin, DE"), texts.toString())
    }

    @Test
    fun `the sender is folded away below the recipient`() {
        val p = renderAttached(dto(sender = PartyDto(id = 9L, name = "Sender", email = "rechnung@example.invalid")))
        val details = flatten(p).filterIsInstance<Details>().single { it.summaryText == tr("invoiceDetail.tab.sender") }
        assertFalse(details.isOpened)
        assertTrue(flatten(details).filterIsInstance<Span>().any { it.text == "rechnung@example.invalid" })
    }

    @Test
    fun `no sender renders the noneAssigned lead`() {
        val p = renderAttached(dto(sender = null))
        assertTrue(spanTexts(p).contains(tr("invoiceDetail.sender.noneAssigned")))
    }

    @Test
    fun `the sender card shows tax number and commercial register number`() {
        val p = renderAttached(
            dto(sender = PartyDto(id = 9L, name = "Sender", taxNumber = "12/345/67890", commercialRegisterNumber = "HRB 12345", courtOfJurisdiction = null)),
        )
        val texts = spanTexts(p)
        assertTrue(texts.contains(tr("senderProfile.taxNumber")))
        assertTrue(texts.contains("12/345/67890"))
        assertTrue(texts.contains(tr("senderProfile.commercialRegisterNumber")))
        assertTrue(texts.contains("HRB 12345"))
        assertFalse(texts.contains(tr("senderProfile.courtOfJurisdiction")))
    }

    @Test
    fun `the sender court is shown when set`() {
        val p = renderAttached(dto(sender = PartyDto(id = 9L, name = "Sender", courtOfJurisdiction = "AG Musterstadt")))
        val texts = spanTexts(p)
        assertTrue(texts.contains(tr("senderProfile.courtOfJurisdiction")))
        assertTrue(texts.contains("AG Musterstadt"))
    }

    // ── Money null-to-ZERO fallback ───────────────────────────────────────────

    @Test
    fun `null net, vat and gross amounts render as zero, not a crash`() {
        val p = renderAttached(dto(netAmount = null, vatAmount = null, grossAmount = null))
        val card = totalCard(p)
        val text = flatten(card).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `real net, vat and gross amounts render their formatted values`() {
        val p = renderAttached(dto(netAmount = Money.of("100.00"), vatAmount = Money.of("19.00"), grossAmount = Money.of("119.00")))
        val card = totalCard(p)
        val grossSpan = flatten(card).filterIsInstance<Span>().first { it.hasClassName("inh-gross-amount") }
        assertTrue(grossSpan.text.contains("119"), grossSpan.text)
    }

    // ── currency lives in the totals card, not in the options card ─────────────────────────

    private fun currencyRowValue(card: Div): String? =
        flatten(card).filterIsInstance<Div>()
            .firstOrNull { it.element.getAttribute("data-testid") == "invoice-total-currency" }
            ?.let { row -> flatten(row).filterIsInstance<Span>().first { it.hasClassName("inh-sub-value") }.text }

    @Test
    fun `the totals card shows the EUR code as its first sub-row`() {
        val p = renderAttached(dto(currency = de.littletrotter.shared.Currency.EUR.ordinal))
        val card = totalCard(p)
        assertEquals("EUR", currencyRowValue(card))
        val firstRow = flatten(card).filterIsInstance<Div>().first { it.hasClassName("inh-sub-row") }
        assertEquals("invoice-total-currency", firstRow.element.getAttribute("data-testid"))
        assertTrue(flatten(firstRow).filterIsInstance<Span>().any { it.text == tr("invoiceDetail.currency") })
    }

    @Test
    fun `the totals card shows the CHF code for a non-EUR invoice`() {
        val p = renderAttached(dto(currency = de.littletrotter.shared.Currency.CHF.ordinal))
        assertEquals("CHF", currencyRowValue(totalCard(p)))
    }

    @Test
    fun `the options card has no currency row`() {
        val p = renderAttached(dto(currency = de.littletrotter.shared.Currency.CHF.ordinal))
        val optionsTitle = tr("invoiceDetail.options.summary")
        val optionsCard = flatten(p).filterIsInstance<Div>().first { card ->
            card.children.toList().any { it is com.vaadin.flow.component.html.H2 && (it as com.vaadin.flow.component.html.H2).text == optionsTitle }
        }
        val texts = flatten(optionsCard).filterIsInstance<Span>().map { it.text }
        assertFalse(texts.contains(tr("invoiceDetail.currency")), "options card must not list a currency row: $texts")
        assertFalse(texts.contains("CHF"), "options card must not show the currency code: $texts")
    }

    // ── formatDate null vs. real date ──────────────────────────────────────────

    @Test
    fun `a null invoice date renders the dash placeholder`() {
        val p = renderAttached(dto(invoiceDate = null))
        val text = flatten(p).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertTrue(text.contains("-"))
    }

    @Test
    fun `a real invoice date renders in dd-MM-yyyy form`() {
        val p = renderAttached(dto(invoiceDate = LocalDate.of(2026, 3, 15)))
        val text = flatten(p).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertTrue(text.contains("15.03.2026"), text)
    }

    // ── line advisor heading ──────────────────────────────────────────────────

    @Test
    fun `an item with no advisory hint shows no advisor heading`() {
        val p = renderAttached(dto(items = listOf(item("Wartung der Heizungsanlage im Auftrag XYZ"))))
        val headings = flatten(p).filterIsInstance<com.vaadin.flow.component.html.H2>().map { it.text }
        assertFalse(headings.contains(tr("invoiceDetail.summary.advisor.title")))
    }

    @Test
    fun `a bare-code description triggers the line advisor heading`() {
        val p = renderAttached(dto(items = listOf(item("12345"))))
        val headings = flatten(p).filterIsInstance<com.vaadin.flow.component.html.H2>().map { it.text }
        assertTrue(headings.contains(tr("invoiceDetail.summary.advisor.title")))
    }

    // ── B2B compliance finding row ────────────────────────────────────────────

    @Test
    fun `no b2b findings renders no b2bLinePrefix row`() {
        whenever(invoiceDetailQueryPort.findB2bComplianceFindings(1L)).thenReturn(emptyList())
        val p = renderAttached(dto())
        val text = flatten(p).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertFalse(text.contains(B2bTerm.BT_19.officialCode))
    }

    @Test
    fun `a b2b finding renders its label as a checklist row`() {
        whenever(invoiceDetailQueryPort.findB2bComplianceFindings(1L)).thenReturn(
            listOf(B2bFindingDto(ruleId = "RULE", businessTermCodes = listOf(B2bTerm.BT_19.officialCode), definition = null, positionId = null)),
        )
        val p = renderAttached(dto())
        val text = flatten(p).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertTrue(text.contains(B2bTerm.BT_19.officialCode), text)
    }

    // ── checkRow both truthy states via the completeness checklist ───────────

    @Test
    fun `completeness checks render both satisfied and unsatisfied rows`() {
        whenever(invoiceDetailQueryPort.findCompletenessChecks(1L)).thenReturn(
            listOf(
                CompletenessCheckDto(CoreCompletenessRules.INVOICE_NUMBER, satisfied = true),
                CompletenessCheckDto(BuyerDataRules.EMAIL, satisfied = false),
            ),
        )
        val p = renderAttached(dto())
        val text = flatten(p).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertTrue(text.contains(tr("invoiceDetail.summary.checklist.invoiceNumber")))
        assertTrue(text.contains(tr("invoiceDetail.summary.checklist.recipientEmail")))
    }

    // ── buildReadOnlyOptionsCard: reverseCharge + the four trimmed optional refs ─

    @Test
    fun `all options fields blank render their placeholders and the no-reverseCharge label`() {
        val p = renderAttached(dto(reverseCharge = false, buyerReference = null, purchaseOrderReference = null, orderReference = null, freeText = null))
        val text = flatten(p).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertTrue(text.contains(tr("app.no")))
    }

    @Test
    fun `reverseCharge true and all optional references populated render their real values`() {
        val p = renderAttached(
            dto(
                reverseCharge = true, buyerReference = " BR-1 ", purchaseOrderReference = " PO-1 ",
                orderReference = " OR-1 ", freeText = " Freitext ",
            ),
        )
        val text = flatten(p).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertTrue(text.contains(tr("app.yes")))
        assertTrue(text.contains("BR-1"), text)
        assertTrue(text.contains("PO-1"), text)
        assertTrue(text.contains("OR-1"), text)
        assertTrue(text.contains("Freitext"), text)
    }

    // ── resolveRecipientAddresses' port lookups: the non-null address path ────

    @Test
    fun `a recipient whose billing and delivery addresses resolve renders both, not the placeholder`() {
        whenever(recipientQueryPort.findBillingAddress(3L))
            .thenReturn(AddressDto(id = 1L, street = "Rechnungsstr. 1", postalCode = "10115", city = "Berlin", countryAlpha2 = "DE"))
        whenever(recipientQueryPort.findDeliveryAddress(3L))
            .thenReturn(AddressDto(id = 2L, street = "Lieferstr. 2", postalCode = "10117", city = "Berlin", countryAlpha2 = "DE"))
        val p = renderAttached(dto(recipient = PartyDto(id = 3L, name = "Kunde")))
        val text = flatten(p).filterIsInstance<Span>().joinToString(" | ") { it.text ?: "" }
        assertTrue(text.contains("Rechnungsstr. 1"), text)
        assertTrue(text.contains("Lieferstr. 2"), text)
    }

    // ── due-date/payment-term cockpit fields, both directions ────────

    private fun cockpitLabels(p: InvoiceOverviewPanel): List<String> =
        flatten(p).filterIsInstance<Span>().filter { it.hasClassName("cockpit-field-label") }.map { it.text ?: "" }

    @Test
    fun `a storno document omits the due-date and payment-term cockpit fields entirely`() {
        val p = renderAttached(
            dto(
                status = InvoiceStatus.CANCELLED,
                typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE.ordinal,
                dueDate = LocalDate.of(2026, 9, 30),
                paymentTermName = "14 Tage netto",
            ),
        )
        val labels = cockpitLabels(p)
        assertFalse(labels.contains(tr("invoiceDetail.dueDate")), "storno must not show the due-date field: $labels")
        assertFalse(labels.contains(tr("invoiceDetail.paymentTerm")), "storno must not show the payment-term field: $labels")
        assertTrue(labels.contains(tr("invoiceDetail.invoiceDate")), "the invoice-date field stays: $labels")
        val values = flatten(p).filterIsInstance<Span>().filter { it.hasClassName("cockpit-field-value") }.map { it.text ?: "" }
        assertFalse(values.contains("14 Tage netto"), "no payment-term value may leak onto a storno cockpit: $values")
    }

    @Test
    fun `a cancelled original keeps its real due-date and payment-term cockpit fields`() {
        val p = renderAttached(
            dto(
                status = InvoiceStatus.CANCELLED,
                dueDate = LocalDate.of(2026, 9, 30),
                paymentTermName = "14 Tage netto",
            ),
        )
        val labels = cockpitLabels(p)
        assertTrue(labels.contains(tr("invoiceDetail.dueDate")), "the cancelled original keeps its due-date field: $labels")
        assertTrue(labels.contains(tr("invoiceDetail.paymentTerm")), "the cancelled original keeps its payment-term field: $labels")
        val values = flatten(p).filterIsInstance<Span>().filter { it.hasClassName("cockpit-field-value") }.map { it.text ?: "" }
        assertTrue(values.contains("30.09.2026"), "the real due date renders: $values")
        assertTrue(values.contains("14 Tage netto"), "the real payment term renders: $values")
    }

    private fun tr(key: String, vararg params: Any): String = de.littletrotter.ui.i18n.UiTranslations.tr(key, *params)

    // ── cash discount cockpit field ─────────────────────────────────

    private fun cockpitValues(p: InvoiceOverviewPanel): List<String> =
        flatten(p).filterIsInstance<Span>().filter { it.hasClassName("cockpit-field-value") }.map { it.text ?: "" }

    @Test
    fun `a cash discount renders as its own cockpit field with the formatted percent, deadline and amounts`() {
        val discount = de.littletrotter.shared.InvoiceCashDiscountDto(
            percent = BigDecimal("2.00"),
            days = 7,
            deadline = "2026-05-08",
            amount = Money.of("2.38"),
            payableAmount = Money.of("116.62"),
        )
        val p = renderAttached(dto(dueDate = LocalDate.of(2026, 5, 15), paymentTermName = "14 Tage 2% Skonto", cashDiscount = discount))

        val labels = cockpitLabels(p)
        assertTrue(labels.contains(tr("invoiceDetail.cashDiscount")), "the cash discount field renders: $labels")
        val expectedValue = tr("invoiceDetail.cashDiscount.value", "2", "08.05.2026", "2,38 EUR", "116,62 EUR")
        assertTrue(cockpitValues(p).contains(expectedValue), "the formatted cash discount value renders: ${cockpitValues(p)}, expected: $expectedValue")
    }

    @Test
    fun `no cash discount field renders when the DTO carries none`() {
        val p = renderAttached(dto(dueDate = LocalDate.of(2026, 5, 15), paymentTermName = "14 Tage netto", cashDiscount = null))

        assertFalse(cockpitLabels(p).contains(tr("invoiceDetail.cashDiscount")), "no cash discount field without a cashDiscount DTO")
    }

    @Test
    fun `a storno never shows the cash discount field even when the DTO carries one`() {
        val discount = de.littletrotter.shared.InvoiceCashDiscountDto(
            percent = BigDecimal("2.00"),
            days = 7,
            deadline = "2026-05-08",
            amount = Money.of("2.38"),
            payableAmount = Money.of("116.62"),
        )
        val p = renderAttached(
            dto(
                typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE.ordinal,
                dueDate = LocalDate.of(2026, 5, 15),
                cashDiscount = discount,
            ),
        )

        assertFalse(cockpitLabels(p).contains(tr("invoiceDetail.cashDiscount")), "storno must not show the cash discount field")
    }
}
