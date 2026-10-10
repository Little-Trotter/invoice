package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry

/** Remote side of `MaintainAttachmentLibraryUseCase`: the offer component call behind the application service. */
interface MaintainAttachmentLibraryRemote {

    fun create(entry: NewAttachmentLibraryEntry): String

    fun addVersion(entryId: String, file: AttachmentLibraryFile): String

    fun archive(entryId: String)
}
