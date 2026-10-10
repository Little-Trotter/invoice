package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.BrFrCtcValidator
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * XPR-3 — CDI producer for [BrFrCtcValidator]. See [FacturXEngineProducer]'s class doc for the
 * full "why a producer method, not the class itself carrying CDI annotations" reasoning (same
 * fallback, verified live during XPR-3 Step 0). No config/eager-init needed here — unlike
 * FacturXSchematronValidator, [BrFrCtcValidator] takes no constructor arguments and compiles
 * its XSLT lazily on first use (its own `executable`/`suppressions` are Kotlin `lazy {}`
 * properties), so a plain `@Produces` is enough.
 */
@ApplicationScoped
class BrFrCtcValidatorProducer {

    @Produces
    @ApplicationScoped
    fun produce(): BrFrCtcValidator = BrFrCtcValidator()
}
