package de.littletrotter.shared

import java.text.Normalizer
import java.util.Locale

private val SUBJECT_NOISE = Regex("[\\s\\p{Z}\\p{Pd}\\p{Pc}/.]")

/**
 * Canonical form of a seller identity: two spellings of the same VAT-ID ("de 123-456/789" and
 * "DE123456789") must land on the same bytes — Unicode composition, upper case, and every
 * whitespace, dash, underscore, slash and dot are dropped.
 */
fun normaliseOutputIntentSubject(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFC)
        .uppercase(Locale.ROOT)
        .replace(SUBJECT_NOISE, "")

/**
 * The seller identity of an invoice: the VAT-ID that legitimises the invoice, otherwise the
 * national tax number, otherwise the seller's name — the first one that carries any content.
 * `null` when the seller carries none of them.
 */
fun resolveOutputIntentSubject(invoice: InvoiceDto): String? {
    val profile = invoice.senderProfile
    return sequenceOf(
        resolveActiveSellerVatId(profile, invoice.meta),
        profile?.taxNumber,
        profile?.name,
    ).map { it?.let(::normaliseOutputIntentSubject) }
        .firstOrNull { !it.isNullOrEmpty() }
}
