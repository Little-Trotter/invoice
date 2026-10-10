package de.littletrotter.shared

/**
 * Official VAT designation labels by country.
 *
 * When a foreign VAT registration governs an invoice, the PDF must label that number with
 * the country's own official designation, not a translation of the German "USt-IdNr."
 * (wording rule: foreign designations keep their official local names).
 *
 * Pure data + nullable lookup only: the fallback label for unknown countries is
 * locale-dependent and lives with the consumer's label bundle (e.g. kotlin-xpdf's
 * `Labels.label("sender.vatId", language)`), which kotlin-shared must not depend on.
 */
val registrationVatDesignationByCountry: Map<String, String> = mapOf(
    "AT" to "UID-Nr.",
    "CH" to "MWST-Nr.",
    "FR" to "TVA",
    "LU" to "TVA",
    "BE" to "BTW/TVA",
    "NL" to "Btw-nr.",
    "PL" to "NIP",
    "CZ" to "DIČ",
    "DK" to "CVR-nr.",
)

/** The official designation for [country] (ISO 3166-1 alpha-2), or null when unknown —
 *  callers supply their own localized fallback. */
fun registrationVatDesignation(country: String?): String? =
    country?.uppercase()?.let { registrationVatDesignationByCountry[it] }
