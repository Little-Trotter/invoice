package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale

/**
 * The write target of [ReceiptDialog]: one position or the invoice as a whole. The dialog is
 * **parameterized by target, not copied** — both targets share the same attached/assign-from-pool
 * mechanics, so they cannot drift apart. Both targets also share the same three-tab assembly
 * (attached / pool / upload) — see [ReceiptDialog.open].
 */
internal sealed class ReceiptTarget {
    abstract val titleKey: String

    /** Receipts of a single invoice item. [itemId] is null while the position is unsaved. */
    data class Item(val itemId: Long?) : ReceiptTarget() {
        override val titleKey get() = "invoiceDetail.items.receipts.dialog.title"
    }

    /** Receipts of the invoice as a whole (Auftragsbestätigung, Vertrag, Stundenzettel). */
    data class Invoice(val invoiceId: Long?) : ReceiptTarget() {
        override val titleKey get() = "invoiceDetail.receipts.dialog.title"
    }
}

/** Binary bytes-per-kibibyte, used by [formatReceiptSize]'s B/KB/MB threshold switch. */
private const val BYTES_PER_KB = 1024L

/**
 * Extracted out of `InvoiceDetailView` into a `@Dependent` CDI bean of its own, the same shape as
 * [InvoiceReceiptsTab]: constructs [ReceiptDialog] with its own receipt ports and supplies the
 * target-specific refresh. A write from the item dialog refreshes both the item grid and the
 * Belege tab — the tab's "Belege an Positionen" card shows per-position receipts too, so it must
 * stay in sync with the item dialog, not only with its own (invoice-level) dialog. The dialog
 * itself never touches `InvoiceDetailView` directly.
 *
 * A successful write no longer calls back into `InvoiceDetailView` directly (this class has no
 * reference to it): [reloadAndNotify] reloads the committed [InvoiceDetailDto] through
 * [invoiceDetailQueryPort] and hands it to the bound [InvoiceDetailState] instead — the same
 * "callbacks via `InvoiceDetailState`" shape the Versionen-tab panel already uses. Shared by this
 * launcher's own dialogs (the [openReceiptDialog] `onChanged` callback) and by
 * [InvoiceReceiptsTab]'s own detach/undo flow, which holds this bean as one of its three
 * dependencies precisely so it does not need an [InvoiceDetailQueryPort] of its own.
 *
 * [invoiceReceiptQueryPort]/[attachReceiptsUseCase] stay `internal` (not private):
 * `InvoiceDetailViewItemDialogsTrip.kt`'s `openTripDialog` reads both through this bean rather than
 * InvoiceDetailView growing its own duplicate direct fields for them (the view's constructor keeps
 * a fixed budget of two dependency bundles plus a handful of directly injected components — see
 * the design concept's own notes on that budget).
 */
@Dependent
class ReceiptDialogLauncher
@Inject
constructor(
    internal val invoiceReceiptQueryPort: InvoiceReceiptQueryPort,
    internal val attachReceiptsUseCase: AttachReceiptsUseCase,
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    internal val receiptReadabilityQueryPort: ReceiptReadabilityQueryPort,
) {
    private var state: InvoiceDetailState? = null

    /** Registers the [InvoiceDetailState] this launcher reports receipt changes to (see the class
     *  KDoc) — called once by [InvoiceDetailView]. */
    internal fun bind(state: InvoiceDetailState) {
        this.state = state
    }

    /** Entry point for the position paperclip in the items grid. */
    fun openItemReceiptDialog(invoice: InvoiceDetailDto, item: ItemDto) =
        openReceiptDialog(invoice, ReceiptTarget.Item(item.id))

    /** Entry point for the Belege tab: receipts of the invoice as a whole. Wires [ReceiptDialog]'s
     *  "Zur Position" button (position-assigned rows only) to this launcher's own
     *  [openItemReceiptDialog], so a dead end there now opens the position's own dialog instead. */
    fun openInvoiceReceiptDialog(invoice: InvoiceDetailDto) {
        openReceiptDialog(
            invoice,
            ReceiptTarget.Invoice(invoice.id),
            onNavigateToItem = { item -> openItemReceiptDialog(invoice, item) },
        )
    }

    private fun openReceiptDialog(
        invoice: InvoiceDetailDto,
        target: ReceiptTarget,
        onNavigateToItem: ((ItemDto) -> Unit)? = null,
    ) {
        ReceiptDialog(
            invoiceReceiptQueryPort = invoiceReceiptQueryPort,
            attachReceiptsUseCase = attachReceiptsUseCase,
            receiptReadabilityQueryPort = receiptReadabilityQueryPort,
            invoice = invoice,
            target = target,
            onChanged = { reloadAndNotify(invoice.id) },
            onNavigateToItem = onNavigateToItem,
        ).open()
    }

    /** Reload the committed aggregate version for either assignment scope before another dialog
     *  opens or the Belege tab re-renders. Refreshing only the receipt tables would leave the
     *  invoice-level picker with a stale version. A concurrently deleted invoice (`null`) is a
     *  no-op: the bound state keeps whatever it already had. */
    internal fun reloadAndNotify(invoiceId: Long) {
        val current = invoiceDetailQueryPort.findInvoiceDetailDto(invoiceId) ?: return
        state?.notifyReceiptsChanged(current)
    }
}

/** Whether the current [InvoiceDocumentBudget] is up against its count limit, so a caller can
 *  style the hint beyond text alone (still never colour-only, see [budgetHint]). The estimated
 *  size is a warning (the budget banner elsewhere), not a link-time gate any more, so this hint
 *  no longer has a size-exhausted state of its own. */
internal enum class BudgetHintState { NORMAL, COUNT_EXHAUSTED }

internal data class BudgetHint(val text: String, val state: BudgetHintState)

/**
 * The invoice-wide receipt budget, worded once and shared by [ReceiptDialog] (the pool tab's
 * hint) and [InvoiceReceiptsTab.render]'s Belege card, so "7 of max. 19 receipts assigned" cannot
 * read differently in the two places that show it.
 *
 * Takes [component] (rather than a [de.littletrotter.ui.i18n.TranslationProvider] receiver) so
 * both callers can use it as-is: [ReceiptDialog] implements that interface, but
 * [InvoiceReceiptsTab] passes itself the same way without formally implementing it there.
 */
internal fun budgetHint(component: Component?, budget: InvoiceDocumentBudget): BudgetHint {
    fun tr(key: String, vararg params: Any) = UiTranslations.tr(component, key, *params)
    val countExhausted = budget.remainingSlots == 0
    val text = if (countExhausted) {
        tr("invoiceDetail.receipts.budget.countExhausted", ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE.toString())
    } else {
        // PO 2026-09-04: the NORMAL state names the count only — the running MB tally was a
        // size-optimization view the product does not offer.
        tr(
            "invoiceDetail.receipts.budget.hint",
            budget.distinctReceiptCount.toString(),
            ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE.toString(),
        )
    }
    val state = if (countExhausted) BudgetHintState.COUNT_EXHAUSTED else BudgetHintState.NORMAL
    return BudgetHint(text, state)
}

/** Applies [BudgetHint] to a hint [com.vaadin.flow.component.html.Span]: text, a
 *  warning class swap (never colour-only — the text itself already differs per state), and
 *  `aria-live` so a screen reader announces the exhausted states as they happen, not only on
 *  next focus. */
internal fun com.vaadin.flow.component.html.Span.applyBudgetHint(hint: BudgetHint) {
    text = hint.text
    classNames.remove("text-muted")
    classNames.remove("text-warning")
    classNames.add(if (hint.state == BudgetHintState.NORMAL) "text-muted" else "text-warning")
    element.setAttribute("aria-live", "polite")
}

/** German is the display language project-wide — formatted explicitly with [Locale.GERMAN]
 *  (decimal comma) rather than the JVM default locale, so the rendered size does not depend on
 *  the host's locale configuration. */
internal fun formatReceiptSize(sizeBytes: Long?): String {
    val size = sizeBytes ?: return "-"
    if (size < BYTES_PER_KB) return "$size B"
    if (size < BYTES_PER_KB * BYTES_PER_KB) return String.format(Locale.GERMAN, "%.1f KB", size / BYTES_PER_KB.toDouble())
    return String.format(Locale.GERMAN, "%.1f MB", size / (BYTES_PER_KB.toDouble() * BYTES_PER_KB))
}

/** Used to render `yyyy-MM-dd HH:mm` (ISO order) while every sibling timestamp in the
 *  application renders `dd.MM.yyyy`; German is the display language. Keeps this function's own
 *  "-" placeholder rather than [DateFormatter]'s "—" — only the pattern/zone logic is shared. */
internal fun formatReceiptUploadedAt(uploadedAt: OffsetDateTime?): String =
    uploadedAt?.let { DateFormatter.format(it) } ?: "-"

/** Belegdatum in the project's German date convention. The field is mandatory on every write
 *  path; the null branch only covers a not-yet-validated in-memory entity. */
internal fun formatReceiptDocumentDate(documentDate: LocalDate?): String =
    documentDate?.let { DateFormatter.format(it) } ?: "-"

/**
 * The in-memory twin of `PanacheReceiptRepository`'s `NEWEST_FIRST_ORDER`
 * (`document_date DESC, uploaded_at DESC, id DESC`): the (mandatory) document date leads,
 * upload time breaks ties, id keeps the order total. [ReceiptDialog.findInvoiceOwnReceipts]
 * sorts its unpaged group with this so the two groups of the item picker can never sort
 * differently.
 */
internal val receiptNewestFirst: Comparator<ReceiptRowDto> =
    compareByDescending<ReceiptRowDto, LocalDate?>(nullsFirst(naturalOrder())) { it.documentDate }
        .thenByDescending(nullsFirst(naturalOrder())) { it.uploadedAt }
        .thenByDescending(nullsFirst(naturalOrder())) { it.id }
