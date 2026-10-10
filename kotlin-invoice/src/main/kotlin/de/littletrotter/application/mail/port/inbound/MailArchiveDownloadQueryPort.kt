package de.littletrotter.application.mail.port.inbound

/** The signed archive package (tar.gz) of one sent mail, as the mail component delivers it. */
data class MailArchiveDownload(val filename: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MailArchiveDownload && filename == other.filename && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * filename.hashCode() + bytes.contentHashCode()
}

/**
 * Fetches the archive package of a journal row from the mail component, which alone holds the
 * bucket; `null` when the component has no package for the row.
 */
fun interface MailArchiveDownloadQueryPort {
    fun findArchive(journalId: Long): MailArchiveDownload?
}
