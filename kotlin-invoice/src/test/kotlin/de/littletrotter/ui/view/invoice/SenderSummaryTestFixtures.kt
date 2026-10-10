package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.SellerVatRegistrationDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.domain.masterdata.SenderProfile

/** Test-only mirror of [de.littletrotter.application.masterdata.SenderQueries]' private mapping —
 *  lets Karibu/unit tests keep building a [SenderProfile] entity (billing address, VAT
 *  registrations, …) and hand [de.littletrotter.application.masterdata.port.inbound.SenderQueryPort]
 *  mocks the [SenderSummaryDto] that port now returns. */
internal fun SenderProfile.toSenderSummaryDto(): SenderSummaryDto = SenderSummaryDto(
    id = requireNotNull(id) { "SenderProfile must have an id for this test fixture." },
    name = name,
    vatId = vatId?.value,
    isDefault = isDefault,
    billingAddressCity = billingAddress?.city,
    additionalVatRegistrations = additionalVatRegistrations.map {
        SellerVatRegistrationDto(countryAlpha2 = it.country.alpha2, vatId = requireNotNull(it.vatId).value)
    },
)
