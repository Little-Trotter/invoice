package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.invoice.KositMessage
import de.littletrotter.application.invoice.KositResult
import de.littletrotter.application.invoice.KositStage
import de.littletrotter.application.invoice.KositStatus
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.shared.ValidationToolchain
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.format.DateFormatter
import java.text.MessageFormat
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Fixed position of the AI improvement specification section in [buildValidationReportMarkdown]. */
private const val KI_SPEC_SECTION_NUMBER = 3

/** Combined validation report: KoSIT norm check + per-model LLM analyses.
 *  Governing rule: KoSIT decides; LLM complements. An LLM finding must never flip a KoSIT
 *  VALID into NOT_SUCCESSFUL. Result is binary: [resolveValidationOutcome] is the single source. */
enum class ValidationVerdict {
    SUCCESSFUL,             // outcome Successful, no LLM findings
    SUCCESSFUL_WITH_NOTES,  // outcome Successful, at least one model finding
    NOT_SUCCESSFUL,         // outcome NotSuccessful (any reason) or still Pending
}

/** Result of combining KoSIT + LLM data. */
data class ValidationReportData(
    val verdict: ValidationVerdict,
    // PO decision (2026-08-24) — the binary outward result + its reason (when not successful),
    // the single source [kositSectionSummary] renders section 1's summary line from.
    val outcome: ValidationOutcome,
    val kositErrorCount: Int,
    val kositWarningCount: Int,
    val models: List<ModelReport>,
    // Version block, carried through from KositResult. Null on a report
    // built from an older job — the Markdown renders "unbekannt" for those, not a blank line.
    val validatorVersion: String? = null,
    val generatorVersion: String? = null,
    val profile: String? = null,
    val checkedAt: String? = null,
    // The exact e-invoice standard/version label (already resolved from the
    // worker's guideline URN, or the legacy profile fallback); null is a data gap like the other
    // version-block fields above and renders "unbekannt" the same way.
    val formatLabel: String? = null,
    // The multi-stage breakdown; empty for older payloads, in which case the Markdown
    // renders exactly as before (no stage table).
    val stages: List<KositStage> = emptyList(),
    // The run's own message-level findings (KoSIT + BUSINESS stage), carried
    // through only so the combined Markdown can list them with their rule ID and, where the AI
    // markdown mentions the same rule ID, a "KI-Tipp" badge jumping to that passage in section 3.
    // Empty on an older report with no `code`, which then renders no findings list at all — same
    // "absent, not fabricated" convention as everywhere else in this report.
    val findings: List<KositMessage> = emptyList(),
)

/**
 * Resolves the combined verdict from the binary [outcome] and per-model analyses.
 * Implements the decision table in H2 §2 — KoSIT (via [outcome]) decides, LLM complements.
 */
internal fun resolveVerdict(outcome: ValidationOutcome, models: List<ModelReport>): ValidationVerdict =
    when (outcome) {
        is ValidationOutcome.Successful ->
            if (models.any { it.hasFinding }) ValidationVerdict.SUCCESSFUL_WITH_NOTES else ValidationVerdict.SUCCESSFUL
        // Every reason a validation is not successful — including one still Pending, which a
        // report should not realistically be built for — reads as NOT_SUCCESSFUL; an LLM
        // finding never changes that either.
        is ValidationOutcome.NotSuccessful -> ValidationVerdict.NOT_SUCCESSFUL
        is ValidationOutcome.Pending -> ValidationVerdict.NOT_SUCCESSFUL
    }

/**
 * Builds the full report data. [kosit] may be absent (null) when no KoSIT report exists at all.
 * [outcome] is the binary result [resolveValidationOutcome] already derived for the job this
 * report is about — the single source of truth for both the verdict and the reason shown in
 * section 1, so this function never re-derives it from [kosit] itself.
 */
internal fun buildReportData(
    outcome: ValidationOutcome,
    kosit: KositResult?,
    analyses: List<ModelReport>,
    formatGuideline: String? = null,
): ValidationReportData {
    val effectiveKosit = kosit ?: KositResult(KositStatus.UNKNOWN, emptyList(), emptyList(), false)
    return ValidationReportData(
        verdict = resolveVerdict(outcome, analyses),
        outcome = outcome,
        kositErrorCount = effectiveKosit.errors.size,
        kositWarningCount = effectiveKosit.warnings.size,
        models = analyses,
        validatorVersion = effectiveKosit.validatorVersion,
        generatorVersion = effectiveKosit.generatorVersion,
        profile = effectiveKosit.profile,
        checkedAt = effectiveKosit.checkedAt,
        formatLabel = formatDescriptorLabel(formatGuideline) ?: effectiveKosit.profile,
        stages = effectiveKosit.stages,
        findings = effectiveKosit.errors + effectiveKosit.warnings,
    )
}

/** Human-readable verdict label (used as Markdown heading text). */
internal fun verdictLabel(verdict: ValidationVerdict): String = when (verdict) {
    ValidationVerdict.SUCCESSFUL -> "Erfolgreich"
    ValidationVerdict.SUCCESSFUL_WITH_NOTES -> "Erfolgreich mit Hinweisen"
    ValidationVerdict.NOT_SUCCESSFUL -> "Nicht erfolgreich"
}

/** Section 1's summary line: the catch-all "no result" label is replaced by the actual reason
 *  a validation was unsuccessful; a [ValidationFailureReason.FINDINGS] result still reads distinctly
 *  from a technical non-result. */
private fun kositSectionSummary(outcome: ValidationOutcome): String = when (outcome) {
    is ValidationOutcome.Successful -> "bestanden"
    is ValidationOutcome.Pending -> "ausstehend"
    is ValidationOutcome.NotSuccessful -> when (outcome.reason) {
        ValidationFailureReason.FINDINGS -> "Befunde des Validators"
        ValidationFailureReason.ABORTED -> "Prüfung abgebrochen"
        ValidationFailureReason.VALIDATOR_UNAVAILABLE -> "Prüf-Container nicht verfügbar"
        ValidationFailureReason.REPORT_MISSING -> "kein Prüfbericht vorhanden"
        ValidationFailureReason.REPORT_UNREADABLE -> "Prüfbericht nicht auswertbar"
        ValidationFailureReason.NOT_REQUESTED -> "Prüfung nicht angefordert"
    }
}

private fun modelStatusLabel(report: ModelReport): String = when (report.status) {
    LlmAnalysisStatus.PENDING -> "ausstehend"
    LlmAnalysisStatus.COMPLETED -> "fertig"
    LlmAnalysisStatus.FAILED -> "Fehler"
    // A disabled record means the LLM was not run for this job (not configured or not requested).
    // It is its own state and is never rendered like "no findings".
    LlmAnalysisStatus.DISABLED -> "nicht angefordert / nicht konfiguriert"
}

/** Appends `- **label:** value` when [value] is present and non-blank, else
 *  appends nothing. Used by both the per-model report section and the fallback report, which
 *  render the identical summary/issues/suggestions triple. */
private fun StringBuilder.appendIfPresent(label: String, value: String?) {
    value.blankToNull()?.let { appendLine("- **$label:** $it") }
}

/**
 * Builds the Markdown validation report. `invoiceNumber` is shown in the title.
 * Section 2 (LLM) reads `reportData.models` together with `improvementSpec` — these two are the
 * single source of truth for what section 2 says when a per-model or a stored report actually
 * exists; there is deliberately no separate "LLM disabled" flag next to them for that case, since
 * a flag that can disagree with the actual data is exactly what let section 2 claim "nicht
 * angefordert" while a report existed right below it in section 3 (PO feedback 2026-08-25).
 * `improvementSpec` is the raw Markdown improvement specification produced by the
 * LLM (stored in the S3-compatible object store via `resultRefs.llmReportKey`); it is embedded as
 * its own section so the specification is displayed right next to the KoSIT verdict.
 *
 * [resolveMessageKey] resolves a `1.1 Einzelbefunde` finding's raw [KositMessage.text] into
 * German prose when that text is actually an i18n key (a `BUSINESS`-stage finding from
 * [de.littletrotter.application.compliance.ComplianceFindingMapper] — the domain never renders
 * text itself) — see [resolveFindingText]. Defaults to always-null (no resolution, raw text
 * rendered as-is) so this function stays a pure, dependency-free unit under test; the real
 * resolver — going through [de.littletrotter.ui.i18n.UiTranslations] — is wired in by
 * [de.littletrotter.ui.view.invoice.report.ValidationReportDialog].
 *
 * [llmRequested] covers the remaining case — no models AND no stored report — where the run may
 * simply never have reached the LLM stage (e.g. the Factur-X engine aborted first). `true`/`false`
 * are only ever passed when the caller has actual proof the AI check was/wasn't requested for this
 * job; `null` (the default) means that proof isn't available, and the report says so honestly
 * instead of guessing (incident: invoice DSC001-2025-09-00001, where an aborted run's section 2
 * falsely claimed "nicht angefordert" although `llmOptimize: true` had been requested).
 */
internal fun buildValidationReportMarkdown(
    invoiceNumber: String,
    reportData: ValidationReportData,
    generatedAt: LocalDateTime,
    improvementSpec: String? = null,
    llmRequested: Boolean? = null,
    resolveMessageKey: (key: String, args: List<String>) -> String? = { _, _ -> null },
): String {
    // Section numbering is dynamic, not hardcoded: section 3 (the AI improvement specification)
    // only exists when `improvementSpec` is present, so the final "Versionen" section is "3." when
    // it's absent and "4." when it's present — the numbering never skips (PO feedback: a report
    // jumping 2 → 4 reads as if a section went missing).
    val kiSpecSectionNumber = KI_SPEC_SECTION_NUMBER
    val versionsSectionNumber = if (!improvementSpec.isNullOrBlank()) kiSpecSectionNumber + 1 else kiSpecSectionNumber

    val sb = StringBuilder()
    sb.appendLine("# Validierungsbericht — Rechnung $invoiceNumber")
    sb.appendLine()
    sb.appendLine("**Gesamturteil:** ${verdictLabel(reportData.verdict)}")
    sb.appendLine()

    val matchedRuleIds = resolveMatchedRuleIds(reportData, improvementSpec)
    appendKositSection(sb, reportData, matchedRuleIds, resolveMessageKey)
    appendAiSection(sb, reportData, AiSectionFallback(improvementSpec, llmRequested, kiSpecSectionNumber))
    appendImprovementSpecSection(sb, improvementSpec, matchedRuleIds, kiSpecSectionNumber)
    appendVersionsSection(sb, reportData, generatedAt, versionsSectionNumber)

    return sb.toString()
}

private fun resolveMatchedRuleIds(reportData: ValidationReportData, improvementSpec: String?): Set<String> {
    val findingRuleIds = reportData.findings.mapNotNull { it.code }.toSet()
    return if (!improvementSpec.isNullOrBlank()) {
        matchAiTipsToFindings(improvementSpec, findingRuleIds)
    } else {
        emptySet()
    }
}

/** [appendKositSection]'s per-stage outcome cell — extracted so that method's own `forEach`
 *  doesn't nest a `when` with an `if`/`else` branch inside it (NestedBlockDepth). */
private fun kositStageOutcomeText(stage: KositStage): String = when (stage.outcome) {
    KositStatus.VALID -> "bestanden"
    KositStatus.INVALID -> "nicht bestanden"
    KositStatus.ERROR -> stage.engineError.takeUnless { it.isNullOrBlank() }?.let { "Prüfung fehlgeschlagen: $it" }
        ?: "Prüfung fehlgeschlagen"
    else -> "Prüfung fehlgeschlagen"
}

private fun appendKositSection(
    sb: StringBuilder,
    reportData: ValidationReportData,
    matchedRuleIds: Set<String>,
    resolveMessageKey: (key: String, args: List<String>) -> String?,
) {
    sb.appendLine("## 1. Normprüfung (KoSIT)")
    sb.appendLine(
        "${kositSectionSummary(reportData.outcome)} — ${reportData.kositErrorCount} Fehler, ${reportData.kositWarningCount} Warnungen"
    )
    sb.appendLine()

    val ranStages = reportData.stages.filterNot { it.skipped }
    if (ranStages.isNotEmpty()) {
        sb.appendLine("| Stufe | Ergebnis | Befunde |")
        sb.appendLine("|---|---|---|")
        ranStages.forEach { stage ->
            sb.appendLine("| ${stage.name} | ${kositStageOutcomeText(stage)} | ${stage.findingCount} |")
        }
        sb.appendLine()
    }

    if (reportData.findings.isNotEmpty()) {
        sb.appendLine("### 1.1 Einzelbefunde")
        reportData.findings.forEach { f ->
            val codeText = f.code?.let { "**$it** — " } ?: ""
            val badge = f.code?.takeIf { it in matchedRuleIds }?.let { " ${aiTipBadgeMarkdown(it)}" } ?: ""
            sb.appendLine("- $codeText${resolveFindingText(f, resolveMessageKey)}$badge")
        }
        sb.appendLine()
    }
}

/** LongParameterList — what [appendAiSection] needs when there is no per-model report to
 *  render: whether an improvement spec exists (and, if so, which section number to point at) and
 *  whether an LLM run was even requested. One cohesive "what to say instead" answer, not a bag —
 *  every field is read together in the section 2 "models empty" branch below. */
private class AiSectionFallback(
    val improvementSpec: String?,
    val llmRequested: Boolean?,
    val kiSpecSectionNumber: Int,
)

private fun appendAiSection(
    sb: StringBuilder,
    reportData: ValidationReportData,
    fallback: AiSectionFallback,
) {
    val improvementSpec = fallback.improvementSpec
    val llmRequested = fallback.llmRequested
    val kiSpecSectionNumber = fallback.kiSpecSectionNumber
    sb.appendLine("## 2. KI-Prüfung")
    if (reportData.models.isEmpty()) {
        if (!improvementSpec.isNullOrBlank()) {
            sb.appendLine("KI-Bericht vorhanden — siehe Abschnitt $kiSpecSectionNumber (Verbesserungsspezifikation).")
        } else when (llmRequested) {
            true ->
                sb.appendLine("KI-Prüfung angefordert, aber nicht ausgeführt — der Lauf brach vor der KI-Stufe ab.")
            false ->
                sb.appendLine("KI-Prüfung für diesen Lauf nicht angefordert (kein oMLX-Lauf).")
            null ->
                sb.appendLine("Kein KI-Lauf für diesen Auftrag.")
        }
        sb.appendLine()
    } else {
        reportData.models.forEachIndexed { idx, m ->
            appendModelAnalysis(sb, idx, m)
        }
        sb.appendLine()
    }
}

private fun appendModelAnalysis(sb: StringBuilder, idx: Int, m: ModelReport) {
    sb.appendLine("### 2.${idx + 1} ${m.modelName}")
    sb.appendLine("- **Status:** ${modelStatusLabel(m)}")
    when (m.status) {
        LlmAnalysisStatus.FAILED -> sb.appendLine("- **Ergebnis:** Fehler — Analyse nicht verfügbar")
        LlmAnalysisStatus.PENDING -> sb.appendLine("- **Ergebnis:** Analyse läuft noch")
        LlmAnalysisStatus.DISABLED -> sb.appendLine("- **Ergebnis:** KI-Prüfung nicht angefordert")
        LlmAnalysisStatus.COMPLETED -> {
            appendRatingAndFindings(sb, m)
            if (m.issues.isNullOrBlank() && m.summary.isNullOrBlank()) {
                sb.appendLine("- **Befunde:** keine")
            }
        }
        else -> sb.appendLine("- **Ergebnis:** Modell nicht aktiv")
    }
    m.analyzedAt?.let { sb.appendLine("- **Geprüft am:** ${DateFormatter.format(it)}") }
    sb.appendLine()
}

/** The rating/Zusammenfassung/Befunde/Empfehlungen lines both [appendModelAnalysis] (the full
 *  status-aware report) and [buildFallbackAiMarkdown] (the dialog's synthesized fallback) render
 *  identically for a completed [ModelReport] — kept in one place instead of twice. */
private fun appendRatingAndFindings(sb: StringBuilder, m: ModelReport) {
    m.rating?.let { sb.appendLine("- **Bewertung:** $it/10") }
    sb.appendIfPresent("Zusammenfassung", m.summary)
    sb.appendIfPresent("Befunde", m.issues)
    sb.appendIfPresent("Empfehlungen", m.suggestions)
}

private fun appendImprovementSpecSection(
    sb: StringBuilder,
    improvementSpec: String?,
    matchedRuleIds: Set<String>,
    kiSpecSectionNumber: Int,
) {
    if (!improvementSpec.isNullOrBlank()) {
        sb.appendLine("## $kiSpecSectionNumber. Verbesserungsspezifikation (KI)")
        val specWithAnchors = insertAiTipAnchors(improvementSpec.trim(), matchedRuleIds)
        sb.appendLine(demoteMarkdownHeadings(specWithAnchors, levels = 2))
        sb.appendLine()
    }
}

private fun appendVersionsSection(
    sb: StringBuilder,
    reportData: ValidationReportData,
    generatedAt: LocalDateTime,
    versionsSectionNumber: Int,
) {
    sb.appendLine("## $versionsSectionNumber. Versionen und Prüfzeitpunkt")
    sb.appendLine("- **Bericht erzeugt am:** ${DateFormatter.format(generatedAt)}")
    sb.appendLine("- **Prüfzeitpunkt:** ${formatCheckedAt(reportData.checkedAt)}")
    sb.appendLine("- **Validator:** ${reportData.validatorVersion ?: "unbekannt"}")
    sb.appendLine("- **Generator:** ${reportData.generatorVersion ?: "unbekannt"}")
    sb.appendLine("- **Standard:** ${reportData.formatLabel ?: "unbekannt"}")
    sb.appendLine("- **Profil:** ${reportData.profile ?: "unbekannt"}")
    sb.appendLine()
}

/**
 * Resolves one finding's displayed text for the `1.1 Einzelbefunde` list (and
 * [de.littletrotter.ui.view.invoice.report.KositReportDialog]'s raw-detail rendering, which
 * shares the same [KositMessage]/args shape for `BUSINESS`-stage findings). A compliance finding
 * carries an i18n key in [KositMessage.text] with its `{0}`/`{1}` arguments in [KositMessage.args]
 * (the domain never renders text itself); a KoSIT/veraPDF finding's [KositMessage.text] is
 * already resolved German prose with no key behind it.
 *
 * [resolveMessageKey] is the single point deciding which of those two this is: a non-null result
 * means the text WAS a resolvable key and that resolved sentence is used; `null` — an unknown key,
 * or [resolveMessageKey] not wired to a real bundle at all — falls back to [KositMessage.text]
 * unchanged, which is exactly right for prose that was never a key to begin with.
 */
internal fun resolveFindingText(
    finding: KositMessage,
    resolveMessageKey: (key: String, args: List<String>) -> String?,
): String = resolveMessageKey(finding.text, finding.args ?: emptyList()) ?: finding.text

private val atxHeadingPattern = Regex("^( {0,3})(#{1,6})(\\s.*|)$")
private val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})")

/** CommonMark's deepest ATX heading level (`######`); [demoteMarkdownHeadings] never shifts past it. */
private const val MAX_ATX_HEADING_LEVEL = 6

/** [atxHeadingPattern]'s "heading text" capture group index. */
private const val ATX_HEADING_REST_GROUP = 3

/**
 * Shifts ATX heading levels (`#` .. `######`) in [markdown] down by [levels], capped at
 * CommonMark's maximum of six `#`. Lines inside fenced code blocks (delimited by ``` or ~~~) are
 * left untouched — a leading `#` there is code content, not a heading. Used to embed the
 * LLM-authored `improvementSpec` report as a subsection of the combined Markdown without its own
 * headings competing with the surrounding document's structure.
 */
internal fun demoteMarkdownHeadings(markdown: String, levels: Int): String {
    var inFence = false
    val out = StringBuilder()
    markdown.lineSequence().forEach { line ->
        if (fencePattern.containsMatchIn(line)) {
            inFence = !inFence
            out.appendLine(line)
            return@forEach
        }
        val headingMatch = if (!inFence) atxHeadingPattern.find(line) else null
        if (headingMatch != null) {
            val leading = headingMatch.groupValues[1]
            val hashes = headingMatch.groupValues[2]
            val rest = headingMatch.groupValues[ATX_HEADING_REST_GROUP]
            val newLevel = (hashes.length + levels).coerceAtMost(MAX_ATX_HEADING_LEVEL)
            out.appendLine("$leading${"#".repeat(newLevel)}$rest")
        } else {
            out.appendLine(line)
        }
    }
    return out.toString().trimEnd('\n')
}

/** Formats the ISO-8601 [checkedAt] payload timestamp in German wall time; falls back to
 *  the raw string on a parse failure and to "unbekannt" when absent (older job). */
private fun formatCheckedAt(checkedAt: String?): String {
    if (checkedAt.isNullOrBlank()) return "unbekannt"
    return try {
        DateFormatter.format(OffsetDateTime.parse(checkedAt))
    } catch (_: Exception) {
        checkedAt
    }
}

// ── Finding↔recommendation cross-linking ────────────────────────────────────────────────────

/** Rule-ID shapes the matcher recognizes in AI-authored Markdown: `BR-52`, `BR-CO-25`, `BR-DE-1`
 *  (an EN16931/CIUS business rule, with or without a sub-category), `CII-SR-123` (a CII
 *  structural rule) and `BT-154` (a business term reference). Case-sensitive by design — a
 *  lowercase "br-52" is prose, not a rule-ID citation. */
private val aiTipRulePattern = Regex("BR-[A-Z]*-?\\d+|CII-SR-\\d+|BT-\\d+")

/**
 * Best-effort match between rule IDs the AI markdown actually mentions and the run's own finding
 * rule IDs. Pure and side-effect-free — a caller only ever gets back a subset of
 * [findingRuleIds]; a rule ID never appears in the result unless it is both a real finding of
 * this run AND literally mentioned in [aiMarkdown] ("keine Erwähnung → kein Abzeichen,
 * niemals raten").
 */
internal fun matchAiTipsToFindings(aiMarkdown: String, findingRuleIds: Set<String>): Set<String> {
    if (findingRuleIds.isEmpty() || aiMarkdown.isBlank()) return emptySet()
    // S6524 — .toSet() straight off .map(), no intermediate mutableSetOf().
    val mentioned = aiTipRulePattern.findAll(aiMarkdown).map { it.value }.toSet()
    return findingRuleIds.intersect(mentioned).toSet()
}

/** The stable HTML anchor id a "KI-Tipp" badge for [ruleId] jumps to, e.g. `BR-52` → `ki-BR-52`. */
internal fun aiTipAnchorId(ruleId: String): String = "ki-$ruleId"

/** Raw inline HTML for the "KI-Tipp" badge rendered next to a matched finding in section 1.1 —
 *  passed straight through by [de.littletrotter.ui.view.invoice.report.ReportMarkdown] (`escapeHtml(false)`), same as every other raw-HTML
 *  fragment already embedded in this report. */
internal fun aiTipBadgeMarkdown(ruleId: String): String =
    "<a href=\"#${aiTipAnchorId(ruleId)}\" class=\"ai-tip-badge\" title=\"Zur KI-Passage zu $ruleId springen\">KI-Tipp</a>"

/**
 * Inserts an `<a id="ki-...">` anchor at the start of the line containing the FIRST mention of
 * each rule ID in [ruleIds], so a "KI-Tipp" badge's `#ki-BR-52` link jumps to the paragraph that
 * actually discusses it. Anchors are inserted in descending index order so earlier insertions
 * never shift the offsets the later ones were computed against. A rule ID with no mention in
 * [markdown] (should not happen — [ruleIds] only ever comes from [matchAiTipsToFindings], which
 * only returns IDs it found) is silently skipped rather than throwing.
 */
internal fun insertAiTipAnchors(markdown: String, ruleIds: Set<String>): String {
    if (ruleIds.isEmpty()) return markdown
    data class Hit(val ruleId: String, val index: Int)
    val hits = ruleIds.mapNotNull { id -> markdown.indexOf(id).takeIf { it >= 0 }?.let { Hit(id, it) } }
        .sortedByDescending { it.index }
    if (hits.isEmpty()) return markdown
    val sb = StringBuilder(markdown)
    hits.forEach { hit ->
        val lineStart = sb.lastIndexOf("\n", hit.index).let { if (it < 0) 0 else it + 1 }
        sb.insert(lineStart, "<a id=\"${aiTipAnchorId(hit.ruleId)}\"></a>\n")
    }
    return sb.toString()
}

// ── Advisory AI chip ─────────────────────────────────────────────────────────────────────────

/** The advisory AI chip's content — never a verdict, purely "is there something to read". */
internal sealed class AiAdvisoryChipState {
    /** [rating] from the newest COMPLETED analysis that actually carries a rating;
     *  [recommendationCount] counts analysis rows with a non-blank `issues` or `suggestions`. */
    data class RatingAndCount(val rating: Int, val recommendationCount: Int) : AiAdvisoryChipState()

    /** No analysis row carries a rating, but a full report is stored (`resultRefs.llmReportKey`) —
     *  e.g. the result-consumer path resolved a stored report without ever inserting a per-model
     *  row (the same gap [buildValidationReportMarkdown]'s section 2 already accounts for). */
    data object ReportOnly : AiAdvisoryChipState()

    /** An analysis row exists in state PENDING (created eagerly by
     *  [de.littletrotter.application.invoice.InvoiceLlmAnalysisEventHandler] on invoice
     *  finalization) and neither a rated row nor a stored report exists yet: the deferred oMLX
     *  stage genuinely hasn't resolved it yet, not "nothing was ever requested". */
    data object Pending : AiAdvisoryChipState()

    /** finding-fixes (2026-08-30) — this job's own `resultRefs.llmStatus` is "FAILED" (the
     *  deferred oMLX call itself failed, or the job never reached that stage at all — see
     *  `de.littletrotter.xpdf.JobConsumer.llmStatusForFailedJob`), and neither a rated analysis
     *  row nor a stored report exists to show instead. Without this, a genuinely requested-and-
     *  failed check rendered no chip at all — indistinguishable from "nothing was ever requested"
     *  (see [Absent]), which is exactly the "AI check is not displayed" incident this fixes. */
    data object Failed : AiAdvisoryChipState()

    /** Neither a usable analysis row nor a stored report — no chip is rendered at all. */
    data object Absent : AiAdvisoryChipState()
}

/**
 * Resolves what the advisory AI chip should show for one job. Pure — the
 * caller does the actual `documentStore.exists(...)` check for [hasStoredReport] and passes the
 * result in, so this stays unit-testable without any I/O.
 *
 * finding-fixes (2026-08-30) — [jobLlmStatus] (this job's own `GenerationJob.llmStatus`, see
 * [resolveLlmRequested]'s own KDoc for its five possible values) is a fallback, consulted only
 * once [analyses] (the invoice-wide, finalization-time bookkeeping from
 * [de.littletrotter.application.invoice.InvoiceLlmAnalysisEventHandler] — a mechanism entirely
 * independent of any one print job's own `llmOptimize` request) and [hasStoredReport] both have
 * nothing to show. Before this fallback existed, a print job that genuinely requested and ran (or
 * failed) an LLM check rendered **no chip at all** whenever this invoice had no matching
 * InvoiceLlmAnalysis row — the common case for any invoice finalized before that row existed, or
 * whose rows were already resolved by an earlier job — even while `job.llmStatus` was `"PENDING"`
 * or `"FAILED"` right there on the job itself. That silent gap (a running or failed check the user
 * had no way to see) was the concrete "AI check is not displayed" incident, distinct from — and
 * found alongside — the reference-data `OMLX_LLM_CHECK` switch defaulting to off. `null` (the
 * default) reproduces every pre-existing call site's behavior unchanged.
 */
internal fun resolveAiAdvisoryChipState(
    analyses: List<ModelReport>,
    hasStoredReport: Boolean,
    jobLlmStatus: String? = null,
): AiAdvisoryChipState {
    val recommendationCount = analyses.count { !it.suggestions.isNullOrBlank() || !it.issues.isNullOrBlank() }
    val newestRated = analyses
        .filter { it.status == LlmAnalysisStatus.COMPLETED && it.rating != null }
        .maxByOrNull { it.analyzedAt ?: OffsetDateTime.MIN }
    return when {
        newestRated != null -> AiAdvisoryChipState.RatingAndCount(newestRated.rating!!, recommendationCount)
        hasStoredReport -> AiAdvisoryChipState.ReportOnly
        // Only once neither of the two states above already has something
        // concrete to show: a PENDING row (still) existing at all means the deferred oMLX stage
        // hasn't resolved it, so "läuft…" is the honest read here, not "nothing to show".
        analyses.any { it.status == LlmAnalysisStatus.PENDING } -> AiAdvisoryChipState.Pending
        // finding-fixes — this job's own status, consulted only as a fallback (see this
        // function's own KDoc): the invoice-wide analyses/report above take precedence whenever
        // they have something concrete, exactly as before this fallback was added.
        jobLlmStatus == "PENDING" -> AiAdvisoryChipState.Pending
        jobLlmStatus == "FAILED" -> AiAdvisoryChipState.Failed
        else -> AiAdvisoryChipState.Absent
    }
}

/**
 * Resolves [de.littletrotter.ui.view.invoice.report.ValidationReportDialog]'s
 * `llmRequested` flag (see that dialog's own KDoc on the DSC001-2025-09-00001 incident this
 * exists to fix) from a job's raw `resultRefs.llmStatus`. The two-callback flow
 * means a job that reached the LLM decision point now reports one of five values over its
 * lifetime: "PENDING" (the FIRST callback — deterministic stages done, oMLX not run yet),
 * "COMPLETED"/"FAILED" (either the deferred stage's SECOND callback, or a job whose deterministic
 * stages themselves failed before ever reaching that stage — see kotlin-xpdf's
 * `JobConsumer.llmStatusForFailedJob`), and "DISABLED"/"NOT_REQUESTED" (the LLM was never going
 * to run at all). Every one of the first three is proof `llmOptimize` was requested; only the
 * last two are proof it wasn't. `null` (unknown) stays the honest fallback for a legacy job
 * predating this field entirely, or an absent/unrecognized value.
 */
internal fun resolveLlmRequested(llmStatus: String?): Boolean? = when (llmStatus) {
    "PENDING", "COMPLETED", "FAILED" -> true
    "NOT_REQUESTED", "DISABLED" -> false
    else -> null
}

/**
 * Fallback Markdown for the KI-Volltext dialog ([de.littletrotter.ui.view.invoice.report
 * .AiReportDialog]) when no stored report exists for the job but analysis rows do — synthesizes
 * a readable Markdown from the per-model fields so "read the full AI answer" is still honestly
 * met from whatever data is actually available, rather than the dialog showing nothing.
 */
internal fun buildFallbackAiMarkdown(models: List<ModelReport>): String {
    if (models.isEmpty()) return "_Kein KI-Bericht verfügbar._"
    val sb = StringBuilder()
    models.forEach { m ->
        sb.appendLine("## ${m.modelName}")
        sb.appendLine("- **Status:** ${modelStatusLabel(m)}")
        appendRatingAndFindings(sb, m)
        m.analyzedAt?.let { sb.appendLine("- **Geprüft am:** ${DateFormatter.format(it)}") }
        sb.appendLine()
    }
    return sb.toString().trimEnd('\n')
}

/**
 * Provenance hint for the combined findings dialog (PO requirement 2026-09-03): one muted line
 * naming the whole validation toolchain — XRechnung syntax configuration, KoSIT Validator,
 * PDF/A conformance via veraPDF, and the product's own business validation. [template] is the
 * localized `invoiceDetail.jobs.validationReport.toolchain` pattern; every version filled in
 * comes from [ValidationToolchain] (kotlin-shared), whose numbers are pinned against their real
 * sources by tests — never a hardcoded UI copy that could drift on the next upgrade.
 */
internal fun validationToolchainHint(template: String): String =
    MessageFormat.format(
        template,
        ValidationToolchain.XRECHNUNG_VERSION,
        ValidationToolchain.KOSIT_VALIDATOR_VERSION,
        ValidationToolchain.PDFA_CONFORMANCE_LEVEL,
        ValidationToolchain.VERAPDF_DEFAULT_VERSION,
        ValidationToolchain.BUSINESS_VALIDATION_NAME,
    )
