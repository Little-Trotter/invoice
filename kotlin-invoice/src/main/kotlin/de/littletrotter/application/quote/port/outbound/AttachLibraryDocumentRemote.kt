package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.ReplaceLibraryVersionCommand

/** Remote side of `AttachLibraryDocumentUseCase`: the offer component call behind the application service. */
interface AttachLibraryDocumentRemote {

    fun attach(command: AttachLibraryDocumentCommand): String

    fun replaceVersion(command: ReplaceLibraryVersionCommand)
}
