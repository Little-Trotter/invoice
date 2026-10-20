package de.littletrotter.shared

/** Result of a KOSIT schematron/validator run. */
data class KositValidationResult(
    val status: KositOutcome,
    val summary: String? = null,
    val details: List<ValidationMessage> = emptyList(),
    val validatorVersion: String? = null,
    /** Optional reference/key to a stored raw report (object store) */
    val rawReportRef: String? = null,
    // Version block. All null by default: an old, already-persisted
    // report has none of these, and the UI must show "unbekannt" for it, not throw.
    /** e.g. Mustang/ZUGFeRD library version — only resolvable in the worker (kotlin-xpdf),
     *  which is why it's captured here at validation time rather than re-derived on read. */
    val generatorVersion: String? = null,
    /** The export profile actually used (e.g. `"XRechnung"`, `"EN16931"`). */
    val profile: String? = null,
    /** ISO-8601 timestamp of the validation run itself — never the moment someone later
     *  opens the report dialog. */
    val checkedAt: String? = null,
    /** The multi-stage breakdown; `null` when only the legacy flat
     *  [details]/[status] were produced (an old validator, or a run that predates this field). */
    val validationRun: ValidationRun? = null,
)

enum class KositOutcome { VALID, INVALID, ERROR }

/**
 * A validation finding is a typed record, not a text line with a severity.
 *
 * [ruleId], [stage] and [validator] default to `null` and are plain `String` (not enums) so
 * an old reader never throws on an unknown value from a newer producer, and a JSON document
 * written before these fields existed stays decodable — see [KositReportPayload]'s tolerant reader.
 */
data class ValidationMessage(
    val severity: String,
    val message: String,
    val location: String? = null,
    /** e.g. `BR-DE-15`, `BR-CO-25`, or an XSD error code. Null when the validator/library
     *  doesn't expose one for this finding. */
    val ruleId: String? = null,
    /** `SYNTAX` | `EN16931` | `CIUS` | `KOSIT` | `PDFA` | `BUSINESS` */
    val stage: String? = null,
    /** e.g. `"kosit"`, `"xsd"` */
    val validator: String? = null,
    /** Message-format arguments for [message] when it is an i18n key rather than resolved prose
     *  (e.g. a `BUSINESS`-stage finding from [de.littletrotter.application.compliance
     *  .ComplianceFindingMapper], where the domain never renders text). `null` by default
     *  so a document written before this field existed stays decodable, same convention as
     *  [ruleId]/[stage]/[validator] above. A KoSIT/veraPDF finding's [message] is already resolved
     *  prose and carries no args. */
    val args: List<String>? = null,
    /** Every `BT-nn` token the `FACTURX_SCHEMATRON` stage found in [message], as its
     *  canonical `officialCode` (e.g. `"BT-27"`) when [de.littletrotter.shared.b2b.B2bTerm]
     *  recognizes the code, or the raw matched token when it does not (never dropped — an
     *  unrecognized BT is still evidence, not noise). `null` for every other stage/validator and
     *  for a finding with no BT reference in its text at all — kotlin-xpdf stays
     *  language-neutral: this carries only the code, never a display name; the name resolves via
     *  [de.littletrotter.shared.b2b.B2bTermTexts]. */
    val businessTerms: List<String>? = null,
    /** `true` exactly for a `FACTURX_SCHEMATRON`
     *  finding whose own Schematron assert is a pure code-list membership lookup (its `test`
     *  XPath contains a `document('...codedb.xml')` call), as opposed to a structural/calculation
     *  assert. Code-list membership is enforced before an invoice ever reaches this validator
     *  (save time, `FinalizationGate`) — a marked finding here means that upstream gate has a
     *  gap, not that the anwender did something wrong. `false` for every other finding (the
     *  default, not a marker of "checked and clean").  */
    val codeListViolation: Boolean = false,
)
