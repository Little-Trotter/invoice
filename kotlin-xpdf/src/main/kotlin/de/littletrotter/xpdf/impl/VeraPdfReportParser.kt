package de.littletrotter.xpdf.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.VeraPdfResult

/**
 * Reads veraPDF's machine-readable (JSON) report into a [VeraPdfResult]. Findings map into the
 * shared finding contract: one [ValidationMessage] per failed rule, carrying the ISO clause as
 * `ruleId`, the engine as `validator` and [STAGE] as `stage`, so there is no second finding format.
 *
 * An engine failure (no output, no job, a job the engine could not process, unparsable output)
 * yields [KositOutcome.ERROR], never [KositOutcome.INVALID]: "the check could not run" and "the
 * document is wrong" are different statements and stay apart.
 */
class VeraPdfReportParser(
    /** Names the engine and version in every finding, e.g. `veraPDF 1.30.2`. */
    private val validatorId: String,
) {

    private val mapper = jacksonObjectMapper()

    /** Parses veraPDF's machine-readable report. Tolerant about where the job node sits: the CLI
     *  has moved it between `report.jobs` and a top-level `jobs` across versions, and a pinned
     *  version should not be the only thing standing between us and an unreadable result. */
    fun parse(json: String): VeraPdfResult {
        if (json.isBlank()) return engineError("veraPDF lieferte keine Ausgabe")
        val root = mapper.readTree(json)
        val jobs = root.path("report").path("jobs").takeIf { it.isArray && it.size() > 0 }
            ?: root.path("jobs").takeIf { it.isArray && it.size() > 0 }
            ?: return engineError("veraPDF-Ausgabe enthält keinen Prüflauf")

        val job = jobs.first()
        val validationResult = job.path("validationResult").let { if (it.isArray) it.first() else it }
        if (validationResult.isMissingNode || validationResult.isNull) {
            // A job without a validation result means veraPDF could not even parse the document.
            val taskError = job.path("taskResult").path("exceptionMessage").asText("")
            return engineError("veraPDF konnte das Dokument nicht prüfen${if (taskError.isBlank()) "" else ": $taskError"}")
        }

        val compliant = validationResult.path("compliant").asBoolean(false)
        val profileName = validationResult.path("profileName").asText(PROFILE)
        val details = validationResult.path("details")
        val failedRules = details.path("failedRules").asInt(0)
        val failedChecks = details.path("failedChecks").asInt(0)

        val findings = details.path("ruleSummaries")
            .filter { it.path("ruleStatus").asText(it.path("status").asText("")).equals("FAILED", ignoreCase = true) }
            .map { summary -> toFinding(summary) }

        return VeraPdfResult(
            outcome = if (compliant) KositOutcome.VALID else KositOutcome.INVALID,
            profile = profileName,
            validator = validatorId,
            failedRuleCount = if (failedRules > 0) failedRules else findings.size,
            failedCheckCount = failedChecks,
            findings = findings,
        )
    }

    private fun toFinding(summary: JsonNode): ValidationMessage {
        val clause = summary.path("clause").asText("")
        val testNumber = summary.path("testNumber").asText("")
        val specification = summary.path("specification").asText("ISO 19005-3")
        val description = summary.path("description").asText("")
        val checks = summary.path("failedChecks").asInt(0)
        val location = summary.path("checks").takeIf { it.isArray && it.size() > 0 }
            ?.first()?.path("context")?.asText()?.takeIf { it.isNotBlank() }

        val ruleId = listOf(clause, testNumber).filter { it.isNotBlank() }.joinToString("-").ifBlank { "PDFA" }
        val message = buildString {
            append(specification)
            if (clause.isNotBlank()) append(", Klausel $clause")
            if (testNumber.isNotBlank()) append(" (Test $testNumber)")
            if (description.isNotBlank()) append(": $description")
            if (checks > 0) append(" — $checks betroffene Stelle(n)")
        }

        return ValidationMessage(
            severity = "ERROR",
            message = message,
            location = location,
            ruleId = ruleId,
            stage = STAGE,
            validator = validatorId,
        )
    }

    /** A result for a check that could not be performed. */
    fun engineError(reason: String) = VeraPdfResult(
        outcome = KositOutcome.ERROR,
        profile = PROFILE,
        validator = validatorId,
        failedRuleCount = 0,
        failedCheckCount = 0,
        findings = emptyList(),
        engineError = reason,
    )

    companion object {
        const val PROFILE = "PDF/A-3B"

        /** Stage name of the findings; the vocabulary of the shared finding contract. */
        const val STAGE = "PDFA"
    }
}
