package de.littletrotter.shared

/**
 * Renders the visible base document of an invoice — the letter a reader sees — as PDF/A-3
 * bytes, before the receipt annex, the Factur-X attachment and the watermark are added.
 *
 * One role interface, implemented in `kotlin-xpdf` by the Typst renderer (the product's only
 * layout engine). Lives here, next to [VeraPdfValidatorPort], so the export pipeline depends on
 * the shape and never on the engine.
 */
fun interface BasePdfRendererPort {
    fun render(invoice: InvoiceDto): ByteArray
}
