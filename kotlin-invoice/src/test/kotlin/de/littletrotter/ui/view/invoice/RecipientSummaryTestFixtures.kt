package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.masterdata.Recipient

/** Test-only mirror of [de.littletrotter.application.masterdata.RecipientQueries]' delegation to
 *  [de.littletrotter.application.masterdata.RecipientReadRepository] — lets Karibu/unit tests keep
 *  building a [Recipient] entity (billing/delivery address, VAT id, …) and hand
 *  [de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort] mocks the
 *  [RecipientSummaryDto] that port now returns. Same precedent as
 *  [de.littletrotter.ui.view.invoice.SenderSummaryTestFixtures]'s `toSenderSummaryDto`. */
internal fun Recipient.toRecipientSummaryDto(): RecipientSummaryDto = RecipientSummaryDto(
    id = requireNotNull(id) { "Recipient must have an id for this test fixture." },
    name = name,
    customerNumber = customerNumber?.value,
    shortHash = shortHash,
    contactPerson = contactPerson,
    routingId = routingId,
    vatId = vatId?.value,
    customerType = customerType,
    vatIdVerificationStatus = vatIdVerificationStatus,
    reverseChargePreference = reverseChargePreference,
    billingCity = billingAddress?.city,
    billingCountryAlpha2 = billingAddress?.country?.alpha2,
    deliveryCountryAlpha2 = deliveryAddress?.country?.alpha2,
)
