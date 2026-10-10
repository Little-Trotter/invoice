package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.outbound.AttachmentLibraryRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `AttachmentLibraryQueryPort`; the call itself is the outbound `AttachmentLibraryRemote`. */
@ApplicationScoped
class AttachmentLibraryQueryService @Inject constructor(
    private val remote: AttachmentLibraryRemote,
) : AttachmentLibraryQueryPort {

    override fun entries(): List<AttachmentLibraryEntry> = remote.entries()

    override fun versionContent(entryId: String, versionId: String): QuoteAttachmentContent =
        remote.versionContent(entryId, versionId)
}
