package de.littletrotter.application.dto

import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.shared.reference.resolveLocalizedText

/**
 * One row of the payment-term catalogue: what the catalogue grid shows, what its editor saves,
 * and what a payment-term selector (seller default, invoice header) picks from.
 *
 * [namesByLanguageCode] carries one raw entry per language actually captured, keyed by language
 * code (e.g. `"de"`/`"en"`) — the same shape the former `PaymentTerm.name`/`english_name` columns
 * were replaced by ([nameFor]/[rawNameFor] mirror the entity's own resolution). [fallbackLanguageCode]
 * names which of those entries is the mandatory fallback-language one; the catalogue's own
 * `validate()` guarantees it is always present and non-blank.
 *
 * [id] `null` marks a new row; [version] is the row's optimistic-lock revision, checked on save.
 */
data class PaymentTermDto(
    val id: Long? = null,
    val version: Long? = null,
    val namesByLanguageCode: Map<String, String> = emptyMap(),
    val fallbackLanguageCode: String? = null,
    val description: String? = null,
    val dueInDays: Int = 30,
    val discountPercent: DiscountPercent = DiscountPercent.ZERO,
    val discountDays: Int? = null,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
) {
    /** The resolved name for [languageCode]: its own entry, or the fallback language's entry —
     *  mirrors [de.littletrotter.domain.reference.LocalizedTextGroup.resolvedFor]. */
    fun nameFor(languageCode: String): String? =
        resolveLocalizedText(namesByLanguageCode, languageCode, fallbackLanguageCode ?: languageCode)

    /** The entry actually captured for [languageCode], no fallback — the catalogue tab's raw
     *  per-language columns and editor fields. */
    fun rawNameFor(languageCode: String): String? = namesByLanguageCode[languageCode]
}
