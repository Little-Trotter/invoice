package de.littletrotter.domain.invoice

/** Result of probing the stored original document of an issued invoice. */
enum class OriginalState {
    /** The stored object exists and its checksum matches the recorded one. */
    PRESENT,

    /** The stored object does not exist. */
    MISSING,

    /** The stored object exists but its checksum differs from the recorded one. */
    MISMATCH,
}

/** Why the original document is considered lost. */
enum class DocumentLossReason { ORIGINAL_MISSING, CHECKSUM_MISMATCH }

/** Independent byte-identical sources a lost original can be written back from. */
enum class RestoreSource { RUSTFS, MAIL_JOURNAL }

/** What the app does when an issued invoice's document is requested. */
sealed interface MissingOriginalResolution {
    /** The stored object is present and its checksum matches: serve it. */
    data object ServeOriginal : MissingOriginalResolution

    /** The invoice was never sent: nobody holds the old bytes, so a normal re-render is legitimate. */
    data class Regenerate(val reason: DocumentLossReason) : MissingOriginalResolution

    /** A byte-identical copy exists in [source]: write it back, never re-render. */
    data class RestoreFrom(val source: RestoreSource, val reason: DocumentLossReason) : MissingOriginalResolution

    /** Sent, but no byte-identical copy survives: re-render as a marked reproduction. */
    data class Reproduce(val reason: DocumentLossReason) : MissingOriginalResolution
}

/**
 * Decides what happens when the original document of an issued invoice is requested.
 * Restoring a byte-identical copy always precedes any rendering. A reproduction is a re-render
 * of an invoice that was already sent; it is internal only and is never dispatched.
 */
object DocumentReproductionPolicy {

    fun resolve(
        state: OriginalState,
        sent: Boolean,
        rustfsRestorable: Boolean,
        mailJournalRestorable: Boolean,
    ): MissingOriginalResolution {
        val reason = when (state) {
            OriginalState.PRESENT -> return MissingOriginalResolution.ServeOriginal
            OriginalState.MISSING -> DocumentLossReason.ORIGINAL_MISSING
            OriginalState.MISMATCH -> DocumentLossReason.CHECKSUM_MISMATCH
        }
        return when {
            !sent -> MissingOriginalResolution.Regenerate(reason)
            rustfsRestorable -> MissingOriginalResolution.RestoreFrom(RestoreSource.RUSTFS, reason)
            mailJournalRestorable -> MissingOriginalResolution.RestoreFrom(RestoreSource.MAIL_JOURNAL, reason)
            else -> MissingOriginalResolution.Reproduce(reason)
        }
    }
}
