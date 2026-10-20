package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.SaveSenderProfileCommand

/** Input port for creating or changing a sender profile together with its billing address. */
interface SaveSenderProfileUseCase : CommandEntryPoint {
    /** Returns the id of the saved profile. */
    fun saveSenderProfile(command: SaveSenderProfileCommand): Long
}
