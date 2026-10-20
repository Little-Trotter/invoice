package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.PaymentTermDto

/**
 * Input port for maintaining the payment-term catalogue: saves one edited row, deletes one.
 * [savePaymentTerm]'s [PaymentTermDto.namesByLanguageCode] carries every per-language name the
 * form collected; the implementation resolves each language code against the active
 * [de.littletrotter.domain.reference.Language] rows itself (see `ManagePaymentTerm`).
 */
interface ManagePaymentTermUseCase : CommandEntryPoint {
    fun savePaymentTerm(term: PaymentTermDto): PaymentTermDto

    fun deletePaymentTerm(id: Long)
}
