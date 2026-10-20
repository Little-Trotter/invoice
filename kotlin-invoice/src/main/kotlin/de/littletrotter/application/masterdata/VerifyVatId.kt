package de.littletrotter.application.masterdata

import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase
import de.littletrotter.application.masterdata.port.outbound.VatIdVerificationPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.dto.VatIdVerificationResult

/** Delegates the registry lookup to the outbound port; kept separate so the UI never sees it. */
@ApplicationScoped
class VerifyVatId @Inject constructor(
    private val verification: VatIdVerificationPort,
) : VerifyVatIdUseCase {
    override fun verify(countryCode: String, vatId: String): VatIdVerificationResult =
        verification.verify(countryCode, vatId)
}
