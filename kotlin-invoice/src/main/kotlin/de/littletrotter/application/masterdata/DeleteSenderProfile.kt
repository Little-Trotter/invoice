package de.littletrotter.application.masterdata

import de.littletrotter.application.masterdata.port.inbound.DeleteSenderProfileUseCase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [DeleteSenderProfileUseCase] on top of [SellerProfileService.delete]. */
@ApplicationScoped
class DeleteSenderProfile @Inject constructor(
    private val sellerProfileService: SellerProfileService,
) : DeleteSenderProfileUseCase {
    override fun deleteSenderProfile(id: Long) = sellerProfileService.delete(id)
}
