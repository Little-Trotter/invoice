package de.littletrotter.application.quote.port.inbound

/** Reads the reusable offer documents (the offer receipt pool). */
interface AttachmentLibraryQueryPort {

    /** All entries, archived ones included, so a view can filter. */
    fun entries(): List<AttachmentLibraryEntry>

    /** The bytes of one version; throws `QuoteNotFoundException` when it does not exist. */
    fun versionContent(entryId: String, versionId: String): QuoteAttachmentContent
}
