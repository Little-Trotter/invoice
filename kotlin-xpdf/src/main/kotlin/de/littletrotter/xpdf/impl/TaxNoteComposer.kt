package de.littletrotter.xpdf.impl

/**
 * PO decision 4 (06.09.2026) — the German exemption-reason text (`tax_rate.exemption_reason_text`,
 * BT-120) is never translated or replaced; on an English document a short, standard English
 * sentence is added *alongside* it, derived only from the UNTDID 5305 tax category and the VATEX
 * code (BT-121) — never captured by a user, never invented per seller.
 *
 * One function for all three render sites (the tax-note box, the tax-breakdown rows, BT-120 on
 * the wire) so none of them grows its own concatenation. Every site passes its own separator:
 * a newline on the printed page (counted by [de.littletrotter.xpdf.impl.estimateNoteLineCount]
 * for the tax-note box's layout), `" — "` on the wire, where BT-120 stays one XML text line.
 *
 * Only the VATEX codes and UNTDID 5305 categories this codebase can actually produce are seeded
 * (`tax_rate.exemption_reason_code`/`category` in `V2__baseline_seed.sql`): `AE` always pairs
 * with `VATEX-EU-AE` (the baseline schema's own CHECK constraint enforces this), `E` and `O` never
 * carry a code. A category/code this codebase cannot produce resolves to "no standard text" here
 * rather than an invented sentence — add it once a rule actually needs it.
 */

private val KNOWN_VATEX_CODES = setOf("VATEX-EU-AE")
private val KNOWN_CATEGORIES = setOf("AE", "E", "O")

/** The UNTDID 5305 tax category (BT-151/BT-95) and VATEX code (BT-121/BT-121) [composeTaxNote]
 *  always resolves together — grouped so it and its two call sites (`InvoiceReportModelBuilder`,
 *  `MustangTransactionBuilder`) take one parameter for it instead of two (LongParameterList). */
internal data class TaxExemptionClassification(
    val category: String?,
    val vatexCode: String?,
)

/** Lookup order: VATEX code first (code-specific wins), then category, then nothing — "nothing"
 *  is a valid outcome (category `S`, or a code/category this codebase does not produce). */
private fun standardTaxNoteKey(classification: TaxExemptionClassification): String? = when {
    classification.vatexCode != null && classification.vatexCode in KNOWN_VATEX_CODES ->
        "taxnote.standard.${classification.vatexCode}"
    classification.category != null && classification.category in KNOWN_CATEGORIES ->
        "taxnote.standard.${classification.category}"
    else -> null
}

/**
 * Composes the printed/exported tax-exemption note for one German master-data text.
 *
 * - `language == "de"`: [germanText] unchanged; a blank [germanText] falls back to the German
 *   half of the standard-text family (today's pre-existing `ifBlank` behaviour, preserved
 *   exactly — this package must not move a single German document).
 * - `language == "en"`: [germanText] followed by [separator] and the English standard text. A
 *   blank [germanText] yields the English standard text alone. No standard text for the
 *   category/code yields [germanText] alone. Both blank yields `null` — never an empty string,
 *   never a dangling separator.
 */
internal fun composeTaxNote(
    germanText: String?,
    classification: TaxExemptionClassification,
    language: String,
    separator: String,
): String? {
    val german = germanText?.trim().orEmpty()
    val key = standardTaxNoteKey(classification)
    if (language != "en") {
        val resolved = german.ifBlank { key?.let { Labels.label(it, "de") } ?: "" }
        return resolved.ifBlank { null }
    }
    val english = key?.let { Labels.label(it, "en") }
    return when {
        german.isNotBlank() && english != null -> german + separator + english
        german.isNotBlank() -> german
        english != null -> english
        else -> null
    }
}
