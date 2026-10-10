package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.HotelRateDto

/** Input port for maintaining the hotel-rate catalogue: saves one edited row, deletes one. */
interface ManageHotelRateUseCase : CommandEntryPoint {
    fun saveHotelRate(rate: HotelRateDto): HotelRateDto

    fun deleteHotelRate(id: Long)
}
