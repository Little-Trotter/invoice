package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.MileageRateDto

/** Input port for maintaining the mileage-rate catalogue: saves one edited row, deletes one. */
interface ManageMileageRateUseCase : CommandEntryPoint {
    fun saveMileageRate(rate: MileageRateDto): MileageRateDto

    fun deleteMileageRate(id: Long)
}
