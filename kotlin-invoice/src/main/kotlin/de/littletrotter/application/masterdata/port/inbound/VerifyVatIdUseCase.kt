package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.VatIdVerificationResult

/**
 * Input port of the "check a customer's VAT ID against the EU registry" use case. The customer
 * form triggers it only on an explicit button press; the outcome is advisory and never blocks
 * saving or printing.
 */
interface VerifyVatIdUseCase : CommandEntryPoint {
    /**
     * Checks [vatId] (without country prefix) for [countryCode] (ISO 3166-1 alpha-2). An
     * unreachable registry is reported as [VatIdVerificationResult.UNREACHABLE], never thrown.
     */
    fun verify(countryCode: String, vatId: String): VatIdVerificationResult
}
