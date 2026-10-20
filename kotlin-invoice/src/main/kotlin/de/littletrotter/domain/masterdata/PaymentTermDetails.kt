package de.littletrotter.domain.masterdata

import de.littletrotter.domain.value.DiscountPercent

/**
 * The plain editable fields of a [PaymentTerm]; see [PaymentTerm.update]. The localized name is
 * edited through [PaymentTerm.setName]. Defaults equal the values of a freshly created term.
 */
data class PaymentTermDetails(
    val description: String? = null,
    val dueInDays: Int = 30,
    val discountPercent: DiscountPercent = DiscountPercent.ZERO,
    val discountDays: Int? = null,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
)
