package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.domain.reference.B2bFieldOwnerType

/** Input port for writing B2B field values of an owner, keyed by `bt_code`. */
interface ManageB2bFieldValueUseCase : CommandEntryPoint {
    /** Replaces the owner's values (partial update). */
    fun saveB2bFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long, values: Map<String, String?>)

    /** Replaces the customer's values for [profileId]'s fields (partial update). */
    fun saveB2bRecipientFieldValues(recipientId: Long, profileId: Long, values: Map<String, String?>)

    /** Copies [articleId]'s `LINE` template values onto [invoiceItemId], scoped to [invoiceId]'s
     *  own B2B profile (resolved server-side; a no-op if none is assigned). */
    fun copyB2bArticleTemplateToInvoiceItem(articleId: Long, invoiceItemId: Long, invoiceId: Long)
}
