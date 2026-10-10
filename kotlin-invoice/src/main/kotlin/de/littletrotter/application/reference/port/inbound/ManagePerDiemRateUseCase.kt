package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.PerDiemRateDto

/** Input port for maintaining the per-diem-rate catalogue: saves one edited row, deletes one. */
interface ManagePerDiemRateUseCase : CommandEntryPoint {
    fun savePerDiemRate(rate: PerDiemRateDto): PerDiemRateDto

    fun deletePerDiemRate(id: Long)
}
