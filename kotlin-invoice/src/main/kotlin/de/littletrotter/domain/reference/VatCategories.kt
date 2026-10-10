package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.tax.VatCategory

/** Invoice-side parse of an external category code: keeps the domain exception the shared, non-throwing enum cannot know. */
object VatCategories {
    fun parse(code: String): VatCategory =
        VatCategory.fromCodeOrNull(code) ?: throw InvalidParameterException("Unbekannter Steuerkategorie-Code: $code")
}
