package de.littletrotter.ui.view.mail

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._doubleClickItem
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailPlaceholderCatalogQueryPort
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewQueryPort
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTemplateQueryPort
import de.littletrotter.application.mail.port.inbound.MailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.MailTemplateVersion
import de.littletrotter.application.mail.port.inbound.ResetMailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.SendMailTemplateBccTestUseCase
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.InvoiceListView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@DisplayName("MailTemplatesView — list, client-side filters and sort, editor and history entry")
class MailTemplatesViewKaribuTest {

    private val query: MailTemplateQueryPort = mock()
    private val catalog: MailPlaceholderCatalogQueryPort = mock()
    private val preview: MailRenderPreviewQueryPort = mock()
    private val templates: MailTemplateUseCase = mock()
    private val reset: ResetMailTemplateUseCase = mock()
    private val bccTest: SendMailTemplateBccTestUseCase = mock()

    private fun template(id: Long, kind: MailTemplateKind, language: MailTemplateLanguage, version: Int, seeded: Boolean, bcc: String? = null) =
        MailTemplate(id, kind, language, version, "Betreff $id", "Text", bcc, seeded, Instant.parse("2026-10-0${id}T10:00:00Z"))

    private val rows = listOf(
        template(1, MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 3, seeded = false, bcc = "b@example.com"),
        template(2, MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.EN, 1, seeded = true),
        template(3, MailTemplateKind.QUOTE_DISPATCH, MailTemplateLanguage.DE, 2, seeded = true),
        template(4, MailTemplateKind.QUOTE_DISPATCH, MailTemplateLanguage.EN, 5, seeded = false),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(query.listTemplates()).thenReturn(rows)
        whenever(catalog.placeholders(any())).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(enabled: Boolean = true) =
        MailTemplatesView(query, catalog, preview, templates, reset, bccTest, MailAvailability(enabled))

    private fun enter(view: MailTemplatesView): BeforeEnterEvent {
        val event = mock<BeforeEnterEvent>()
        UI.getCurrent().add(view)
        view.beforeEnter(event)
        return event
    }

    private fun shown(view: MailTemplatesView): List<MailTemplate> =
        view.grid.dataProvider.fetch(com.vaadin.flow.data.provider.Query()).toList()

    @Test
    fun `without the component the view forwards to the invoice list`() {
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceListView::class.java)
        val view = newView(enabled = false)
        val event = enter(view)

        verify(event).forwardTo(InvoiceListView::class.java)
        assertTrue(shown(view).isEmpty())
    }

    @Test
    fun `every template is listed with its columns`() {
        val view = newView().also { enter(it) }

        assertEquals(4, shown(view).size)
        assertEquals(
            listOf("kind", "language", "version", "subject", "changedAt", "origin", "bcc", "actions"),
            view.grid.columns.map { it.key },
        )
        assertEquals("b@example.com", view.grid._getFormattedRow(0)[6])
        assertEquals("–", view.grid._getFormattedRow(1)[6])
        assertEquals(UiTranslations.tr("mail.templates.origin.standard"), view.grid._getFormattedRow(1)[5])
        assertEquals("3", view.grid._getFormattedRow(0)[2])
    }

    @Test
    fun `the filters narrow the list on the client`() {
        val view = newView().also { enter(it) }

        view.kindFilter.value = MailTemplateKind.QUOTE_DISPATCH
        assertEquals(listOf(3L, 4L), shown(view).map { it.id })
        view.languageFilter.value = MailTemplateLanguage.EN
        assertEquals(listOf(4L), shown(view).map { it.id })
        view.kindFilter.value = null
        view.languageFilter.value = null
        view.customOnlyToggle.value = true
        assertEquals(listOf(1L, 4L), shown(view).map { it.id })
        view.customOnlyToggle.value = false
        assertEquals(4, shown(view).size)
    }

    @Test
    fun `every sortable column orders the rows itself`() {
        val view = newView().also { enter(it) }
        val grid: Grid<MailTemplate> = view.grid

        listOf("kind", "language", "version", "subject", "changedAt", "origin", "bcc").forEach { key ->
            val column = grid.getColumnByKey(key)
            assertTrue(column.isSortable, key)
            val sorted = rows.sortedWith(column.getComparator(com.vaadin.flow.data.provider.SortDirection.ASCENDING))
            assertEquals(4, sorted.size, key)
        }
        val byVersion = rows.sortedWith(grid.getColumnByKey("version").getComparator(com.vaadin.flow.data.provider.SortDirection.ASCENDING))
        assertEquals(listOf(1, 2, 3, 5), byVersion.map { it.version })
        val byDate = rows.sortedWith(grid.getColumnByKey("changedAt").getComparator(com.vaadin.flow.data.provider.SortDirection.DESCENDING))
        assertEquals(listOf(4L, 3L, 2L, 1L), byDate.map { it.id })
    }

    @Test
    fun `a double click opens the editor for that row`() {
        val view = newView().also { enter(it) }

        view.grid._doubleClickItem(0)

        assertEquals(1, _find<MailTemplateEditorDialog>().size)
    }

    @Test
    fun `the row buttons open the editor and the history`() {
        whenever(query.listVersions(any(), any())).thenReturn(
            listOf(MailTemplateVersion(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 3, "s", "b", null, false, Instant.EPOCH, true)),
        )
        val view = newView().also { enter(it) }

        buttons(view, 0, "actions").first { it.element.getAttribute("aria-label") == UiTranslations.tr("mail.templates.action.edit") }.click()
        assertEquals(1, _find<MailTemplateEditorDialog>().size)
        buttons(view, 0, "actions").first { it.element.getAttribute("aria-label") == UiTranslations.tr("mail.templates.action.history") }.click()
        val history = _find<MailTemplateHistoryDialog>().single()
        assertEquals(1, history.grid.dataCommunicator.itemCount)
        val show = history.grid._getCellComponent(0, "actions").children.toList().filterIsInstance<Button>().single()
        show.click()
        assertEquals(2, _find<MailTemplateEditorDialog>().size, "showing a version opens the editor with it pinned")
    }

    @Test
    fun `a restore from the list history reloads the rows`() {
        whenever(query.listVersions(any(), any())).thenReturn(
            listOf(MailTemplateVersion(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1, "s", "b", null, true, Instant.EPOCH, false)),
        )
        whenever(templates.restoreVersion(any(), any(), any())).thenReturn(rows[0])
        val view = newView().also { enter(it) }
        buttons(view, 0, "actions").first { it.element.getAttribute("aria-label") == UiTranslations.tr("mail.templates.action.history") }.click()
        val history = _find<MailTemplateHistoryDialog>().single()

        history.grid._getCellComponent(0, "actions").children.toList().filterIsInstance<Button>()
            .first { it.element.getAttribute("aria-label") == UiTranslations.tr("mail.templates.history.restore") }.click()

        verify(templates).restoreVersion(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1)
        verify(query, org.mockito.kotlin.times(2)).listTemplates()
    }

    @Test
    fun `a failing load shows no stale rows and tells the user`() {
        whenever(query.listTemplates()).thenThrow(MailDispatchException("down"))

        val view = newView().also { enter(it) }

        assertTrue(shown(view).isEmpty())
        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty())
    }

    private fun buttons(view: MailTemplatesView, row: Int, column: String): List<Button> =
        view.grid._getCellComponent(row, column).children.toList().filterIsInstance<Button>()
}
