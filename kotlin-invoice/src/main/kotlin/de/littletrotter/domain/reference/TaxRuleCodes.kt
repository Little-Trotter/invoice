package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.tax.TaxRuleCode

/** Invoice-side parse of a persisted rule code: keeps the domain exception the shared, non-throwing type cannot know. */
object TaxRuleCodes {
    fun parse(value: String): TaxRuleCode =
        TaxRuleCode.ofOrNull(value) ?: throw InvalidParameterException("Regel-Code darf nicht leer sein.")
}
