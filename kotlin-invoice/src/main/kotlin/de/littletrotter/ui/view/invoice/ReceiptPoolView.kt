package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import com.vaadin.flow.server.streams.UploadHandler
import de.littletrotter.application.error.ApplicationUserException
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.port.inbound.DeleteReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.EditReceiptDetailsUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptPoolQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptSort
import de.littletrotter.application.invoice.port.inbound.ReceiptView
import de.littletrotter.application.invoice.port.inbound.UploadReceiptUseCase
import de.littletrotter.domain.exception.InvoiceDomainException
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import jakarta.inject.Inject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate
import org.jboss.logging.Logger
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.confirmDelete

private const val ARIA_LABEL = "aria-label"
private const val TITLE_ATTR = "title"
private const val APPLICATION_PDF = "application/pdf"
private const val HTTP_NOT_FOUND = 404
private const val SMALL_PAGE_SIZE = 25
private const val DEFAULT_PAGE_SIZE = 50
private const val BYTES_PER_KB = 1024.0

/** [ReceiptPoolView.buildUploadSection]'s own picked-file bundle — carries the values
 *  [ReceiptPoolView.performUpload] needs from pick time through to the (possibly deferred, if
 *  [offerReceiptUpload] opens the reduce dialog) upload-confirm time, keeping `performUpload`
 *  itself under detekt's `LongParameterList` threshold. Not a `data class`: [bytes] is a
 *  `ByteArray` (detekt `ArrayInDataClass`), same reasoning as `ReceiptUploadService.ReducedPayload`. */
private class PendingPoolUpload(
    val fileName: String?,
    val bytes: ByteArray,
    val descriptionField: TextField,
    val documentDatePicker: DatePicker,
)

@Route(value = "receipt-pool", layout = MainLayout::class)
@PageTitle("Belege")
class ReceiptPoolView
@Inject
constructor(
    private val uploadReceiptUseCase: UploadReceiptUseCase,
    private val receiptPoolQueryPort: ReceiptPoolQueryPort,
    private val editReceiptDetailsUseCase: EditReceiptDetailsUseCase,
    private val deleteReceiptUseCase: DeleteReceiptUseCase,
    private val receiptReadabilityQueryPort: ReceiptReadabilityQueryPort,
) : VerticalLayout() {

    private val log = Logger.getLogger(javaClass)

    private val grid = DataTable(ReceiptView::class.java, false)
    private val filterField = TextField()
    private val pageSizeField = ComboBox<Int>()
    private var currentFilter: String = ""
    private var currentPageSize: Int = DEFAULT_PAGE_SIZE
    /** Usage summaries for the page currently rendered, filled by the fetch callback in
     *  ONE query per page. The old column called findReceiptUsage per rendered row (N+1) and
     *  re-ran on every scroll and repaint. */
    private var usageByReceiptId: Map<Long, String> = emptyMap()
    /** fix-receipt-editable-after-invoice-issued — receipt ids whose edit is locked, for the
     *  page currently rendered. */
    private var lockedReceiptIds: Set<Long> = emptySet()

    init {
        setSizeFull()
        isPadding = true
        buildLayout()
        updatePageTitle()
    }

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        ui.page.setTitle("${UiTranslations.tr(this, "receipt.title")} | ${UiTranslations.tr(this, "app.title")}")
    }

    private fun buildLayout() {
        configureGrid()
        val gridWrapper = GridWrapper(grid)
        val shell = PageShell(UiTranslations.tr(this, "receipt.title")).apply {
            content(buildUploadSection(), buildToolbar(), gridWrapper)
        }
        shell.setSizeFull()
        add(shell)
        expand(shell)
        refresh()
    }

    private fun buildToolbar(): HorizontalLayout {
        filterField.placeholder = UiTranslations.tr(this, "receipt.filter.placeholder")
        filterField.prefixComponent = VaadinIcon.SEARCH.create()
        filterField.isClearButtonVisible = true
        filterField.addValueChangeListener { e ->
            currentFilter = e.value.trim().lowercase()
            refresh()
        }

        pageSizeField.setItems(SMALL_PAGE_SIZE, DEFAULT_PAGE_SIZE, 100)
        pageSizeField.label = null
        pageSizeField.element.setAttribute(ARIA_LABEL, UiTranslations.tr(this, "invoice.page.size"))
        pageSizeField.value = currentPageSize
        pageSizeField.isClearButtonVisible = false
        pageSizeField.width = "8.5rem"
        pageSizeField.addValueChangeListener { e ->
            val selected = e.value ?: DEFAULT_PAGE_SIZE
            currentPageSize = selected
            grid.pageSize = selected
            refresh()
        }

        return HorizontalLayout(filterField, pageSizeField).apply {
            addClassName("ino-toolbar")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.END
            setWidthFull()
            setFlexGrow(1.0, filterField)
        }
    }

    private fun buildUploadSection(): VerticalLayout {
        val descriptionField = TextField(UiTranslations.tr(this, "receipt.description")).apply {
            width = "350px"
            placeholder = UiTranslations.tr(this@ReceiptPoolView, "receipt.description.placeholder")
            isRequiredIndicatorVisible = true
            valueChangeMode = ValueChangeMode.EAGER
        }

        // Required, but deliberately NOT defaulted to today: a silently-wrong default date on a
        // document is worse than none — the user has to pick the date the document carries.
        val documentDatePicker = DatePicker(UiTranslations.tr(this, "receipt.documentDate")).apply {
            isRequiredIndicatorVisible = true
        }

        val upload = Upload(UploadHandler.inMemory { metadata, bytes ->
            // IR-2's upload-time decision (offerReceiptUpload) runs first: accept immediately,
            // offer a reduce/keep choice, or refuse outright — before either upload service call.
            val pending = PendingPoolUpload(metadata.fileName, bytes, descriptionField, documentDatePicker)
            offerReadabilityHint(this@ReceiptPoolView, bytes, { offerReceiptUpload(this@ReceiptPoolView, bytes) { mode -> performUpload(pending, mode) } }, receiptReadabilityQueryPort)
        })
        upload.setAcceptedMimeTypes(APPLICATION_PDF)
        upload.setAcceptedFileExtensions(".pdf")
        // IR-2 — the client-side cap is the outer bound a file must clear before the
        // reduce/keep decision even runs; the server-side check stays authoritative. A tighter
        // cap here would silently block a file that should instead reach the "offer reduction"
        // dialog.
        upload.maxFileSize = ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES.toInt()
        upload.isDropAllowed = true
        upload.applyReceiptUploadI18n(this)

        upload.uploadButton = AppButtons.primary(UiTranslations.tr(this, "receipt.action.upload"), ActionIcons.UPLOAD) {}

        // Both fields are mandatory; Vaadin Upload fires on file pick, so the ergonomic
        // enforcement is to keep the whole upload control disabled until description AND
        // Belegdatum are filled (the server check in ReceiptUploadService.upload stays the backstop).
        // A successful upload clears both fields, which re-disables the control via the same
        // listeners.
        fun refreshUploadEnabled() {
            upload.isEnabled = descriptionField.value.isNotBlank() && documentDatePicker.value != null
        }
        descriptionField.addValueChangeListener { refreshUploadEnabled() }
        documentDatePicker.addValueChangeListener { refreshUploadEnabled() }
        refreshUploadEnabled()

        upload.addFileRejectedListener { event ->
            UiNotifications.error(UiTranslations.tr(this@ReceiptPoolView, "receipt.error.rejected", event.errorMessage ?: ""))
        }

        val row = HorizontalLayout(upload, descriptionField, documentDatePicker)
        row.defaultVerticalComponentAlignment = FlexComponent.Alignment.END
        row.isSpacing = true

        // Stated up front, not only after a rejected upload.
        val maxSizeHint = Span(
            UiTranslations.tr(this, "receipt.upload.maxSizeHint", DocumentSizeFormatter.format(ReceiptSizePolicy.MAX_UPLOAD_SIZE)),
        ).apply { addClassNames("fs-08", "text-muted") }

        return VerticalLayout(maxSizeHint, row).apply {
            isPadding = false
            isSpacing = true
        }
    }

    /** The upload handler's own completion callback ([offerReceiptUpload]'s `onConfirmed`):
     *  performs the actual upload with the chosen [mode] and reports the outcome. Extracted out of
     *  [buildUploadSection] so that closure stays about building the control, not about handling
     *  its result. */
    private fun performUpload(pending: PendingPoolUpload, mode: ReceiptUploadMode) {
        // Field values are read here, under the lock; the upload itself runs off-lock.
        val description = pending.descriptionField.value
        val documentDate = pending.documentDatePicker.value
        runOffLock(
            this,
            work = { uploadReceiptUseCase.uploadReceipt(pending.fileName, description, pending.bytes, documentDate, mode) },
        ) { outcome -> reportUpload(pending, mode, outcome) }
    }

    private fun reportUpload(pending: PendingPoolUpload, mode: ReceiptUploadMode, outcome: Result<ReceiptView>) {
        try {
            val receipt = outcome.getOrThrow()
            pending.descriptionField.clear()
            pending.documentDatePicker.clear()
            refresh()
            // L14: detect dedup by comparing the returned receipt's name with the attempted name
            val isDeduplicated = receipt.fileName != (pending.fileName ?: "receipt.pdf")
            if (isDeduplicated) {
                UiNotifications.info(UiTranslations.tr(this, "receipt.uploaded.deduplicated", receipt.fileName))
            } else {
                showReceiptUploadResult(
                    this,
                    ReceiptUploadOutcome(
                        receipt.fileName, receipt.normalisationStage, receipt.originalSizeBytes, receipt.sizeBytes,
                        reductionRequested = mode == ReceiptUploadMode.REDUCE,
                    ),
                )
            }
        } catch (e: InvoiceDomainException) {
            UiNotifications.error(e.message ?: "Upload failed")
        } catch (e: ApplicationUserException) {
            // ReceiptReductionFailedException/ReceiptUnreducedTooLargeException/
            // ReceiptUploadTooLargeException each get their own named wording; anything else
            // falls back to the exception's own message, same as before this decision existed.
            UiNotifications.error(receiptUploadExceptionMessage(this, e) ?: (e.message ?: "Upload failed"))
        } catch (e: IOException) {
            UiNotifications.error(UiTranslations.tr(this, "receipt.error.read", e.message ?: ""))
        }
    }

    private fun configureGrid() {
        grid.setWidthFull()
        grid.pageSize = currentPageSize
        // Long file names and descriptions retain their full text in a tooltip.
        grid.ellipsizedTextColumn { r: ReceiptView -> r.fileName }
            .setKey("fileName").setSortProperty("fileName")
            .setHeader(UiTranslations.tr(this, "receipt.column.fileName")).asTextColumn(TextColumnWidth.MEDIUM).isSortable = true
        grid.ellipsizedTextColumn { r: ReceiptView -> r.description }
            .setKey("description").setSortProperty("description")
            .setHeader(UiTranslations.tr(this, "receipt.column.description")).asTextColumn(TextColumnWidth.MEDIUM).isSortable = true
        grid.addColumn({ r: ReceiptView -> formatReceiptDocumentDate(r.documentDate) })
            .setKey("documentDate").setSortProperty("documentDate")
            .setHeader(UiTranslations.tr(this, "receipt.column.documentDate")).asCompactColumn().isSortable = true
        // Shares formatReceiptUploadedAt with ReceiptDialog's own attached-receipts grid,
        // instead of a second, slightly different inline formatter: the previous
        // `uploadedAt.toLocalDateTime()` here dropped the offset as-stored instead of converting
        // to Europe/Berlin wall time, which only a non-Berlin process default zone (e.g.
        // a container without a TZ override) would actually expose.
        grid.addColumn({ r: ReceiptView -> formatReceiptUploadedAt(r.uploadedAt) })
            .setKey("uploadedAt").setSortProperty("uploadedAt")
            .setHeader(UiTranslations.tr(this, "receipt.column.uploadedAt")).asCompactColumn().isSortable = true
        grid.addColumn({ r: ReceiptView ->
            r.sizeBytes?.let { sz -> "%.1f KB".format(sz / BYTES_PER_KB) } ?: "-"
        }).setKey("sizeBytes").setSortProperty("sizeBytes")
            .setHeader(UiTranslations.tr(this, "receipt.column.size")).asNumericColumn().isSortable = true
        // One batched lookup per page (see usageByReceiptId), not one query per row.
        grid.addComponentColumn { receipt: ReceiptView ->
            val text = receipt.id?.let { usageByReceiptId[it] } ?: "-"
            Span(text).apply { element.setAttribute(TITLE_ATTR, text) }
        }.setHeader(UiTranslations.tr(this, "receipt.column.usage")).asTextColumn(TextColumnWidth.MEDIUM)
        // receipt-size-normalisation: names an unreduced-and-oversized receipt, or how much a
        // reduced one shrank, so the reduce action below isn't the only place this is visible.
        grid.addColumn({ r: ReceiptView -> receiptHintText(r) })
            .setKey("hint").setHeader(UiTranslations.tr(this, "receipt.column.hint")).asTextColumn(TextColumnWidth.MEDIUM)
        grid.addComponentColumn { receipt: ReceiptView -> buildActionColumn(receipt) }
            .asActionColumn()
    }

    /** [configureGrid]'s "Hinweis" column: names an unreduced receipt still above the target size,
     *  or the from/to sizes of one that was already reduced — empty for anything else (a reduced
     *  receipt at/under target, or one whose original size was never recorded). */
    private fun receiptHintText(r: ReceiptView): String = when {
        r.normalisationStage == ReceiptNormalisationStage.NONE && (r.sizeBytes ?: 0L) > ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES ->
            UiTranslations.tr(this, "receipt.hint.unreduced", DocumentSizeFormatter.format(DocumentSize.ofBytes(r.sizeBytes ?: 0L)))
        r.normalisationStage != ReceiptNormalisationStage.NONE && r.originalSizeBytes != null ->
            UiTranslations.tr(
                this,
                "receipt.hint.reduced",
                DocumentSizeFormatter.format(DocumentSize.ofBytes(r.originalSizeBytes)),
                DocumentSizeFormatter.format(DocumentSize.ofBytes(r.sizeBytes ?: 0L)),
            )
        else -> ""
    }

    /** Resolves the bytes before any streaming starts: a receipt row can outlive its stored
     *  object, and throwing from inside a streaming download handler leaves the servlet with a
     *  half-written response and an ERROR trace per click. A missing object answers 404, tells the
     *  user in the original tab and logs one WARN line. */
    // UI event boundary: a DownloadHandler callback must not let any exception through.
    internal fun receiptDownloadResponse(event: DownloadEvent, receipt: ReceiptView): DownloadResponse =
        try {
            val data = receiptPoolQueryPort.getBytes(receipt.id!!)
            DownloadResponse(ByteArrayInputStream(data), receipt.fileName, APPLICATION_PDF, data.size.toLong())
        } catch (ex: Exception) {
            log.warn("Receipt download failed: receiptId=${receipt.id}: ${ex.message}")
            event.ui?.access { UiNotifications.error(UiTranslations.tr(this, "receipt.error.unavailable")) }
            DownloadResponse.error(HTTP_NOT_FOUND)
        }

    private fun buildActionColumn(receipt: ReceiptView): HorizontalLayout {
        val downloadLink = Anchor(
            DownloadHandler.fromInputStream { event -> receiptDownloadResponse(event, receipt) },
            "",
        ).apply {
            addClassName("action-icon-anchor")
            element.setAttribute("download", receipt.fileName)
            element.setAttribute(TITLE_ATTR, UiTranslations.tr(this@ReceiptPoolView, "receipt.action.download"))
            element.setAttribute(ARIA_LABEL, UiTranslations.tr(this@ReceiptPoolView, "receipt.action.download"))
            add(ActionIcons.DOWNLOAD.create())
        }

        val viewLink = Anchor(
            DownloadHandler.fromInputStream { event -> receiptDownloadResponse(event, receipt) }.inline(),
            "",
        ).apply {
            addClassName("action-icon-anchor")
            element.setAttribute("target", "_blank")
            element.setAttribute(TITLE_ATTR, UiTranslations.tr(this@ReceiptPoolView, "receipt.action.view"))
            element.setAttribute(ARIA_LABEL, UiTranslations.tr(this@ReceiptPoolView, "receipt.action.view"))
            add(ActionIcons.VIEW.create())
        }

        // receipt-delete-guard — a receipt assigned to an invoice is that invoice's proof and must
        // not be deletable at all, so the affordance is not offered rather than offered and
        // refused. Read off the same per-page usage map the usage column renders (no extra query);
        // DeleteReceiptUseCase.delete stays the authoritative backstop for a stale row.
        val delBtn = AppButtons.icon(ActionIcons.DELETE, UiTranslations.tr(this, "app.delete")) {
            confirmDelete(receipt)
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
            isVisible = isUnassigned(receipt)
        }

        // fix-receipt-editable-after-invoice-issued — hidden (not just disabled) once any linked
        // invoice is issued, same affordance-withholding pattern as [delBtn] above;
        // EditReceiptDetailsUseCase.updateDetails is the backstop for a stale row.
        val editBtn = AppButtons.icon(ActionIcons.EDIT, UiTranslations.tr(this, "receipt.action.edit")) {
            openEditDialog(receipt)
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            isVisible = !isLocked(receipt)
        }

        return HorizontalLayout(editBtn, viewLink, downloadLink, delBtn).apply { isSpacing = true }
    }

    private val unusedUsageLabel: String
        get() = UiTranslations.tr(this, "invoiceDetail.items.receipts.usage.unused")

    /** receipt-delete-guard — whether the row currently shows no invoice usage, read from the
     *  per-page [usageByReceiptId] map the usage column renders from. An id-less receipt (never
     *  persisted) can carry no link either. */
    private fun isUnassigned(receipt: ReceiptView): Boolean =
        receipt.id?.let { usageByReceiptId[it] }?.let { it == unusedUsageLabel } ?: true

    /** fix-receipt-editable-after-invoice-issued — whether the row's edit action is locked, read
     *  from the per-page [lockedReceiptIds] set the fetch callback fills. */
    private fun isLocked(receipt: ReceiptView): Boolean = receipt.id?.let { it in lockedReceiptIds } ?: false

    /** Small dialog to edit a receipt's description and Belegdatum; pre-filled, saves via
     *  [EditReceiptDetailsUseCase.updateDetails] and refreshes the grid so the new values are visible
     *  immediately, without a manual reload. Both fields are mandatory — clearing either blocks
     *  the save with an inline error on the emptied field. */
    private fun openEditDialog(receipt: ReceiptView) {
        val dlg = AppDialog(UiTranslations.tr(this, "receipt.dialog.editTitle"), AppDialog.Size.S)

        val descriptionField = TextField(UiTranslations.tr(this, "receipt.description")).apply {
            setWidthFull()
            placeholder = UiTranslations.tr(this@ReceiptPoolView, "receipt.description.placeholder")
            value = receipt.description
            isRequiredIndicatorVisible = true
        }
        val documentDatePicker = DatePicker(UiTranslations.tr(this, "receipt.documentDate")).apply {
            setWidthFull()
            value = receipt.documentDate
            isRequiredIndicatorVisible = true
        }
        dlg.add(VerticalLayout(descriptionField, documentDatePicker).apply { isPadding = false; isSpacing = true })

        val saveBtn = AppButtons.primary(UiTranslations.tr(this, "app.save")) {
            val receiptId = receipt.id
            if (receiptId != null && validateEditFields(descriptionField, documentDatePicker)) {
                saveReceiptDetails(receiptId, descriptionField.value.trim(), documentDatePicker.value!!, dlg)
            }
        }
        dlg.footer(AppButtons.cancel { dlg.close() }, saveBtn)
        dlg.open()
    }

    /** Both fields mandatory — an emptied field blocks the save with an inline error instead of
     *  reaching the (backstop) server check. Extracted out of [openEditDialog] to keep that
     *  method's own cognitive complexity down. */
    private fun validateEditFields(descriptionField: TextField, documentDatePicker: DatePicker): Boolean {
        val description = descriptionField.value.trim()
        val documentDate = documentDatePicker.value
        descriptionField.isInvalid = description.isEmpty()
        descriptionField.errorMessage =
            if (description.isEmpty()) UiTranslations.tr(this, "receipt.error.descriptionRequired") else null
        documentDatePicker.isInvalid = documentDate == null
        documentDatePicker.errorMessage =
            if (documentDate == null) UiTranslations.tr(this, "receipt.error.documentDateRequired") else null
        return description.isNotEmpty() && documentDate != null
    }

    private fun saveReceiptDetails(receiptId: Long, description: String, documentDate: LocalDate, dlg: Dialog) {
        try {
            editReceiptDetailsUseCase.updateDetails(receiptId, description, documentDate)
            dlg.close()
            refresh()
            UiNotifications.success(UiTranslations.tr(this, "receipt.updated"))
        } catch (ex: Exception) {
            // Click listener: must not let an exception escape. Logged with the receipt id
            // so a failed description update is discoverable.
            log.error("Failed to update details for receiptId=$receiptId", ex)
            UiNotifications.error(UiTranslations.tr(this, "receipt.error.update", ex.message ?: ""))
        }
    }

    /**
     * receipt-delete-guard — an assigned receipt is not deletable at all: the delete button is
     * already hidden for one ([isUnassigned]), and this is the backstop for the row that was
     * rendered before the assignment happened (a second tab, a peer session). The eligibility
     * check is re-run server-side here, so a stale row refuses with a named error instead of
     * opening a confirmation it could never carry out.
     */
    private fun confirmDelete(receipt: ReceiptView) {
        val check = try {
            deleteReceiptUseCase.checkDeleteEligibility(receipt.id!!)
        } catch (ex: Exception) {
            // Runs synchronously off the delete button's click listener: must not let an exception
            // escape. Logged with the receipt id so a failed eligibility check is discoverable.
            log.error("Failed to check delete eligibility for receiptId=${receipt.id}", ex)
            UiNotifications.error(UiTranslations.tr(this@ReceiptPoolView, "receipt.error.delete", ex.message ?: ""))
            return
        }

        if (!check.deletable) {
            // A draft draws its invoice number only at send time, so there is nothing to name for
            // a draft-only assignment — the message says where to release it instead.
            UiNotifications.error(
                if (check.assignedInvoiceNumbers.isEmpty()) {
                    UiTranslations.tr(this@ReceiptPoolView, "receipt.dialog.deleteBlocked")
                } else {
                    UiTranslations.tr(
                        this@ReceiptPoolView,
                        "receipt.dialog.deleteBlockedNamed",
                        check.assignedInvoiceNumbers.joinToString(", "),
                    )
                },
            )
            refresh()
            return
        }

        confirmDelete(
            UiTranslations.tr(this, "receipt.dialog.deleteTitle"),
            UiTranslations.tr(this, "receipt.dialog.deleteConfirm", receipt.fileName),
        ) {
            try {
                deleteReceiptUseCase.delete(receipt.id)
                refresh()
                UiNotifications.success(UiTranslations.tr(this@ReceiptPoolView, "receipt.deleted"))
            } catch (ex: Exception) {
                // Click listener: must not let an exception escape. Logged with the receipt id so
                // a failed delete is discoverable.
                log.error("Failed to delete receiptId=${receipt.id}", ex)
                UiNotifications.error(UiTranslations.tr(this@ReceiptPoolView, "receipt.error.delete", ex.message ?: ""))
            }
        }
    }

    private fun refresh() {
        val filter = currentFilter.blankToNull()
        val activePageSize = currentPageSize
        val provider = com.vaadin.flow.data.provider.DataProvider.fromCallbacks(
            { query ->
                val requestedLimit = if (query.limit <= 0) activePageSize else query.limit
                val pageIndex = if (requestedLimit <= 0) 0 else query.offset / requestedLimit
                fetchReceiptsPage(pageIndex, requestedLimit, filter, query.sortOrders.firstOrNull())
            },
            { countReceipts(filter) },
        )
        grid.pageSize = activePageSize
        grid.dataProvider = provider
    }

    /** [refresh]'s fetch callback, extracted to keep that method's own cognitive complexity down.
     *  DataProvider fetch callback: Vaadin invokes this while rendering the grid, so an
     *  escaping exception breaks the grid the same way an escaping listener exception would.
     *  Stays broad, but logged with the filter/page. */
    private fun fetchReceiptsPage(
        pageIndex: Int,
        requestedLimit: Int,
        filter: String?,
        order: com.vaadin.flow.data.provider.QuerySortOrder?,
    ): java.util.stream.Stream<ReceiptView> = try {
        val page = if (order == null) receiptPoolQueryPort.findReceiptViews(pageIndex, requestedLimit, filter)
            else receiptPoolQueryPort.findReceiptViewsSorted(
                pageIndex,
                requestedLimit,
                filter,
                ReceiptSort(order.sorted, order.direction == com.vaadin.flow.data.provider.SortDirection.DESCENDING),
            )
        // Fill the usage column for this whole page in one query, before the
        // rows are rendered (same pattern as the item dialog's pool tab).
        val pageIds = page.mapNotNull { it.id }
        usageByReceiptId = receiptPoolQueryPort.findUsageSummaries(
            pageIds,
            UiTranslations.tr(this, "invoiceDetail.items.receipts.usage.unused"),
            UiTranslations.tr(this, "receipt.usage.invoiceLevel"),
        )
        lockedReceiptIds = receiptPoolQueryPort.findLockedReceiptIds(pageIds)
        page.stream()
    } catch (ex: Exception) {
        log.error("Failed to load receipts (pageIndex=$pageIndex, pageSize=$requestedLimit, filter=$filter)", ex)
        UiNotifications.error(UiTranslations.tr(this, "receipt.error.load", ex.message ?: ""))
        emptyList<ReceiptView>().stream()
    }

    private fun countReceipts(filter: String?): Int = try {
        receiptPoolQueryPort.countAll(filter).toInt()
    } catch (ex: Exception) {
        // Same rationale as the fetch callback above.
        log.error("Failed to count receipts (filter=$filter)", ex)
        UiNotifications.error(UiTranslations.tr(this, "receipt.error.load", ex.message ?: ""))
        0
    }
}
