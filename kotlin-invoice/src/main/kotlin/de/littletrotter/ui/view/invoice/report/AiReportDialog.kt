package de.littletrotter.ui.view.invoice.report

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.view.invoice.buildFallbackAiMarkdown
import de.littletrotter.ui.view.invoice.parseGenerationJobResultRef
import org.jboss.logging.Logger

/**
 * "KI-Volltext lesbar": the full, UNMODIFIED LLM Markdown (no heading
 * demotion, unlike the same report embedded as section 3 of [ValidationReportDialog]'s combined
 * report), reachable both from the advisory AI chip (`InvoiceExportPanel`) and from a tertiary
 * footer button in [ValidationReportDialog].
 *
 * Prefers the stored report (`resultRefs.llmReportKey`); when a job has analysis rows but no
 * stored report (the same result-consumer gap [de.littletrotter.ui.view.invoice
 * .buildValidationReportMarkdown]'s section 2 already accounts for), falls back to a synthesized
 * Markdown built from those rows via [buildFallbackAiMarkdown] — "read the KI answer in full" is
 * still honestly met from whatever data actually exists. A load failure is named visibly (same
 * line as [ValidationReportDialog]'s own stored-report guard) rather than silently dropped.
 */
class AiReportDialog(
    private val tr: (String) -> String,
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val documentStore: DocumentStore,
) {

    private val log = Logger.getLogger(javaClass)

    /** The full AI Markdown for [job] — exposed separately from [open] so it can be unit-tested
     *  without a UI. */
    internal fun resolveMarkdown(job: GenerationJobView): String {
        val llmReportKey = parseGenerationJobResultRef(job.resultRefs, "llmReportKey")
        val hasStoredReport = !llmReportKey.isNullOrBlank() &&
            try { documentStore.exists(llmReportKey) } catch (_: Exception) { false }
        if (hasStoredReport) {
            return try {
                String(documentStore.get(llmReportKey), Charsets.UTF_8)
            } catch (ex: Exception) {
                // AP-EXC-2 — reviewed and kept broad: called from open(), escaping here would
                // prevent the dialog from opening at all instead of showing the fallback markdown
                // below. Already logged with the report key/job id and surfaced in the returned
                // markdown, exactly the "named visibly, not silently dropped" behavior this class'
                // own KDoc documents.
                log.warn("Failed to load stored AI report '$llmReportKey' for job ${job.id}: ${ex.message}", ex)
                "_KI-Bericht konnte nicht geladen werden: ${ex.message ?: ex.javaClass.simpleName}_"
            }
        }
        val analyses = invoiceDetailQueryPort.findLlmAnalyses(job.invoiceId)
        return buildFallbackAiMarkdown(analyses)
    }

    fun open(job: GenerationJobView) {
        val dlg = AppDialog(tr("invoiceDetail.jobs.aiReport.dialog.title"), AppDialog.Size.L).apply {
            isResizable = true
            isDraggable = true
        }
        val markdown = resolveMarkdown(job)
        val html = ReportMarkdown.toHtml(markdown)
        dlg.add(Div().apply {
            // Reuses ValidationReportDialog's own scroll area — same geometry, own
            // dialog instance.
            addClassName("validation-report-body")
            element.setProperty("innerHTML", html)
        })

        dlg.footer(AppButtons.tertiary(tr("app.ok")) { dlg.close() })
        lateinit var copyButton: Button
        copyButton = AppButtons.secondary(tr("invoiceDetail.jobs.report.copy"), ActionIcons.COPY) {
            copyMarkdownToClipboard(markdown, copyButton, tr)
        }
        dlg.footer.addComponentAsFirst(copyButton)
        dlg.open()
    }
}
