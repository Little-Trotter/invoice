package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.RecipientB2bFieldValue

/** Persistence for the per-customer `recipient_b2b_field_value` table; see
 *  [RecipientB2bFieldValue]'s own KDoc for why this is a dedicated table/repository rather than a
 *  fourth [de.littletrotter.domain.reference.B2bFieldOwnerType]. */
interface RecipientB2bFieldValueRepository {
    fun findByRecipientId(recipientId: Long): List<RecipientB2bFieldValue>
    fun findByRecipientIdAndProfileFieldId(recipientId: Long, profileFieldId: Long): RecipientB2bFieldValue?
    fun save(value: RecipientB2bFieldValue): RecipientB2bFieldValue
    fun delete(id: Long)
}
