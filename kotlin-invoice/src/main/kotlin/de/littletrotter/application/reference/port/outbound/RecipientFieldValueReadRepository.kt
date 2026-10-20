package de.littletrotter.application.reference.port.outbound

/** Read side of the per-customer values of B2B profile fields, so callers need no loaded recipient. */
fun interface RecipientFieldValueReadRepository {

    /** The stored values of [recipientId], keyed by profile field id. */
    fun findValues(recipientId: Long): Map<Long, String?>
}
