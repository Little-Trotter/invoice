package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.validation.CiiSchematronValidator
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * CDI producer for [CiiSchematronValidator]: a producer method rather than CDI annotations on
 * the class itself, because Quarkus's build-time bean discovery does not reliably find a bean
 * declared in a dependency.
 *
 * `@Startup` sits on the producer *method*: the validator pays the EN16931 Schematron compile
 * cost at application startup (see its class doc's "Eager EN16931, lazy EXTENDED" section)
 * rather than on the first real request. `@Startup` works on a CDI producer method the
 * same way it does on a bean class — it forces the produced bean's eager instantiation (and
 * therefore this method's own `.apply { init() }`, still the same eager EN16931 compile) at
 * application boot, exactly as before; putting it on this wrapper *class* instead would only
 * eager-instantiate the wrapper, not the [CiiSchematronValidator] it produces.
 */
@ApplicationScoped
class CiiSchematronValidatorProducer {

    @Produces
    @ApplicationScoped
    @Startup
    fun produce(): CiiSchematronValidator = CiiSchematronValidator().apply { init() }
}
