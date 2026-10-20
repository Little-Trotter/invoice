package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.logRejection
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import java.math.BigDecimal
import org.jboss.logging.Logger

/**
 * orchestration for the Positionen tab's write path, extracted out of
 * `InvoiceDetailViewItemCommands.kt`/`InvoiceDetailViewItemDialogsGoodsService.kt`'s extension
 * functions into a `@Dependent` CDI bean of its own, the same shape as [InvoiceStatusActions]:
 * opens the Waren-/Dienstleistungs-/Reise dialogs and wires their save callbacks into
 * [saveNewItem]/[updateItem]/[deleteItem]/[saveNewTrip]/[updateTripItem]. Not itself a [Component]
 * — every build/write method takes [host] purely as translation/notification context, the same
 * idiom [InvoiceStatusActions]/[InvoiceMailActions] already use.
 *
 * A successful write no longer reaches back into `InvoiceDetailView` directly: [bind] registers
 * the [InvoiceDetailState] this bean reports through, and every write applies its result via
 * [InvoiceDetailState.applyChange] — the same "callbacks via `InvoiceDetailState`" shape
 * [InvoiceStatusActions]/[ReceiptDialogLauncher] already use.
 */
@Dependent
class InvoiceItemDialogs
@Inject
constructor(
    private val editInvoiceItemsUseCase: EditInvoiceItemsUseCase,
    private val itemEntryQueryPort: ItemEntryQueryPort,
    internal val tripDialogLauncher: TripDialogLauncher,
    private val time: TimeSource,
) {
    private val log = Logger.getLogger(javaClass)
    private lateinit var state: InvoiceDetailState

    /** Registers the [InvoiceDetailState] a successful write reports through — mirrors
     *  [InvoiceStatusActions.bind]/[ReceiptDialogLauncher.bind]. */
    internal fun bind(state: InvoiceDetailState) {
        this.state = state
    }

    fun openEditInvoiceItemDialog(host: Component, invoice: InvoiceDetailDto, item: ItemDto) {
        when (item.categoryEnum()) {
            InvoiceItemCategory.PRODUCT -> openGoodsDialog(host, invoice, item, InvoiceItemCategory.PRODUCT)
            InvoiceItemCategory.UNIT_PRICE -> openGoodsDialog(host, invoice, item, InvoiceItemCategory.UNIT_PRICE)
            InvoiceItemCategory.SERVICE -> openServiceDialog(host, invoice, item)
            // One trip dialog covers every TRIP position, with or without per-diem days.
            InvoiceItemCategory.TRIP -> openTripDialog(host, invoice, item)
        }
    }

    fun openGoodsDialog(
        host: Component,
        invoice: InvoiceDetailDto,
        existingItem: ItemDto? = null,
        category: InvoiceItemCategory = InvoiceItemCategory.PRODUCT,
    ) {
        GoodsItemDialog(
            recipientQueryPort = tripDialogLauncher.recipientQueryPort,
            itemEntryQueryPort = itemEntryQueryPort,
            invoice = invoice,
            existingItem = existingItem,
            category = category,
            defaultTaxRate = ::defaultTaxRate,
            onSaveNew = { command -> saveNewItem(host, invoice, command) },
            onUpdate = { command -> updateItem(host, invoice, command) },
            today = time.today(),
        ).open()
    }

    fun openServiceDialog(host: Component, invoice: InvoiceDetailDto, existingItem: ItemDto? = null) {
        ServiceItemDialog(
            recipientQueryPort = tripDialogLauncher.recipientQueryPort,
            itemEntryQueryPort = itemEntryQueryPort,
            invoice = invoice,
            existingItem = existingItem,
            defaultTaxRate = ::defaultTaxRate,
            onSaveNew = { command -> saveNewItem(host, invoice, command) },
            onUpdate = { command -> updateItem(host, invoice, command) },
            today = time.today(),
        ).open()
    }

    fun openTripDialog(host: Component, invoice: InvoiceDetailDto, existingItem: ItemDto? = null) {
        tripDialogLauncher.open(
            invoice = invoice,
            existingItem = existingItem,
            onSaveNew = { command -> saveNewTrip(host, invoice, command) },
            onUpdate = { command ->
                updateTripItem(host, invoice, requireNotNull(existingItem) { "update needs an existing item" }.positionId, command)
            },
        )
    }

    /**
     * @return the newly created position's id, or `null` if the save failed. Since
     *   [InvoiceItemCommand] carries neither a positionId nor a sortOrder (`Invoice.addItem`,
     *   server-side, is their single source of truth), callers that need the new id (the B2B
     *   template copy step) can no longer match on a client-assigned positionId; the reloaded
     *   invoice's highest [de.littletrotter.domain.invoice.InvoiceItem.sortOrder] is always the
     *   position `Invoice.addItem` just assigned.
     */
    internal fun saveNewItem(host: Component, invoice: InvoiceDetailDto, command: InvoiceItemCommand): Long? {
        try {
            val saved = editInvoiceItemsUseCase.addItemToInvoice(invoice.id, command.copy(expectedVersion = invoice.version))
            state.dto = saved
            state.applyChange(saved)
            return saved.items.maxByOrNull { it.sortOrder }?.id
        } catch (e: Exception) {
            // Called synchronously from an item dialog's save-button click listener: must not let
            // an exception escape. Logged with the invoice id so a failed item creation is
            // discoverable.
            logRejection(log, "Failed to add item to invoiceId=${invoice.id}", e)
            UiNotifications.error(host, e)
            state.reloadOnConflict(e)
            return null
        }
    }

    internal fun updateItem(host: Component, invoice: InvoiceDetailDto, command: InvoiceItemCommand) {
        try {
            // command carries only ids/values, never the entity. The
            // service handler finds the managed item by id within the managed invoice it loads
            // itself and applies these fields — it never merges `invoice` as a whole.
            val saved = editInvoiceItemsUseCase.updateInvoiceItem(invoice.id, command.copy(expectedVersion = invoice.version))
            state.dto = saved
            state.applyChange(saved)
        } catch (e: Exception) {
            logRejection(log, "Failed to update item itemId=${command.itemId} on invoiceId=${invoice.id}", e)
            UiNotifications.error(host, e)
            state.reloadOnConflict(e)
        }
    }

    /** The [TripCommand] counterpart to [saveNewItem]; see that method's own KDoc for the
     *  return-value contract. */
    internal fun saveNewTrip(host: Component, invoice: InvoiceDetailDto, command: TripCommand): Long? {
        try {
            val saved = editInvoiceItemsUseCase.addTripToInvoice(invoice.id, command.copy(expectedVersion = invoice.version))
            state.dto = saved
            state.applyChange(saved)
            return saved.items.maxByOrNull { it.sortOrder }?.id
        } catch (e: Exception) {
            logRejection(log, "Failed to add trip to invoiceId=${invoice.id}", e)
            UiNotifications.error(host, e)
            state.reloadOnConflict(e)
            return null
        }
    }

    /** The [TripCommand] counterpart to [updateItem]. */
    internal fun updateTripItem(host: Component, invoice: InvoiceDetailDto, positionId: String, command: TripCommand) {
        try {
            val saved = editInvoiceItemsUseCase.updateTripInInvoice(invoice.id, positionId, command.copy(expectedVersion = invoice.version))
            state.dto = saved
            state.applyChange(saved)
        } catch (e: Exception) {
            logRejection(log, "Failed to update trip positionId=$positionId on invoiceId=${invoice.id}", e)
            UiNotifications.error(host, e)
            state.reloadOnConflict(e)
        }
    }

    fun deleteItem(host: Component, invoice: InvoiceDetailDto, item: ItemDto) {
        try {
            val saved = editInvoiceItemsUseCase.deleteItemFromInvoiceByPosition(invoice.id, item.positionId, invoice.version)
            state.dto = saved
            state.applyChange(saved)
        } catch (e: Exception) {
            // No local "best-effort revert": this method never mutated a client-side item
            // list to begin with (the grid only ever reflects the use case's response), so there
            // is nothing to add back. Called synchronously from the item grid's delete click
            // listener: must not let an exception escape. Logged with the invoice/position ids so
            // a failed delete is discoverable.
            log.error("Failed to delete item positionId=${item.positionId} on invoiceId=${invoice.id}", e)
            // WP1 (0.9.10) — routed through UiNotifications.resolveMessage (the same tier every
            // other write path here uses) instead of the raw e.message this used to show: a
            // concurrent-edit conflict now surfaces the translated optimistic-lock notification,
            // and a position deleted by another session in the meantime surfaces the translated
            // "deleted concurrently" one, instead of a raw technical exception string.
            UiNotifications.error(host, e)
        }
    }

    /** Applies an inline grid edit — reported by [InvoiceItemGrid.onInlineSave] once it has
     *  validated the exemption details itself. */
    internal fun applyInlineUpdate(host: Component, invoice: InvoiceDetailDto, command: InvoiceItemCommand) {
        updateItem(host, invoice, command)
    }

    fun defaultTaxRateFor(valueFallback: BigDecimal): TaxRateDto? {
        val all = itemEntryQueryPort.findAllTaxRates()
        return all.find { it.isDefault } ?: all.find { it.rate.value == valueFallback }
    }

    fun defaultTaxRate(): TaxRateDto? = defaultTaxRateFor(BigDecimal("19"))
}
