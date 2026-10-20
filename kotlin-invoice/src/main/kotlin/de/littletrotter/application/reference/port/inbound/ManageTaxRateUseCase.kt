package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.TaxRateDto

/**
 * Input port for maintaining the tax-rate catalogue. [saveTaxRate] creates a row when the
 * [TaxRateDto.id] is `null`, otherwise updates the stored row after checking its revision.
 */
interface ManageTaxRateUseCase : CommandEntryPoint {
    fun saveTaxRate(rate: TaxRateDto): TaxRateDto

    fun deleteTaxRate(id: Long)
}
