package de.littletrotter.application.invoice

/** Post-commit hint that a credit note was issued and still needs its document. */
data class CreditNoteIssued(val creditNoteId: Long)
