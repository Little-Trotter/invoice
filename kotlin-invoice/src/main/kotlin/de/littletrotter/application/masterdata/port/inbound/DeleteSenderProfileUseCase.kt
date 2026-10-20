package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Input port for deleting a sender profile that no invoice references. */
interface DeleteSenderProfileUseCase : CommandEntryPoint {
    fun deleteSenderProfile(id: Long)
}
