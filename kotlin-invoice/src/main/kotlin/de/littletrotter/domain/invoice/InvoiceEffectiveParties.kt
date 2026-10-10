package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.SenderProfile

/**
 * Which party and address apply to this invoice.
 * Selected party overrides master data; invoice-recorded addresses override master data.
 */

internal fun Invoice.effectiveSender(): SenderProfile? = selectedSeller ?: senderProfile

internal fun Invoice.effectiveSenderBillingAddress(): Address? = billingAddress ?: effectiveSender()?.billingAddress

internal fun Invoice.effectiveRecipientBillingAddress(): Address? = recipient?.billingAddress

internal fun Invoice.effectiveRecipientDeliveryAddress(): Address? =
    deliveryAddress ?: recipient?.deliveryAddress
