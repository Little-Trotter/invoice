package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile

/** Remote side of `MaintainAttachmentLibraryUseCase`: the offer component call behind the application service. */
interface MaintainAttachmentLibraryRemote {

    fun create(entry: NewAttachmentLibraryEntry): StoredQuoteFile

    fun addVersion(entryId: String, file: AttachmentLibraryFile): StoredQuoteFile

    fun archive(entryId: String)
}
