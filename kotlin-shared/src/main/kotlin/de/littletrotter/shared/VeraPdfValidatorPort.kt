package de.littletrotter.shared

/**
 * A5 () — port for a PDF/A-3 conformance check against real PDF bytes, analogous to
 * [KositValidatorPort] for XML. Lives here (not in kotlin-xpdf) so [PdfExportPort] can depend on
 * the shape without depending on the CLI-process implementation behind it.
 */
fun interface VeraPdfValidatorPort {
    fun validate(pdfBytes: ByteArray): VeraPdfResult
}

/**
 * A5 — typed outcome of one veraPDF run, moved here from `kotlin-xpdf/impl/VeraPdfCliValidator`
 * so [VeraPdfValidatorPort] can return it without kotlin-shared depending on kotlin-xpdf.
 *
 * [outcome] follows F1's three-way split: `VALID` (conformant), `INVALID` (the document violates
 * the profile) and `ERROR` (the check could not be performed — see [engineError]).
 */
data class VeraPdfResult(
    val outcome: KositOutcome,
    val profile: String,
    val validator: String,
    val failedRuleCount: Int,
    val failedCheckCount: Int,
    val findings: List<ValidationMessage>,
    val engineError: String? = null,
) {
    val conformant: Boolean get() = outcome == KositOutcome.VALID

    /** One-line summary for the release report. */
    fun summary(): String = when (outcome) {
        KositOutcome.VALID -> "konform ($profile, $validator)"
        KositOutcome.INVALID -> "nicht konform ($profile): $failedRuleCount Regel(n), $failedCheckCount Prüfung(en) fehlgeschlagen"
        else -> "nicht geprüft: ${engineError ?: "unbekannter Engine-Fehler"}"
    }
}
