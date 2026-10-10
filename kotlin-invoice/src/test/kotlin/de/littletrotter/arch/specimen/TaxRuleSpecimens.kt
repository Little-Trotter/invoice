package de.littletrotter.arch.specimen

import java.math.BigDecimal

/**
 * Test-only specimens for [de.littletrotter.arch.TaxRuleArchitectureTest]: a private copy of the
 * tax decision core in an application module, which the rule must reject.
 */
object ViolatingTaxRuleResolver {
    fun standardRate(): BigDecimal = BigDecimal("19.00")
}

enum class VatCategory { STANDARD }
