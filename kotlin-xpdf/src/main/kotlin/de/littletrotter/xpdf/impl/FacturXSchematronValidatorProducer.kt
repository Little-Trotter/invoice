package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXSchematronValidator
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * XPR-3 — CDI producer for [FacturXSchematronValidator]. See [FacturXEngineProducer]'s class doc
 * for the full "why a producer method, not the class itself carrying CDI annotations" reasoning
 * (same fallback, verified live during XPR-3 Step 0).
 *
 * `@Startup` moved here, onto the producer *method*: before the split,
 * `FacturXSchematronValidator` ate the EN16931 Schematron compile cost at application startup
 * (`@Startup @PostConstruct`, eager — see its own class doc's "Eager EN16931, lazy EXTENDED"
 * section) rather than on the first real request. `@Startup` works on a CDI producer method the
 * same way it does on a bean class — it forces the produced bean's eager instantiation (and
 * therefore this method's own `.apply { init() }`, still the same eager EN16931 compile) at
 * application boot, exactly as before; putting it on this wrapper *class* instead would only
 * eager-instantiate the wrapper, not the [FacturXSchematronValidator] it produces.
 */
@ApplicationScoped
class FacturXSchematronValidatorProducer {

    @Produces
    @ApplicationScoped
    @Startup
    fun produce(): FacturXSchematronValidator = FacturXSchematronValidator().apply { init() }
}
