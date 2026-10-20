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
         *  INVALID on any stage makes the run INVALID. Skipped, pending and advisory stages never
         *  participate: a result that arrives later, or only warns, must not change a verdict
         *  the send decision has already been made on. */
        fun overallOutcomeOf(stages: List<ValidationStageResult>): KositOutcome {
            val active = stages.filterNot { it.skipped || it.pending || it.advisory }
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
    /** The stage was requested but its result has not arrived yet (asynchronous check). Its
     *  [outcome] is a placeholder and must not be read as a verdict. */
    val pending: Boolean = false,
    /** Findings of this stage are shown as warnings and never decide the run's overall verdict. */
    val advisory: Boolean = false,
) {
    companion object {
        /** Stage name of the asynchronous veraPDF conformance check. */
        const val VERAPDF = "VERAPDF"
    }
}
