package de.littletrotter.domain.compliance

import java.time.LocalDate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.effectiveRecipientBillingAddress
import de.littletrotter.domain.invoice.effectiveRecipientDeliveryAddress
import de.littletrotter.domain.invoice.effectiveSender
import de.littletrotter.domain.invoice.effectiveSenderBillingAddress
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.b2b.B2bTerm

/**
 * `ruleId` (`CORE.*`) and `messageKey` are stable identifiers; the UI layer
 * (`InvoiceCompletenessChecklist`) owns the actual text.
 *
 * Party checks come from [SellerDataRules]/[BuyerDataRules], not a second copy of the same rule
 * — the difference is the subject checked: here the invoice's **effective** party
 * (`selectedSeller ?: senderProfile`, deviating billing/delivery address), not the master
 * record's own data.
 *
 * Check order matches the invoice view's checklist and is protected: the UI displays it in
 * exactly this order, and reordering without a business reason would be a regression for the user.
 */
object CoreCompletenessRules : ComplianceRuleSet {

    const val INVOICE_NUMBER = "CORE.INVOICE_NUMBER"
    const val INVOICE_DATE = "CORE.INVOICE_DATE"
    const val SERVICE_DATE = "CORE.SERVICE_DATE"
    const val SERVICE_PERIOD_XOR = "CORE.SERVICE_PERIOD_XOR"
    const val BUYER_BILLING_ADDRESS = "CORE.BUYER_BILLING_ADDRESS"
    const val BUYER_DELIVERY_ADDRESS = "CORE.BUYER_DELIVERY_ADDRESS"
    const val SELLER_BILLING_ADDRESS = "CORE.SELLER_BILLING_ADDRESS"
    const val ITEMS = "CORE.ITEMS"
    const val REVERSE_CHARGE_VAT_ID = "CORE.REVERSE_CHARGE_VAT_ID"
    const val PRECEDING_INVOICE_NUMBER = "CORE.PRECEDING_INVOICE_NUMBER"
    const val BUYER_VAT_ID_VALIDATED = "CORE.BUYER_VAT_ID_VALIDATED"

    override val id = "CORE"
    override val levels = setOf(ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val invoice = (subject as? ComplianceSubject.OfInvoice)?.invoice ?: return emptyList()
        return checks(invoice).findings()
    }

    /** All applicable checks, including satisfied ones — the single source both [evaluate] and
     *  the UI checklist read, so display and blocking can't drift apart. */
    fun checks(invoice: Invoice): List<ComplianceCheck> = with(invoice) {
        val seller = effectiveSender()
        val sellerBilling = effectiveSenderBillingAddress()
        val buyerBilling = effectiveRecipientBillingAddress()
        val buyerDelivery = effectiveRecipientDeliveryAddress()

        val sellerChecks = SellerDataRules.checks(seller, sellerBilling).associateBy { it.ruleId }
        val buyerChecks = BuyerDataRules.checks(recipient, buyerBilling, buyerDelivery).associateBy { it.ruleId }

        buildList {
            addDateChecks(this@with)
            addBuyerChecks(buyerChecks, buyerBilling, buyerDelivery)
            addSellerChecks(sellerChecks, sellerBilling)
            // BG-25 (Business Group "Invoice line") — no B2bTerm equivalent. Checklist order is
            // protected (see this object's own KDoc): kept last among the static checks, exactly
            // where it sat before this method was split up, not grouped with the date checks above.
            add(header(ITEMS, "compliance.core.items", emptyList(), itemList.isNotEmpty()))
            addConditionalChecks(this@with)
        }
    }

    /** BT-1/BT-2/BT-72..74/BT-134/135 — invoice-level date/number checks, independent of
     *  seller/buyer master data. */
    private fun MutableList<ComplianceCheck>.addDateChecks(invoice: Invoice): Unit = with(invoice) {
        add(header(INVOICE_NUMBER, "compliance.core.invoiceNumber", listOf(B2bTerm.BT_1), invoiceNumber.isAssigned()))
        // BT-2: structurally always met since the date invariants made Invoice.invoiceDate
        // non-nullable with a creation default — same reasoning as SERVICE_DATE below. Kept so
        // the checklist still communicates the BT-2 requirement to the user.
        add(header(INVOICE_DATE, "compliance.core.invoiceDate", listOf(B2bTerm.BT_2), satisfied = true))
        // BT-72/73/74: any one suffices — without any time reference the document can't be
        // dated; which one applies depends on the case. QOD-3 — structurally always met since
        // the date invariants made `Invoice.invoiceDate` non-nullable: it alone already stands
        // in whenever neither service-period bound is set, so this row can no longer fail. Kept
        // (not dropped) because the checklist still communicates the BT-72/73/74 requirement to
        // the user; SERVICE_PERIOD_XOR below is the stricter sibling rule that can still fail.
        add(
            header(
                SERVICE_DATE, "compliance.core.serviceDate", listOf(B2bTerm.BT_72, B2bTerm.BT_73, B2bTerm.BT_74),
                satisfied = true,
            ),
        )
        // D-R1 (Nekar-Rechnungsrichtlinie Ziff. 1.3 — "Leistungsdatum-XOR"): stricter than
        // SERVICE_DATE above, which also accepts a bare invoiceDate as a stand-in and never
        // looks at line periods. Here, at least one of three genuinely time-of-service
        // sources must be set: the delivery date (BT-72), a complete header service period
        // (BT-73 and BT-74), or a line-level period (BT-134/135, either endpoint) on every
        // single line. An invoice with no items never satisfies the third alternative
        // vacuously — `all {}` on an empty list would otherwise report true.
        add(
            header(
                SERVICE_PERIOD_XOR, "compliance.core.servicePeriodXor",
                listOf(B2bTerm.BT_72, B2bTerm.BT_73, B2bTerm.BT_74, B2bTerm.BT_134, B2bTerm.BT_135),
                hasServicePeriodReference(),
            ),
        )
    }

    private fun Invoice.hasServicePeriodReference(): Boolean =
        deliveryDate != null ||
            (servicePeriodFrom != null && servicePeriodTo != null) ||
            (itemList.isNotEmpty() && itemList.all { it.servicePeriodFrom != null || it.servicePeriodTo != null })

    private fun MutableList<ComplianceCheck>.addBuyerChecks(
        buyerChecks: Map<String, ComplianceCheck>,
        buyerBilling: Address?,
        buyerDelivery: Address?,
    ) {
        add(buyerChecks.getValue(BuyerDataRules.IDENTITY))
        add(buyerChecks.getValue(BuyerDataRules.EMAIL))
        add(buyerChecks.getValue(BuyerDataRules.VAT_ID))
        add(buyerChecks.getValue(BuyerDataRules.ADDRESS))
        // The two individual addresses are INFO: which one is missing is informational; that
        // at least one is missing is the ERROR check above. No businessTerms here — BG-8/
        // BG-13 are Business Groups, not Business Terms, and B2bTerm has no BG equivalent.
        add(info(CheckIdentity(BUYER_BILLING_ADDRESS, "compliance.core.buyer.billingAddress"), FindingTarget.Buyer, emptyList(), buyerBilling.isPostalAddressComplete()))
        add(info(CheckIdentity(BUYER_DELIVERY_ADDRESS, "compliance.core.buyer.deliveryAddress"), FindingTarget.Buyer, emptyList(), buyerDelivery.isPostalAddressComplete()))
    }

    private fun MutableList<ComplianceCheck>.addSellerChecks(
        sellerChecks: Map<String, ComplianceCheck>,
        sellerBilling: Address?,
    ) {
        add(sellerChecks.getValue(SellerDataRules.IDENTITY))
        add(sellerChecks.getValue(SellerDataRules.EMAIL))
        add(sellerChecks.getValue(SellerDataRules.ADDRESS))
        add(info(CheckIdentity(SELLER_BILLING_ADDRESS, "compliance.core.seller.billingAddress"), FindingTarget.Seller, emptyList(), sellerBilling.isPostalAddressComplete()))
        add(sellerChecks.getValue(SellerDataRules.IBAN))
        add(sellerChecks.getValue(SellerDataRules.TAX_INFO))
    }

    private fun MutableList<ComplianceCheck>.addConditionalChecks(invoice: Invoice) {
        val buyer = invoice.recipient
        if (buyer != null && buyer.vatId != null) {
            add(
                ComplianceCheck(
                    BUYER_VAT_ID_VALIDATED,
                    if (invoice.reverseCharge) ComplianceSeverity.ERROR else ComplianceSeverity.WARNING,
                    "compliance.core.buyer.vatIdValidated", FindingTarget.Buyer, listOf(B2bTerm.BT_48),
                    buyer.vatIdVerificationStatus == VatIdVerificationStatus.VERIFIED,
                ),
            )
        }
        if (invoice.reverseCharge) {
            add(
                ComplianceCheck(
                    REVERSE_CHARGE_VAT_ID, ComplianceSeverity.ERROR,
                    "compliance.core.reverseChargeVatId", FindingTarget.Buyer, listOf(B2bTerm.BT_48),
                    invoice.recipient?.vatId != null,
                ),
            )
        }
        if (invoice.typeCode == InvoiceTypeCode.CORRECTED_INVOICE) {
            add(
                header(
                    PRECEDING_INVOICE_NUMBER, "compliance.core.precedingInvoiceNumber", listOf(B2bTerm.BT_25),
                    invoice.originalInvoiceNumber?.isAssigned() == true,
                ),
            )
        }
    }

    /** [ruleId]/[messageKey] bundled so [info] takes one parameter for them instead of two
     *  (LongParameterList) — every call site above only ever varies them together. */
    private data class CheckIdentity(val ruleId: String, val messageKey: String)

    private fun header(ruleId: String, messageKey: String, terms: List<B2bTerm>, satisfied: Boolean) =
        ComplianceCheck(ruleId, ComplianceSeverity.ERROR, messageKey, FindingTarget.Header, terms, satisfied)

    private fun info(
        id: CheckIdentity,
        target: FindingTarget,
        terms: List<B2bTerm>,
        satisfied: Boolean,
    ) = ComplianceCheck(id.ruleId, ComplianceSeverity.INFO, id.messageKey, target, terms, satisfied)
}
