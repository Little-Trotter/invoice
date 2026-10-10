package de.littletrotter.ui.view.quote

import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.BeforeEnterEvent
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.QuoteModuleDisabledException
import de.littletrotter.application.quote.QuoteNoNumberRangeException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.shared.Money
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.ui.component.ChipSpec
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.StatusGlyph
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.invoice.InvoiceListView
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

private const val MAX_FRACTION_DIGITS = 4

internal const val ARIA_LABEL = "aria-label"

/** The paths of the offer views, kept in one place so navigation and `@Route` values cannot drift apart. */
internal object QuoteRoutes {
    const val LIST = "quotes"
    const val STATISTICS = "quotes/statistics"
    const val RECEIPT_POOL = "quote-receipt-pool"
    const val DETAIL = "quotes/:id"
    const val EDIT = "quotes/:id/edit"
    const val ID_PARAM = "id"
    const val TAB_PARAM = "tab"
    const val TAB_ITEMS = "items"

    fun detail(id: String): String = "$LIST/$id"

    fun edit(id: String): String = "$LIST/$id/edit"

    /** Query of the editor link that opens the positions instead of the header. */
    fun itemsTab(): QueryParameters = QueryParameters.simple(mapOf(TAB_PARAM to TAB_ITEMS))
}

/**
 * Who may enter an offer view: the component must be part of the instance and switched on, and
 * the caller must hold the view's permission. Otherwise the caller is sent to the invoice list, the same hidden-not-
 * disabled rule the drawer follows.
 */
internal class QuoteAccess(private val availability: QuoteAvailability, private val roles: AppRoleProvider) {

    fun mayEdit(): Boolean = roles.hasPermission(AppPermission.QUOTE_EDIT)

    /** Ordering an invoice draft needs the offer decision right and the right to create invoices. */
    fun mayRequestInvoice(): Boolean =
        roles.hasPermission(AppPermission.QUOTE_EDIT) && roles.hasPermission(AppPermission.INVOICE_FULL_EDIT)

    fun maySend(): Boolean = roles.hasPermission(AppPermission.QUOTE_SEND)

    fun mayMaintainLibrary(): Boolean = roles.hasPermission(AppPermission.QUOTE_LIBRARY_EDIT)

    fun mayArchiveLibrary(): Boolean = roles.hasPermission(AppPermission.QUOTE_LIBRARY_ARCHIVE)

    /** `true` when the navigation may proceed; otherwise the event is already forwarded. */
    fun admit(event: BeforeEnterEvent, permission: AppPermission): Boolean {
        val allowed = availability.offered && roles.hasPermission(permission)
        if (!allowed) event.forwardTo(InvoiceListView::class.java)
        return allowed
    }
}

/**
 * Resolves the offer id of the entered route and lets [load] fetch it; a missing id or an offer
 * that cannot be loaded sends the user back to the offer list.
 */
internal fun BeforeEnterEvent.enterQuote(load: (String) -> Boolean) {
    val id = routeParameters.get(QuoteRoutes.ID_PARAM).orElse(null)
    if (id == null || !load(id)) forwardTo(QuoteListView::class.java)
}

/**
 * Runs one call into the offer component. A refusal or an unreachable component becomes a
 * notification carrying the component's own explanation and yields `null`, so a caller can tell
 * "did not happen" from any result (a successful `Unit` call is non-null).
 */
internal fun <T> guarded(block: () -> T): T? =
    try {
        block()
    } catch (e: QuoteNoNumberRangeException) {
        UiNotifications.error(UiNotifications.tr(e.messageKey))
        null
    } catch (e: QuoteModuleDisabledException) {
        UiNotifications.error(UiNotifications.tr(e.messageKey))
        null
    } catch (e: QuoteServiceException) {
        UiNotifications.error("${e.message}")
        null
    }

/** Texts and chips shared by the offer views; every label comes from the message bundle. */
internal class QuoteLabels(private val host: TranslationProvider) {

    fun status(status: QuoteStatus): String = host.tr("quote.status.${status.name}")

    fun priceDisplay(display: QuotePriceDisplay): String = host.tr("quote.priceDisplay.${display.name}")

    fun logKind(kind: QuoteLogKind): String = host.tr("quote.log.kind.${kind.name}")

    fun attachmentKind(kind: QuoteAttachmentKind): String = host.tr("quote.attachment.kind.${kind.name}")

    fun documentStatus(status: QuoteDocumentStatus): String = host.tr("quote.documents.status.${status.name}")

    fun xmlFormat(format: QuoteXmlFormat): String = host.tr("quote.settings.xmlFormat.${format.name}")

    fun statusChip(status: QuoteStatus): StatusChip = StatusChip(status(status), statusTone(status))

    /** Symbol-only status for the list overview; the label is tooltip and accessible name. */
    fun listStatusGlyph(status: QuoteStatus): StatusGlyph = StatusGlyph(statusGlyph(status), statusTone(status), status(status))

    /** The list's quick-filter chip of [status]: same colour family as the detail hero, plus a glyph. */
    fun quickFilterSpec(status: QuoteStatus): ChipSpec {
        val label = status(status)
        return ChipSpec(label, statusTone(status), statusGlyph(status)) { count ->
            host.tr("quote.quickFilter.state.ariaLabel", label, count)
        }
    }

    fun money(money: Money): String = MoneyFormatter.format(money)

    fun date(date: LocalDate): String = DateFormatter.format(date)

    /** Quantities and rates in German notation without insignificant zeros (`2,5`, `19`). */
    fun number(value: BigDecimal): String =
        NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = MAX_FRACTION_DIGITS }.format(value)

    /**
     * Label of a business term by its BT code: plain wording followed by the code so support can still
     * refer to it (`... (BT-19)`); codes without a dedicated label read `BT-<code>`.
     */
    fun btLabel(code: Int): String =
        UiTranslations.trOrNull(host.getComponent(), "quote.bt.$code")?.let { "$it (BT-$code)" } ?: "BT-$code"

    /** One-sentence explanation of a business term, or null when none is maintained. */
    fun btHelp(code: Int): String? = UiTranslations.trOrNull(host.getComponent(), "quote.bt.$code.help")

    companion object {
        /** One distinct silhouette per status, so the chip row still reads without colour. */
        fun statusGlyph(status: QuoteStatus): VaadinIcon = when (status) {
            QuoteStatus.DRAFT -> VaadinIcon.PENCIL
            QuoteStatus.REVIEW -> VaadinIcon.SEARCH
            QuoteStatus.SENT -> VaadinIcon.PAPERPLANE
            QuoteStatus.ACCEPTED -> VaadinIcon.CHECK
            QuoteStatus.INVOICED -> VaadinIcon.INVOICE
            QuoteStatus.REJECTED -> VaadinIcon.CLOSE_SMALL
            QuoteStatus.CANCELLED -> VaadinIcon.BAN
        }

        /** Same semantics as the invoice status tones: draft neutral, review review, sent accent, done ok. */
        fun statusTone(status: QuoteStatus): ChipTone = when (status) {
            QuoteStatus.DRAFT -> ChipTone.NEUTRAL
            QuoteStatus.REVIEW -> ChipTone.REVIEW
            QuoteStatus.SENT -> ChipTone.ACCENT
            QuoteStatus.ACCEPTED, QuoteStatus.INVOICED -> ChipTone.OK
            QuoteStatus.REJECTED -> ChipTone.ERROR
            QuoteStatus.CANCELLED -> ChipTone.STORNO
        }

        /** Same silhouettes as the e-invoice job status: in progress (hourglass), ready (check), failed (exclamation). */
        fun documentStatusGlyph(status: QuoteDocumentStatus): VaadinIcon = when (status) {
            QuoteDocumentStatus.REQUESTED -> VaadinIcon.HOURGLASS
            QuoteDocumentStatus.COMPLETED -> VaadinIcon.CHECK
            QuoteDocumentStatus.FAILED -> VaadinIcon.EXCLAMATION_CIRCLE
        }

        fun documentStatusTone(status: QuoteDocumentStatus): ChipTone = when (status) {
            QuoteDocumentStatus.REQUESTED -> ChipTone.ACCENT
            QuoteDocumentStatus.COMPLETED -> ChipTone.OK
            QuoteDocumentStatus.FAILED -> ChipTone.ERROR
        }
    }
}
