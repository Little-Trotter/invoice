package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.domain.compliance.CoreCompletenessRules
import de.littletrotter.domain.compliance.SellerDataRules
import de.littletrotter.domain.compliance.BuyerDataRules

/** Lives in the UI layer because it carries `translationKey`: presentation is a UI concern. */
data class InvoiceCompletenessCheck(
    val translationKey: String,
    val isComplete: Boolean,
)

/** Explicit mapping, not derived: would silently show wrong text on corner cases. */
private val checklistLabels: Map<String, String> = mapOf(
    CoreCompletenessRules.INVOICE_NUMBER to "invoiceDetail.summary.checklist.invoiceNumber",
    CoreCompletenessRules.INVOICE_DATE to "invoiceDetail.summary.checklist.invoiceDate",
    CoreCompletenessRules.SERVICE_DATE to "invoiceDetail.summary.checklist.serviceDate",
    CoreCompletenessRules.SERVICE_PERIOD_XOR to "invoiceDetail.summary.checklist.servicePeriodXor",
    BuyerDataRules.IDENTITY to "invoiceDetail.summary.checklist.recipient",
    BuyerDataRules.EMAIL to "invoiceDetail.summary.checklist.recipientEmail",
    BuyerDataRules.VAT_ID to "invoiceDetail.summary.checklist.recipientVatId",
    CoreCompletenessRules.BUYER_VAT_ID_VALIDATED to "invoiceDetail.summary.checklist.recipientVatIdValidated",
    BuyerDataRules.ADDRESS to "invoiceDetail.summary.checklist.recipientAddress",
    CoreCompletenessRules.BUYER_BILLING_ADDRESS to "invoiceDetail.summary.checklist.recipientBillingAddress",
    CoreCompletenessRules.BUYER_DELIVERY_ADDRESS to "invoiceDetail.summary.checklist.recipientDeliveryAddress",
    SellerDataRules.IDENTITY to "invoiceDetail.summary.checklist.sender",
    SellerDataRules.EMAIL to "invoiceDetail.summary.checklist.senderEmail",
    SellerDataRules.ADDRESS to "invoiceDetail.summary.checklist.senderAddress",
    CoreCompletenessRules.SELLER_BILLING_ADDRESS to "invoiceDetail.summary.checklist.senderBillingAddress",
    SellerDataRules.IBAN to "invoiceDetail.summary.checklist.senderIban",
    SellerDataRules.TAX_INFO to "invoiceDetail.summary.checklist.sellerTaxInfo",
    CoreCompletenessRules.ITEMS to "invoiceDetail.summary.checklist.items",
    CoreCompletenessRules.REVERSE_CHARGE_VAT_ID to "invoiceDetail.summary.checklist.reverseChargeVatId",
    CoreCompletenessRules.PRECEDING_INVOICE_NUMBER to "invoiceDetail.summary.checklist.originalInvoiceNumber",
)

/** Maps the server-evaluated checks to checklist rows; a check without a label is not part of
 *  the tax-relevant checklist and is left out rather than shown as a raw rule id. */
internal fun List<CompletenessCheckDto>.taxRelevantCompletenessChecks(): List<InvoiceCompletenessCheck> =
    mapNotNull { check -> checklistLabels[check.ruleId]?.let { InvoiceCompletenessCheck(it, check.satisfied) } }
