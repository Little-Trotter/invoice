package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.invoice.ReceiptReductionFailedException
import de.littletrotter.application.invoice.ReceiptUnreducedTooLargeException
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.ReceiptUploadTooLargeException
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.receipt.ReceiptUploadDecision
import de.littletrotter.shared.receipt.acceptsUnreduced
import de.littletrotter.shared.receipt.uploadDecision
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications

/**
 * `ReceiptSizePolicy.uploadDecision`'s upload-time consequence, applied identically at both
 * receipt upload sites ([ReceiptDialog]'s upload tab and [ReceiptPoolView]'s upload block):
 * [ReceiptUploadDecision.ACCEPT_AS_IS] uploads immediately, [ReceiptUploadDecision.OFFER_REDUCTION]
 * opens [ReceiptReduceDialog] for a reduce/keep choice, and [ReceiptUploadDecision.REJECT] shows the
 * rejection notification without ever reaching either upload service. Neither caller needs to know
 * the thresholds themselves, which is what keeps the two upload sites from drifting on this rule.
 */
internal fun offerReceiptUpload(
    component: Component?,
    bytes: ByteArray,
    onConfirmed: (ReceiptUploadMode) -> Unit,
) {
    val size = DocumentSize.ofBytes(bytes.size.toLong())
    when (ReceiptSizePolicy.uploadDecision(size)) {
        ReceiptUploadDecision.ACCEPT_AS_IS -> onConfirmed(ReceiptUploadMode.REDUCE)
        ReceiptUploadDecision.OFFER_REDUCTION -> ReceiptReduceDialog(component, size, onConfirmed).open()
        ReceiptUploadDecision.REJECT -> UiNotifications.error(
            UiTranslations.tr(
                component,
                "receipt.error.uploadTooLarge",
                DocumentSizeFormatter.format(size),
                DocumentSizeFormatter.format(ReceiptSizePolicy.MAX_UPLOAD_SIZE),
            ),
        )
    }
}

/**
 * Runs the storage write and database work of a receipt upload outside the session lock: Vaadin
 * invokes upload and dialog callbacks while holding it, and a slow object-store write or PDF
 * reduction would stall every other request of the session. [work] runs on the tick executor and
 * has returned (so its transactions are committed) before [onDone] runs in a short `ui.access`
 * to touch components and show the outcome. A failing [work] arrives as a failed [Result]; if the
 * UI is gone by then nothing is shown. Without any UI (headless) the call stays synchronous.
 */
internal fun <T> runReceiptUploadOffLock(component: Component?, work: () -> T, onDone: (Result<T>) -> Unit) {
    val ui = component?.ui?.orElse(null) ?: UI.getCurrent()
    if (ui == null) {
        onDone(runCatching(work))
        return
    }
    PollingViewLifecycle.dispatchEventTick(ui) { finished ->
        PollingViewLifecycle.runServerTick(ui, { runCatching(work) }, onDone, finished = finished)
    }
}

/**
 * The "Beleg reduzieren" choice — [ReceiptUploadDecision.OFFER_REDUCTION]'s only caller
 * ([offerReceiptUpload]): reduce (preselected) or keep the upload unchanged. Keeping is refused
 * outright above `ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE` — that option is disabled rather
 * than offered and rejected, the same affordance-withholding idiom [ReceiptPoolView]'s delete/edit
 * buttons already use — and [keepHint] only appears once "keep" is actually selectable AND
 * selected, so it never competes for attention with the (usual) reduce path.
 */
internal class ReceiptReduceDialog(
    private val component: Component?,
    size: DocumentSize,
    private val onConfirmed: (ReceiptUploadMode) -> Unit,
) {
    private fun tr(key: String, vararg params: Any) = UiTranslations.tr(component, key, *params)

    private val dlg = AppDialog(tr("receipt.reduce.title"), AppDialog.Size.S)

    private val canKeep = ReceiptSizePolicy.acceptsUnreduced(size)

    private val modeGroup = RadioButtonGroup<ReceiptUploadMode>().apply {
        setItems(ReceiptUploadMode.REDUCE, ReceiptUploadMode.KEEP_UNREDUCED)
        setItemLabelGenerator {
            when (it) {
                ReceiptUploadMode.REDUCE -> tr("receipt.reduce.option.reduce")
                ReceiptUploadMode.KEEP_UNREDUCED -> tr("receipt.reduce.option.keep")
            }
        }
        setItemEnabledProvider { it != ReceiptUploadMode.KEEP_UNREDUCED || canKeep }
        value = ReceiptUploadMode.REDUCE
    }

    // Above the unreduced cap, keeping is not offered at all rather than offered and rejected —
    // this hint says exactly why the (visibly disabled) option cannot be picked.
    private val keepDisabledHint = Span(tr("receipt.reduce.keep.disabled")).apply {
        addClassNames("fs-08", "text-muted")
        isVisible = !canKeep
    }

    // Shown only while "keep" is the actual selection, so it never competes with the reduce
    // path's own (usual) silence.
    private val keepHint = Span(tr("receipt.reduce.keep.hint")).apply {
        addClassNames("fs-08", "text-muted")
        isVisible = false
    }

    fun open() {
        modeGroup.addValueChangeListener { refreshKeepHint() }
        val confirmBtn = AppButtons.primary(tr("receipt.reduce.confirm")) {
            onConfirmed(modeGroup.value)
            dlg.close()
        }
        val cancelBtn = AppButtons.cancel { dlg.close() }

        dlg.add(VerticalLayout(modeGroup, keepDisabledHint, keepHint).apply { isPadding = false; isSpacing = true })
        dlg.footer(cancelBtn, confirmBtn)
        dlg.open()
    }

    private fun refreshKeepHint() {
        keepHint.isVisible = canKeep && modeGroup.value == ReceiptUploadMode.KEEP_UNREDUCED
    }
}

/**
 * The four receipt fields [receiptUploadResultText]/[showReceiptUploadResult] need, bundled so
 * those two functions stay under detekt's `LongParameterList` threshold. [de.littletrotter.domain
 * .masterdata.Receipt] and [de.littletrotter.application.dto.ReceiptRowDto] both carry these same
 * four fields under the same names — either maps onto this directly at the call site.
 */
internal class ReceiptUploadOutcome(
    val fileName: String,
    val normalisationStage: ReceiptNormalisationStage,
    val originalSizeBytes: Long?,
    val sizeBytes: Long?,
)

/**
 * The result toast text for a completed upload (or an on-demand pool reduction, see
 * [ReceiptPoolView]'s "Beleg reduzieren" row action): the plain "uploaded" wording when the
 * reducer left the bytes untouched, or a from/to size sentence — plus the searchability warning
 * for [ReceiptNormalisationStage.RASTERISED] — when it actually acted. Shared by both upload sites
 * (and the pool's reduce action) so the wording cannot drift between them.
 */
internal fun receiptUploadResultText(component: Component?, outcome: ReceiptUploadOutcome): String {
    fun tr(key: String, vararg params: Any) = UiTranslations.tr(component, key, *params)
    val fromSize = DocumentSizeFormatter.format(DocumentSize.ofBytes(outcome.originalSizeBytes ?: 0L))
    val toSize = DocumentSizeFormatter.format(DocumentSize.ofBytes(outcome.sizeBytes ?: 0L))
    return when (outcome.normalisationStage) {
        ReceiptNormalisationStage.NONE -> tr("receipt.uploaded", outcome.fileName)
        ReceiptNormalisationStage.IMAGES_DOWNSAMPLED -> tr("receipt.reduced", fromSize, toSize)
        ReceiptNormalisationStage.RASTERISED -> tr("receipt.reduced", fromSize, toSize) + " " + tr("receipt.reduced.rasterised")
    }
}

/** Shows [receiptUploadResultText] at the severity the stage warrants: [ReceiptNormalisationStage
 *  .RASTERISED] lost the text layer, worth a second look, everything else is a plain success. */
internal fun showReceiptUploadResult(component: Component?, outcome: ReceiptUploadOutcome) {
    val text = receiptUploadResultText(component, outcome)
    if (outcome.normalisationStage == ReceiptNormalisationStage.RASTERISED) UiNotifications.warning(text) else UiNotifications.success(text)
}

/**
 * The named-rejection message for an upload/reduction failure — [ReceiptReductionFailedException],
 * [ReceiptUnreducedTooLargeException] and [ReceiptUploadTooLargeException] each need their own
 * wording instead of falling through to a generic error. `null` for anything else, so a caller can
 * fall back to its own usual generic handling unchanged.
 */
internal fun receiptUploadExceptionMessage(component: Component?, ex: Exception): String? {
    fun tr(key: String, vararg params: Any) = UiTranslations.tr(component, key, *params)
    return when (ex) {
        is ReceiptReductionFailedException -> tr(
            "receipt.error.reductionFailed",
            ex.pageCount.toString(),
            DocumentSizeFormatter.format(ex.resultingSize),
        )
        is ReceiptUnreducedTooLargeException -> tr(
            "receipt.error.unreducedTooLarge",
            DocumentSizeFormatter.format(ex.uploaded),
            DocumentSizeFormatter.format(ex.limit),
        )
        is ReceiptUploadTooLargeException -> tr(
            "receipt.error.uploadTooLarge",
            DocumentSizeFormatter.format(ex.uploaded),
            DocumentSizeFormatter.format(ex.limit),
        )
        else -> null
    }
}
