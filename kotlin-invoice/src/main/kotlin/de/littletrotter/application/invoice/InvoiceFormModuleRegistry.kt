package de.littletrotter.application.invoice

import de.littletrotter.spi.form.InvoiceFormModule
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * The single point from which the form views obtain the registered field-modules. Any
 * [InvoiceFormModule] bean is discovered by CDI simply because it is present on the classpath and
 * contributes unconditionally; no View code changes to add or remove a module.
 */
@ApplicationScoped
class InvoiceFormModuleRegistry @Inject constructor(
    private val modules: Instance<InvoiceFormModule>,
) {
    fun modules(): List<InvoiceFormModule> = modules.toList()
}
