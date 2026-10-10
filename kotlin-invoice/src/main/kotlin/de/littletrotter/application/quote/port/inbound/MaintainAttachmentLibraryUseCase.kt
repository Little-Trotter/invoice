package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Maintains the offer receipt pool: new entries, new versions, archiving. */
interface MaintainAttachmentLibraryUseCase : CommandEntryPoint {

    /** Creates an entry with its first version and returns the entry id. */
    fun create(entry: NewAttachmentLibraryEntry): String

    /** Adds [file] as the next version of [entryId] and returns the version id. */
    fun addVersion(entryId: String, file: AttachmentLibraryFile): String

    /** Withdraws [entryId] from selection; versions already attached to offers stay readable. */
    fun archive(entryId: String)
}
