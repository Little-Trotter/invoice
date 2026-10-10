package de.littletrotter.application.reference

import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.domain.masterdata.PaymentTerm

/** Row view of a stored payment term, including its per-language names — raw entries, not
 *  fallback-resolved (see [PaymentTermDto.nameFor]/[PaymentTermDto.rawNameFor]). */
internal fun PaymentTerm.toDto(): PaymentTermDto = PaymentTermDto(
    id = id,
    version = version,
    namesByLanguageCode = nameGroup?.entries
        ?.mapNotNull { entry -> entry.language?.code?.let { it to entry.value } }
        ?.toMap()
        ?: emptyMap(),
    fallbackLanguageCode = nameGroup?.entries?.firstOrNull { it.language?.isFallback == true }?.language?.code,
    description = description,
    dueInDays = dueInDays,
    discountPercent = discountPercent,
    discountDays = discountDays,
    isDefault = isDefault,
    isActive = isActive,
)

/** Copies every plain editable field of this row onto [term]; id and revision stay the entity's
 *  own. Names are deliberately not part of this mirror — a [de.littletrotter.domain.reference
 *  .LocalizedTextGroup] needs [PaymentTerm.setName]'s own create/update/delete handling per
 *  language, done by [ManagePaymentTerm] against the resolved [de.littletrotter.domain.reference
 *  .Language] rows, not a plain field copy. */
internal fun PaymentTermDto.applyTo(term: PaymentTerm) {
    term.description = description
    term.dueInDays = dueInDays
    term.discountPercent = discountPercent
    term.discountDays = discountDays
    term.isDefault = isDefault
    term.isActive = isActive
}
