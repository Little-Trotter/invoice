package de.littletrotter.domain.invoice

/**
 * Where the editable invoice note currently comes from. [SELLER_DEFAULT] means it may follow a
 * sender or language change while the user has not touched it. [MANUAL] also covers an explicit
 * empty value and therefore protects deliberate clearing from later default changes.
 */
enum class InvoiceNoteOrigin {
    SELLER_DEFAULT,
    MANUAL,
}

/** The complete decision state used to update an invoice note without consulting mutable master
 * data after the decision has been made. [sourceContext] is an extra input the default depends on
 * (e.g. the recipient type for the consumer note); empty when the default has no such input. */
data class InvoiceNoteSnapshot(
    val value: String?,
    val origin: InvoiceNoteOrigin,
    val sourceSenderProfileId: Long?,
    val sourceLanguage: String,
    val sourceDefaultValue: String?,
    val sourceContext: String = "",
)

/** Creates the immutable initial copy of the sender's language-specific default. */
fun initialInvoiceNoteSnapshot(
    senderProfileId: Long?,
    language: String,
    sellerDefault: String?,
    sourceContext: String = "",
): InvoiceNoteSnapshot = InvoiceNoteSnapshot(
    value = sellerDefault,
    origin = InvoiceNoteOrigin.SELLER_DEFAULT,
    sourceSenderProfileId = senderProfileId,
    sourceLanguage = language,
    sourceDefaultValue = sellerDefault,
    sourceContext = sourceContext,
)

/** One header submission's note-related fields, bundled to keep
 *  [updateInvoiceNoteSnapshot]'s parameter list short. [resetToSellerDefault] is the explicit way
 *  back to automatic default tracking. */
data class NoteSubmission(
    val submitted: Boolean,
    val value: String?,
    val resetToSellerDefault: Boolean,
)

/** [senderProfileId]/[language]/[sellerDefault] bundled so [updateInvoiceNoteSnapshot] takes one
 *  parameter for them instead of three (LongParameterList) — the same triple
 *  [initialInvoiceNoteSnapshot] already takes, unpacked at each of its two call sites below. */
data class SellerNoteDefault(
    val senderProfileId: Long?,
    val language: String,
    val sellerDefault: String?,
    val sourceContext: String = "",
)

/**
 * Applies one header submission. An actual value change is a manual edit, including clearing the
 * field. An untouched seller default follows a changed sender/language. A manual value never does.
 * [NoteSubmission.resetToSellerDefault] is the explicit way back to automatic default tracking.
 */
fun updateInvoiceNoteSnapshot(
    current: InvoiceNoteSnapshot,
    submission: NoteSubmission,
    source: SellerNoteDefault,
): InvoiceNoteSnapshot {
    if (submission.resetToSellerDefault) {
        return initialInvoiceNoteSnapshot(source.senderProfileId, source.language, source.sellerDefault, source.sourceContext)
    }

    val normalizedSubmission = submission.value?.takeUnless { it.isEmpty() }
    if (submission.submitted && normalizedSubmission != current.value) {
        return current.copy(value = normalizedSubmission, origin = InvoiceNoteOrigin.MANUAL)
    }

    val sourceChanged = current.sourceSenderProfileId != source.senderProfileId ||
        current.sourceLanguage != source.language ||
        current.sourceContext != source.sourceContext
    return if (current.origin == InvoiceNoteOrigin.SELLER_DEFAULT && sourceChanged) {
        initialInvoiceNoteSnapshot(source.senderProfileId, source.language, source.sellerDefault, source.sourceContext)
    } else {
        current
    }
}

/** The invoice-owned private-customer notice with its provenance, for snapshot updates. */
fun Invoice.consumerNoteSnapshot(): InvoiceNoteSnapshot = InvoiceNoteSnapshot(
    consumerNote,
    consumerNoteOrigin,
    consumerNoteSourceSenderProfileId,
    consumerNoteSourceLanguage,
    consumerNoteSourceDefaultValue,
    consumerNoteSourceContext,
)

/** Writes [snapshot] back onto the invoice's consumer note columns. */
fun Invoice.applyConsumerNoteSnapshot(snapshot: InvoiceNoteSnapshot) {
    consumerNote = snapshot.value
    consumerNoteOrigin = snapshot.origin
    consumerNoteSourceSenderProfileId = snapshot.sourceSenderProfileId
    consumerNoteSourceLanguage = snapshot.sourceLanguage
    consumerNoteSourceContext = snapshot.sourceContext
    consumerNoteSourceDefaultValue = snapshot.sourceDefaultValue
}
