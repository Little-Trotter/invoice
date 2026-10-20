package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent

/** Remote side of `AttachmentLibraryQueryPort`: the offer component call behind the application service. */
interface AttachmentLibraryRemote {

    fun entries(): List<AttachmentLibraryEntry>

    fun versionContent(entryId: String, versionId: String): QuoteAttachmentContent
}
