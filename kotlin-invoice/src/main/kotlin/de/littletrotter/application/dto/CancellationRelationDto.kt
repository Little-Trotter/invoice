package de.littletrotter.application.dto

/** Role of the counterpart in a cancellation pair, seen from the invoice being displayed. */
enum class CancellationRole { ORIGINAL, CREDIT_NOTE }

/** The other half of a cancellation pair: the credit note of a cancelled original, or the original of a credit note. */
data class CancellationRelationDto(val counterpartId: Long, val counterpartNumber: String, val role: CancellationRole)
