package de.littletrotter.shared

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * JSON serialisation envelope that flows through `ExportResult.kositReport` →
 * NATS → `GenerationJob.kositReport`. Carries the full typed validation result
 * (status + individual messages) so the UI can render details rather than a count.
 */
data class KositReportPayload(
    val status: String,
    val summary: String? = null,
    val details: List<ValidationMessage> = emptyList(),
    val validatorVersion: String? = null,
    // F2 — version block (CO-03/CO-06); null on documents written before this field existed.
    val generatorVersion: String? = null,
    val profile: String? = null,
    val checkedAt: String? = null,
    /** F3 — the multi-stage breakdown; `null` for a run/reader that predates F3. */
    val validationRun: ValidationRun? = null,
) {
    /**
     * This report with [stage] in place of the stage of the same name (appended when the run has
     * none), and the flat fields derived from the stages again, exactly as the worker builds
     * them. `null` when the report carries no stage breakdown, since there is nothing to replace
     * in. Used for results that arrive after the document was stored.
     */
    fun withStage(stage: ValidationStageResult): KositReportPayload? {
        val run = validationRun ?: return null
        val stages = if (run.stages.any { it.stage == stage.stage }) {
            run.stages.map { if (it.stage == stage.stage) stage else it }
        } else {
            run.stages + stage
        }
        val overall = ValidationRun.overallOutcomeOf(stages)
        val counted = stages.filterNot { it.skipped || it.pending }
        val findingCount = counted.sumOf { it.findings.size }
        return copy(
            status = overall.name,
            summary = if (findingCount == 0) null else "$findingCount validation finding(s) across ${counted.size} stage(s)",
            details = stages.flatMap { it.findings },
            validationRun = run.copy(stages = stages, overall = overall),
        )
    }

    /** The JSON text [decode] reads back. */
    fun toJson(): String = mapper.writeValueAsString(this)

    companion object {
        // F1: an old reader must not throw on a field a newer producer added (e.g. ruleId/
        // stage/validator on ValidationMessage) — otherwise decode() silently returns null
        // and the invoice reads as INVALID instead of "not yet evaluable". This is the
        // reason the tolerant reader ships as its own commit ahead of any field addition.
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        /** Encodes a [KositValidationResult] into a non-blank JSON string (always present even for VALID). */
        fun encode(result: KositValidationResult): String = mapper.writeValueAsString(
            KositReportPayload(
                status = result.status.name,
                summary = result.summary,
                details = result.details,
                validatorVersion = result.validatorVersion,
                generatorVersion = result.generatorVersion,
                profile = result.profile,
                checkedAt = result.checkedAt,
                validationRun = result.validationRun,
            )
        )

        /** Decodes a JSON report payload, or returns null when the input is not a JSON payload. */
        fun decode(json: String?): KositReportPayload? {
            if (json.isNullOrBlank()) return null
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            return try {
                mapper.readValue<KositReportPayload>(trimmed)
            } catch (_: Exception) {
                null
            }
        }
    }
}
