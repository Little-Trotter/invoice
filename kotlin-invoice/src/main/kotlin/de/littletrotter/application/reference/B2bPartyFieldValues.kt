package de.littletrotter.application.reference

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.effectiveSender
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.application.reference.port.outbound.B2bFieldValueStore
import de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository
import de.littletrotter.shared.b2b.B2bTerm
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The party's own stored value for a RECIPIENT- or SELLER-placed field: the seller's
 * `SENDER_PROFILE` row (via [B2bFieldValueStore]) for [B2bPlacement.SELLER], the
 * recipient's `recipient_b2b_field_value` row (via [RecipientFieldValueReadRepository]) for
 * [B2bPlacement.RECIPIENT] — `null` for [B2bPlacement.INVOICE] or a party with no id yet.
 *
 * Shared by [B2bFieldValueService] (the form/export resolution chain) and
 * [de.littletrotter.application.invoice.B2bProfileRuleSet] (the compliance-finding side): both
 * used to carry an identical `when (field.placement)` branch of their own — one lookup, one place
 * to change it.
 */
@ApplicationScoped
class B2bPartyFieldValues @Inject constructor(
    private val fieldValueStore: B2bFieldValueStore,
    private val recipientFieldValues: RecipientFieldValueReadRepository,
) {
    fun partyValue(invoice: Invoice, field: B2bProfileField, term: B2bTerm): String? =
        when (field.placement) {
            B2bPlacement.SELLER -> invoice.effectiveSender()?.id?.let { sellerId ->
                fieldValueStore.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, sellerId, term)?.value
            }
            B2bPlacement.RECIPIENT -> {
                val recipientId = invoice.recipient?.id
                val fieldId = field.id
                if (recipientId != null && fieldId != null) {
                    recipientFieldValues.findValues(recipientId)[fieldId]
                } else {
                    null
                }
            }
            B2bPlacement.INVOICE -> null
        }
}
