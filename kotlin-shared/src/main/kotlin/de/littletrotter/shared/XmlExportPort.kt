package de.littletrotter.shared

/**
 * Port for XML generation — implemented by the document worker (kotlin-xpdf).
 * Keeping a port here prevents kotlin-invoice from depending on Mustang/KoSIT.
 */
interface XmlExportPort {
    fun generate(invoice: InvoiceDto): ByteArray
    fun resolveProfile(invoice: InvoiceDto): String
}
