package de.littletrotter.shared

/**
 * A full multi-stage validation run: named stages instead of one
 * flat [KositValidationResult]. The EN-16931 Schematron, CIUS/profile,
 * and the actual KoSIT run stages are delivered together by today's single KoSIT engine call and
 * stay collapsed under one `"KOSIT"`-stage entry here — [ValidationMessage.stage] already
 * distinguishes `SYNTAX`/`KOSIT`/`PDFA`/`BUSINESS` per finding, splitting the KoSIT-internal
 * rules further would need signal the engine doesn't expose today. Genuinely separate stages:
 * `SYNTAX` (CII XSD, `CiiXsdValidator` — runs before KoSIT), `PDFA`
 * (`PdfA3Validator` — runs after PDF export), and `BUSINESS` (tax/
 * identity findings — an always-`skipped` placeholder until that work lands).
 */
data class ValidationRun(
    val stages: List<ValidationStageResult>,
    val overall: KositOutcome,
    val validatorVersions: Map<String, String> = emptyMap(),
    val startedAt: String,
) {
    companion object {
        /** ERROR on a stage makes the run ERROR, not INVALID — invoice is unchecked, not faulty.
         *  INVALID on any stage makes the run INVALID. Skipped stages never participate. */
        fun overallOutcomeOf(stages: List<ValidationStageResult>): KositOutcome {
            val active = stages.filterNot { it.skipped }
            return when {
                active.any { it.outcome == KositOutcome.ERROR } -> KositOutcome.ERROR
                active.any { it.outcome == KositOutcome.INVALID } -> KositOutcome.INVALID
                else -> KositOutcome.VALID
            }
        }
    }
}

/** One named stage's own result. [findings] carries only that stage's own
 *  [ValidationMessage]s. A [skipped] stage's [outcome] is not authoritative for the run's
 *  overall verdict — see [ValidationRun.overallOutcomeOf]. */
data class ValidationStageResult(
    val stage: String,
    val outcome: KositOutcome,
    val findings: List<ValidationMessage> = emptyList(),
    val skipped: Boolean = false,
    val skipReason: String? = null,
    /** Only ever set when [outcome] is [KositOutcome.ERROR]: the human-readable reason a
     *  technical validator failure occurred (e.g. veraPDF-CLI unavailable) — never a document
     *  defect (see the V2/A1 rule in `VeraPdfCliValidator`'s KDoc). */
    val engineError: String? = null,
)
