package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.invoice.KositMessage
import de.littletrotter.application.invoice.KositReportReader
import de.littletrotter.application.invoice.KositResult
import de.littletrotter.application.invoice.KositStage
import de.littletrotter.application.invoice.KositStatus
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.fixture.anInvoiceLlmAnalysis
import de.littletrotter.fixture.edit
import de.littletrotter.shared.ValidationToolchain
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The outward result is binary (SUCCESSFUL / SUCCESSFUL_WITH_NOTES / NOT_SUCCESSFUL);
 * [resolveVerdict]/[buildReportData] take the already-resolved [ValidationOutcome] along instead
 * of re-deriving it from [KositStatus] themselves. `kosit` is still used for error/warning
 * counts, the version block and the F3 stage table.
 */
class ValidationReportTest {

    private val successful = ValidationOutcome.Successful
    private fun notSuccessful(reason: ValidationFailureReason, detail: String? = null) =
        ValidationOutcome.NotSuccessful(reason, detail)

    private fun kosit(status: KositStatus, errors: Int = 0, warnings: Int = 0): KositResult =
        KositResult(
            status = status,
            errors = (1..errors).map { KositMessage(null, null, "ERROR", "err", null) },
            warnings = (1..warnings).map { KositMessage(null, null, "WARN", "warn", null) },
            isXml = true,
        )

    private fun kositWithVersions(): KositResult =
        KositResult(
            status = KositStatus.VALID,
            errors = emptyList(),
            warnings = emptyList(),
            isXml = false,
            validatorVersion = "1.6.2 / 2026-01-31",
            generatorVersion = "2.25.0",
            profile = "XRechnung",
            checkedAt = "2026-08-11T09:30:00+02:00",
        )

    // F3 — a KositResult carrying the multi-stage breakdown (as parsed from an F3+ payload).
    private fun kositWithStages(stages: List<KositStage>): KositResult =
        KositResult(
            status = KositStatus.INVALID,
            errors = listOf(
                KositMessage(null, null, "ERROR", "err1", null, stages[0].name),
                KositMessage(null, null, "ERROR", "err2", null, stages[1].name),
            ),
            warnings = emptyList(),
            isXml = false,
            stages = stages,
        )

    private fun analysis(
        model: String,
        status: LlmAnalysisStatus = LlmAnalysisStatus.COMPLETED,
        issues: String? = null,
        summary: String? = null,
        rating: Int? = null,
        suggestions: String? = null,
    ): InvoiceLlmAnalysis = anInvoiceLlmAnalysis {
        modelName = model
        this.status = status
        this.issues = issues
        this.summary = summary
        this.rating = rating
        this.suggestions = suggestions
        analyzedAt = OffsetDateTime.of(2026, 8, 11, 12, 0, 0, 0, ZoneOffset.UTC)
    }

    // ── Verdict table (§1.2/H2 §2) ────────────────────────────────────────────

    @Test
    fun `NotSuccessful outcome is NOT_SUCCESSFUL regardless of models`() {
        val noModels = resolveVerdict(notSuccessful(ValidationFailureReason.FINDINGS), emptyList())
        assertEquals(ValidationVerdict.NOT_SUCCESSFUL, noModels)
        val findingModels = resolveVerdict(
            notSuccessful(ValidationFailureReason.FINDINGS),
            listOf(ModelReport.from(analysis("qwen", issues = "Befund"))),
        )
        assertEquals(ValidationVerdict.NOT_SUCCESSFUL, findingModels)
    }

    @Test
    fun `Successful outcome without findings is SUCCESSFUL`() {
        val models = listOf(ModelReport.from(analysis("qwen")), ModelReport.from(analysis("gemma")))
        assertEquals(ValidationVerdict.SUCCESSFUL, resolveVerdict(successful, models))
    }

    @Test
    fun `Successful outcome with at least one finding is SUCCESSFUL_WITH_NOTES`() {
        val models = listOf(
            ModelReport.from(analysis("qwen", issues = "Befund")),
            ModelReport.from(analysis("gemma")),
        )
        assertEquals(ValidationVerdict.SUCCESSFUL_WITH_NOTES, resolveVerdict(successful, models))
    }

    @Test
    fun `every NotSuccessful reason maps to NOT_SUCCESSFUL`() {
        ValidationFailureReason.entries.forEach { reason ->
            assertEquals(ValidationVerdict.NOT_SUCCESSFUL, resolveVerdict(notSuccessful(reason), emptyList()))
        }
    }

    @Test
    fun `Pending outcome is NOT_SUCCESSFUL (a report should not realistically be built while pending)`() {
        assertEquals(ValidationVerdict.NOT_SUCCESSFUL, resolveVerdict(ValidationOutcome.Pending, emptyList()))
    }

    // ── DoD: LLM finding must not flip a successful outcome into NOT_SUCCESSFUL ─

    @Test
    fun `llm finding cannot flip Successful into NOT_SUCCESSFUL`() {
        val models = listOf(ModelReport.from(analysis("qwen", issues = "Schwere Halluzination")))
        val verdict = resolveVerdict(successful, models)
        assertTrue(verdict == ValidationVerdict.SUCCESSFUL_WITH_NOTES || verdict == ValidationVerdict.SUCCESSFUL)
        assertFalse(verdict == ValidationVerdict.NOT_SUCCESSFUL)
    }

    // ── DoD: model in timeout/error is visible as error, not "no findings" ─────

    @Test
    fun `failed model is marked as error and has no finding`() {
        val m = ModelReport.from(analysis("gemma", status = LlmAnalysisStatus.FAILED))
        assertTrue(m.isError)
        assertFalse(m.hasFinding)
        // A failed model must not silently count as "no findings".
        val verdict = resolveVerdict(successful, listOf(m))
        assertEquals(ValidationVerdict.SUCCESSFUL, verdict) // failed != finding, but report shows status
    }

    @Test
    fun `pending model is neither error nor finding`() {
        val m = ModelReport.from(analysis("qwen", status = LlmAnalysisStatus.PENDING))
        assertFalse(m.isError)
        assertFalse(m.hasFinding)
    }

    // ── VALIDATOR_UNAVAILABLE is not successful, an LLM finding
    //    doesn't lift that ─────────────────────────────────────────────────────

    @Test
    fun `VALIDATOR_UNAVAILABLE reason is NOT_SUCCESSFUL regardless of models`() {
        val noModels = resolveVerdict(notSuccessful(ValidationFailureReason.VALIDATOR_UNAVAILABLE), emptyList())
        assertEquals(ValidationVerdict.NOT_SUCCESSFUL, noModels)
        val findingModels = resolveVerdict(
            notSuccessful(ValidationFailureReason.VALIDATOR_UNAVAILABLE),
            listOf(ModelReport.from(analysis("qwen", issues = "Befund"))),
        )
        assertEquals(ValidationVerdict.NOT_SUCCESSFUL, findingModels)
    }

    // ── Mixed findings are counted separately in the Markdown report ──────────

    @Test
    fun `markdown report counts errors and warnings separately`() {
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kosit(KositStatus.INVALID, errors = 3, warnings = 2), emptyList())
        val md = buildValidationReportMarkdown("2026-0048", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("3 Fehler, 2 Warnungen"))
    }

    // ── F2: version block (CO-03/CO-06) ────────────────────────────────────────

    // A report with the full version block shows all four values.
    @Test
    fun `markdown shows all four version values when present`() {
        val data = buildReportData(successful, kositWithVersions(), emptyList())
        val md = buildValidationReportMarkdown("2026-0049", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("1.6.2 / 2026-01-31"))
        assertTrue(md.contains("2.25.0"))
        assertTrue(md.contains("XRechnung"))
        assertFalse(md.contains("unbekannt"))
    }

    // A legacy job (from before this version block existed) has no version data; the report shows "unbekannt" for
    // each, not a missing/blank line.
    @Test
    fun `markdown shows unbekannt for each missing version value`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown("2026-0050", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("**Validator:** unbekannt"))
        assertTrue(md.contains("**Generator:** unbekannt"))
        assertTrue(md.contains("**Standard:** unbekannt"))
        assertTrue(md.contains("**Profil:** unbekannt"))
        assertTrue(md.contains("**Prüfzeitpunkt:** unbekannt"))
    }

    // The "Prüfzeitpunkt" line shows the payload's own checkedAt, not the
    // dialog-open moment (generatedAt, which the report keeps as a separate "Bericht erzeugt
    // am" line), and formats it rather than dumping the raw ISO string.
    @Test
    fun `markdown Pruefzeitpunkt line shows the payload checkedAt, not generatedAt`() {
        val data = buildReportData(successful, kositWithVersions(), emptyList())
        val dialogOpenedAt = LocalDateTime.of(2030, 1, 1, 0, 0) // deliberately far from checkedAt
        val md = buildValidationReportMarkdown("2026-0051", data, dialogOpenedAt)
        val checkedAtLine = md.lineSequence().first { it.startsWith("- **Prüfzeitpunkt:**") }
        assertTrue(checkedAtLine.contains("11.08.2026"), "Expected the formatted checkedAt date, got: $checkedAtLine")
        assertFalse(checkedAtLine.contains("01.01.2030"), "Prüfzeitpunkt must not be the dialog-open time")
    }

    // formatCheckedAt converts to German wall time regardless of the payload's own
    // offset. Both fixtures carry UTC (+00:00), which is neither CET nor CEST, so the assertions
    // only pass if the Europe/Berlin conversion actually ran.
    @Test
    fun `Pruefzeitpunkt line converts a UTC checkedAt to German wall time in CET (winter)`() {
        val kosit = kositWithVersions().copy(checkedAt = "2026-01-15T09:30:00Z")
        val data = buildReportData(successful, kosit, emptyList())
        val md = buildValidationReportMarkdown("2026-0052", data, LocalDateTime.of(2026, 1, 15, 0, 0))
        val checkedAtLine = md.lineSequence().first { it.startsWith("- **Prüfzeitpunkt:**") }
        assertTrue(checkedAtLine.contains("15.01.2026 10:30"), "Expected CET (UTC+1), got: $checkedAtLine")
    }

    @Test
    fun `Pruefzeitpunkt line converts a UTC checkedAt to German wall time in CEST (summer)`() {
        val kosit = kositWithVersions().copy(checkedAt = "2026-08-11T09:30:00Z")
        val data = buildReportData(successful, kosit, emptyList())
        val md = buildValidationReportMarkdown("2026-0053", data, LocalDateTime.of(2026, 8, 11, 0, 0))
        val checkedAtLine = md.lineSequence().first { it.startsWith("- **Prüfzeitpunkt:**") }
        assertTrue(checkedAtLine.contains("11.08.2026 11:30"), "Expected CEST (UTC+2), got: $checkedAtLine")
    }

    // ── DoD: boundary cases (§5) ──────────────────────────────────────────────

    @Test
    fun `no models and no stored report with llmRequested false reads section 2 as not requested`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown(
            "2026-0042", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            llmRequested = false,
        )
        // V3 — "nicht geprüft" must read as such, never as "keine Befunde" (F1, Pkt. 4).
        assertTrue(md.contains("KI-Prüfung für diesen Lauf nicht angefordert"))
        assertTrue(md.contains("Erfolgreich"))
        assertTrue(md.contains("2026-0042"))
    }

    // ── Incident DSC001-2025-09-00001 — truthful section-2 states (llmRequested) ──────────────
    // Four states section 2 can honestly be in when reportData.models is empty and no report is
    // stored: (1) is covered by "stored report" tests below/above, this block covers (2)-(4).

    @Test
    fun `llmRequested null (unknown) never claims not requested`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown(
            "2026-0300", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            llmRequested = null,
        )
        assertTrue(md.contains("Kein KI-Lauf für diesen Auftrag."), "got:\n$md")
        assertFalse(md.contains("nicht angefordert"), "must never assert 'nicht angefordert' without proof, got:\n$md")
    }

    @Test
    fun `llmRequested null is also the default when the parameter is omitted`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown("2026-0301", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("Kein KI-Lauf für diesen Auftrag."), "got:\n$md")
    }

    @Test
    fun `llmRequested true but no models and no report states the run aborted before the AI stage`() {
        // Reproduces the reported incident: DSC001-2025-09-00001, Factur-X engine aborted before
        // the AI stage ever ran, but llmOptimize had genuinely been requested.
        val data = buildReportData(
            notSuccessful(ValidationFailureReason.ABORTED), kosit(KositStatus.UNKNOWN), emptyList(),
        )
        val md = buildValidationReportMarkdown(
            "DSC001-2025-09-00001", data, LocalDateTime.of(2026, 8, 25, 9, 0),
            llmRequested = true,
        )
        assertTrue(
            md.contains("KI-Prüfung angefordert, aber nicht ausgeführt — der Lauf brach vor der KI-Stufe ab."),
            "got:\n$md",
        )
        assertFalse(md.contains("nicht angefordert"), "must not also claim 'nicht angefordert', got:\n$md")
    }

    @Test
    fun `llmRequested false with no models and no report states not requested`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown(
            "2026-0302", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            llmRequested = false,
        )
        assertTrue(md.contains("KI-Prüfung für diesen Lauf nicht angefordert (kein oMLX-Lauf)."))
    }

    @Test
    fun `a stored report takes precedence over llmRequested regardless of its value`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val spec = "## Bewertung\n7/10"
        listOf(true, false, null).forEach { requested ->
            val md = buildValidationReportMarkdown(
                "2026-0303", data, LocalDateTime.of(2026, 8, 11, 12, 0),
                improvementSpec = spec,
                llmRequested = requested,
            )
            assertTrue(md.contains("siehe Abschnitt 3"), "requested=$requested, got:\n$md")
            assertFalse(md.contains("nicht angefordert"), "requested=$requested must not show 'nicht angefordert', got:\n$md")
            assertFalse(md.contains("brach vor der KI-Stufe ab"), "requested=$requested must not show the aborted line, got:\n$md")
        }
    }

    // ── Dynamic section numbering — no jump 2 → 4 when section 3 (AI spec) is absent ──────────

    @Test
    fun `versions section is numbered 3 when the AI spec section is absent`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown("2026-0304", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("## 3. Versionen und Prüfzeitpunkt"), "got:\n$md")
        assertFalse(md.contains("## 4."), "no section 4 must appear when there is no section 3, got:\n$md")
    }

    @Test
    fun `versions section is numbered 4 when the AI spec section is present`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val spec = "## Bewertung\n9/10"
        val md = buildValidationReportMarkdown(
            "2026-0305", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            improvementSpec = spec,
        )
        assertTrue(md.contains("## 3. Verbesserungsspezifikation (KI)"), "got:\n$md")
        assertTrue(md.contains("## 4. Versionen und Prüfzeitpunkt"), "got:\n$md")
        assertFalse(md.contains("## 3. Versionen"), "the versions section must not also claim '3.' here, got:\n$md")
    }

    @Test
    fun `section 2's cross-reference always matches the actual number of the spec section`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val spec = "## Bewertung\n5/10"
        val md = buildValidationReportMarkdown(
            "2026-0306", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            improvementSpec = spec,
        )
        val specHeadingNumber = Regex("""## (\d)\. Verbesserungsspezifikation""").find(md)?.groupValues?.get(1)
        assertEquals("3", specHeadingNumber, "got:\n$md")
        assertTrue(md.contains("siehe Abschnitt $specHeadingNumber"), "got:\n$md")
    }

    // ── the AI report going missing without a trace ─────────────────
    // Reproduces the reported scenario: a job whose result callback resolved llmReportKey/
    // llmStatus into a stored oMLX report, but never created (or updated) an InvoiceLlmAnalysis
    // row for it — so `reportData.models` is empty even though a report exists. Section 2 must
    // never say "nicht angefordert" in that case; it must point at section 3, where the report
    // itself is embedded verbatim.

    @Test
    fun `stored report with empty analyses repository is never reported as not requested`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val spec = "## Bewertung\n8/10\n\nBitte BT-5 pruefen."
        val md = buildValidationReportMarkdown(
            "2026-0055", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            improvementSpec = spec,
        )
        assertFalse(
            md.contains("nicht angefordert (kein oMLX-Lauf)"),
            "a stored AI report must never be reported as 'not requested', got:\n$md",
        )
        assertTrue(md.contains("siehe Abschnitt 3"), "section 2 must point at section 3 instead")
        assertTrue(md.contains("## 3. Verbesserungsspezifikation (KI)"), "the AI report itself must be visible in section 3")
        assertTrue(md.contains("Bitte BT-5 pruefen."), "the AI report text itself must be visible")
    }

    @Test
    fun `V3 disabled model is rendered as not requested, not as no findings`() {
        val data = buildReportData(
            successful,
            kosit(KositStatus.VALID),
            listOf(analysis("oMLX", status = LlmAnalysisStatus.DISABLED)).map(ModelReport::from),
        )
        val md = buildValidationReportMarkdown("2026-0052", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("nicht angefordert / nicht konfiguriert"))
        assertTrue(md.contains("KI-Prüfung nicht angefordert"))
        // The disabled label must not be mistaken for an empty-but-present finding list.
        assertFalse(md.contains("**Befunde:** keine"))
    }

    @Test
    fun `no kosit report yields NOT_SUCCESSFUL with the reportMissing reason`() {
        val data = buildReportData(notSuccessful(ValidationFailureReason.REPORT_MISSING), null, emptyList())
        assertEquals(ValidationVerdict.NOT_SUCCESSFUL, data.verdict)
        val md = buildValidationReportMarkdown("2026-0043", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("Nicht erfolgreich"))
        assertTrue(md.contains("kein Prüfbericht vorhanden"))
    }

    @Test
    fun `unparsable kosit report is UNKNOWN and yields NOT_SUCCESSFUL with the reportUnreadable reason`() {
        // KositReportReader falls back to UNKNOWN/parsed=false for unparsable input
        val unparsable = KositReportReader.read("<not-xml>")
        assertTrue(unparsable.status == KositStatus.UNKNOWN)
        val data = buildReportData(notSuccessful(ValidationFailureReason.REPORT_UNREADABLE), unparsable, emptyList())
        assertEquals(ValidationVerdict.NOT_SUCCESSFUL, data.verdict)
        val md = buildValidationReportMarkdown("2026-0043b", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("Prüfbericht nicht auswertbar"))
    }

    @Test
    fun `report shows per-model name status and findings`() {
        val data = buildReportData(
            successful,
            kosit(KositStatus.VALID, warnings = 2),
            listOf(
                analysis("qwen", issues = "Befund A"),
                analysis("gemma", status = LlmAnalysisStatus.FAILED),
            ).map(ModelReport::from),
        )
        val md = buildValidationReportMarkdown("2026-0044", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("qwen"))
        assertTrue(md.contains("gemma"))
        assertTrue(md.contains("Befund A"))
        assertTrue(md.contains("Fehler"))
        assertTrue(md.contains("2 Warnungen"))
    }

    @Test
    fun `single model section 2 shows its findings`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), listOf(analysis("qwen", issues = "Befund")).map(ModelReport::from))
        val md = buildValidationReportMarkdown("2026-0045", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("qwen"))
        assertTrue(md.contains("Befund"))
    }

    @Test
    fun `improvement spec is embedded as section 3`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val spec = "## Bewertung\n8/10\n\n## Verbesserungsvorschläge\n| Priorität | Beschreibung |"
        val md = buildValidationReportMarkdown(
            "2026-0046", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            improvementSpec = spec,
        )
        assertTrue(md.contains("## 3. Verbesserungsspezifikation (KI)"))
        assertTrue(md.contains("8/10"))
        assertTrue(md.contains("Verbesserungsvorschläge"))
    }

    @Test
    fun `improvement spec absent yields no section 3`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown("2026-0047", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(!md.contains("Verbesserungsspezifikation"))
    }

    // ── improvement spec headings nest under section 3, they never compete with it ────────────

    @Test
    fun `improvement spec headings are demoted two levels so they nest under section 3`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val spec = "# Bewertung\n8/10\n\n## Details"
        val md = buildValidationReportMarkdown(
            "2026-0056", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            improvementSpec = spec,
        )
        assertTrue(md.contains("### Bewertung"), "a top-level '# ' heading must become '### ', got:\n$md")
        assertTrue(md.contains("#### Details"), "a '## ' heading must become '#### ', got:\n$md")
        assertFalse(md.contains("\n# Bewertung"), "the un-demoted heading must not remain")
    }

    @Test
    fun `demoteMarkdownHeadings leaves headings inside code fences untouched`() {
        val md = "```\n# not a heading\n```\n# real heading"
        val result = demoteMarkdownHeadings(md, levels = 2)
        assertTrue(result.contains("# not a heading"), "content inside a code fence must not be demoted, got:\n$result")
        assertTrue(result.contains("### real heading"), "a real heading outside the fence must be demoted, got:\n$result")
    }

    // ── The exact standard/version line in section 4 ─────────────

    @Test
    fun `markdown shows the resolved format label when formatGuideline is present`() {
        val data = buildReportData(
            successful, kosit(KositStatus.VALID), emptyList(),
            formatGuideline = "urn:xeinkauf.de:kosit:xrechnung_3.0",
        )
        val md = buildValidationReportMarkdown("2026-0053", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("- **Standard:** XRechnung 3.0"))
    }

    @Test
    fun `markdown falls back to the payload profile when formatGuideline is absent`() {
        val data = buildReportData(successful, kositWithVersions(), emptyList(), formatGuideline = null)
        val md = buildValidationReportMarkdown("2026-0054", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("- **Standard:** XRechnung"))
    }

    // ── Stage breakdown in the report ──────────────────────────────────────────

    // A report with six stages shows one table row per stage; skipped stages carry
    // their reason, so "nicht geprüft" never reads like "bestanden".
    @Test
    fun `F3-T13 markdown shows one row per stage, skipped ones with reason`() {
        val stages = listOf(
            KositStage("SYNTAX", KositStatus.VALID, 0),
            KositStage("EN16931", KositStatus.INVALID, 2),
            KositStage("CIUS", KositStatus.VALID, 0),
            KositStage("KOSIT", KositStatus.VALID, 0),
            KositStage("PDFA", KositStatus.ERROR, 0, skipped = true, skipReason = "skipValidation"),
            KositStage("BUSINESS", KositStatus.ERROR, 0, skipped = true, skipReason = "keine Fachregeln aktiv"),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kositWithStages(stages), emptyList())
        val md = buildValidationReportMarkdown("2026-0100", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertTrue(md.contains("| Stufe | Ergebnis | Befunde |"), "stage table header missing")
        // Skipped stages omitted entirely; only executed stages render.
        assertFalse(md.contains("| PDFA |"), "skipped stage must be omitted entirely:\n$md")
        assertFalse(md.contains("| BUSINESS |"), "skipped stage must be omitted entirely:\n$md")
        assertFalse(md.contains("übersprungen"), "no skip wording anywhere:\n$md")
        assertTrue(md.contains("| EN16931 | nicht bestanden | 2 |"))
    }

    // veraPDF engine-error visibility — an ERROR stage's markdown line names the reason.
    @Test
    fun `markdown appends the engineError reason to an ERROR stage line`() {
        val stages = listOf(
            KositStage(
                "VERAPDF", KositStatus.ERROR, 0,
                engineError = "veraPDF-CLI nicht ausführbar (/opt/verapdf/verapdf)",
            ),
            KositStage("PDFA", KositStatus.VALID, 0),
        )
        val data = buildReportData(
            notSuccessful(ValidationFailureReason.VALIDATOR_UNAVAILABLE, "veraPDF-CLI nicht ausführbar (/opt/verapdf/verapdf)"),
            kositWithStages(stages), emptyList(),
        )
        val md = buildValidationReportMarkdown("2026-0102", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertTrue(
            md.contains("| VERAPDF | Prüfung fehlgeschlagen: veraPDF-CLI nicht ausführbar (/opt/verapdf/verapdf) |"),
            "expected the engineError reason in the stage line, got:\n$md",
        )
    }

    @Test
    fun `markdown ERROR stage without engineError keeps the plain label`() {
        val stages = listOf(
            KositStage("VERAPDF", KositStatus.ERROR, 0),
            KositStage("PDFA", KositStatus.VALID, 0),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.VALIDATOR_UNAVAILABLE), kositWithStages(stages), emptyList())
        val md = buildValidationReportMarkdown("2026-0103", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertTrue(md.contains("| VERAPDF | Prüfung fehlgeschlagen |"))
    }

    // A report built from an old payload without stages renders exactly as before:
    // no stage table, not even an empty one.
    @Test
    fun `F3-T14 markdown without stages renders legacy layout, no stage table`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown("2026-0101", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertFalse(md.contains("| Stufe |"), "no stage table may appear for a pre-F3 payload")
        assertTrue(md.contains("## 1. Normprüfung (KoSIT)"))
        assertTrue(md.contains("bestanden — 0 Fehler, 0 Warnungen"))
    }

    // ── matchAiTipsToFindings (pure matcher) ─────────────────────────────────────────────────

    @Test
    fun `matchAiTipsToFindings finds a mentioned BR rule that is also a real finding`() {
        val matched = matchAiTipsToFindings("Bitte BR-52 im Feld pruefen.", setOf("BR-52", "BR-53"))
        assertEquals(setOf("BR-52"), matched)
    }

    @Test
    fun `matchAiTipsToFindings never fabricates a match for a rule not mentioned`() {
        val matched = matchAiTipsToFindings("Alles sieht gut aus, keine Regel-IDs hier.", setOf("BR-52"))
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `matchAiTipsToFindings ignores a mention that is not among the run's own findings`() {
        // BR-CO-25 is mentioned in the AI text but never occurred as an actual finding this run —
        // must never be fabricated into a badge.
        val matched = matchAiTipsToFindings("BR-CO-25 koennte relevant sein.", setOf("BT-1"))
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `matchAiTipsToFindings recognizes BT and CII-SR patterns distinctly from BR`() {
        val text = "Pruefe BT-154 (Empfaenger) und CII-SR-042 sowie BR-DE-1."
        val matched = matchAiTipsToFindings(text, setOf("BT-154", "CII-SR-042", "BR-DE-1"))
        assertEquals(setOf("BT-154", "CII-SR-042", "BR-DE-1"), matched)
    }

    @Test
    fun `matchAiTipsToFindings is case-sensitive, a lowercase mention does not match`() {
        val matched = matchAiTipsToFindings("br-52 ist wahrscheinlich betroffen.", setOf("BR-52"))
        assertTrue(matched.isEmpty(), "a lowercase 'br-52' must not count as a rule-ID citation")
    }

    @Test
    fun `matchAiTipsToFindings with no findings at all never matches anything`() {
        val matched = matchAiTipsToFindings("BR-52 BT-1 CII-SR-1", emptySet())
        assertTrue(matched.isEmpty())
    }

    // ── insertAiTipAnchors + badge/anchor round-trip in the combined report ──────────────────────

    @Test
    fun `insertAiTipAnchors inserts an anchor right before the mentioning line`() {
        val md = "Intro.\n\nBitte BR-52 korrigieren.\n\nWeiterer Text."
        val result = insertAiTipAnchors(md, setOf("BR-52"))
        assertTrue(result.contains("<a id=\"ki-BR-52\"></a>\nBitte BR-52 korrigieren."), "got:\n$result")
    }

    @Test
    fun `insertAiTipAnchors with an empty rule-id set returns the markdown unchanged`() {
        val md = "Nothing to anchor here."
        assertEquals(md, insertAiTipAnchors(md, emptySet()))
    }

    @Test
    fun `combined report gives a matched finding a KI-Tipp badge linking into section 3`() {
        val kositWithBr52 = kosit(KositStatus.INVALID, errors = 0, warnings = 0).copy(
            errors = listOf(KositMessage(null, "BR-52", "ERROR", "Rechnungsnummer fehlt", null)),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kositWithBr52, emptyList())
        val spec = "## Befund\nDie Regel BR-52 verlangt eine Rechnungsnummer."
        val md = buildValidationReportMarkdown(
            "2026-0200", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            improvementSpec = spec,
        )
        assertTrue(md.contains("### 1.1 Einzelbefunde"), "got:\n$md")
        assertTrue(md.contains("BR-52"))
        assertTrue(md.contains("href=\"#ki-BR-52\""), "the finding must carry a KI-Tipp badge, got:\n$md")
        assertTrue(md.contains("<a id=\"ki-BR-52\"></a>"), "section 3 must carry the matching anchor, got:\n$md")
    }

    @Test
    fun `combined report never fabricates a badge when the AI markdown mentions nothing`() {
        val kositWithBr52 = kosit(KositStatus.INVALID).copy(
            errors = listOf(KositMessage(null, "BR-52", "ERROR", "Rechnungsnummer fehlt", null)),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kositWithBr52, emptyList())
        val spec = "Alles unauffaellig, keine konkrete Regel genannt."
        val md = buildValidationReportMarkdown(
            "2026-0201", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            improvementSpec = spec,
        )
        assertTrue(md.contains("BR-52"))
        assertFalse(md.contains("ai-tip-badge"), "no mention in the AI text means no badge, got:\n$md")
    }

    // ── resolveAiAdvisoryChipState (chip-label decision) ─────────────────────────────────────

    @Test
    fun `chip state is RatingAndCount from the newest COMPLETED rated analysis`() {
        val older = analysis("qwen", rating = 6, issues = "A").edit { analyzedAt = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC) }
        val newer = analysis("gemma", rating = 8, suggestions = "B").edit { analyzedAt = OffsetDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneOffset.UTC) }
        val state = resolveAiAdvisoryChipState(listOf(older, newer).map(ModelReport::from), hasStoredReport = false)
        assertEquals(AiAdvisoryChipState.RatingAndCount(8, 2), state)
    }

    @Test
    fun `chip state counts recommendations from rows with non-blank issues or suggestions`() {
        val a = analysis("qwen", rating = 7, issues = "x")
        val b = analysis("gemma", rating = 7, suggestions = "y")
        val c = analysis("phi", rating = 7) // neither issues nor suggestions
        val state = resolveAiAdvisoryChipState(listOf(a, b, c).map(ModelReport::from), hasStoredReport = false)
        assertEquals(AiAdvisoryChipState.RatingAndCount(7, 2), state)
    }

    @Test
    fun `chip state is ReportOnly without a rated analysis but with a stored report`() {
        val state = resolveAiAdvisoryChipState(emptyList(), hasStoredReport = true)
        assertEquals(AiAdvisoryChipState.ReportOnly, state)
    }

    @Test
    fun `chip state is ReportOnly when only unrated or non-completed analyses exist`() {
        val pending = analysis("qwen", status = LlmAnalysisStatus.PENDING)
        val state = resolveAiAdvisoryChipState(listOf(pending).map(ModelReport::from), hasStoredReport = true)
        assertEquals(AiAdvisoryChipState.ReportOnly, state)
    }

    @Test
    fun `chip state is Absent with neither analyses nor a stored report`() {
        val state = resolveAiAdvisoryChipState(emptyList(), hasStoredReport = false)
        assertEquals(AiAdvisoryChipState.Absent, state)
    }

    // ── A PENDING analysis (the deferred oMLX stage hasn't resolved yet)
    // renders as its own chip state, distinct from Absent. ───────────────────────────────────

    @Test
    fun `chip state is Pending when only a PENDING analysis exists and no report is stored`() {
        val pending = analysis("qwen", status = LlmAnalysisStatus.PENDING)
        val state = resolveAiAdvisoryChipState(listOf(pending).map(ModelReport::from), hasStoredReport = false)
        assertEquals(AiAdvisoryChipState.Pending, state)
    }

    @Test
    fun `chip state stays ReportOnly over Pending when a report is already stored`() {
        // Same fixture as "chip state is ReportOnly when only unrated or non-completed analyses
        // exist" above — this only makes the precedence explicit: a stored report always outranks
        // a still-PENDING row, since there is already something concrete to show.
        val pending = analysis("qwen", status = LlmAnalysisStatus.PENDING)
        val state = resolveAiAdvisoryChipState(listOf(pending).map(ModelReport::from), hasStoredReport = true)
        assertEquals(AiAdvisoryChipState.ReportOnly, state)
    }

    @Test
    fun `chip state stays RatingAndCount over Pending when another model already completed`() {
        val pending = analysis("qwen", status = LlmAnalysisStatus.PENDING)
        val rated = analysis("gemma", rating = 8)
        val state = resolveAiAdvisoryChipState(listOf(pending, rated).map(ModelReport::from), hasStoredReport = false)
        assertEquals(AiAdvisoryChipState.RatingAndCount(8, 0), state)
    }

    // ── jobLlmStatus fallback: this job's own GenerationJob.llmStatus
    // is consulted once the invoice-wide analyses/report above have nothing concrete to show. Fixes
    // the "AI check is not displayed" incident for any invoice with no matching InvoiceLlmAnalysis
    // row (the common case: that row is only ever created by InvoiceLlmAnalysisEventHandler at
    // invoice finalization, a mechanism independent of any one print job's own request). ─────────

    @Test
    fun `chip state defaults to Absent when jobLlmStatus is omitted, unchanged from before this fallback`() {
        val state = resolveAiAdvisoryChipState(emptyList(), hasStoredReport = false)
        assertEquals(AiAdvisoryChipState.Absent, state)
    }

    @Test
    fun `chip state falls back to Pending from jobLlmStatus when no analysis row exists yet`() {
        val state = resolveAiAdvisoryChipState(emptyList(), hasStoredReport = false, jobLlmStatus = "PENDING")
        assertEquals(AiAdvisoryChipState.Pending, state)
    }

    @Test
    fun `chip state falls back to Failed from jobLlmStatus when no analysis row exists`() {
        val state = resolveAiAdvisoryChipState(emptyList(), hasStoredReport = false, jobLlmStatus = "FAILED")
        assertEquals(AiAdvisoryChipState.Failed, state)
    }

    @Test
    fun `chip state stays Absent for jobLlmStatus values that never requested or ran a check`() {
        assertEquals(
            AiAdvisoryChipState.Absent,
            resolveAiAdvisoryChipState(emptyList(), hasStoredReport = false, jobLlmStatus = "NOT_REQUESTED"),
        )
        assertEquals(
            AiAdvisoryChipState.Absent,
            resolveAiAdvisoryChipState(emptyList(), hasStoredReport = false, jobLlmStatus = "DISABLED"),
        )
        assertEquals(
            AiAdvisoryChipState.Absent,
            resolveAiAdvisoryChipState(emptyList(), hasStoredReport = false, jobLlmStatus = null),
        )
    }

    @Test
    fun `chip state prefers a stored report over the jobLlmStatus Failed fallback`() {
        val state = resolveAiAdvisoryChipState(emptyList(), hasStoredReport = true, jobLlmStatus = "FAILED")
        assertEquals(AiAdvisoryChipState.ReportOnly, state)
    }

    @Test
    fun `chip state prefers a rated analysis over the jobLlmStatus Failed fallback`() {
        val rated = analysis("gemma", rating = 8)
        val state = resolveAiAdvisoryChipState(listOf(rated).map(ModelReport::from), hasStoredReport = false, jobLlmStatus = "FAILED")
        assertEquals(AiAdvisoryChipState.RatingAndCount(8, 0), state)
    }

    // ── resolveLlmRequested (ValidationReportDialog's `llmRequested` wiring) ─────────────────

    @Test
    fun `resolveLlmRequested is true for PENDING, COMPLETED and FAILED`() {
        assertEquals(true, resolveLlmRequested("PENDING"))
        assertEquals(true, resolveLlmRequested("COMPLETED"))
        assertEquals(true, resolveLlmRequested("FAILED"))
    }

    @Test
    fun `resolveLlmRequested is false for NOT_REQUESTED and DISABLED`() {
        assertEquals(false, resolveLlmRequested("NOT_REQUESTED"))
        assertEquals(false, resolveLlmRequested("DISABLED"))
    }

    @Test
    fun `resolveLlmRequested is null (unknown) for an absent or unrecognized value`() {
        assertEquals(null, resolveLlmRequested(null))
        assertEquals(null, resolveLlmRequested("SOMETHING_ELSE"))
    }

    // ── buildFallbackAiMarkdown ────────────────────────────────────────────────────────────────

    @Test
    fun `buildFallbackAiMarkdown renders a readable summary from model rows`() {
        val md = buildFallbackAiMarkdown(listOf(ModelReport.from(analysis("qwen", rating = 8, issues = "Befund A"))))
        assertTrue(md.contains("qwen"))
        assertTrue(md.contains("8/10"))
        assertTrue(md.contains("Befund A"))
    }

    @Test
    fun `buildFallbackAiMarkdown with no models still returns a non-blank placeholder`() {
        val md = buildFallbackAiMarkdown(emptyList())
        assertTrue(md.isNotBlank())
    }

    // ── Fix for the raw-i18n-key defect: "1.1 Einzelbefunde" must render resolved German prose
    // for a compliance finding's messageKey, not the raw key, and must fill its {0}/{1} args ────

    @Test
    fun `resolver hit renders the resolved German text with args filled, not the raw key`() {
        val kositWithKeyFinding = kosit(KositStatus.INVALID).copy(
            errors = listOf(
                KositMessage(
                    null, "TAX.CATEGORY_MISMATCH", "ERROR", "compliance.tax.categoryMismatch", null,
                    args = listOf("S", "AE"),
                ),
            ),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kositWithKeyFinding, emptyList())
        val md = buildValidationReportMarkdown(
            "2026-0400", data, LocalDateTime.of(2026, 8, 25, 12, 0),
            resolveMessageKey = { key, args ->
                if (key == "compliance.tax.categoryMismatch") {
                    "Erwartet wird Steuerkategorie ${args[0]}, mindestens eine Position trägt ${args[1]}."
                } else null
            },
        )
        assertTrue(
            md.contains("Erwartet wird Steuerkategorie S, mindestens eine Position trägt AE."),
            "got:\n$md",
        )
        assertFalse(md.contains("compliance.tax.categoryMismatch"), "the raw key must not appear once resolved, got:\n$md")
        assertTrue(md.contains("**TAX.CATEGORY_MISMATCH**"), "the rule id prefix must still be shown, got:\n$md")
    }

    @Test
    fun `resolver miss falls back to the raw key text unchanged`() {
        val kositWithKeyFinding = kosit(KositStatus.INVALID).copy(
            errors = listOf(KositMessage(null, "SOME.CODE", "ERROR", "some.unknown.key", null)),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kositWithKeyFinding, emptyList())
        val md = buildValidationReportMarkdown(
            "2026-0401", data, LocalDateTime.of(2026, 8, 25, 12, 0),
            resolveMessageKey = { _, _ -> null },
        )
        assertTrue(md.contains("some.unknown.key"), "got:\n$md")
    }

    @Test
    fun `default resolver (parameter omitted) renders the raw text unchanged`() {
        // KoSIT/veraPDF findings carry real prose, not keys — a report built without a resolver
        // at all (the pure function's own default) must render them exactly as before.
        val kositWithProse = kosit(KositStatus.INVALID).copy(
            errors = listOf(KositMessage(null, "BR-52", "ERROR", "Rechnungsnummer fehlt", null)),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kositWithProse, emptyList())
        val md = buildValidationReportMarkdown("2026-0402", data, LocalDateTime.of(2026, 8, 25, 12, 0))
        assertTrue(md.contains("Rechnungsnummer fehlt"), "got:\n$md")
    }

    @Test
    fun `a key resolving with missing args still renders without throwing`() {
        val kositWithKeyFinding = kosit(KositStatus.INVALID).copy(
            errors = listOf(KositMessage(null, "TAX.CATEGORY_MISMATCH", "ERROR", "compliance.tax.categoryMismatch", null)),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kositWithKeyFinding, emptyList())
        // resolveMessageKey itself decides what to do with an empty args list here — this pins
        // the least-surprising behavior: it may resolve with blank placeholders, or decline (null)
        // and let the raw key show; either way the report must render, not throw.
        val md = buildValidationReportMarkdown(
            "2026-0403", data, LocalDateTime.of(2026, 8, 25, 12, 0),
            resolveMessageKey = { key, args -> if (args.isEmpty()) null else key },
        )
        assertTrue(md.contains("compliance.tax.categoryMismatch"), "got:\n$md")
    }

    @Test
    fun `resolveFindingText resolves via the callback and falls back to raw text on null`() {
        val resolved = KositMessage(null, "X", "ERROR", "some.key", null, args = listOf("a"))
        assertEquals("resolved!", resolveFindingText(resolved) { key, args -> "resolved!".takeIf { key == "some.key" && args == listOf("a") } })

        val unresolved = KositMessage(null, "X", "ERROR", "prose text", null)
        assertEquals("prose text", resolveFindingText(unresolved) { _, _ -> null })
    }

    // ── UI-5 slice 3a — remaining branch gaps ───────────────────────────────────────────────────

    @Test
    fun `hasFinding is false for a COMPLETED model with blank issues`() {
        val m = ModelReport.from(analysis("qwen", status = LlmAnalysisStatus.COMPLETED, issues = "  "))
        assertFalse(m.hasFinding, "blank issues must not count as a finding")
    }

    @Test
    fun `kositSectionSummary reads ABORTED and NOT_REQUESTED distinctly in the markdown`() {
        val aborted = buildReportData(notSuccessful(ValidationFailureReason.ABORTED), null, emptyList())
        val mdAborted = buildValidationReportMarkdown("2026-0500", aborted, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(mdAborted.contains("Prüfung abgebrochen"), "got:\n$mdAborted")

        val notRequested = buildReportData(notSuccessful(ValidationFailureReason.NOT_REQUESTED), null, emptyList())
        val mdNotRequested = buildValidationReportMarkdown("2026-0501", notRequested, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(mdNotRequested.contains("Prüfung nicht angefordert"), "got:\n$mdNotRequested")
    }

    @Test
    fun `a stage outcome that is neither VALID INVALID nor ERROR renders the generic failure label`() {
        val stages = listOf(
            KositStage("SYNTAX", KositStatus.UNKNOWN, 0),
            KositStage("PDFA", KositStatus.VALID, 0),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.FINDINGS), kositWithStages(stages), emptyList())
        val md = buildValidationReportMarkdown("2026-0502", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertTrue(md.contains("| SYNTAX | Prüfung fehlgeschlagen |"), "an UNKNOWN stage outcome must fall through the else arm, got:\n$md")
    }

    @Test
    fun `a PENDING model renders the still-running result line`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), listOf(analysis("qwen", status = LlmAnalysisStatus.PENDING)).map(ModelReport::from))
        val md = buildValidationReportMarkdown("2026-0503", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertTrue(md.contains("Analyse läuft noch"), "got:\n$md")
    }

    @Test
    fun `a COMPLETED model with neither issues nor summary renders keine`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), listOf(analysis("qwen", issues = null, summary = null)).map(ModelReport::from))
        val md = buildValidationReportMarkdown("2026-0504", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertTrue(md.contains("- **Befunde:** keine"), "got:\n$md")
    }

    @Test
    fun `formatCheckedAt falls back to the raw string on a genuinely unparsable non-blank value`() {
        val data = buildReportData(successful, kositWithVersions().copy(checkedAt = "not-a-timestamp"), emptyList())
        val md = buildValidationReportMarkdown("2026-0505", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        val checkedAtLine = md.lineSequence().first { it.startsWith("- **Prüfzeitpunkt:**") }

        assertTrue(checkedAtLine.contains("not-a-timestamp"), "an unparsable value must be shown raw, not silently dropped, got: $checkedAtLine")
    }

    @Test
    fun `matchAiTipsToFindings returns nothing for a blank AI markdown even with real findings`() {
        assertTrue(matchAiTipsToFindings("   ", setOf("BR-52")).isEmpty())
    }

    @Test
    fun `insertAiTipAnchors is a no-op when none of the rule ids actually appear in the markdown`() {
        val markdown = "Alles in Ordnung, keine Regel erwähnt."
        assertEquals(markdown, insertAiTipAnchors(markdown, setOf("BR-52")))
    }

    @Test
    fun `chip recommendation count includes a row with only suggestions and one with only issues`() {
        val onlySuggestions = analysis("qwen", status = LlmAnalysisStatus.COMPLETED, suggestions = "Tipp", issues = null)
        val onlyIssues = analysis("gemma", status = LlmAnalysisStatus.COMPLETED, issues = "Problem", suggestions = null)
        val state = resolveAiAdvisoryChipState(listOf(onlySuggestions, onlyIssues).map(ModelReport::from), hasStoredReport = false)

        assertTrue(state is AiAdvisoryChipState.ReportOnly || state is AiAdvisoryChipState.Absent || state is AiAdvisoryChipState.RatingAndCount)
        // Neither row carries a rating, so this must NOT be RatingAndCount; both rows count as
        // recommendations once a rated row exists to surface the count through.
        val withRating = analysis("phi", status = LlmAnalysisStatus.COMPLETED, rating = 7)
        val rated = resolveAiAdvisoryChipState(listOf(onlySuggestions, onlyIssues, withRating).map(ModelReport::from), hasStoredReport = false)
        assertEquals(AiAdvisoryChipState.RatingAndCount(7, 2), rated)
    }

    @Test
    fun `a COMPLETED analysis without a rating never becomes the newest rated row`() {
        val unrated = analysis("qwen", status = LlmAnalysisStatus.COMPLETED, rating = null, issues = "x")
        val state = resolveAiAdvisoryChipState(listOf(unrated).map(ModelReport::from), hasStoredReport = false)

        assertEquals(AiAdvisoryChipState.Absent, state, "an unrated COMPLETED row must not count as newestRated")
    }

    @Test
    fun `buildFallbackAiMarkdown omits the Bewertung line for a model without a rating`() {
        val md = buildFallbackAiMarkdown(listOf(analysis("qwen", rating = null, summary = "Alles gut").let { ModelReport.from(it) }))

        assertFalse(md.contains("Bewertung"), "got:\n$md")
        assertTrue(md.contains("Alles gut"))
    }

    // ── Task 2 (PO 2026-09-03): toolchain provenance hint ───────────────────────────────────

    @Test
    fun `validationToolchainHint fills the template with the shared toolchain constants`() {
        val hint = validationToolchainHint("{0}|{1}|{2}|{3}|{4}")

        assertEquals(
            listOf(
                ValidationToolchain.XRECHNUNG_VERSION,
                ValidationToolchain.KOSIT_VALIDATOR_VERSION,
                ValidationToolchain.PDFA_CONFORMANCE_LEVEL,
                ValidationToolchain.VERAPDF_DEFAULT_VERSION,
                ValidationToolchain.BUSINESS_VALIDATION_NAME,
            ).joinToString("|"),
            hint,
        )
    }

    // ── Remaining branch gaps ──────────────────────────────────────────────────────────────────

    @Test
    fun `a Pending outcome renders ausstehend in the report's own summary line, not just in resolveVerdict`() {
        val data = buildReportData(ValidationOutcome.Pending, null, emptyList())
        assertEquals(ValidationVerdict.NOT_SUCCESSFUL, data.verdict)
        val md = buildValidationReportMarkdown("2026-0600", data, LocalDateTime.of(2026, 8, 11, 12, 0))
        assertTrue(md.contains("ausstehend — 0 Fehler, 0 Warnungen"), "got:\n$md")
    }

    @Test
    fun `a blank (non-null) improvementSpec is treated exactly like an absent one, section 3 stays absent`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), emptyList())
        val md = buildValidationReportMarkdown(
            "2026-0601", data, LocalDateTime.of(2026, 8, 11, 12, 0),
            improvementSpec = "   \n  ",
        )
        assertFalse(md.contains("Verbesserungsspezifikation"), "got:\n$md")
        assertTrue(md.contains("## 3. Versionen und Prüfzeitpunkt"), "numbering must not have shifted, got:\n$md")
        assertTrue(md.contains("Kein KI-Lauf für diesen Auftrag."), "got:\n$md")
    }

    @Test
    fun `ValidationReportData built without the findings parameter defaults to an empty list`() {
        val data = ValidationReportData(
            verdict = ValidationVerdict.SUCCESSFUL, outcome = successful,
            kositErrorCount = 0, kositWarningCount = 0, models = emptyList(),
        )
        assertTrue(data.findings.isEmpty())
    }

    @Test
    fun `an ERROR stage with a blank (non-null) engineError falls back to the plain failure label`() {
        val stages = listOf(
            KositStage("VERAPDF", KositStatus.ERROR, 0, engineError = "   "),
            KositStage("PDFA", KositStatus.VALID, 0),
        )
        val data = buildReportData(notSuccessful(ValidationFailureReason.VALIDATOR_UNAVAILABLE), kositWithStages(stages), emptyList())
        val md = buildValidationReportMarkdown("2026-0602", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertTrue(md.contains("| VERAPDF | Prüfung fehlgeschlagen |"), "a blank engineError must read as absent, got:\n$md")
    }

    @Test
    fun `a COMPLETED model with a summary but no issues shows the summary, not the keine line`() {
        val data = buildReportData(successful, kosit(KositStatus.VALID), listOf(analysis("qwen", issues = null, summary = "Alles plausibel")).map(ModelReport::from))
        val md = buildValidationReportMarkdown("2026-0603", data, LocalDateTime.of(2026, 8, 11, 12, 0))

        assertTrue(md.contains("- **Zusammenfassung:** Alles plausibel"), "got:\n$md")
        assertFalse(md.contains("- **Befunde:** keine"), "a present summary must not also render the empty-findings line, got:\n$md")
    }

    @Test
    fun `validationToolchainHint on the German bundle template names all four tools with their versions`() {
        val template = UiTranslations.tr("invoiceDetail.jobs.validationReport.toolchain")
        val hint = validationToolchainHint(template)

        listOf("XRechnung", "KoSIT Validator", "veraPDF", "Little Trotter").forEach { name ->
            assertTrue(hint.contains(name), "hint must name $name, got: $hint")
        }
        assertTrue(hint.contains("XRechnung ${ValidationToolchain.XRECHNUNG_VERSION}"), "got: $hint")
        assertTrue(hint.contains("KoSIT Validator ${ValidationToolchain.KOSIT_VALIDATOR_VERSION}"), "got: $hint")
        assertTrue(hint.contains("veraPDF ${ValidationToolchain.VERAPDF_DEFAULT_VERSION}"), "got: $hint")
        assertTrue(hint.contains(ValidationToolchain.PDFA_CONFORMANCE_LEVEL), "got: $hint")
        assertFalse(hint.contains("{"), "no unfilled placeholder may survive, got: $hint")
    }
}
