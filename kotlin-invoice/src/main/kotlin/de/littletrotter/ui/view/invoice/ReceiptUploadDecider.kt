package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.invoice.ReceiptReductionFailedException
import de.littletrotter.application.invoice.ReceiptUnreducedTooLargeException
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.ReceiptUploadTooLargeException
import de.littletrotter.application.invoice.port.inbound.PreviewReceiptReductionQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptAutoReduceQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptReductionPreview
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
import jakarta.enterprise.inject.spi.CDI
import java.util.Base64

/**
 * The two input ports the upload decision needs beyond the size rule: the reduction setting and
 * the preview of the reduced version.
 */
internal class ReceiptUploadAdvice(
    val autoReduce: ReceiptAutoReduceQueryPort,
    val preview: PreviewReceiptReductionQueryPort,
)

/**
 * Where [offerReceiptUpload] gets its [ReceiptUploadAdvice]: from the container, looked up only
 * when an upload above the target size needs it (the dialogs that call it are not container
 * beans). Tests replace [provider] with ports they control.
 */
internal object ReceiptUploadAdviceSource {
    @Volatile
    var provider: () -> ReceiptUploadAdvice = {
        val container = CDI.current()
        ReceiptUploadAdvice(
            container.select(ReceiptAutoReduceQueryPort::class.java).get(),
            container.select(PreviewReceiptReductionQueryPort::class.java).get(),
        )
    }
}

/**
 * `ReceiptSizePolicy.uploadDecision`'s upload-time consequence, applied identically at both
 * receipt upload sites ([ReceiptDialog]'s upload tab and [ReceiptPoolView]'s upload block):
 * [ReceiptUploadDecision.ACCEPT_AS_IS] uploads immediately; [ReceiptUploadDecision.OFFER_REDUCTION]
 * reduces without asking when the setting is on, otherwise shows [ReceiptReduceDialog] with both
 * versions' sizes and a preview; [ReceiptUploadDecision.REJECT] shows the rejection notification
 * without ever reaching either upload service. Neither caller needs to know the thresholds
 * themselves, which keeps the two upload sites from drifting on this rule.
 */
internal fun offerReceiptUpload(
    component: Component?,
    bytes: ByteArray,
    onConfirmed: (ReceiptUploadMode) -> Unit,
) {
    val size = DocumentSize.ofBytes(bytes.size.toLong())
    when (ReceiptSizePolicy.uploadDecision(size)) {
        ReceiptUploadDecision.ACCEPT_AS_IS -> onConfirmed(ReceiptUploadMode.REDUCE)
        ReceiptUploadDecision.OFFER_REDUCTION -> askOrReduce(component, bytes, ReceiptUploadAdviceSource.provider(), onConfirmed)
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

private fun askOrReduce(
    component: Component?,
    bytes: ByteArray,
    advice: ReceiptUploadAdvice,
    onConfirmed: (ReceiptUploadMode) -> Unit,
) {
    if (advice.autoReduce.isAutoReduceEnabled()) {
        onConfirmed(ReceiptUploadMode.REDUCE)
        return
    }
    // Reducing and rendering the preview takes a moment; it must not hold the session lock.
    runOffLock(component, { advice.preview.previewReduction(bytes) }) { outcome ->
        outcome
            .onSuccess { ReceiptReduceDialog(component, it, onConfirmed).open() }
            .onFailure { UiNotifications.error(UiTranslations.tr(component, "invoiceDetail.items.receipts.error", it.message ?: "")) }
    }
}

/**
 * Runs slow work (a receipt's storage write or PDF reduction, the mail handover) outside the
 * session lock: Vaadin invokes upload, dialog and click callbacks while holding it, so slow work
 * there stalls every other request of the session and delays the response that carries the
 * click's own visual feedback. [work] runs on the tick executor and
 * has returned (so its transactions are committed) before [onDone] runs in a short `ui.access`
 * to touch components and show the outcome. A failing [work] arrives as a failed [Result]; if the
 * UI is gone by then nothing is shown. Without any UI (headless) the call stays synchronous.
 */
internal fun <T> runOffLock(component: Component?, work: () -> T, onDone: (Result<T>) -> Unit) {
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
 * The choice between the two versions of a receipt that exceeds the target size: shows what each
 * weighs and how the reduced first page looks, then stores exactly the chosen one. Keeping the
 * original is refused above `ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE` — that button is
 * disabled with the reason beside it rather than offered and rejected.
 */
internal class ReceiptReduceDialog(
    private val component: Component?,
    private val preview: ReceiptReductionPreview,
    private val onConfirmed: (ReceiptUploadMode) -> Unit,
) {
    private fun tr(key: String, vararg params: Any) = UiTranslations.tr(component, key, *params)

    private val dlg = AppDialog(tr("receipt.reduceDialog.title"), AppDialog.Size.S)

    private val canKeep = ReceiptSizePolicy.acceptsUnreduced(preview.originalSize)

    fun open() {
        val body = Span(
            tr(
                "receipt.reduceDialog.body",
                DocumentSizeFormatter.format(preview.originalSize),
                DocumentSizeFormatter.format(preview.reducedSize),
                DocumentSizeFormatter.format(ReceiptSizePolicy.TARGET_RECEIPT_SIZE),
            ),
        )
        val keepDisabledHint = Span(tr("receipt.reduce.keep.disabled")).apply {
            addClassNames("fs-08", "text-muted")
            isVisible = !canKeep
        }
        val content = VerticalLayout(body).apply { isPadding = false; isSpacing = true }
        previewImage()?.let { content.add(it) }
        content.add(keepDisabledHint)

        val reducedBtn = AppButtons.primary(tr("receipt.reduceDialog.reduced")) { choose(ReceiptUploadMode.REDUCE) }
        val originalBtn = AppButtons.secondary(tr("receipt.reduceDialog.original")) { choose(ReceiptUploadMode.KEEP_UNREDUCED) }
            .apply { isEnabled = canKeep }
        dlg.add(content)
        dlg.footer(AppButtons.cancel { dlg.close() }, originalBtn, reducedBtn)
        dlg.open()
    }

    private fun choose(mode: ReceiptUploadMode) {
        onConfirmed(mode)
        dlg.close()
    }

    private fun previewImage(): Image? {
        val png = preview.firstPagePng ?: return null
        return Image("data:image/png;base64," + Base64.getEncoder().encodeToString(png), "").apply {
            addClassName("receipt-reduce-preview")
            maxWidth = PREVIEW_MAX_WIDTH
        }
    }

    private companion object {
        const val PREVIEW_MAX_WIDTH = "100%"
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
    /** True when the upload asked for a reduction, so a result above the target means it fell short. */
    val reductionRequested: Boolean = false,
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
    if (outcome.targetMissed()) {
        return tr("receipt.autoReduce.targetMissed", DocumentSizeFormatter.format(ReceiptSizePolicy.TARGET_RECEIPT_SIZE), toSize)
    }
    return when (outcome.normalisationStage) {
        ReceiptNormalisationStage.NONE -> tr("receipt.uploaded", outcome.fileName)
        ReceiptNormalisationStage.IMAGES_DOWNSAMPLED -> tr("receipt.autoReduced", fromSize, toSize)
        ReceiptNormalisationStage.RASTERISED -> tr("receipt.autoReduced", fromSize, toSize) + " " + tr("receipt.reduced.rasterised")
    }
}

/** A requested reduction ended above the target size: the receipt was stored as the smallest version found. */
private fun ReceiptUploadOutcome.targetMissed(): Boolean =
    reductionRequested && (sizeBytes ?: 0L) > ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES

/** Shows [receiptUploadResultText] at the severity the result warrants: a missed target or a lost
 *  text layer ([ReceiptNormalisationStage.RASTERISED]) is worth a second look, everything else is
 *  a plain success. */
internal fun showReceiptUploadResult(component: Component?, outcome: ReceiptUploadOutcome) {
    val text = receiptUploadResultText(component, outcome)
    val worthALook = outcome.targetMissed() || outcome.normalisationStage == ReceiptNormalisationStage.RASTERISED
    if (worthALook) UiNotifications.warning(text) else UiNotifications.success(text)
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
