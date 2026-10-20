package de.littletrotter.ui.view.message

import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.data.provider.CallbackDataProvider
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.dto.SystemMessageView
import de.littletrotter.application.message.port.inbound.MarkAllMessagesReadUseCase
import de.littletrotter.application.message.port.inbound.MarkMessageReadUseCase
import de.littletrotter.application.message.port.inbound.MessageCenterQueryPort
import de.littletrotter.application.dto.MessageCenterFilter
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * [MessageCenterView] against mocked [MessageCenterQueryPort]/[AuditActorQueryPort],
 * same "reflection into private fields/methods" idiom as `B2bProfilesTabKaribuTest`.
 */
class MessageCenterViewKaribuTest {

    private val messageCenterService: MessageCenterQueryPort = mock()
    private val markMessageRead: MarkMessageReadUseCase = mock()
    private val markAllMessagesRead: MarkAllMessagesReadUseCase = mock()
    private val actorProvider: AuditActorQueryPort = mock()

    @BeforeEach
    fun setup() {
        // Real route registration (not the bare no-arg MockVaadin.setup() other views' tests
        // use) — this view's "Bezug" cell builds a RouterLink to InvoiceDetailView, which
        // throws unless the target route is actually registered. Only the two routes this
        // test needs, not a full autoDiscoverViews scan: that would also register the root
        // route ("") and MockVaadin's default UI eagerly navigates there on setup, which
        // fails instantiating CDI-injected views (no no-arg constructor) outside a container.
        val testRoutes: MutableSet<Class<out com.vaadin.flow.component.Component>> = mutableSetOf(InvoiceDetailView::class.java, MessageCenterView::class.java)
        val testErrorRoutes: MutableSet<Class<out com.vaadin.flow.router.HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
        whenever(actorProvider.currentActor()).thenReturn("alice")
        whenever(messageCenterService.count(any(), any())).thenReturn(0L)
        whenever(messageCenterService.find(any(), any(), any(), any())).thenReturn(emptyList())
        whenever(messageCenterService.unreadMessageIds(any())).thenReturn(emptySet())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): MessageCenterView = MessageCenterView(messageCenterService, markMessageRead, markAllMessagesRead, actorProvider)

    private fun message(
        id: Long,
        severity: SystemMessageSeverity = SystemMessageSeverity.INFO,
        source: SystemMessageSource = SystemMessageSource.SYSTEM,
        messageKey: String = "message.unknown.key",
        resolvedText: String = "Text $id",
        refType: String? = null,
        refId: String? = null,
    ): SystemMessageView = SystemMessageView(
        id = id,
        occurredAt = OffsetDateTime.parse("2026-08-25T10:15:00Z"),
        severity = severity,
        source = source,
        messageKey = messageKey,
        args = emptyList(),
        resolvedText = resolvedText,
        refType = refType,
        refId = refId,
    )

    private fun <T : Any> privateField(view: MessageCenterView, name: String, type: kotlin.reflect.KClass<T>): T =
        type.java.cast(MessageCenterView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(view))

    private fun privateMethod(name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method {
        val m = MessageCenterView::class.java.getDeclaredMethod(name, *paramTypes)
        m.isAccessible = true
        return m
    }

    private fun severityBadge(view: MessageCenterView, message: SystemMessageView): Span =
        privateMethod("severityChip", SystemMessageSeverity::class.java).invoke(view, message.severity) as Span

    private fun refCell(view: MessageCenterView, message: SystemMessageView): Any =
        privateMethod("refCell", SystemMessageView::class.java).invoke(view, message)

    private fun onRowClick(view: MessageCenterView, message: SystemMessageView) {
        privateMethod("onRowClick", SystemMessageView::class.java).invoke(view, message)
    }

    private fun isUnread(view: MessageCenterView, message: SystemMessageView): Boolean =
        privateMethod("isUnread", SystemMessageView::class.java).invoke(view, message) as Boolean

    private fun dataProvider(view: MessageCenterView): CallbackDataProvider<SystemMessageView, Void> = view.dataProvider

    private fun sourceLabel(view: MessageCenterView, source: SystemMessageSource): String =
        privateMethod("sourceLabel", SystemMessageSource::class.java).invoke(view, source) as String

    private fun dateTimeCell(view: MessageCenterView, timestamp: OffsetDateTime): com.vaadin.flow.component.html.Div =
        privateMethod("dateTimeCell", OffsetDateTime::class.java).invoke(view, timestamp) as com.vaadin.flow.component.html.Div

    private fun messageText(view: MessageCenterView, message: SystemMessageView): String =
        privateMethod("messageText", SystemMessageView::class.java).invoke(view, message) as String

    @Test
    fun `a phone-width window keeps time, severity and message and hides source and reference`() {
        val view = newView()

        view.narrowColumns!!.apply(390)

        assertEquals(
            listOf("dateTime", "severity", "message").map { UiTranslations.tr("messageCenter.column.$it") },
            view.grid.columns.filter { it.isVisible }.map { column -> column.headerText },
        )
        view.narrowColumns!!.apply(1200)
        assertEquals(5, view.grid.columns.count { it.isVisible })
    }

    // ── severity badges ──────────────────────────────────────────────────────

    @Test
    fun `ERROR severity renders as the error-themed badge`() {
        val view = newView()
        val badge = severityBadge(view, message(1, severity = SystemMessageSeverity.ERROR))

        assertTrue(badge.classNames.contains("status-chip--error"))
        assertEquals("vaadin:exclamation-circle", badgeIcon(badge))
    }

    @Test
    fun `INFO severity renders as the accent badge with an info symbol`() {
        val view = newView()
        val badge = severityBadge(view, message(1, severity = SystemMessageSeverity.INFO))

        assertTrue(badge.classNames.contains("status-chip--accent"))
        assertEquals("vaadin:info-circle", badgeIcon(badge))
    }

    @Test
    fun `WARNING severity renders as the amber warning tokens, not brown, not error`() {
        val view = newView()
        val badge = severityBadge(view, message(1, severity = SystemMessageSeverity.WARNING))

        assertTrue(badge.classNames.contains("status-chip--warn"))
        assertFalse(badge.classNames.contains("status-chip--error"), "WARNING must not reuse the ERROR color")
        assertEquals("vaadin:warning", badgeIcon(badge))
    }

    private fun badgeIcon(badge: Span): String? = badge.element.getChild(0).getAttribute("icon")

    // ── deep link ────────────────────────────────────────────────────────────

    @Test
    fun `an INVOICE reference renders as a deep link naming the invoice`() {
        val view = newView()
        val cell = refCell(view, message(1, refType = "INVOICE", refId = "34"))

        assertTrue(cell is RouterLink, "an INVOICE reference must render as a RouterLink, not plain text")
        assertTrue((cell as RouterLink).text.contains("34"))
    }

    @Test
    fun `no reference renders as plain text, not a link`() {
        val view = newView()
        val cell = refCell(view, message(1, refType = null, refId = null))

        assertFalse(cell is RouterLink)
    }

    @Test
    fun `an INVOICE refType with a blank refId still renders as plain text, not a broken link`() {
        val view = newView()
        val cell = refCell(view, message(1, refType = "INVOICE", refId = null))

        assertFalse(cell is RouterLink)
    }

    // ── unread bold + row click marks read ──────────────────────────────────

    @Test
    fun `a message present in unreadMessageIds is unread`() {
        whenever(messageCenterService.find(any(), any(), any(), any())).thenReturn(listOf(message(7)))
        whenever(messageCenterService.count(any(), any())).thenReturn(1L)
        whenever(messageCenterService.unreadMessageIds("alice")).thenReturn(setOf(7L))
        val view = newView()

        // Forces the data provider's fetch callback to run, which is what populates unreadIds.
        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()

        assertTrue(isUnread(view, message(7)))
    }

    @Test
    fun `row click marks the message read and clears its unread state`() {
        whenever(messageCenterService.unreadMessageIds("alice")).thenReturn(setOf(9L))
        val view = newView()
        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()
        val msg = message(9)
        assertTrue(isUnread(view, msg))

        onRowClick(view, msg)

        verify(markMessageRead).markRead(9L, "alice")
        assertFalse(isUnread(view, msg), "the row must no longer be unread right after the click, without waiting for a full grid reload")
    }

    @Test
    fun `row click on an already-read message does not call markRead again`() {
        whenever(messageCenterService.unreadMessageIds("alice")).thenReturn(emptySet())
        val view = newView()
        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()

        onRowClick(view, message(11))

        verify(markMessageRead, never()).markRead(any(), any())
    }

    // ── filters forward into the service filter object ─────────────────────

    @Test
    fun `severity and source filter selections forward into the service call`() {
        val view = newView()
        val severityFilter: MultiSelectComboBox<SystemMessageSeverity> = view.severityFilter
        val sourceFilter: MultiSelectComboBox<SystemMessageSource> = view.sourceFilter

        severityFilter.select(SystemMessageSeverity.ERROR)
        sourceFilter.select(SystemMessageSource.INVOICE)

        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()

        verify(messageCenterService).find(
            eq("alice"),
            eq(MessageCenterFilter(severity = setOf(SystemMessageSeverity.ERROR), source = setOf(SystemMessageSource.INVOICE))),
            eq(0),
            eq(50),
        )
    }

    @Test
    fun `the unread-only toggle forwards into the service call`() {
        val view = newView()
        val unreadOnlyToggle: Checkbox = privateField(view, "unreadOnlyToggle", Checkbox::class)

        unreadOnlyToggle.value = true

        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()

        verify(messageCenterService).find(eq("alice"), eq(MessageCenterFilter(unreadOnly = true)), eq(0), eq(50))
    }

    @Test
    fun `from and to date filters forward as a UTC-ish day range`() {
        val view = newView()
        val fromPicker: DatePicker = privateField(view, "fromPicker", DatePicker::class)
        val toPicker: DatePicker = privateField(view, "toPicker", DatePicker::class)

        fromPicker.value = java.time.LocalDate.of(2026, 8, 1)
        toPicker.value = java.time.LocalDate.of(2026, 8, 25)

        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()

        verify(messageCenterService).find(eq("alice"), any(), eq(0), eq(50))
    }

    // ── mark all read ────────────────────────────────────────────────────────

    @Test
    fun `mark-all-read button calls markAllRead with the current actor id`() {
        val view = newView()
        val button: Button = privateField(view, "markAllReadButton", Button::class)

        button.click()

        verify(markAllMessagesRead).markAllRead("alice")
    }

    // ── actor id comes exclusively from AuditActorQueryPort, never a hardcoded value ────────────

    /**
     * Pins the seam: swap [actorProvider] to a different [AuditActorQueryPort]
     * (`"bob"` instead of the `setup()` default `"alice"`) and every call this view makes into
     * [MessageCenterQueryPort] must follow — grid fetch, unread-row lookup, row-click read receipt
     * and mark-all-read. Proves the view has no hardcoded `"system"`/`"alice"` fallback anywhere;
     * with Authentik, an `OidcAuditActorProvider` CDI-replaces [actorProvider] and this class
     * needs no change.
     */
    @Test
    fun `swapping the AuditActorQueryPort changes the actor id used for every service call, unchanged`() {
        whenever(actorProvider.currentActor()).thenReturn("bob")
        whenever(messageCenterService.unreadMessageIds("bob")).thenReturn(setOf(3L))
        val view = newView()

        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()
        onRowClick(view, message(3))
        val markAllReadButton: Button = privateField(view, "markAllReadButton", Button::class)
        markAllReadButton.click()

        verify(messageCenterService).find(eq("bob"), any(), eq(0), eq(50))
        // init() and markAllRead() each call refreshGrid(), so count() runs twice — both as "bob".
        verify(messageCenterService, org.mockito.kotlin.times(2)).count(eq("bob"), any())
        verify(messageCenterService).unreadMessageIds("bob")
        verify(markMessageRead).markRead(3L, "bob")
        verify(markAllMessagesRead).markAllRead("bob")
        verify(messageCenterService, never()).find(eq("alice"), any(), any(), any())
        verify(messageCenterService, never()).find(eq("system"), any(), any(), any())
    }

    // ── toolbar wiring sanity (indirectly covers the "filters above the grid" layout) ─────────

    @Test
    fun `the toolbar carries the severity, source, date and unread-only filters plus the mark-all-read button`() {
        val view = newView()
        val toolbar = view._find<HorizontalLayout>().first { row -> row.children.anyMatch { it is MultiSelectComboBox<*> } }
        val childTypes = toolbar.children.toList().map { it::class }

        assertTrue(childTypes.contains(MultiSelectComboBox::class))
        assertTrue(childTypes.contains(DatePicker::class))
        assertTrue(childTypes.contains(Checkbox::class))
        assertTrue(view._find<Button>().any { it.text == UiTranslations.tr("messageCenter.markAllRead") })
    }

    // ── Message column: CSS ellipsis + mouse-over tooltip for the resolved message text ──

    @Test
    fun `the message column renders the ellipsis class and carries the full resolved text as a tooltip`() {
        val view = newView()
        val msg = message(1, resolvedText = "Rechnungsvalidierung fehlgeschlagen: die Rechnungssumme weicht von der Summe der Positionen ab.")
        val grid: de.littletrotter.ui.component.DataTable<SystemMessageView> = view.grid
        val column = grid.columns.first { it.headerText == UiTranslations.tr("messageCenter.column.message") }

        val cell = column.renderer.javaClass.getMethod("createComponent", Any::class.java).invoke(column.renderer, msg) as Span

        assertTrue(cell.element.classList.contains("lt-cell-ellipsis"))
        assertEquals(msg.resolvedText, cell.element.getAttribute("title"))
    }

    // ── boundary tests ──────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - sourceLabel resolves a distinct German label for every SystemMessageSource`() {
        val view = newView()

        val labels = SystemMessageSource.entries.associateWith { sourceLabel(view, it) }

        assertEquals(SystemMessageSource.entries.size, labels.values.toSet().size, "every source must have its own label")
        assertTrue(labels.values.none { it.isBlank() })
    }

    @Test
    fun `boundary - dateTimeCell renders a date line and a separate HH-mm time line`() {
        val view = newView()

        val cell = dateTimeCell(view, OffsetDateTime.parse("2026-08-25T10:15:00Z"))

        val lines = cell.children.toList().filterIsInstance<Span>()
        assertEquals(2, lines.size)
        assertTrue(lines[1].text.matches(Regex("\\d{2}:\\d{2}")), "second line must be an HH:mm time")
    }

    @Test
    fun `boundary - a resolvable message key renders the live translation, not the stored fallback`() {
        val view = newView()
        val msg = message(1, messageKey = "messageCenter.severity.info", resolvedText = "STALE FALLBACK")

        val rendered = messageText(view, msg)

        assertEquals("Info", rendered)
        assertFalse(rendered.contains("STALE"), "a resolvable key must never fall back to the stored text")
    }

    @Test
    fun `boundary - an empty result shows the empty state and hides the grid`() {
        whenever(messageCenterService.count(any(), any())).thenReturn(0L)
        val view = newView()

        assertTrue(privateField(view, "emptyState", Div::class).isVisible)
        assertFalse(privateField(view, "gridWrapper", GridWrapper::class).isVisible)
    }

    @Test
    fun `boundary - a non-empty result hides the empty state and shows the grid`() {
        whenever(messageCenterService.count(any(), any())).thenReturn(3L)
        val view = newView()

        assertFalse(privateField(view, "emptyState", Div::class).isVisible)
        assertTrue(privateField(view, "gridWrapper", GridWrapper::class).isVisible)
    }

    // ── boundary tests ─────────────────────────────────────────────────────────────────────

    @Test
    fun `boundary - the row part name generator marks an unread row and leaves a read row unmarked`() {
        whenever(messageCenterService.unreadMessageIds("alice")).thenReturn(setOf(21L))
        val view = newView()
        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()
        val grid: de.littletrotter.ui.component.DataTable<SystemMessageView> = view.grid

        assertEquals("unread-row", grid.partNameGenerator.apply(message(21)))
        assertEquals(null, grid.partNameGenerator.apply(message(22)))
    }

    @Test
    fun `boundary - a row click on a message with no id is a no-op instead of crashing on a null id`() {
        val view = newView()
        val unsaved = message(1).copy(id = null, resolvedText = "No id yet")
        // unsaved.id is left null (never persisted) -- onRowClick's elvis-return must short-circuit.

        onRowClick(view, unsaved)

        verify(markMessageRead, never()).markRead(any(), any())
    }

    @Test
    fun `boundary - isUnread is false for a message with no id, without evaluating the unreadIds membership check`() {
        val view = newView()
        val unsaved = message(1).copy(id = null, resolvedText = "No id yet")

        assertFalse(isUnread(view, unsaved))
    }

    @Test
    fun `boundary - an INVOICE refType with a blank, non-null refId still renders as plain text`() {
        val view = newView()
        val cell = refCell(view, message(1, refType = "INVOICE", refId = "   "))

        assertFalse(cell is RouterLink)
    }

    @Test
    fun `boundary - a from-date filter is converted into the service call, not left null`() {
        val view = newView()
        val fromPicker: DatePicker = privateField(view, "fromPicker", DatePicker::class)
        val captor = argumentCaptor<MessageCenterFilter>()

        fromPicker.value = LocalDate.of(2026, 8, 1)
        // A DatePicker's own value-change listener rebuilds the grid's data provider (via
        // refreshGrid); find() itself only fires lazily when the grid actually fetches a page.
        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()

        verify(messageCenterService, atLeastOnce()).find(eq("alice"), captor.capture(), any(), any())
        assertTrue(captor.allValues.any { it.from != null }, "at least one find() call must carry the from-date filter")
    }

    @Test
    fun `boundary - a to-date filter is converted into the service call, not left null`() {
        val view = newView()
        val toPicker: DatePicker = privateField(view, "toPicker", DatePicker::class)
        val captor = argumentCaptor<MessageCenterFilter>()

        toPicker.value = LocalDate.of(2026, 8, 31)
        dataProvider(view).fetch(Query<SystemMessageView, Void>(0, 50, emptyList(), null, null)).count()

        verify(messageCenterService, atLeastOnce()).find(eq("alice"), captor.capture(), any(), any())
        assertTrue(captor.allValues.any { it.to != null }, "at least one find() call must carry the to-date filter")
    }
}
