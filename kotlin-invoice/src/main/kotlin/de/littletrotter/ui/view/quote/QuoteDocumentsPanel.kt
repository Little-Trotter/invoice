package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.StatusGlyph
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.invoice.DETAIL_POLL_INTERVAL_MS
import de.littletrotter.ui.view.invoice.PollingViewLifecycle
import de.littletrotter.ui.view.invoice.pollTick
import java.io.ByteArrayInputStream
import java.time.Instant

private const val HTTP_NOT_FOUND = 404
private const val TITLE_ATTR = "title"

/**
 * The "E-Angebot" tab of an offer, laid out like the e-invoice jobs of an invoice: the latest job
 * with its status, the request control and one table of all jobs (id, type, status, dates,
 * download, validation findings). The stand of a job (draft or review) shows as a chip next to its
 * status; the dispatch copy carries none. A request always produces the stand matching the offer
 * status and is the primary button only while no such job exists. There is no refresh button:
 * while a job is still being generated the panel polls by itself and stops as soon as none is pending.
 */
internal class QuoteDocumentsPanel(
    private val host: TranslationProvider,
    private val ports: QuoteDocumentPorts,
) : VerticalLayout() {

    internal val requestButton =
        AppButtons.primary(host.tr("quote.documents.request"), ActionIcons.E_DOCUMENT_JOB) { request() }
    internal val currentRow = Div()
    internal val pendingHint = Span(host.tr("quote.documents.pending"))
    internal val emptyState = Span(host.tr("quote.documents.empty"))
    internal val grid = DataTable(QuoteDocument::class.java)

    private val labels = QuoteLabels(host)
    private val polling = PollingViewLifecycle()
    private var polled = false
    private var quoteId = ""
    private var requestMark: QuoteDocumentMark? = null
    private var pending = false
    private var copies: List<QuoteDocument> = emptyList()

    init {
        isPadding = false
        pendingHint.addClassName("text-muted")
        pendingHint.element.setAttribute("role", "status")
        emptyState.addClassName("text-muted")
        currentRow.addClassName("quote-documents-row")
        setWidthFull()
        grid.setWidthFull()
        configureColumns()
        add(currentRow, ActionBar(requestButton), pendingHint, emptyState, grid)
    }

    /**
     * Shows the jobs of [id]; [mayRequest] hides the request control from readers. A request is
     * made in the stand matching [status], so a combination the offer component refuses cannot be
     * requested.
     */
    fun bind(id: String, status: QuoteStatus, mayRequest: Boolean) {
        quoteId = id
        requestMark = QuoteActionRules.requestMark(status)
        requestButton.isVisible = mayRequest && requestMark != null
        reload()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        syncPolling()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        super.onDetach(detachEvent)
        stopPolling(detachEvent.ui)
    }

    /** Polls only while a copy is pending and the panel is on screen; a quiet panel costs no traffic. */
    private fun syncPolling() {
        val current = ui.orElse(null)
        if (pending && !polled && current != null) {
            polled = true
            polling.attach(
                current,
                DETAIL_POLL_INTERVAL_MS,
                register = { UiEventBus.Registration { } },
                tick = pollTick(load = ::loadQuietly, apply = ::applyPolled),
            )
        } else if (!pending && polled) {
            stopPolling(current)
        }
    }

    private fun stopPolling(ui: UI?) {
        if (!polled) return
        polled = false
        polling.detach(ui)
    }

    /** Runs off the session lock, so a refusal is not shown as a notification; the next tick retries. */
    private fun loadQuietly(): List<QuoteDocument>? =
        try {
            ports.documents.documents(quoteId)
        } catch (_: QuoteServiceException) {
            null
        }

    private fun applyPolled(items: List<QuoteDocument>?) {
        if (items != null && isAttached) show(items)
    }

    private fun configureColumns() {
        grid.isAllRowsVisible = true
        grid.element.setAttribute(ARIA_LABEL, host.tr("quote.documents.ariaLabel"))
        grid.addColumn({ it.id }).setHeader(host.tr("quote.documents.column.id")).setKey("id").asCompactColumn()
        grid.addColumn({ host.tr(if (it.hasXml) "quote.documents.type.pdfXml" else "quote.documents.type.pdf") })
            .setHeader(host.tr("quote.documents.column.type")).setKey("type").asCompactColumn()
        grid.addComponentColumn { statusCell(it) }.setHeader(host.tr("quote.documents.column.status")).setKey("status").asCompactColumn()
        grid.addColumn({ stamp(it.requestedAt) }).setHeader(host.tr("quote.documents.column.requestedAt")).setKey("requestedAt").asCompactColumn()
        grid.addColumn({ it.completedAt?.let(::stamp).orEmpty() }).setHeader(host.tr("quote.documents.column.completedAt")).setKey("completedAt").asCompactColumn()
        grid.addComponentColumn { downloads(it) }.setKey("download").asActionColumn().setHeader(host.tr("quote.documents.column.download"))
        grid.addComponentColumn { validation(it) }.setKey("validation").asActionColumn().setHeader(host.tr("quote.documents.column.validation"))
    }

    /** The job state as a symbol in its colour; a failure carries its last error in the tooltip. */
    private fun statusGlyph(document: QuoteDocument): StatusGlyph {
        val label = labels.documentStatus(document.status)
        val tooltip = document.error?.let { "$label — ${host.tr("invoiceDetail.jobs.lastError.tooltip", it)}" } ?: label
        return StatusGlyph(
            QuoteLabels.documentStatusGlyph(document.status),
            QuoteLabels.documentStatusTone(document.status),
            tooltip,
            pulsing = document.status == QuoteDocumentStatus.REQUESTED,
        )
    }

    /** The status symbol plus the stand symbol: pencil for a draft copy, magnifier for a review copy, none for dispatch. */
    private fun statusCell(document: QuoteDocument): Component =
        HorizontalLayout().apply {
            isPadding = false
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            add(statusGlyph(document))
            standGlyph(document.mark)?.let { add(it) }
        }

    /** The stand uses the quote list's own status symbols, so a draft or review copy reads like the quote it came from. */
    private fun standGlyph(mark: QuoteDocumentMark): StatusGlyph? =
        when (mark) {
            QuoteDocumentMark.DRAFT ->
                StatusGlyph(QuoteLabels.statusGlyph(QuoteStatus.DRAFT), ChipTone.NEUTRAL, host.tr("invoiceDetail.jobs.draftChip.tooltip"))
            QuoteDocumentMark.REVIEW ->
                StatusGlyph(QuoteLabels.statusGlyph(QuoteStatus.REVIEW), ChipTone.NEUTRAL, host.tr("quote.documents.reviewChip.tooltip"))
            QuoteDocumentMark.DISPATCH -> null
        }

    private fun downloads(document: QuoteDocument): Component {
        val row = HorizontalLayout()
        if (document.status == QuoteDocumentStatus.COMPLETED) {
            row.add(pdfAnchor(document))
            if (document.hasXml) row.add(xmlAnchor(document))
        }
        return row
    }

    private fun validation(document: QuoteDocument): Component {
        val row = HorizontalLayout()
        if (document.hasValidationReport) {
            row.add(
                AppButtons.icon(ActionIcons.VALIDATION_REPORT, host.tr("quote.documents.report")) { showReport(document) }.apply {
                    setId("quote-document-report-${document.id}")
                },
            )
        }
        return row
    }

    /** Opens inline in a new tab, like the e-invoice PDF: a viewer tab, never a silent save. */
    private fun pdfAnchor(document: QuoteDocument): Anchor =
        Anchor(DownloadHandler.fromInputStream { event -> pdfResponse(document, event) }.inline(), "").apply {
            element.setAttribute("target", "_blank")
            setId("quote.documents.pdf-${document.id}")
            add(
                AppButtons.primary("PDF", ActionIcons.PDF) { }.apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL)
                    element.setAttribute(TITLE_ATTR, host.tr("quote.documents.pdf"))
                },
            )
        }

    private fun xmlAnchor(document: QuoteDocument): Anchor =
        Anchor(DownloadHandler.fromInputStream { event -> xmlResponse(document, event) }, "").apply {
            element.setAttribute("download", true)
            setId("quote.documents.xml-${document.id}")
            add(
                AppButtons.tertiary("XML", ActionIcons.XML) { }.apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL)
                    element.setAttribute(TITLE_ATTR, host.tr("quote.documents.xml"))
                },
            )
        }

    /**
     * The bytes are fetched only when the download is requested, so listing never moves file content.
     * A failure never escapes the handler: the viewer tab stays blank and the reason is shown in the
     * tab the panel lives in.
     */
    internal fun pdfResponse(document: QuoteDocument, event: DownloadEvent? = null): DownloadResponse =
        fileResponse(event, quiet = true) { ports.files.pdf(quoteId, document.id) }

    internal fun xmlResponse(document: QuoteDocument, event: DownloadEvent? = null): DownloadResponse =
        fileResponse(event, quiet = false) { ports.files.xml(quoteId, document.id) }

    private fun fileResponse(event: DownloadEvent?, quiet: Boolean, load: () -> QuoteDocumentContent?): DownloadResponse {
        val failure = try {
            val content = load()
            if (content != null) {
                return DownloadResponse(ByteArrayInputStream(content.bytes), content.fileName, mimeOf(content.fileName), content.bytes.size.toLong())
            }
            host.tr("quote.documents.noFile")
        } catch (e: QuoteServiceException) {
            "${e.message}"
        }
        event?.ui?.access { UiNotifications.error(failure) }
        return if (quiet) {
            DownloadResponse(ByteArrayInputStream(ByteArray(0)), "", "application/pdf", 0)
        } else {
            DownloadResponse.error(HTTP_NOT_FOUND, failure)
        }
    }

    private fun mimeOf(fileName: String): String =
        if (fileName.endsWith(".xml", ignoreCase = true)) "text/xml" else "application/pdf"

    internal fun showReport(document: QuoteDocument) {
        val report = guarded { ports.reports.report(quoteId, document.id) }
        if (report != null) {
            QuoteValidationReportDialog(host, report).open()
        } else {
            UiNotifications.warning(host.tr("quote.documents.report.none"))
        }
    }

    internal fun request() {
        val selected = requestMark ?: return
        val jobId = guarded { ports.request.request(quoteId, selected) }
        if (jobId != null) {
            UiNotifications.confirmOn(requestButton)
            reload()
        }
    }

    internal fun reload() {
        show(guarded { ports.documents.documents(quoteId) } ?: emptyList())
    }

    private fun show(items: List<QuoteDocument>) {
        grid.setItems(items)
        grid.isVisible = items.isNotEmpty()
        renderLatest(items.maxByOrNull { it.requestedAt })
        emptyState.isVisible = items.isEmpty()
        pending = items.any { it.status == QuoteDocumentStatus.REQUESTED }
        pendingHint.isVisible = pending
        copies = items
        styleRequestButton()
        syncPolling()
    }

    /** The newest job in the stand a request would produce that is ready or still being made; failed ones are history. */
    private fun currentCopy(items: List<QuoteDocument>): QuoteDocument? =
        items.filter { it.mark == requestMark && it.status != QuoteDocumentStatus.FAILED }
            .maxByOrNull { it.requestedAt }

    private fun renderLatest(latest: QuoteDocument?) {
        currentRow.removeAll()
        currentRow.isVisible = latest != null
        if (latest == null) return
        currentRow.add(
            Span(host.tr("quote.documents.latest", latest.id)).apply { addClassName("quote-documents-row__caption") },
            statusCell(latest),
        )
    }

    private fun styleRequestButton() {
        val needsCopy = currentCopy(copies) == null
        requestButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_TERTIARY)
        requestButton.addThemeVariants(if (needsCopy) ButtonVariant.LUMO_PRIMARY else ButtonVariant.LUMO_TERTIARY)
    }

    private fun stamp(at: Instant): String =
        DateFormatter.format(at.atZone(GermanTime.ZONE).toLocalDateTime())
}
