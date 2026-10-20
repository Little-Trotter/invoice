package de.littletrotter.shared.tax

import de.littletrotter.shared.country.Country

sealed class VatIdCrossCheckResult {
    object Ok : VatIdCrossCheckResult()
    data class Invalid(val reason: String) : VatIdCrossCheckResult()
}

/** R5 "USt-Id-Prüfung" — the structural checks that don't need VIES: format, buyer-prefix vs.
 *  buyer country, seller ≠ buyer. VIES itself is [VatIdVerificationPort]'s job. */
object VatIdCrossCheck {
    fun validate(sellerVatId: String?, buyerVatId: String?, buyerCountry: Country): VatIdCrossCheckResult {
        if (buyerVatId.isNullOrBlank()) return VatIdCrossCheckResult.Invalid("Käufer-USt-IdNr. fehlt.")
        if (sellerVatId.isNullOrBlank()) return VatIdCrossCheckResult.Invalid("Verkäufer-USt-IdNr. fehlt.")

        val normalizedBuyer = normalize(buyerVatId)
        val normalizedSeller = normalize(sellerVatId)
        if (normalizedBuyer == normalizedSeller) {
            return VatIdCrossCheckResult.Invalid("Verkäufer- und Käufer-USt-IdNr. dürfen nicht identisch sein.")
        }
        val expectedPrefix = buyerCountry.alpha2
        if (!normalizedBuyer.startsWith(expectedPrefix)) {
            return VatIdCrossCheckResult.Invalid(
                "Käufer-USt-IdNr. '$buyerVatId' passt nicht zum Empfängerland ${buyerCountry.alpha2} (Präfix $expectedPrefix erwartet)."
            )
        }
        return VatIdCrossCheckResult.Ok
    }

    /** Keeps the original value available to the caller — only the comparison is normalized. */
    fun normalize(vatId: String): String = vatId.trim().uppercase().replace(" ", "").replace("-", "")
}
