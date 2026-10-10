package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.domain.invoice.RestoreSource

/**
 * Outcome of asking for an invoice's document of record. Restoring a byte-identical copy always
 * precedes rendering; rendering runs through the normal job pipeline and therefore answers with a
 * started job, never with bytes.
 *
 * A sealed class, not an interface: this package holds the input-port interfaces, and a result
 * type is not one.
 */
sealed class DocumentResolution {
    /** The stored original is present and its checksum matches: serve it as is. */
    data class Serve(val objectKey: String) : DocumentResolution()

    /** A byte-identical copy was written back under the original key; serve it. */
    data class Restored(val objectKey: String, val source: RestoreSource) : DocumentResolution()

    /**
     * No valid original and nothing to restore: a generation job was started (or is already
     * running). [reproduction] is true when the invoice was sent and the result will carry the
     * reproduction tag.
     */
    data class RegenerationStarted(val jobId: Long, val reproduction: Boolean) : DocumentResolution()

    /** The document store answered with an I/O error, not with "missing" — retry later, never re-render. */
    data object StorageUnavailable : DocumentResolution()

    /** The invoice has no completed issued document yet, so there is nothing to resolve. */
    data object NoDocument : DocumentResolution()
}
