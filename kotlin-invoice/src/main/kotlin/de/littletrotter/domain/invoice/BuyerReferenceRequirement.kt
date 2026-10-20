package de.littletrotter.domain.invoice

/**
 * BT-10 (buyer reference) is mandatory for German invoices (BR-DE-15/PEPPOL-EN16931-R008).
 * Export cannot prevent an empty element, so validation is here before ISSUED, not masked in export.
 * Falls back to recipient's routing ID; if still empty, that is a data gap, not a silent default.
 */
fun Invoice.ensureBuyerReferenceForFinalization() {
    if (!references.buyerReference.isNullOrBlank()) return
    val fallback = recipient?.routingId?.takeIf { it.isNotBlank() }
    if (fallback == null) throw BuyerReferenceMissingException()
    references.assignBuyerReference(fallback)
}

/** Extends RuntimeException directly for the same reason as [de.littletrotter.domain.compliance
 *  .MixedTaxCategoryException] — a sealed hierarchy in domain/exception/ can't be subclassed
 *  from this package. */
class BuyerReferenceMissingException : RuntimeException(
    "Käuferreferenz (BT-10) fehlt — bitte im Rechnungskopf oder in den Kundenstammdaten (Leitweg-ID) hinterlegen."
)
