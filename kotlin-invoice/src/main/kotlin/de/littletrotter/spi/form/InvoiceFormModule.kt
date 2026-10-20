package de.littletrotter.spi.form

/**
 * The registration point for additional invoice-form fields.
 *
 * A module is discovered via CDI and contributes [fields] into the stated [FieldSpec.slot]s. Any
 * bean implementing this interface on the classpath is active unconditionally.
 */
interface InvoiceFormModule {
    /** Stable identifier of this module (module-scope unique). */
    val id: String

    /**
     * All fields this module can contribute. Implementations should return a new list on each
     * call so callers may freely mutate the result.
     */
    fun fields(ctx: FormContext): List<FieldSpec>
}
