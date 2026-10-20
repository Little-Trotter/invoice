package de.littletrotter.application.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.repository.PaymentTermRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The seller's own defaults as the edit dialog submits them: only ids of the chosen catalogue
 * rows, never the rows themselves. Each `null` clears that default — every save states it.
 */
data class SellerDefaultSelections(
    val paymentTermId: Long? = null,
)

/**
 * Turns a [SellerDefaultSelections] id back into the stored catalogue row the seller profile
 * references. An id that no longer exists is an error the user must see, not a silent reset.
 */
@ApplicationScoped
class SellerDefaultReferences @Inject constructor(
    private val paymentTermRepository: PaymentTermRepository,
) {
    fun paymentTerm(id: Long?): PaymentTerm? {
        if (id == null) return null
        return paymentTermRepository.findById(id)
            ?: throw InvalidParameterException("Zahlungsbedingung mit ID $id wurde nicht gefunden.")
    }
}
