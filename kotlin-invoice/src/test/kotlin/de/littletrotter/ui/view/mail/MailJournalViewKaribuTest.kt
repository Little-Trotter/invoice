package de.littletrotter.ui.view.mail

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.data.provider.ListDataProvider
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.RouteConfiguration
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.export.port.inbound.MailJournalWorkbookExportQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceIdByNumberQueryPort
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalAttachment
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverRetryOutcome
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import de.littletrotter.application.mail.port.inbound.RetryArchiveHandoverUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.application.mail.port.inbound.MailJournalQueryPort
import de.littletrotter.application.mail.port.inbound.MailJournalSortColumn
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailRearchiveOutcome
import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.inbound.MailSortOrder
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.RearchiveMailUseCase
import de.littletrotter.application.mail.port.inbound.MailArchiveDownloadQueryPort
import de.littletrotter.application.mail.port.inbound.RetryMailUseCase
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.InvoiceListView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate

@DisplayName("MailJournalView — list, filters, details and row actions")
class MailJournalViewKaribuTest {

    private val journalQuery: MailJournalQueryPort = mock()
    private val retryUseCase: RetryMailUseCase = mock()
    private val rearchiveUseCase: RearchiveMailUseCase = mock()
    private val handoverUseCase: RetryArchiveHandoverUseCase = mock()
    private val roles: AppRoleQueryPort = mock()
    private val invoiceIdByNumber: InvoiceIdByNumberQueryPort = mock()
    private val exportService: MailJournalWorkbookExportQueryPort = mock()
    private val archiveDownload: MailArchiveDownloadQueryPort = mock()

    private fun entry(
        id: Long,
        status: MailJournalStatus = MailJournalStatus.SENT,
        archive: MailJournalArchiveStatus = MailJournalArchiveStatus.ARCHIVED,
        retryOf: Long? = null,
        reference: String = "RE-2026-000$id",
        handover: MailJournalHandoverStatus = MailJournalHandoverStatus.NOT_CONFIGURED,
        handoverError: String? = null,
    ) = MailJournalEntry(
        id = id,
        referenceKey = reference,
        recipient = "kunde$id@example.com",
        subject = "Rechnung $reference",
        messageId = "<m$id@example.com>",
        status = status,
        smtpResult = "250 queued as $id",
        templateKind = MailTemplateKind.INVOICE_DISPATCH,
        templateLanguage = MailTemplateLanguage.DE,
        templateVersion = 2,
        bodyEditedByUser = false,
        retryOfJournalId = retryOf,
        createdAt = Instant.parse("2026-09-06T10:15:30Z"),
        attachments = listOf(MailJournalAttachment("rechnung.pdf", "application/pdf", 2_048, "a".repeat(64))),
        archiveStatus = archive,
        archiveS3Key = "mail-journal/$id/archive.tar.gz",
        archiveHandoverStatus = handover,
        archiveHandoverError = handoverError,
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(journalQuery.findPage(any())).thenReturn(listOf(entry(1), entry(2, status = MailJournalStatus.FAILED)))
        whenever(journalQuery.count(any())).thenReturn(2L)
        whenever(journalQuery.countByStatus(any())).thenReturn(mapOf(MailJournalStatus.SENT to 1L, MailJournalStatus.FAILED to 1L))
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceDetailView::class.java)
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceListView::class.java)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(mailEnabled: Boolean = true) = MailJournalView(
        journalQuery, MailJournalRowActions(retryUseCase, rearchiveUseCase, handoverUseCase, roles), MailJournalArchiveDownloadCell(archiveDownload),
        invoiceIdByNumber, exportService,
        MailAvailability(mailEnabled), ClockTimeSource.fixedAt(Instant.parse("2026-10-02T08:00:00Z")),
    )

    private fun enter(view: MailJournalView, query: String = ""): BeforeEnterEvent {
        val event = mock<BeforeEnterEvent>()
        whenever(event.location).thenReturn(Location("mail/journal" + if (query.isEmpty()) "" else "?$query"))
        UI.getCurrent().add(view)
        view.beforeEnter(event)
        return event
    }

    private fun lastCriteria(): MailJournalCriteria {
        val captor = argumentCaptor<MailJournalCriteria>()
        verify(journalQuery, atLeastOnce()).findPage(captor.capture())
        return captor.lastValue
    }

    private fun items(view: MailJournalView): List<MailJournalEntry> =
        (view.grid.dataProvider as ListDataProvider<MailJournalEntry>).items.toList()

    @Test
    fun `entering loads the first page newest first and fills the grid and chips`() {
        val view = newView()
        enter(view)

        val criteria = lastCriteria()
        assertEquals(MailJournalSortColumn.CREATED_AT, criteria.sort)
        assertEquals(MailSortOrder.DESC, criteria.direction)
        assertEquals(0, criteria.page)
        assertEquals(50, criteria.size)
        assertEquals(listOf(1L, 2L), items(view).map { it.id })
        assertEquals(5, view.statusChips.children.count())
        assertTrue(view.statusChips.children.toList().filterIsInstance<NativeButton>().any { it.text == "Fehlgeschlagen 1" })
    }

    @Test
    fun `a phone-width window keeps time, status, reference and actions and hides the rest`() {
        val view = newView()
        enter(view)

        view.narrowColumns!!.apply(390)

        val visible = view.grid.columns.filter { it.isVisible }.map { it.key }
        assertEquals(listOf("createdAt", "status", "referenceKey", "actions"), visible)
        view.narrowColumns!!.apply(1200)
        assertTrue(view.grid.columns.all { it.isVisible })
    }

    @Test
    fun `only archived rows render the archive download anchor`() {
        val view = newView()
        enter(view)
        val column = view.grid.getColumnByKey("archiveDownload")
        val renderer = column.renderer as com.vaadin.flow.data.renderer.ComponentRenderer<*, MailJournalEntry>

        assertTrue(renderer.createComponent(entry(1)) is com.vaadin.flow.component.html.Anchor)
        assertTrue(renderer.createComponent(entry(3, archive = MailJournalArchiveStatus.PENDING)) is com.vaadin.flow.component.html.Span)
        assertFalse(column.isSortable)
    }

    @Test
    fun `the text filter and the toolbar fields become query criteria`() {
        val view = newView()
        enter(view)

        view.filterBar.textField.value = "  kunde  "
        view.filterBar.kindFilter.value = MailTemplateKind.QUOTE_DISPATCH
        view.filterBar.archiveFilter.value = MailJournalArchiveStatus.FAILED
        view.filterBar.fromPicker.value = LocalDate.of(2026, 9, 1)
        view.filterBar.toPicker.value = LocalDate.of(2026, 9, 30)
        view.filterBar.editedOnlyToggle.value = true

        val criteria = lastCriteria()
        assertEquals("kunde", criteria.text)
        assertEquals(MailTemplateKind.QUOTE_DISPATCH, criteria.templateKind)
        assertEquals(MailJournalArchiveStatus.FAILED, criteria.archiveStatus)
        assertEquals(LocalDate.of(2026, 9, 1), criteria.from)
        assertEquals(LocalDate.of(2026, 9, 30), criteria.to)
        assertTrue(criteria.editedOnly)
        assertEquals(0, criteria.page)
        assertTrue(view.filterBar.activeFilterChip.isVisible)
        assertEquals("6 Filter aktiv", view.filterBar.activeFilterChip.text)
    }

    @Test
    fun `a status chip toggles the status filter and the counts ignore it`() {
        val view = newView()
        enter(view)

        val failedChip = view.statusChips.children.toList().filterIsInstance<NativeButton>().first { it.text.startsWith("Fehlgeschlagen") }
        failedChip._click()
        assertEquals(MailJournalStatus.FAILED, lastCriteria().status)

        view.statusChips.children.toList().filterIsInstance<NativeButton>().first { it.text.startsWith("Fehlgeschlagen") }._click()
        assertNull(lastCriteria().status)
        val counts = argumentCaptor<MailJournalCriteria>()
        verify(journalQuery, atLeastOnce()).countByStatus(counts.capture())
        assertNull(counts.lastValue.status)
    }

    @Test
    fun `every sortable column maps to its server sort column and direction`() {
        val view = newView()
        enter(view)
        val expected = mapOf(
            "createdAt" to MailJournalSortColumn.CREATED_AT,
            "status" to MailJournalSortColumn.STATUS,
            "referenceKey" to MailJournalSortColumn.REFERENCE_KEY,
            "recipient" to MailJournalSortColumn.RECIPIENT,
            "subject" to MailJournalSortColumn.SUBJECT,
            "template" to MailJournalSortColumn.TEMPLATE,
            "archiveStatus" to MailJournalSortColumn.ARCHIVE_STATUS,
        )

        expected.forEach { (key, column) ->
            val gridColumn = view.grid.getColumnByKey(key)
            assertTrue(gridColumn.isSortable, "$key must be sortable")
            view.grid.sort(listOf(GridSortOrder(gridColumn, SortDirection.ASCENDING)))
            assertEquals(column, lastCriteria().sort)
            assertEquals(MailSortOrder.ASC, lastCriteria().direction)
            view.grid.sort(listOf(GridSortOrder(gridColumn, SortDirection.DESCENDING)))
            assertEquals(MailSortOrder.DESC, lastCriteria().direction)
        }
    }

    @Test
    fun `clearing the grid sort falls back to newest first`() {
        val view = newView()
        enter(view)
        view.grid.sort(listOf(GridSortOrder(view.grid.getColumnByKey("recipient"), SortDirection.ASCENDING)))

        view.grid.sort(emptyList())

        assertEquals(MailJournalSortColumn.CREATED_AT, lastCriteria().sort)
        assertEquals(MailSortOrder.DESC, lastCriteria().direction)
    }

    @Test
    fun `the unsortable columns carry no sort`() {
        val view = newView()
        assertFalse(view.grid.getColumnByKey("retryOf").isSortable)
        assertFalse(view.grid.getColumnByKey("actions").isSortable)
        assertNull(MailJournalGridColumns.sortColumnForKey("retryOf"))
    }

    @Test
    fun `the URL restores filters sort and paging and the view writes the same state back`() {
        whenever(journalQuery.count(any())).thenReturn(80L)
        val view = newView()
        enter(
            view,
            "filter=abc&status=FAILED&archive=PENDING&kind=INVOICE_DISPATCH&from=2026-09-01&to=2026-09-30&edited=true" +
                "&sortField=RECIPIENT&sortDir=ASC&page=1&size=25",
        )

        val criteria = lastCriteria()
        assertEquals("abc", criteria.text)
        assertEquals(MailJournalStatus.FAILED, criteria.status)
        assertEquals(MailJournalArchiveStatus.PENDING, criteria.archiveStatus)
        assertEquals(MailTemplateKind.INVOICE_DISPATCH, criteria.templateKind)
        assertEquals(LocalDate.of(2026, 9, 1), criteria.from)
        assertEquals(LocalDate.of(2026, 9, 30), criteria.to)
        assertTrue(criteria.editedOnly)
        assertEquals(MailJournalSortColumn.RECIPIENT, criteria.sort)
        assertEquals(MailSortOrder.ASC, criteria.direction)
        assertEquals(1, criteria.page)
        assertEquals(25, criteria.size)
        val written = view.currentQueryParameters().parameters.mapValues { it.value.single() }
        assertEquals(
            mapOf(
                "filter" to "abc", "status" to "FAILED", "archive" to "PENDING", "kind" to "INVOICE_DISPATCH",
                "from" to "2026-09-01", "to" to "2026-09-30", "edited" to "true", "page" to "1", "size" to "25",
                "sortField" to "RECIPIENT", "sortDir" to "ASC",
            ),
            written,
        )
    }

    @Test
    fun `an edited bookmark with unknown values keeps the defaults`() {
        val view = newView()
        enter(view, "status=BOGUS&kind=NOPE&archive=X&from=notadate&to=nope&sortField=XYZ&sortDir=SIDEWAYS&size=7&page=abc")

        val criteria = lastCriteria()
        assertNull(criteria.status)
        assertNull(criteria.templateKind)
        assertNull(criteria.archiveStatus)
        assertNull(criteria.from)
        assertNull(criteria.to)
        assertEquals(MailJournalSortColumn.CREATED_AT, criteria.sort)
        assertEquals(MailSortOrder.DESC, criteria.direction)
        assertEquals(50, criteria.size)
        assertEquals(0, criteria.page)
    }

    @Test
    fun `a visit without parameters restores the filters of the session but not the page`() {
        val first = newView()
        enter(first, "filter=abc&status=SENT")
        MockVaadin.clientRoundtrip()

        val second = newView()
        enter(second)

        val criteria = lastCriteria()
        assertEquals("abc", criteria.text)
        assertEquals(MailJournalStatus.SENT, criteria.status)
        assertEquals(0, criteria.page)
    }

    @Test
    fun `the reference parameter presets the reference filter and shows its chip`() {
        val view = newView()
        enter(view, "reference=RE-2026-0042")

        assertEquals("RE-2026-0042", lastCriteria().referenceKey)
        assertTrue(view.filterBar.referenceChip.isVisible)
        assertEquals("Rechnung RE-2026-0042", view.filterBar.referenceChip.text)

        view.filterBar.referenceChip._click()

        assertNull(lastCriteria().referenceKey)
        assertFalse(view.filterBar.referenceChip.isVisible)
    }

    @Test
    fun `reset all clears every filter with a single reload`() {
        val view = newView()
        enter(view, "filter=abc&status=FAILED&reference=RE-1&edited=true")
        clearInvocations()

        view.filterBar.resetButton._click()

        val criteria = lastCriteria()
        assertEquals(MailJournalCriteria(), criteria.copy(page = 0, size = 50))
        verify(journalQuery, times(1)).findPage(any())
        assertFalse(view.filterBar.activeFilterChip.isVisible)
    }

    @Test
    fun `the active filter chip resets as well`() {
        val view = newView()
        enter(view, "filter=abc")

        view.filterBar.activeFilterChip._click()

        assertNull(lastCriteria().text)
    }

    @Test
    fun `paging and page size reload the journal`() {
        whenever(journalQuery.count(any())).thenReturn(120L)
        val view = newView()
        enter(view)

        view.pager.children.toList().filterIsInstance<Button>().first { it.text == "→" }._click()
        assertEquals(1, lastCriteria().page)

        view.pageSizeSelect.value = 25
        assertEquals(25, lastCriteria().size)
        assertEquals(0, lastCriteria().page)
    }

    @Test
    fun `clearing the page size keeps the current size`() {
        val view = newView()
        enter(view)
        clearInvocations()

        view.pageSizeSelect.value = null

        verify(journalQuery, never()).findPage(any())
    }

    @Test
    fun `a small journal disables the page size choice`() {
        val view = newView()
        enter(view)
        assertFalse(view.pageSizeSelect.isEnabled)
    }

    @Test
    fun `the reference links into the invoice when it resolves and is plain text otherwise`() {
        whenever(journalQuery.findPage(any())).thenReturn(listOf(entry(1, reference = "RE-2026-0001"), entry(2, reference = "Q-2")))
        whenever(invoiceIdByNumber.findIdsByNumbers(any())).thenReturn(mapOf("RE-2026-0001" to 77L))
        val view = newView()
        enter(view)
        MockVaadin.clientRoundtrip()

        val link = cell(view, 0, "referenceKey") as RouterLink
        assertEquals("RE-2026-0001", link.text)
        assertTrue(link.href.endsWith("invoice/77"), link.href)
        assertEquals("Q-2", (cell(view, 1, "referenceKey") as Span).text)
        verify(invoiceIdByNumber, times(1)).findIdsByNumbers(setOf("RE-2026-0001", "Q-2"))
    }

    @Test
    fun `a retry row links back to its reference and filters the chain`() {
        whenever(journalQuery.findPage(any())).thenReturn(listOf(entry(5, retryOf = 4, reference = "RE-9")))
        val view = newView()
        enter(view)
        MockVaadin.clientRoundtrip()

        (cell(view, 0, "retryOf") as Button).also { assertEquals("#4", it.text) }._click()

        assertEquals("RE-9", lastCriteria().referenceKey)
    }

    @Test
    fun `the grid renders row details with smtp result attachments and chain`() {
        val view = newView()
        enter(view)
        val first = items(view).first()
        view.grid.setDetailsVisible(first, true)
        MockVaadin.clientRoundtrip()
        assertTrue(view.grid.isDetailsVisible(first))

        val panel = MailJournalDetails(view).build(
            entry(5, retryOf = 4),
            listOf(entry(5, retryOf = 4), entry(6, retryOf = 5)),
        )
        val text = panel.element.textRecursively

        assertTrue(text.contains("250 queued as 5"), text)
        assertTrue(text.contains("<m5@example.com>"), text)
        assertTrue(text.contains("mail-journal/5/archive.tar.gz"), text)
        assertTrue(text.contains("Wiederholung von #4"), text)
        assertTrue(text.contains("wiederholt als #6"), text)
        assertTrue(text.contains("rechnung.pdf · 2,0 KB · SHA-256 aaaaaaaaaaaa…"), text)
    }

    @Test
    fun `a bare row shows no empty facts`() {
        val view = newView()
        val bare = entry(8).copy(smtpResult = null, messageId = null, archiveS3Key = null, attachments = emptyList())

        val text = MailJournalDetails(view).build(bare, emptyList()).element.textRecursively

        assertEquals("", text)
    }

    @Test
    fun `an attachment of unknown size says so`() {
        val view = newView()
        val row = entry(8).copy(attachments = listOf(MailJournalAttachment("a.pdf", "application/pdf", null, "ab")))

        val text = MailJournalDetails(view).build(row, emptyList()).element.textRecursively

        assertTrue(text.contains("a.pdf · Größe unbekannt · SHA-256 ab"), text)
    }

    @Test
    fun `retry asks for confirmation and then calls the use case`() {
        whenever(retryUseCase.retry(2L)).thenReturn(MailSendOutcome(9L, MailJournalStatus.QUEUED))
        val view = newView()
        enter(view)
        MockVaadin.clientRoundtrip()

        actionButtons(view, 1).single { it.text == "Erneut senden" }._click()
        verify(retryUseCase, never()).retry(any())
        confirmDialog()

        verify(retryUseCase).retry(2L)
        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty())
        verify(journalQuery, times(2)).findPage(any())
    }

    @Test
    fun `a refused retry is shown and the journal still reloads`() {
        whenever(retryUseCase.retry(2L)).thenThrow(MailDispatchException("only a failed row can be repeated"))
        val view = newView()
        enter(view)
        MockVaadin.clientRoundtrip()
        clearNotifications()

        actionButtons(view, 1).single { it.text == "Erneut senden" }._click()
        confirmDialog()

        MockVaadin.clientRoundtrip()
        assertEquals(1, getNotifications().size)
        verify(journalQuery, times(2)).findPage(any())
    }

    @Test
    fun `rearchive is offered only for a sent row whose archive failed and reports both outcomes`() {
        whenever(journalQuery.findPage(any())).thenReturn(
            listOf(
                entry(1, archive = MailJournalArchiveStatus.FAILED),
                entry(2, status = MailJournalStatus.FAILED, archive = MailJournalArchiveStatus.FAILED),
                entry(3),
            ),
        )
        whenever(rearchiveUseCase.rearchive(1L)).thenReturn(MailRearchiveOutcome(1L, MailJournalArchiveStatus.ARCHIVED, null))
        val view = newView()
        enter(view)
        MockVaadin.clientRoundtrip()

        assertEquals(listOf("Erneut archivieren"), actionButtons(view, 0).map { it.text })
        assertEquals(listOf("Erneut senden"), actionButtons(view, 1).map { it.text })
        assertEquals(emptyList<String>(), actionButtons(view, 2).map { it.text })
        actionButtons(view, 0).single()._click()
        confirmDialog()
        verify(rearchiveUseCase).rearchive(1L)

        whenever(rearchiveUseCase.rearchive(1L)).thenReturn(MailRearchiveOutcome(1L, MailJournalArchiveStatus.FAILED, null))
        MockVaadin.clientRoundtrip()
        actionButtons(view, 0).single()._click()
        confirmDialog()
        verify(rearchiveUseCase, times(2)).rearchive(1L)

        whenever(rearchiveUseCase.rearchive(1L)).thenReturn(MailRearchiveOutcome(1L, MailJournalArchiveStatus.FAILED, "bucket down"))
        MockVaadin.clientRoundtrip()
        actionButtons(view, 0).single()._click()
        confirmDialog()
        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty(), "the archive error text is shown to the user")
    }

    @Test
    fun `the hand-over retry is offered to administrators for a failed hand-over and reports both outcomes`() {
        whenever(roles.hasPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)).thenReturn(true)
        whenever(journalQuery.findPage(any())).thenReturn(
            listOf(
                entry(1, handover = MailJournalHandoverStatus.FAILED, handoverError = "550 mailbox full"),
                entry(2, handover = MailJournalHandoverStatus.HANDED_OVER),
                entry(3, status = MailJournalStatus.FAILED, handover = MailJournalHandoverStatus.FAILED),
            ),
        )
        whenever(handoverUseCase.retryArchiveHandover(1L)).thenReturn(ArchiveHandoverRetryOutcome(1L, MailJournalHandoverStatus.HANDED_OVER, null))
        val view = newView()
        enter(view)
        MockVaadin.clientRoundtrip()

        assertEquals(listOf("Erneut übergeben"), actionButtons(view, 0).map { it.text })
        assertEquals(emptyList<String>(), actionButtons(view, 1).map { it.text })
        assertEquals(listOf("Erneut senden"), actionButtons(view, 2).map { it.text })
        actionButtons(view, 0).single()._click()
        confirmDialog()
        verify(handoverUseCase).retryArchiveHandover(1L)
        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty())

        clearNotifications()
        whenever(handoverUseCase.retryArchiveHandover(1L)).thenReturn(ArchiveHandoverRetryOutcome(1L, MailJournalHandoverStatus.FAILED, "550 mailbox full"))
        actionButtons(view, 0).single()._click()
        confirmDialog()
        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty(), "the error text is shown to the user")
    }

    @Test
    fun `without the administrator permission no hand-over retry is offered`() {
        whenever(roles.hasPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)).thenReturn(false)
        whenever(journalQuery.findPage(any())).thenReturn(listOf(entry(1, handover = MailJournalHandoverStatus.FAILED)))
        val view = newView()
        enter(view)
        MockVaadin.clientRoundtrip()

        assertEquals(emptyList<String>(), actionButtons(view, 0).map { it.text })
    }

    @Test
    fun `the hand-over column shows the state as a symbol with a descriptive label`() {
        whenever(journalQuery.findPage(any())).thenReturn(
            listOf(
                entry(1, handover = MailJournalHandoverStatus.FAILED, handoverError = "550 mailbox full"),
                entry(2, handover = MailJournalHandoverStatus.HANDED_OVER, handoverError = "old error"),
                entry(3, handover = MailJournalHandoverStatus.FAILED, handoverError = " "),
            ),
        )
        val view = newView()
        enter(view)
        MockVaadin.clientRoundtrip()

        fun label(row: Int) = cell(view, row, "archiveHandover").element.getAttribute("aria-label")
        assertEquals("Fehlgeschlagen: Die Übergabe hat nicht geklappt. 550 mailbox full", label(0))
        assertEquals("Übergeben: Der Mailserver des Archivs hat die Kopie angenommen. Ob sie dauerhaft gespeichert ist, zeigt nur das Archiv.", label(1))
        assertEquals("Fehlgeschlagen: Die Übergabe hat nicht geklappt.", label(2))
    }

    @Test
    fun `an unreachable component is reported instead of showing stale rows`() {
        whenever(journalQuery.count(any())).thenThrow(MailDispatchException("down"))
        val view = newView()
        enter(view)

        MockVaadin.clientRoundtrip()
        assertTrue(items(view).isEmpty())
        assertFalse(getNotifications().isEmpty())
    }

    @Test
    fun `without the mail component the view forwards to the invoice list and loads nothing`() {
        val view = newView(mailEnabled = false)
        val event = enter(view)

        verify(event).forwardTo(InvoiceListView::class.java)
        verify(journalQuery, never()).findPage(any())
    }

    @Test
    fun `the export button reads the filter at download time`() {
        val view = newView()
        enter(view)

        assertEquals(1, _find<Button>().count { it.id.orElse("") == "mail-journal-export-button" })
    }

    private fun descendants(root: Component): List<Component> = root.children.toList().flatMap { listOf(it) + descendants(it) }

    private fun cell(view: MailJournalView, row: Int, key: String): Component = view.grid._getCellComponent(row, key)

    private fun actionButtons(view: MailJournalView, row: Int): List<Button> =
        descendants(cell(view, row, "actions")).filterIsInstance<Button>()

    private fun clearInvocations() = org.mockito.kotlin.clearInvocations(journalQuery)

    private fun confirmDialog() {
        val dialog = _find<ConfirmDialog>().single()
        ComponentUtil.fireEvent(dialog, ConfirmDialog.ConfirmEvent(dialog, false))
        // The real dialog closes itself on confirm; Karibu does not, and an open modal hides later toasts.
        dialog.close()
    }
}
