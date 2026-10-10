package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.MaintainAttachmentLibraryUseCase
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry
import de.littletrotter.application.quote.port.outbound.MaintainAttachmentLibraryRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `MaintainAttachmentLibraryUseCase`; the call itself is the outbound `MaintainAttachmentLibraryRemote`. */
@ApplicationScoped
class MaintainAttachmentLibraryService @Inject constructor(
    private val remote: MaintainAttachmentLibraryRemote,
) : MaintainAttachmentLibraryUseCase {

    override fun create(entry: NewAttachmentLibraryEntry): String = remote.create(entry)

    override fun addVersion(entryId: String, file: AttachmentLibraryFile): String = remote.addVersion(entryId, file)

    override fun archive(entryId: String) = remote.archive(entryId)
}
