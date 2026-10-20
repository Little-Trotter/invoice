package de.littletrotter.ui.view.quote

import com.vaadin.flow.router.QueryParameters
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import com.vaadin.flow.router.BeforeEnterEvent
import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.quote.QuoteModuleDisabledException
import de.littletrotter.application.quote.QuoteNoNumberRangeException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.QuoteUnknownValueException
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteByNumberQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteSummary
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.shared.Money
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.ui.component.ChipSpec
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.LifecycleMeaning
import de.littletrotter.ui.component.LifecycleSubject
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.StatusGlyph
import de.littletrotter.ui.component.lifecycleGlyph
import de.littletrotter.ui.component.lifecycleTone
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
    const val DETAIL = "quotes/:number"
    const val EDIT = "quotes/:number/edit"
    const val NUMBER_PARAM = "number"
    const val TAB_PARAM = "tab"
    const val TAB_ITEMS = "items"

    fun detail(number: String): String = "$LIST/$number"

    fun edit(number: String): String = "$LIST/$number/edit"

    /** Query of the editor link that opens the positions instead of the header. */
    fun itemsTab(): QueryParameters = QueryParameters.simple(mapOf(TAB_PARAM to TAB_ITEMS))
}

/**
 * Who may enter an offer view: the component must be part of the instance and switched on, and
 * the caller must hold the view's permission. Otherwise the caller is sent to the invoice list, the same hidden-not-
 * disabled rule the drawer follows.
 */
internal class QuoteAccess(private val availability: QuoteAvailabilityQueryPort, private val roles: AppRoleQueryPort) {

    fun mayEdit(): Boolean = roles.hasPermission(AppPermission.QUOTE_EDIT)

    /** Ordering an invoice draft needs the offer decision right and the right to create invoices. */
    fun mayRequestInvoice(): Boolean =
        roles.hasPermission(AppPermission.QUOTE_EDIT) && roles.hasPermission(AppPermission.INVOICE_FULL_EDIT)

    /** The dispatch history is a mail-component page; its own route permission decides who gets the link. */
    fun mayViewMailJournal(): Boolean = roles.hasPermission(AppPermission.MAIL_JOURNAL_VIEW)

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

private val UUID_FORM = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** The two lookups an offer route needs: the number is the address, the technical id only a legacy form of it. */
@Dependent
class QuoteEntry
@Inject
constructor(
    val detail: QuoteDetailQueryPort,
    private val byNumberQuery: QuoteByNumberQueryPort,
) {
    /**
     * Resolves the entered route to its offer and lets [load] show it. A bookmark that still carries the
     * technical id is forwarded to the same view under the offer's number; an unknown offer sends the user
     * back to the offer list. [route] builds the view's path from a number.
     */
    fun enter(event: BeforeEnterEvent, route: (String) -> String, load: (QuoteDetail) -> Boolean) {
        val parameter = event.routeParameters.get(QuoteRoutes.NUMBER_PARAM).orElse(null)
        if (parameter == null) {
            event.forwardTo(QuoteListView::class.java)
            return
        }
        if (UUID_FORM.matches(parameter)) {
            val legacy = guarded { detail.find(parameter) }
            if (legacy == null) event.forwardTo(QuoteListView::class.java)
            else event.forwardTo(route(legacy.requireNumber()), event.location.queryParameters)
            return
        }
        val detail = guarded { byNumberQuery.find(parameter) }
        if (detail == null || !load(detail)) event.forwardTo(QuoteListView::class.java)
    }
}

/** Every offer carries its number from creation on, so a missing one is a defect, not a display variant. */
internal fun QuoteDetail.requireNumber(): String = checkNotNull(number) { "offer $id has no number" }

internal fun QuoteSummary.requireNumber(): String = checkNotNull(number) { "offer $id has no number" }

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
    } catch (e: QuoteUnknownValueException) {
        UiNotifications.error(UiNotifications.tr(e.messageKey, e.field, e.value))
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

    /** Detail chip; a sent offer past its validity keeps its label but turns warning-toned with the explanation as tooltip. */
    fun statusChip(status: QuoteStatus, validUntil: LocalDate, today: LocalDate): StatusChip {
        if (!isValidityExpired(status, validUntil, today)) return StatusChip(status(status), statusTone(status))
        val hint = expiredHint()
        return StatusChip(status(status), ChipTone.WARN).apply {
            element.setAttribute("title", hint)
            element.setAttribute("aria-label", hint)
        }
    }

    /** Symbol-only status for the list overview; the label is tooltip and accessible name. */
    fun listStatusGlyph(status: QuoteStatus, validUntil: LocalDate, today: LocalDate): StatusGlyph =
        if (isValidityExpired(status, validUntil, today)) {
            StatusGlyph(statusGlyph(status), ChipTone.WARN, expiredHint())
        } else {
            StatusGlyph(statusGlyph(status), statusTone(status), status(status))
        }

    private fun expiredHint(): String = host.tr("quote.status.SENT.expired")

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
        /** A sent offer whose validity ended before [today]; presentation only, the status itself stays SENT. */
        fun isValidityExpired(status: QuoteStatus, validUntil: LocalDate, today: LocalDate): Boolean =
            status == QuoteStatus.SENT && validUntil.isBefore(today)

        /** What an offer status means for the reader; tone, symbol and cockpit edge follow from it, as for invoices. */
        fun lifecycleMeaning(status: QuoteStatus): LifecycleMeaning = when (status) {
            QuoteStatus.DRAFT -> LifecycleMeaning.DRAFT
            QuoteStatus.REVIEW -> LifecycleMeaning.IN_REVIEW
            QuoteStatus.SENT -> LifecycleMeaning.SENT
            QuoteStatus.ACCEPTED -> LifecycleMeaning.ENDED_POSITIVE
            QuoteStatus.REJECTED -> LifecycleMeaning.ENDED_NEGATIVE
            QuoteStatus.CANCELLED -> LifecycleMeaning.VOIDED
            QuoteStatus.UNKNOWN -> LifecycleMeaning.DRAFT
        }

        /** One distinct silhouette per status, so the chip row still reads without colour. */
        fun statusGlyph(status: QuoteStatus): VaadinIcon =
            if (status == QuoteStatus.UNKNOWN) VaadinIcon.QUESTION_CIRCLE_O else lifecycleGlyph(lifecycleMeaning(status), LifecycleSubject.OFFER)

        /** Same tone as the invoice status of the same meaning. */
        fun statusTone(status: QuoteStatus): ChipTone = lifecycleTone(lifecycleMeaning(status))

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
