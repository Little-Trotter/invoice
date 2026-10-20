package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Maintains the offer receipt pool: new entries, new versions, archiving. */
interface MaintainAttachmentLibraryUseCase : CommandEntryPoint {

    /** Creates an entry with its first version and returns the entry id with the outcome of a requested reduction. */
    fun create(entry: NewAttachmentLibraryEntry): StoredQuoteFile

    /** Adds [file] as the next version of [entryId] and returns the version id with the outcome of a requested reduction. */
    fun addVersion(entryId: String, file: AttachmentLibraryFile): StoredQuoteFile

    /** Withdraws [entryId] from selection; versions already attached to offers stay readable. */
    fun archive(entryId: String)
}
