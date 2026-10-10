package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.SellerVatRegistrationDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.masterdata.SenderProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [SenderQueryPort] on top of [SellerProfileService]'s reads. */
@ApplicationScoped
class SenderQueries @Inject constructor(
    private val sellerProfileService: SellerProfileService,
) : SenderQueryPort {

    // Int.MAX_VALUE as "unpaged": the seller list is small and must never be silently capped.
    override fun findAllSenders(): List<SenderSummaryDto> =
        sellerProfileService.findAll(0, Int.MAX_VALUE, null).map { it.toSummaryDto() }

    override fun findSenderById(id: Long): SenderSummaryDto? = sellerProfileService.findById(id)?.toSummaryDto()

    // additionalVatRegistrations is EAGER on SenderProfile (see its own KDoc), so no extra
    // query is triggered by reading it here.
    private fun SenderProfile.toSummaryDto(): SenderSummaryDto = SenderSummaryDto(
        id = requireNotNull(id) { "SenderProfile must be persisted before it can be mapped to SenderSummaryDto." },
        name = name,
        vatId = vatId?.value,
        isDefault = isDefault,
        billingAddressCity = billingAddress?.city,
        additionalVatRegistrations = additionalVatRegistrations.map {
            SellerVatRegistrationDto(countryAlpha2 = it.country.alpha2, vatId = requireNotNull(it.vatId).value)
        },
    )
}
