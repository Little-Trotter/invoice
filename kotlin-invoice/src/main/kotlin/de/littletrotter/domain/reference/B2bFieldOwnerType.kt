package de.littletrotter.domain.reference

/** Which kind of record a [B2bFieldValue] row belongs to. */
enum class B2bFieldOwnerType {
    SENDER_PROFILE,
    INVOICE,
    INVOICE_ITEM,
    ARTICLE,
}
