package de.littletrotter.ui.view.message

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.provider.CallbackDataProvider
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import jakarta.inject.Inject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private const val ARIA_LABEL = "aria-label"
private const val END_OF_DAY_HOUR = 23
private const val END_OF_DAY_MINUTE_SECOND = 59

/**
 * The message center's UI: a lazy-loaded grid over [MessageCenterService.find]
 * (newest first, paged — never loads everything), severity/source/time-range/unread-only
 * filters, and read-state actions (row click marks one message read, the toolbar button marks
 * every visible-and-unread message read). Reachable via [MainLayout]'s bell and directly at
 * `/messages`.
 *
 * The current user id always comes from [AuditActorProvider.currentActor] — this view never
 * hardcodes `"system"`; that is the seam a future Authentik integration replaces later without
 * touching this class.
 */
@Route(value = "messages", layout = MainLayout::class)
@PageTitle("Meldungen")
class MessageCenterView
@Inject
constructor(
    private val messageCenterService: MessageCenterService,
    private val actorProvider: AuditActorProvider,
) : VerticalLayout(), TranslationProvider {

    // internal, not private: Karibu tests drive these fields with their real generic item type.
    // Reflection erases that type, so a reflected field can only be cast back to it unchecked;
    // same-module access keeps the type and needs no cast at all.
    internal val grid = DataTable(SystemMessage::class.java, false)
    internal val severityFilter = MultiSelectComboBox<SystemMessageSeverity>()
    internal val sourceFilter = MultiSelectComboBox<SystemMessageSource>()
    private val fromPicker = DatePicker()
    private val toPicker = DatePicker()
    private val unreadOnlyToggle = Checkbox()
    private val markAllReadButton = AppButtons.tertiary(tr("messageCenter.markAllRead")) { markAllRead() }
    private val gridWrapper = GridWrapper(grid)
    private val emptyState = buildEmptyState()

    // Ids currently unread-and-visible for the actor, refreshed alongside every grid page
    // fetch (see [buildDataProvider]): one extra query per page load, not one per row — same
    // N+1-free idiom as B2bProfilesTab's `usageCounts`. Backs [isUnread] for row bolding.
    private var unreadIds: Set<Long> = emptySet()
    internal val dataProvider: CallbackDataProvider<SystemMessage, Void> = buildDataProvider()

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        configureGrid()
        add(PageShell(tr("messageCenter.title")).actions(markAllReadButton).filters(buildToolbar()).content(gridWrapper, emptyState))
        grid.dataProvider = dataProvider
        refreshGrid()
    }

    override fun getComponent(): Component = this

    private fun actorId(): String = actorProvider.currentActor()

    // ── toolbar / filters ────────────────────────────────────────────────────

    private fun buildToolbar(): Component {
        severityFilter.placeholder = tr("messageCenter.filter.severity.placeholder")
        severityFilter.element.setAttribute(ARIA_LABEL, tr("messageCenter.filter.severity.placeholder"))
        severityFilter.setItems(SystemMessageSeverity.entries)
        severityFilter.setItemLabelGenerator { severityLabel(it) }
        severityFilter.setRenderer(ComponentRenderer { severity: SystemMessageSeverity -> severityChip(severity) })
        severityFilter.addValueChangeListener { refreshGrid() }
        severityFilter.width = "14rem"

        sourceFilter.placeholder = tr("messageCenter.filter.source.placeholder")
        sourceFilter.element.setAttribute(ARIA_LABEL, tr("messageCenter.filter.source.placeholder"))
        sourceFilter.setItems(SystemMessageSource.entries)
        sourceFilter.setItemLabelGenerator { sourceLabel(it) }
        sourceFilter.addValueChangeListener { refreshGrid() }
        sourceFilter.width = "14rem"

        fromPicker.placeholder = tr("messageCenter.filter.from")
        fromPicker.element.setAttribute(ARIA_LABEL, tr("messageCenter.filter.from"))
        fromPicker.addValueChangeListener { refreshGrid() }

        toPicker.placeholder = tr("messageCenter.filter.to")
        toPicker.element.setAttribute(ARIA_LABEL, tr("messageCenter.filter.to"))
        toPicker.addValueChangeListener { refreshGrid() }

        unreadOnlyToggle.label = tr("messageCenter.filter.unreadOnly")
        unreadOnlyToggle.addValueChangeListener { refreshGrid() }

        return HorizontalLayout(severityFilter, sourceFilter, fromPicker, toPicker, unreadOnlyToggle).also {
            it.defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            it.isSpacing = true
            it.setWidthFull()
        }
    }

    private fun markAllRead() {
        messageCenterService.markAllRead(actorId())
        refreshGrid()
        UiNotifications.confirmOn(markAllReadButton)
    }

    // ── grid ─────────────────────────────────────────────────────────────────

    private fun configureGrid() {
        grid.setSizeFull()
        grid.addClassName("message-center-grid")
        grid.addComponentColumn { dateTimeCell(it.occurredAt) }
            .setHeader(tr("messageCenter.column.dateTime"))
            .asCompactColumn()
        grid.addComponentColumn { severityChip(it.severity) }
            .setHeader(tr("messageCenter.column.severity"))
            .asCompactColumn()
        grid.addColumn { sourceLabel(it.source) }
            .setHeader(tr("messageCenter.column.source"))
            .asCompactColumn()
        // The resolved message text can be long (see messageText's KDoc); ellipsized
        // with the full text as a mouse-over tooltip instead of silently clipping it.
        grid.ellipsizedTextColumn { messageText(it) }
            .setHeader(tr("messageCenter.column.message"))
            .asTextColumn(TextColumnWidth.LARGE)
        grid.addComponentColumn { refCell(it) }
            .setHeader(tr("messageCenter.column.ref"))
            .asCompactColumn()

        // Unread rows render bold — a row `::part()` name (Grid has no row-level
        // setClassNameGenerator in this Vaadin version, only setPartNameGenerator), styled by
        // weight only, no color, so it reads correctly in both themes without a token lookup.
        grid.setPartNameGenerator { message: SystemMessage -> if (isUnread(message)) "unread-row" else null }
        grid.addItemClickListener { event -> event.item?.let { onRowClick(it) } }
        grid.element.setAttribute(ARIA_LABEL, tr("messageCenter.accessibility.ariaLabel"))
    }

    /** Row click marks the message read (idempotent — a no-op for an already-read row) and
     *  refreshes just that row's style via [CallbackDataProvider.refreshItem], not a full grid
     *  reload. */
    private fun onRowClick(message: SystemMessage) {
        val id = message.id ?: return
        if (!isUnread(message)) return
        messageCenterService.markRead(id, actorId())
        unreadIds = unreadIds - id
        dataProvider.refreshItem(message)
    }

    private fun isUnread(message: SystemMessage): Boolean = message.id != null && message.id in unreadIds

    private fun buildDataProvider(): CallbackDataProvider<SystemMessage, Void> = CallbackDataProvider(
        { query: Query<SystemMessage, Void> ->
            val limit = query.limit
            val offset = query.offset
            val pageIndex = if (limit == 0) 0 else offset / limit
            val items = messageCenterService.find(actorId(), currentFilter(), pageIndex, limit)
            unreadIds = messageCenterService.unreadMessageIds(actorId())
            items.stream()
        },
        { _: Query<SystemMessage, Void> -> messageCenterService.count(actorId(), currentFilter()).toInt() },
        { message: SystemMessage -> message.id ?: -1L },
    )

    private fun currentFilter(): SystemMessageFilter = SystemMessageFilter(
        severity = severityFilter.selectedItems,
        source = sourceFilter.selectedItems,
        from = fromPicker.value?.atStartOfDay(GermanTime.ZONE)?.toOffsetDateTime(),
        to = toPicker.value?.atTime(END_OF_DAY_HOUR, END_OF_DAY_MINUTE_SECOND, END_OF_DAY_MINUTE_SECOND)?.atZone(GermanTime.ZONE)?.toOffsetDateTime(),
        unreadOnly = unreadOnlyToggle.value,
    )

    /** Full reload: re-derives the empty-state visibility from an explicit [MessageCenterService
     *  .count] call (the lazy data provider's own fetch/size callbacks only run once the grid is
     *  attached and rendering, too late for the very first paint) and refreshes the grid. */
    private fun refreshGrid() {
        val total = messageCenterService.count(actorId(), currentFilter())
        updateEmptyState(total == 0L)
        dataProvider.refreshAll()
    }

    private fun updateEmptyState(empty: Boolean) {
        gridWrapper.isVisible = !empty
        emptyState.isVisible = empty
    }

    // ── cell rendering ───────────────────────────────────────────────────────

    /** Two-line date/time cell in German wall time — [SystemMessage.occurredAt] is stored
     *  in UTC (see the entity's KDoc). */
    private fun dateTimeCell(timestamp: OffsetDateTime): Div {
        val zone = GermanTime.ZONE
        val dateLine = Span(DateFormatter.format(timestamp.atZoneSameInstant(zone).toLocalDate())).also { it.addClassName("lt-block") }
        // Time-only ("HH:mm") is deliberately not routed through DateFormatter — same reasoning
        // as GenerationJobFormatting.buildDateTimeCell's own two-line cell.
        val timeLine = Span(DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(timestamp)).also { it.addClassName("lt-job-time") }
        return Div(dateLine, timeLine)
    }

    /**
     * Severity chip: each level has its own tone and symbol, so it reads by shape as well as colour
     * (in the grid and in the filter dropdown alike).
     */
    private fun severityChip(severity: SystemMessageSeverity): Span {
        val (icon, tone) = when (severity) {
            SystemMessageSeverity.ERROR -> VaadinIcon.EXCLAMATION_CIRCLE to ChipTone.ERROR
            SystemMessageSeverity.INFO -> VaadinIcon.INFO_CIRCLE to ChipTone.ACCENT
            SystemMessageSeverity.WARNING -> VaadinIcon.WARNING to ChipTone.WARN
        }
        return StatusChip(severityLabel(severity), tone, icon = icon)
    }

    private fun severityLabel(severity: SystemMessageSeverity): String = when (severity) {
        SystemMessageSeverity.INFO -> tr("messageCenter.severity.info")
        SystemMessageSeverity.WARNING -> tr("messageCenter.severity.warning")
        SystemMessageSeverity.ERROR -> tr("messageCenter.severity.error")
    }

    private fun sourceLabel(source: SystemMessageSource): String = when (source) {
        SystemMessageSource.EXPORT -> tr("messageCenter.source.export")
        SystemMessageSource.VALIDATION -> tr("messageCenter.source.validation")
        SystemMessageSource.GENERATION -> tr("messageCenter.source.generation")
        SystemMessageSource.INVOICE -> tr("messageCenter.source.invoice")
        SystemMessageSource.SYSTEM -> tr("messageCenter.source.system")
        SystemMessageSource.UI -> tr("messageCenter.source.ui")
    }

    /** Re-renders [message] from its key+args when the key still resolves to a translation;
     *  falls back to the stored [SystemMessage.resolvedText] otherwise — a renamed/removed i18n
     *  key must not leave the row blank (the reason for storing resolvedText at all). */
    private fun messageText(message: SystemMessage): String {
        val args = message.argsList().toTypedArray()
        val rendered = UiTranslations.tr(this, message.messageKey, *args)
        return if (rendered.startsWith("!{")) message.resolvedText else rendered
    }

    /** Deep link „Rechnung {refId}" → `/invoice/{id}` for `ref_type = INVOICE`; a plain dash for
     *  every other reference (including none). */
    private fun refCell(message: SystemMessage): Component {
        val refId = message.refId
        if (message.refType == "INVOICE" && !refId.isNullOrBlank()) {
            return RouterLink(tr("messageCenter.ref.invoice", refId), InvoiceDetailView::class.java, RouteParameters("id", refId))
        }
        return Span(tr("messageCenter.ref.none"))
    }

    private fun buildEmptyState(): Div = Div(
        H3(tr("messageCenter.empty.title")).also { it.addClassName("empty-state-title") },
        Paragraph(tr("messageCenter.empty.hint")),
    ).also {
        it.addClassName("empty-state")
    }
}
