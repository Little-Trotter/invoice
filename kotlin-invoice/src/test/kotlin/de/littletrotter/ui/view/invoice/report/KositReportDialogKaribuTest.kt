package de.littletrotter.ui.view.invoice.report

import de.littletrotter.testing.jobView
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [KositReportDialog] and the private top-level `buildKositMessageSection`
 * (module `KositReportDialogKt`) had zero coverage: the whole grouped-by-stage vs. flat
 * rendering fork, the outcome-header derivation, and the per-message meta/hint/location
 * rendering in `buildKositMessageSection` were never exercised. [KositReportDialog.open] renders
 * synchronously into the dialog it creates and calls `Dialog.open()`, so every test just invokes
 * `open` and inspects the resulting dialog via Karibu's `_get<Dialog>()`.
 */
class KositReportDialogKaribuTest {

    private val mapper = jacksonObjectMapper()
    private val tr: (String) -> String = { key -> UiTranslations.tr(key) }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun texts(dialog: Dialog): List<String> =
        flatten(dialog).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

    private fun job(status: GenerationJobStatus, kositReport: String?, resultRefs: String? = null): GenerationJobView =
        jobView {
            this.status = status
            this.kositReport = kositReport
            this.resultRefs = resultRefs
        }

    // ── flat rendering (no stage breakdown) ────────────────────────────────────────────────

    @Test
    fun `a valid report with no findings shows the valid chip and the no-issues message`() {
        val payload = KositReportPayload(status = "VALID")

        KositReportDialog(tr).open(job(GenerationJobStatus.COMPLETED, mapper.writeValueAsString(payload)))
        val all = texts(_get<Dialog>())

        assertTrue(all.contains(UiTranslations.tr("invoiceDetail.jobs.kosit.status.valid")))
        assertTrue(all.contains(UiTranslations.tr("invoiceDetail.jobs.kosit.valid.noIssues")))
    }

    @Test
    fun `an invalid report with errors and warnings shows badges and both message sections`() {
        val payload = KositReportPayload(
            status = "INVALID",
            details = listOf(
                ValidationMessage(severity = "ERROR", message = "Fehler 1", ruleId = "BR-CO-25", location = "/Invoice/BR1"),
                ValidationMessage(severity = "ERROR", message = "Fehler 2"),
                ValidationMessage(severity = "WARNING", message = "Warn 1", location = "/Invoice/W1"),
                ValidationMessage(severity = "WARNING", message = "Warn 2", ruleId = "BR-DE-17"),
            ),
        )
        KositReportDialog(tr).open(job(GenerationJobStatus.COMPLETED, mapper.writeValueAsString(payload)))
        val all = texts(_get<Dialog>())

        assertTrue(all.any { it.contains("2 ") && it.contains(UiTranslations.tr("invoiceDetail.jobs.kosit.errors.title")) })
        assertTrue(all.any { it.contains("2 ") && it.contains(UiTranslations.tr("invoiceDetail.jobs.kosit.warnings")) })
        assertTrue(all.any { it.contains("Fehler 1") })
        assertTrue(all.any { it.contains("Fehler 2") })
        assertTrue(all.any { it.contains("Warn 1") })
        assertTrue(all.any { it.contains("Warn 2") })
        assertTrue(all.any { it.contains("/Invoice/BR1") }, "location must be rendered when present")
        assertTrue(all.none { it.contains("/Invoice/W2") }, "no location was given for Warn 2")
    }

    @Test
    fun `a FAILED job with a stored error detail appends it to the reason line`() {
        KositReportDialog(tr).open(
            job(GenerationJobStatus.FAILED, kositReport = null, resultRefs = """{"error":"boom"}"""),
        )
        val all = texts(_get<Dialog>())

        assertTrue(all.any { it.contains("boom") }, "the ABORTED reason line must append the stored detail")
    }

    @Test
    fun `a pending job shows the pending chip and no raw-xml download`() {
        KositReportDialog(tr).open(job(GenerationJobStatus.PENDING, kositReport = null))
        val dialog = _get<Dialog>()

        assertTrue(texts(dialog).contains(UiTranslations.tr("invoiceDetail.jobs.kosit.status.pending")))
        assertTrue(_find<Anchor>().isEmpty(), "no download anchor for a blank report")
    }

    // ── stage-grouped rendering ─────────────────────────────────────────────────────────────

    @Test
    fun `a staged report groups findings per stage and reports skipped, erroring, and clean stages`() {
        val run = ValidationRun(
            overall = KositOutcome.INVALID,
            startedAt = "2026-09-01T00:00:00Z",
            stages = listOf(
                ValidationStageResult(stage = "SKIPPED_STAGE", outcome = KositOutcome.VALID, skipped = true, skipReason = "not needed"),
                ValidationStageResult(stage = "ENGINE_ERROR_STAGE", outcome = KositOutcome.ERROR, engineError = "engine crashed"),
                ValidationStageResult(stage = "CLEAN_VALID_STAGE", outcome = KositOutcome.VALID),
                ValidationStageResult(stage = "CLEAN_INVALID_STAGE", outcome = KositOutcome.INVALID),
                ValidationStageResult(stage = "SILENT_ERROR_STAGE", outcome = KositOutcome.ERROR),
                ValidationStageResult(stage = "BOTH_STAGE", outcome = KositOutcome.INVALID),
                ValidationStageResult(stage = "WARN_ONLY_STAGE", outcome = KositOutcome.INVALID),
                ValidationStageResult(stage = "ERROR_ONLY_STAGE", outcome = KositOutcome.INVALID),
            ),
        )
        val payload = KositReportPayload(
            status = "INVALID",
            details = listOf(
                ValidationMessage(severity = "ERROR", message = "Both error", stage = "BOTH_STAGE"),
                ValidationMessage(severity = "WARNING", message = "Both warning", stage = "BOTH_STAGE"),
                ValidationMessage(severity = "WARNING", message = "Warn only", stage = "WARN_ONLY_STAGE"),
                ValidationMessage(severity = "ERROR", message = "Error only", stage = "ERROR_ONLY_STAGE"),
            ),
            validationRun = run,
        )
        KositReportDialog(tr).open(job(GenerationJobStatus.COMPLETED, mapper.writeValueAsString(payload)))
        val all = texts(_get<Dialog>())

        assertTrue(all.none { it.contains("SKIPPED_STAGE", ignoreCase = true) }, "a skipped stage renders nothing")
        assertTrue(all.any { it.contains("engine crashed") }, "the engine-error stage shows its engineError")
        assertTrue(all.any { it.contains("bestanden") && it.contains("CLEAN_VALID_STAGE", ignoreCase = true) })
        assertTrue(all.any { it.contains("nicht bestanden") && it.contains("CLEAN_INVALID_STAGE", ignoreCase = true) })
        assertTrue(
            all.any { it.contains("SILENT_ERROR_STAGE", ignoreCase = true) && it.contains("fehlgeschlagen") },
            "a stage that is ERROR without an engineError falls into the generic failed label",
        )
        assertTrue(all.any { it.contains("Both error") })
        assertTrue(all.any { it.contains("Both warning") })
        assertTrue(all.any { it.contains("Warn only") })
        assertTrue(all.any { it.contains("Error only") })
    }
}
