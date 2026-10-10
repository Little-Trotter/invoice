package de.littletrotter.ui.view.invoice.report

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.invoice.KositReportReader
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.InvoiceUnitLabels
import de.littletrotter.ui.view.invoice.buildReportData
import de.littletrotter.ui.view.invoice.buildValidationReportMarkdown
import de.littletrotter.ui.view.invoice.parseGenerationJobResultRef
import de.littletrotter.ui.view.invoice.resolveLlmRequested
import de.littletrotter.ui.view.invoice.resolveValidationOutcome
import de.littletrotter.ui.view.invoice.validationToolchainHint
import org.jboss.logging.Logger
import java.io.ByteArrayInputStream
import de.littletrotter.ui.component.ActionIcons

private const val TITLE_ATTR = "title"

/**
 * Z3 — extracted from `InvoiceExportPanel.openValidationReportDialog`: combines the KoSIT result
 * with the per-model LLM analyses into one Markdown report, rendered in the dialog and offered
 * as the dialog's ONLY download (the additional KoSIT-XML download here read as a
 * confusing duplicate; the raw XML stays downloadable inside the nested KoSIT-Details dialog).
 *
 * UX consolidation — this is now the single report entry point in the
 * grid (the former separate "Prüfbericht"/"KI-Prüfung" columns are gone). Everything those used
 * to show is reachable from here: the raw KoSIT detail (message-level findings, codes, positions,
 * hints) via [kositReportDialog], opened as a nested dialog through the "KoSIT-Einzelbefunde
 * anzeigen" footer action, and the LLM markdown report embedded in the combined Markdown
 * (section 2/3) — there is a single download button for the whole report, not a separate one per
 * source (two near-identical download buttons read as duplicates).
 */
class ValidationReportDialog(
    private val tr: (String) -> String,
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val documentStore: DocumentStore,
    /** Resolves a `1.1 Einzelbefunde` finding's raw text as an i18n key against
     *  `messages.properties` — see [de.littletrotter.ui.view.invoice.resolveFindingText] for the
     *  full contract. Defaults to [UiTranslations.trOrNull] without a Vaadin `Component` (a
     *  component-less lookup still resolves against the German bundle directly, just without the
     *  Vaadin `I18NProvider` layer [tr] itself goes through) — production wiring
     *  ([de.littletrotter.ui.view.invoice.InvoiceExportPanel]) overrides this with the same
     *  component-aware `tr` idiom used everywhere else in this dialog. */
    private val resolveMessageKey: (key: String, args: List<String>) -> String? =
        { key, args ->
            UiTranslations.trOrNull(
                null,
                key,
                *args.mapIndexed { index, argument -> InvoiceUnitLabels.validationArgument(null, key, index, argument) }.toTypedArray(),
            )
        },
    private val time: de.littletrotter.shared.time.TimeSource,
) {

    private val log = Logger.getLogger(javaClass)
    private val kositReportDialog = KositReportDialog(tr, resolveMessageKey)
    // The "KI-Bericht anzeigen" footer button below opens this same instance,
    // so the full-text dialog is reachable both from here and from the advisory AI chip
    // (InvoiceExportPanel), sharing the exact same stored-report/fallback resolution.
    private val aiReportDialog = AiReportDialog(tr, invoiceDetailQueryPort, documentStore)

    /** The grid cell that triggers [open] — a dash when there is nothing to show (no KoSIT
     *  report, no stored LLM report, no LLM analyses). */
    fun buildAction(job: GenerationJob): Component {
        val hasKosit = !job.kositReport.isNullOrBlank()
        val hasLlmReport = parseGenerationJobResultRef(job.resultRefs, "llmReportKey")
            ?.let { try { documentStore.exists(it) } catch (_: Exception) { false } } == true
        val hasLlmAnalyses = invoiceDetailQueryPort.findLlmAnalyses(job.invoice?.id).isNotEmpty()
        if (!(hasKosit || hasLlmReport || hasLlmAnalyses)) {
            return Span("—").apply {
                addClassName("lt-text-secondary")
                element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.report.unavailable"))
            }
        }
        return AppButtons.tertiary(tr("invoiceDetail.jobs.validationReport.open"), ActionIcons.VALIDATION_REPORT) { open(job) }.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.validationReport.open.tooltip"))
        }
    }

    fun open(job: GenerationJob) {
        val dlg = AppDialog(tr("invoiceDetail.jobs.validationReport.dialog.title"), AppDialog.Size.L).apply { isResizable = true; isDraggable = true }

        val rawKosit = job.kositReport
        val kositResult = KositReportReader.read(rawKosit)

        // LLM Analyses from repository
        val analyses = invoiceDetailQueryPort.findLlmAnalyses(job.invoice?.id)

        // LLM report keys from resultRefs (fallback)
        val llmReportKey = parseGenerationJobResultRef(job.resultRefs, "llmReportKey")
        val hasLlmStoredReport = !llmReportKey.isNullOrBlank() && try { documentStore.exists(llmReportKey) } catch (_: Exception) { false }
        val improvementSpec = resolveImprovementSpec(llmReportKey, hasLlmStoredReport, job.id)

        // Build combined report data — outcome is the same binary result the grid chip and the
        // KoSIT dialog header already show (PO decision, resolveValidationOutcome).
        val outcome = resolveValidationOutcome(job)
        val formatGuideline = parseGenerationJobResultRef(job.resultRefs, "formatGuideline")
        val reportData = buildReportData(outcome, kositResult, analyses, formatGuideline)

        // Truthfulness fix (incident DSC001-2025-09-00001) — whether the AI check was actually
        // *requested* (`llmOptimize`) for this job. `GenerationJob` does not persist the job's
        // request options anywhere (they only ever travel one-way on the NATS publish payload,
        // see DocumentJobPublisher.publishTransactional); the only in-DB trace is the worker's own
        // `resultRefs.llmStatus`. A later change decoupled the oMLX analysis from the print job (two
        // callbacks: the deterministic stages report immediately, the deferred stage's own outcome
        // follows later) and, as part of that, extended `llmStatusForFailedJob`
        // (kotlin-xpdf `JobConsumer`) to also cover the DSC001 case — a run that aborts before the
        // deterministic stages ever reach a verdict (a Factur-X engine abort, an unknown engine, a
        // template ref that expired) now still reports `llmStatus` on its FAILED result, so the
        // gap that produced the false "nicht angefordert" claim no longer exists for any job
        // produced by a worker aware of this change. `resolveLlmRequested` (this file's own package,
        // `de.littletrotter.ui.view.invoice`) is the single source for turning that raw string into
        // this flag; `null` (unknown) remains the honest fallback purely for a legacy job whose
        // `resultRefs` predates this field entirely.
        val llmRequested = resolveLlmRequested(parseGenerationJobResultRef(job.resultRefs, "llmStatus"))

        // Markdown content — generatedAt is when THIS report document is built (now); the
        // actual validation timestamp (F2: reportData.checkedAt) is a separate field and
        // comes from the payload, not from opening this dialog.
        val generatedAt = time.localNow()
        val markdown = buildValidationReportMarkdown(
            invoiceNumber = job.invoice?.invoiceNumber?.value ?: "n/a",
            reportData = reportData,
            generatedAt = generatedAt,
            improvementSpec = improvementSpec,
            llmRequested = llmRequested,
            resolveMessageKey = resolveMessageKey,
        )

        // Render Markdown in dialog body
        val html = ReportMarkdown.toHtml(markdown)
        dlg.add(Div().apply {
            // validation-report-body (styles.css) replaces the former inline
            // overflow-y/height/padding trio and adds scrollbar-gutter so the report text doesn't
            // end up flush against the scrollbar.
            addClassName("validation-report-body")
            element.setProperty("innerHTML", html)
        })

        // Toolchain provenance hint (PO 2026-09-03) — one muted line naming what actually
        // validated this invoice; versions come from ValidationToolchain (kotlin-shared), the
        // single pinned source per number.
        dlg.add(Span(validationToolchainHint(tr("invoiceDetail.jobs.validationReport.toolchain"))).apply {
            addClassNames("fs-08", "text-muted")
        })

        addReportFooterButtons(dlg, job, markdown, hasLlmStoredReport)
        dlg.open()
    }

    /** [open]'s footer buttons, extracted to keep that method's own LongMethod down.
     *  Reads the raw KoSIT report straight off [job] (the same [GenerationJob.kositReport]
     *  [open] itself reads into its own `rawKosit` local) instead of taking it as a separate
     *  parameter, to stay under LongParameterList. */
    private fun addReportFooterButtons(dlg: AppDialog, job: GenerationJob, markdown: String, hasLlmStoredReport: Boolean) {
        val rawKosit = job.kositReport
        // Footer: combined download + individual reports
        val invNum = job.invoice?.invoiceNumber?.value?.replace("/", "-").blankToNull()
        val combinedFileName = if (invNum != null) "validierungsbericht-$invNum.md" else "validierungsbericht-job-${job.id}.md"
        val combinedHandler = DownloadHandler.fromInputStream {
            val data = markdown.toByteArray(Charsets.UTF_8)
            DownloadResponse(ByteArrayInputStream(data), combinedFileName, "text/markdown", data.size.toLong())
        }
        val downloadAnchor = Anchor(combinedHandler, "").also { a ->
            a.element.setAttribute("download", true)
            a.add(AppButtons.primary(tr("invoiceDetail.jobs.validationReport.download"), ActionIcons.DOWNLOAD) {})
        }

        // Copies the FULL combined Markdown (KoSIT + KI + spec), not just one
        // section, so the whole report can be pasted into an email/chat as fast as the download.
        lateinit var copyButton: Button
        copyButton = AppButtons.tertiary(tr("invoiceDetail.jobs.report.copy"), ActionIcons.COPY) {
            copyMarkdownToClipboard(markdown, copyButton, tr)
        }
        val contextActions = mutableListOf<Component>(copyButton)

        // Reachable here AND from the advisory AI chip; only offered when a
        // stored report actually exists (a job with only analysis rows and no stored report still
        // gets a full-text dialog from the chip's own fallback, but this footer button stays tied
        // to "there is a real report", matching hasCombinedReport's own hasLlmReport check).
        if (hasLlmStoredReport) {
            contextActions.add(
                AppButtons.tertiary(tr("invoiceDetail.jobs.aiReport.footer.open"), VaadinIcon.MAGIC) { aiReportDialog.open(job) }.apply {
                    element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.aiReport.open.tooltip"))
                },
            )
        }

        // Raw KoSIT detail (message-level findings, codes, positions, hints) — reachable from the
        // combined dialog itself since the "Prüfbericht" grid column that used to open it directly
        // is gone (UX consolidation). Opens as a nested dialog stacked on
        // top; the combined Markdown above only carries per-stage counts, not this level of detail.
        if (!rawKosit.isNullOrBlank()) {
            contextActions.add(
                AppButtons.tertiary(tr("invoiceDetail.jobs.validationReport.kositDetails.open"), ActionIcons.VIEW) { kositReportDialog.open(job) }.apply {
                    element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.validationReport.kositDetails.open.tooltip"))
                },
            )
        }

        // Exactly ONE download in this dialog ("Bericht herunterladen" above).
        // The raw KoSIT XML stays reachable through "KoSIT-Details öffnen", whose nested dialog
        // carries its own XML download; a second download button here only confused users.

        // Footer order: context actions on the left, then the dismiss button, the download last.
        dlg.footer(AppButtons.tertiary(tr("app.ok")) { dlg.close() })
        contextActions.asReversed().forEach { dlg.footer.addComponentAsFirst(it) }
        dlg.footer.add(downloadAnchor)
    }

    // A failed load must never silently drop section 3 — a stored report that can't be read is a
    // data-store problem worth a WARN and a visible line in the dialog, not a blank spot that
    // reads exactly like "no report was ever produced" (the AI report going missing without a
    // trace). Extracted out of [open] to keep that method's own LongMethod down.
    private fun resolveImprovementSpec(llmReportKey: String?, hasLlmStoredReport: Boolean, jobId: Long?): String? {
        val key = llmReportKey?.takeIf { hasLlmStoredReport } ?: return null
        return try {
            String(documentStore.get(key), Charsets.UTF_8)
        } catch (ex: Exception) {
            // AP-EXC-2 — reviewed and kept broad: the comment above already explains why (a
            // failed load must surface as a visible line, not vanish). Already logged with the
            // report key/job id and surfaced in reportData; nothing further to change.
            log.warn("Failed to load stored LLM report '$key' for job $jobId: ${ex.message}", ex)
            "_KI-Bericht konnte nicht geladen werden: ${ex.message ?: ex.javaClass.simpleName}_"
        }
    }
}
