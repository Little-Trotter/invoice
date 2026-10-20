package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.export.port.inbound.TripWorkbookExportQueryPort
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.invoice.port.inbound.TripEntryQueryPort
import de.littletrotter.application.settings.port.inbound.FeatureSettingsQueryPort
import de.littletrotter.application.travel.port.inbound.HotelRatePrefillQueryPort
import de.littletrotter.application.travel.port.inbound.MileageRatePrefillQueryPort
import de.littletrotter.application.travel.port.inbound.PerDiemPreviewQueryPort
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * orchestration only: builds and opens [TripDialog], extracted out of
 * `InvoiceDetailViewItemDialogsTrip.kt`'s extension function into a `@Dependent` CDI bean of its
 * own, the same shape as [ReceiptDialogLauncher]. Replaces the deleted `openTravelDialog`/
 * `openPerDiemDialog` pair (the earlier bridge dialogs) — a TRIP position always opens this same dialog,
 * whether or not it carries per-diem days.
 *
 * Injects [receiptDialogLauncher] rather than its two receipt ports directly: both are already
 * `internal` on that bean specifically for this chain (see [ReceiptDialogLauncher]'s own KDoc), so
 * this class reads them off it instead of holding (and the CDI container re-resolving) its own
 * copies — the same "reuse the already-injected bean" idiom keeps this constructor at 6 mandatory/
 * optional parameters instead of the 9 a flat port list would need.
 *
 * [recipientQueryPort] is also exposed `internal` — [InvoiceItemDialogs] reads it off this bean to
 * build the Goods-/ServiceItemDialog's own [de.littletrotter.ui.view.invoice.TaxRuleProposalPanel],
 * rather than holding a fourth copy of the same port itself.
 */
@Dependent
class TripDialogLauncher
@Inject
constructor(
    private val tripEntryQueryPort: TripEntryQueryPort,
    internal val recipientQueryPort: RecipientQueryPort,
    private val receiptDialogLauncher: ReceiptDialogLauncher,
    private val hotelRateResolver: HotelRatePrefillQueryPort? = null,
    private val mileageRateResolver: MileageRatePrefillQueryPort? = null,
    private val perDiemCalculator: PerDiemPreviewQueryPort? = null,
    private val tripWorkbookExport: TripWorkbookExportQueryPort? = null,
    private val featureSettingsGate: FeatureSettingsQueryPort? = null,
    private val time: TimeSource,
) {
    /** Whether per-diem *creation* affordances render (add button, filter chip). Reads of
     *  already-saved PER_DIEM items never consult it; see the Settings spec's Wirkmatrix.
     *  `internal` (not `private`) purely as a test seam, same idiom as [ReceiptDialogLauncher]'s
     *  own two receipt ports. */
    internal val perDiemUiEnabled: Boolean
        get() = featureSettingsGate?.isEnabled(FeatureFlag.PER_DIEM_UI) ?: true

    fun open(
        invoice: InvoiceDetailDto,
        existingItem: ItemDto? = null,
        onSaveNew: (TripCommand) -> Long?,
        onUpdate: (TripCommand) -> Unit,
    ) {
        TripDialog(
            tripEntryQueryPort = tripEntryQueryPort,
            receiptPorts = TripReceiptPorts(
                receiptDialogLauncher.invoiceReceiptQueryPort,
                receiptDialogLauncher.attachReceiptsUseCase,
                receiptDialogLauncher.receiptReadabilityQueryPort,
            ),
            recipientQueryPort = recipientQueryPort,
            hotelRateResolver = hotelRateResolver,
            mileageRateResolver = mileageRateResolver,
            perDiemCalculator = perDiemCalculator,
            perDiemUiEnabled = perDiemUiEnabled,
            invoice = invoice,
            existingItem = existingItem,
            tripWorkbookExport = tripWorkbookExport,
            saveCallbacks = TripSaveCallbacks(onSaveNew, onUpdate),
            today = time.today(),
        ).open()
    }
}
