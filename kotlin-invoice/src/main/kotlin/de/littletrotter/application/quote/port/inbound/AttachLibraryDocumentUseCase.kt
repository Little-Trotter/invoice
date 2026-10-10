package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Takes pool documents over into a draft offer and swaps the version they carry. */
interface AttachLibraryDocumentUseCase : CommandEntryPoint {

    /** Attaches the library version and returns the attachment id. */
    fun attach(command: AttachLibraryDocumentCommand): String

    /** Lets the attachment carry another version of the same entry; only a draft offer accepts it. */
    fun replaceVersion(command: ReplaceLibraryVersionCommand)
}
