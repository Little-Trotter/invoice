package de.littletrotter.ui.view.invoice.report

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.invoice.KositMessage
import de.littletrotter.application.invoice.KositReportReader
import de.littletrotter.application.invoice.KositResult
import de.littletrotter.application.invoice.KositStage
import de.littletrotter.application.invoice.KositStatus
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.ValidationOutcome
import de.littletrotter.ui.view.invoice.kositXmlFileName
import de.littletrotter.ui.view.invoice.rawXmlDownloadAnchor
import de.littletrotter.ui.view.invoice.resolveFindingText
import de.littletrotter.ui.view.invoice.InvoiceUnitLabels
import de.littletrotter.ui.view.invoice.resolveValidationHint
import de.littletrotter.ui.view.invoice.resolveValidationOutcome
import de.littletrotter.ui.view.invoice.validationReasonKey

/**
 * Extracted from `InvoiceExportPanel.openKositReportDialog`: shows the interpreted KoSIT
 * report (status, findings grouped by stage where available) plus a raw-XML download.
 *
 * UX consolidation — no longer wired to its own grid column; [open] is
 * now invoked from within [ValidationReportDialog] as the "raw KoSIT detail" section of the
 * single combined validation report entry point.
 */
class KositReportDialog(
    private val tr: (String) -> String,
    /** Same contract as [de.littletrotter.ui.view.invoice.report.ValidationReportDialog]'s own
     *  `resolveMessageKey` — this raw-detail dialog renders `BUSINESS`-stage findings' text too
     *  (via [buildKositMessageSection]), so it needs the same key resolution [ValidationReportDialog]
     *  wires into `1.1 Einzelbefunde`, not just the KoSIT/veraPDF findings that are already prose. */
    private val resolveMessageKey: (key: String, args: List<String>) -> String? =
        { key, args ->
            UiTranslations.trOrNull(
                null,
                key,
                *args.mapIndexed { index, argument -> InvoiceUnitLabels.validationArgument(null, key, index, argument) }.toTypedArray(),
            )
        },
) {

    fun open(job: GenerationJobView) {
        val dlg = AppDialog(tr("invoiceDetail.jobs.report.dialog.title")).apply { isResizable = true; isDraggable = true }
        val raw = job.kositReport ?: ""
        val result = KositReportReader.read(raw)
        // PO decision — the header shows the binary outward result, not the
        // parser's internal KositStatus; for NotSuccessful the reason (+ detail, if any) is
        // rendered as its own line right under the header.
        val outcome = resolveValidationOutcome(job)
        val body = VerticalLayout().apply { isPadding = false; isSpacing = false; setWidthFull() }
        body.add(buildHeaderRow(outcome, result))
        addNotSuccessfulReason(body, outcome)
        renderFindings(body, result)
        val scroller = Scroller(body).apply { scrollDirection = Scroller.ScrollDirection.VERTICAL; setWidthFull(); addClassName("lt-kosit-scroller") }
        dlg.add(scroller)
        addFooter(dlg, job, raw)
        dlg.open()
    }

    private fun statusChip(outcome: ValidationOutcome): StatusChip = when (outcome) {
        is ValidationOutcome.Successful -> StatusChip(tr("invoiceDetail.jobs.kosit.status.valid"), ChipTone.OK)
        is ValidationOutcome.NotSuccessful -> StatusChip(tr("invoiceDetail.jobs.kosit.status.invalid"), ChipTone.ERROR)
        is ValidationOutcome.Pending -> StatusChip(tr("invoiceDetail.jobs.kosit.status.pending"), ChipTone.ACCENT, pulsing = true)
    }

    private fun buildHeaderRow(outcome: ValidationOutcome, result: KositResult): Div {
        return Div().apply {
            addClassName("lt-kosit-header")
            add(statusChip(outcome))
            if (result.errors.isNotEmpty()) add(Span("${result.errors.size} ${tr("invoiceDetail.jobs.kosit.errors.title")}").apply { addClassNames("lt-kosit-count", "lt-kosit-count--error") })
            if (result.warnings.isNotEmpty()) add(Span("${result.warnings.size} ${tr("invoiceDetail.jobs.kosit.warnings")}").apply { addClassNames("lt-kosit-count", "lt-kosit-count--warning") })
        }
    }

    private fun addNotSuccessfulReason(body: VerticalLayout, outcome: ValidationOutcome) {
        if (outcome !is ValidationOutcome.NotSuccessful) return
        val reasonText = tr(validationReasonKey(outcome.reason))
        val text = if (!outcome.detail.isNullOrBlank()) "$reasonText: ${outcome.detail}" else reasonText
        body.add(Div().apply {
            addClassName("lt-kosit-reason")
            add(Span(text))
        })
    }

    // When the payload carries a stage breakdown, findings are grouped by stage and
    // skipped stages appear with their reason; older payloads keep the flat rendering.
    private fun renderFindings(body: VerticalLayout, result: KositResult) {
        if (result.stages.isNotEmpty()) {
            result.stages.forEach { stage -> renderStage(body, stage, result) }
        } else {
            renderFlatFindings(body, result)
        }
    }

    private fun renderStage(body: VerticalLayout, stage: KositStage, result: KositResult) {
        val messagesInStage = (result.errors + result.warnings).filter { it.stage == stage.name }
        when {
            stage.skipped -> Unit // Skipped stages are no longer displayed.
            messagesInStage.isNotEmpty() -> renderStageMessages(body, stage, messagesInStage)
            stage.stateLabel != null -> body.add(stageStateRow(stage))
            stage.outcome == KositStatus.ERROR && !stage.engineError.isNullOrBlank() -> body.add(stageEngineErrorRow(stage))
            else -> body.add(stageNoFindingsRow(stage))
        }
    }

    private fun renderStageMessages(body: VerticalLayout, stage: KositStage, messagesInStage: List<KositMessage>) {
        val errorsInStage = messagesInStage.filter { it.severity in setOf("ERROR", "FATAL") }
        val warningsInStage = messagesInStage.filter { it.severity == "WARNING" }
        if (errorsInStage.isNotEmpty()) {
            body.add(buildKositMessageSection(stage.name, errorsInStage, KositSectionColors.ERROR, resolveMessageKey))
        }
        if (warningsInStage.isNotEmpty()) {
            body.add(buildKositMessageSection(stage.name, warningsInStage, KositSectionColors.WARNING, resolveMessageKey))
        }
    }

    private fun stageStateRow(stage: KositStage): Div = stageRow("${stage.name.uppercase()} — ${stage.stateLabel}")

    private fun stageEngineErrorRow(stage: KositStage): Div =
        stageRow("${stage.name.uppercase()} — Prüfung fehlgeschlagen: ${stage.engineError}")

    private fun stageNoFindingsRow(stage: KositStage): Div {
        val outcomeLabel = when (stage.outcome) {
            KositStatus.VALID -> "bestanden"
            KositStatus.INVALID -> "nicht bestanden"
            else -> "Prüfung fehlgeschlagen"
        }
        return stageRow("${stage.name.uppercase()} — $outcomeLabel, keine Befunde")
    }

    private fun stageRow(text: String): Div = Div().apply {
        addClassName("lt-kosit-stage-row")
        add(Span(text))
    }

    private fun renderFlatFindings(body: VerticalLayout, result: KositResult) {
        if (result.errors.isNotEmpty()) {
            body.add(buildKositMessageSection(tr("invoiceDetail.jobs.kosit.errors.title"), result.errors, KositSectionColors.ERROR, resolveMessageKey))
        }
        if (result.warnings.isNotEmpty()) {
            body.add(buildKositMessageSection(tr("invoiceDetail.jobs.kosit.warnings.title"), result.warnings, KositSectionColors.WARNING, resolveMessageKey))
        }
        if (result.errors.isEmpty() && result.warnings.isEmpty()) {
            body.add(Div().apply {
                addClassName("lt-kosit-empty")
                add(Span(if (result.status == KositStatus.VALID) tr("invoiceDetail.jobs.kosit.valid.noIssues") else tr("invoiceDetail.jobs.report.unavailable")))
            })
        }
    }

    private fun addFooter(dlg: AppDialog, job: GenerationJobView, raw: String) {
        dlg.footer(AppButtons.tertiary(tr("app.ok")) { dlg.close() })
        if (raw.isNotBlank()) {
            val xmlName = kositXmlFileName(job)
            dlg.footer.addComponentAsFirst(rawXmlDownloadAnchor(xmlName, raw, tr("invoiceDetail.jobs.report.downloadXml")))
        }
    }
}

/** The two looks a findings section can have; the stylesheet maps each to its background,
 *  border and title colours. */
private enum class KositSectionColors(val cssClass: String) {
    ERROR("lt-kosit-section--error"),
    WARNING("lt-kosit-section--warning"),
}

private fun buildKositMessageSection(
    title: String,
    messages: List<KositMessage>,
    colors: KositSectionColors,
    resolveMessageKey: (key: String, args: List<String>) -> String?,
): Div = Div().apply {
    addClassNames("lt-kosit-section", colors.cssClass)
    add(Span(title).apply { addClassName("lt-kosit-section__title") })
    messages.forEach { msg -> add(buildKositMessageRow(msg, resolveMessageKey)) }
}

private fun buildKositMessageRow(
    msg: KositMessage,
    resolveMessageKey: (key: String, args: List<String>) -> String?,
): Div = Div().apply {
    addClassName("lt-kosit-row")
    val hasMeta = !msg.code.isNullOrBlank() || !msg.position.isNullOrBlank()
    if (hasMeta) add(buildMessageMetaRow(msg))
    add(Paragraph(resolveFindingText(msg, resolveMessageKey)).apply { addClassName("lt-kosit-row__text") })
    // A maintained correction hint appears BELOW the original message, never in
    // its place: the original finding always stands on its own, a hint is additive.
    resolveValidationHint(msg.code)?.let { hint ->
        add(Paragraph(hint).apply { addClassName("lt-kosit-row__hint") })
    }
    if (!msg.location.isNullOrBlank()) add(buildMessageLocationRow(msg))
}

private fun buildMessageMetaRow(msg: KositMessage): Div = Div().apply {
    addClassName("lt-kosit-meta")
    if (!msg.code.isNullOrBlank()) add(Span(msg.code).apply { addClassName("lt-kosit-meta__code") })
    if (!msg.position.isNullOrBlank()) add(Span(msg.position).apply { addClassName("lt-kosit-meta__position") })
}

private fun buildMessageLocationRow(msg: KositMessage): Div = Div().apply {
    addClassName("lt-kosit-location")
    add(Span("Pfad:").apply { addClassName("lt-kosit-location__label") })
    add(Span(msg.location).apply { addClassName("lt-kosit-location__path") })
}
