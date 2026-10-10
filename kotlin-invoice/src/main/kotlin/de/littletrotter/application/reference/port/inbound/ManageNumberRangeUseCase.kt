package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.NumberRangeDto

/** Input port for maintaining number ranges. */
interface ManageNumberRangeUseCase : CommandEntryPoint {
    /** Validates [range] and rejects a prefix change once a number has been allocated from it. */
    fun saveNumberRange(range: NumberRangeDto): NumberRangeDto

    fun deleteNumberRange(id: Long)
}
