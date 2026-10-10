package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Replaces the archive hand-over setting of the mail component. */
interface UpdateArchiveHandoverSettingsUseCase : CommandEntryPoint {

    /**
     * Saves [update] as the whole setting and returns what the component stored. Configuring an
     * address also withdraws the standing "address missing" notice in the message center.
     */
    fun update(update: ArchiveHandoverSettingsUpdate): ArchiveHandoverSettings
}
