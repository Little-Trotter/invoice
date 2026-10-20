package de.littletrotter.quotationxml

/**
 * Serializes a [QuotationModel] into one concrete machine-readable syntax. This is the extension
 * point for further syntaxes; every implementation consumes the same syntax-neutral model.
 */
fun interface QuotationWriter {
    /** UTF-8 encoded XML document. */
    fun write(model: QuotationModel): ByteArray
}
