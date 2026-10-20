package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentUseCase
import de.littletrotter.application.quote.port.inbound.ReplaceLibraryVersionCommand
import de.littletrotter.application.quote.port.outbound.AttachLibraryDocumentRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `AttachLibraryDocumentUseCase`; the call itself is the outbound `AttachLibraryDocumentRemote`. */
@ApplicationScoped
class AttachLibraryDocumentService @Inject constructor(
    private val remote: AttachLibraryDocumentRemote,
) : AttachLibraryDocumentUseCase {

    override fun attach(command: AttachLibraryDocumentCommand): String = remote.attach(command)

    override fun replaceVersion(command: ReplaceLibraryVersionCommand) = remote.replaceVersion(command)
}
