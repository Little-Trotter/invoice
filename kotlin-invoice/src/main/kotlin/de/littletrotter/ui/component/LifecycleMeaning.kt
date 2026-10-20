package de.littletrotter.ui.component

import com.vaadin.flow.component.icon.VaadinIcon

/**
 * What a document status means for the reader, independent of the document kind. Invoices and
 * offers map their own status onto one of these, so the same word always wears the same colour and
 * symbol; there is no second table per document kind.
 */
enum class LifecycleMeaning {
    /** Still being written. */
    DRAFT,

    /** Handed to a second pair of eyes before it leaves the house. */
    IN_REVIEW,

    /** Delivered to the recipient and waiting for what follows. */
    SENT,

    /** Dropped before it was ever sent. */
    VOIDED,

    /** Closed after sending without success: an invoice reversed by a credit note, an offer declined. */
    ENDED_NEGATIVE,

    /** Closed after sending with success: an offer the customer accepted. */
    ENDED_POSITIVE,
}

/** The kind of document a status belongs to; only the symbol of a negative ending differs between them. */
enum class LifecycleSubject { INVOICE, OFFER }

fun lifecycleTone(meaning: LifecycleMeaning): ChipTone = when (meaning) {
    LifecycleMeaning.DRAFT -> ChipTone.NEUTRAL
    LifecycleMeaning.IN_REVIEW -> ChipTone.REVIEW
    LifecycleMeaning.SENT -> ChipTone.ACCENT
    LifecycleMeaning.VOIDED -> ChipTone.ERROR
    LifecycleMeaning.ENDED_NEGATIVE -> ChipTone.CANCELLATION
    LifecycleMeaning.ENDED_POSITIVE -> ChipTone.OK
}

/** One distinct silhouette per meaning, so a row of chips still reads without colour. */
fun lifecycleGlyph(meaning: LifecycleMeaning, subject: LifecycleSubject): VaadinIcon = when (meaning) {
    LifecycleMeaning.DRAFT -> VaadinIcon.EDIT
    LifecycleMeaning.IN_REVIEW -> VaadinIcon.EYE
    LifecycleMeaning.SENT -> VaadinIcon.PAPERPLANE
    LifecycleMeaning.VOIDED -> VaadinIcon.BAN
    LifecycleMeaning.ENDED_NEGATIVE -> when (subject) {
        LifecycleSubject.INVOICE -> VaadinIcon.ROTATE_LEFT
        LifecycleSubject.OFFER -> VaadinIcon.CLOSE_SMALL
    }
    LifecycleMeaning.ENDED_POSITIVE -> VaadinIcon.CHECK
}

/** Accent class of a cockpit header: the edge colour follows the meaning, not the document kind. */
fun lifecycleHeroClass(meaning: LifecycleMeaning): String = when (meaning) {
    LifecycleMeaning.DRAFT -> "cockpit-hero--draft"
    LifecycleMeaning.IN_REVIEW -> "cockpit-hero--review"
    LifecycleMeaning.SENT, LifecycleMeaning.ENDED_POSITIVE -> "cockpit-hero--sent"
    LifecycleMeaning.VOIDED -> "cockpit-hero--voided"
    LifecycleMeaning.ENDED_NEGATIVE -> "cockpit-hero--cancelled"
}
