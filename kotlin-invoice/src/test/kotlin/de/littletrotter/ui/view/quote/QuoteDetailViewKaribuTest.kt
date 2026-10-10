package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.QuoteNoNumberRangeException
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteModuleDisabledException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AcceptQuoteUseCase
import de.littletrotter.application.quote.port.inbound.AddQuoteNoteUseCase
import de.littletrotter.application.quote.port.inbound.CancelQuoteUseCase
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteWorkbookExportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentFileQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteLogEntry
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuoteLogQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteValidationReportQueryPort
import de.littletrotter.application.quote.port.inbound.RejectQuoteUseCase
import de.littletrotter.application.quote.port.inbound.RequestQuoteInvoiceUseCase
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrder
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderStatus
import de.littletrotter.application.quote.port.inbound.RequestQuoteDocumentUseCase
import de.littletrotter.application.quote.port.inbound.ReturnQuoteToDraftUseCase
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.inbound.SendQuoteUseCase
import de.littletrotter.application.quote.port.inbound.SubmitQuoteForReviewUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.GridScrollBox
import de.littletrotter.ui.view.invoice.InvoiceListView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import com.vaadin.flow.data.provider.Query
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate

/** Stands in for the mail compose view, whose own dependencies are not part of this test. */
class ComposeStub : com.vaadin.flow.component.html.Div()

class QuoteDetailViewKaribuTest {

    private val detailQuery: QuoteDetailQueryPort = mock()
    private val logQuery: QuoteLogQueryPort = mock()
    private val submit: SubmitQuoteForReviewUseCase = mock()
    private val returnToDraft: ReturnQuoteToDraftUseCase = mock()
    private val cancel: CancelQuoteUseCase = mock()
    private val accept: AcceptQuoteUseCase = mock()
    private val reject: RejectQuoteUseCase = mock()
    private val send: SendQuoteUseCase = mock()
    private val requestInvoice: RequestQuoteInvoiceUseCase = mock()
    private val addNote: AddQuoteNoteUseCase = mock()
    private val requestDocument: RequestQuoteDocumentUseCase = mock()
    private val documentQuery: QuoteDocumentQueryPort = mock()
    private val documentFiles: QuoteDocumentFileQueryPort = mock()
    private val reports: QuoteValidationReportQueryPort = mock()
    private val attachmentQuery: QuoteAttachmentQueryPort = mock()
    private val workbookExport: QuoteWorkbookExportQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        registerQuoteRoutes()
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail())
        whenever(logQuery.log(any(), any())).thenReturn(
            listOf(QuoteLogEntry(Instant.parse("2026-10-01T09:30:00Z"), "anna", QuoteLogKind.NOTE, "Rückruf vereinbart")),
        )
        whenever(submit.submit("q-1")).thenReturn("AN-2026-0002")
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(enabled: Boolean = true, vararg granted: AppPermission = ALL_QUOTE_PERMISSIONS) = QuoteDetailView(
        detailQuery,
        QuoteLogPorts(logQuery, addNote),
        QuoteLifecyclePorts(submit, returnToDraft, cancel, accept, reject, send, requestInvoice),
        QuoteDocumentPorts(requestDocument, documentQuery, documentFiles, reports, attachmentQuery, workbookExport),
        QuoteAvailability(enabled),
        rolesWith(*granted),
        ClockTimeSource.fixedAt(Instant.parse("2026-10-02T08:00:00Z")),
    )

    private fun enter(view: QuoteDetailView, id: String? = "q-1"): BeforeEnterEvent {
        val event = quoteEvent(id, "quotes/$id")
        UI.getCurrent().add(view)
        view.beforeEnter(event)
        return event
    }

    private fun showing(detail: QuoteDetail, vararg granted: AppPermission = ALL_QUOTE_PERMISSIONS): QuoteDetailView {
        whenever(detailQuery.find("q-1")).thenReturn(detail)
        return newView(true, *granted).also { enter(it) }
    }

    private fun actionIds(view: QuoteDetailView): List<String> =
        view.hero.actions!!._find<Button>().mapNotNull { it.id.orElse(null) }.filterNot { it == "quote-export-xlsx" }

    /** Ids of the direct children of the action row; the export anchor is identified by its inner button. */
    private fun rowIds(view: QuoteDetailView): List<String> =
        view.hero.actions!!.children.map { child ->
            child.id.orElse(null) ?: child._find<Button>().firstNotNullOfOrNull { it.id.orElse(null) }.orEmpty()
        }.toList()

    private fun overflowItems(view: QuoteDetailView) =
        view.hero.actions!!._find<MenuBar>().flatMap { bar -> bar.items.flatMap { it.subMenu.items } }

    private fun overflowCaptions(view: QuoteDetailView): List<String> = overflowItems(view).map { it.text }

    private fun clickCancel() {
        val item = overflowItems(_get<QuoteDetailView>()).single()
        ComponentUtil.fireEvent(item, ClickEvent(item))
    }

    @Test
    fun `without the component or the permission the view forwards to the invoice list`() {
        val disabled = enter(newView(enabled = false))
        verify(disabled).forwardTo(InvoiceListView::class.java)

        val denied = enter(newView(true, AppPermission.QUOTE_EDIT))
        verify(denied).forwardTo(InvoiceListView::class.java)
        verify(detailQuery, never()).find(any())
    }

    @Test
    fun `an unknown offer or a missing id forwards to the offer list`() {
        whenever(detailQuery.find("q-404")).thenThrow(QuoteNotFoundException("Angebot nicht gefunden"))
        val missing = enter(newView(), "q-404")
        verify(missing).forwardTo(QuoteListView::class.java)
        assertTrue(notificationTexts().any { it.contains("Angebot nicht gefunden") })

        val noId = enter(newView(), null)
        verify(noId).forwardTo(QuoteListView::class.java)
    }

    @Test
    fun `the totals sit at the right edge in the overview and the items tab`() {
        val view = newView()
        enter(view)

        assertFalse(view.overview.totals.classNames.contains("quote-totals--end"))
        assertFalse(view.itemsTotals.classNames.contains("quote-totals--end"))
        val itemsTab = view.itemsTotals.parent.get() as com.vaadin.flow.component.orderedlayout.VerticalLayout
        assertEquals(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END, itemsTab.getAlignSelf(view.itemsTotals))
    }

    @Test
    fun `the read only items grid scrolls sideways in its own box inside the items tab`() {
        val view = newView()
        enter(view)

        val box = view.items.grid.parent.get() as GridScrollBox
        assertEquals(view.itemsTotals.parent.get(), box.parent.get())
        assertNull(view.items.grid.width)
    }

    @Test
    fun `header items totals and the documents area are shown`() {
        val view = newView()
        enter(view)

        assertEquals("Angebot AN-2026-0001", view.hero.title.text)
        val header = visibleText(view.overview)
        assertTrue(header.contains("Muster GmbH"), header)
        assertTrue(header.contains("Köln"), header)
        assertTrue(header.contains("Beratung Oktober"), header)
        assertTrue(header.contains("Dokumentsprache"), header)
        assertEquals(2, (view.items.grid.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<*>).items.size)
        val totals = visibleText(view.overview.totals)
        assertTrue(totals.contains("297,50"), totals)
        assertTrue(totals.contains("Umsatzsteuer 19 % auf"), totals)
        assertFalse(totals.contains("Bruttopreise angezeigt"))
        assertEquals(
            listOf("Übersicht", "Positionen", "Belege", "E-Angebot", "Protokoll"),
            (0 until view.tabs.tabCount).map { view.tabs.getTabAt(it).label },
        )
        assertTrue(view.documents.emptyState.isVisible)
        verify(documentQuery).documents("q-1")
    }

    @Test
    fun `the item description carries article number and the customers own reference`() {
        val view = newView()
        enter(view)

        val cell = view.items.grid._getCellComponent(0, "description")
        assertEquals(
            listOf("Beratung 1", "A-1 · Ihre Artikelnummer: K-77 · Ihre Artikelbezeichnung: Kundenwerk"),
            descendants(cell).filterIsInstance<com.vaadin.flow.component.html.Span>().map { it.text },
        )
        assertEquals("Stunde (h)", view.items.grid._getFormattedRow(0)[4])
        assertEquals("2,5", visibleText(view.items.grid._getCellComponent(0, "quantity")))
        assertEquals("19 %", visibleText(view.items.grid._getCellComponent(0, "vat")))
    }

    @Test
    fun `the read only grid has the invoices columns without actions and plain numbers`() {
        val view = newView().also { enter(it) }

        assertEquals(
            listOf("position", "description", "attachments", "quantity", "unit", "unitPrice", "vat", "net"),
            view.items.grid.columns.map { it.key },
        )
        assertTrue(descendants(view.items.grid._getCellComponent(0, "quantity")).none { it is com.vaadin.flow.component.textfield.NumberField })
        assertEquals("100,00 EUR", visibleText(view.items.grid._getCellComponent(0, "unitPrice")).replace('\u00A0', ' '))
    }

    @Test
    fun `the paperclip shows the count and opens a download only dialog`() {
        val file = QuoteAttachment("f-1", QuoteAttachmentKind.CUSTOMER, "zeichnung.pdf", "application/pdf", 10, "a".repeat(64), Instant.parse("2026-10-01T09:00:00Z"), "i-1", 1)
        whenever(attachmentQuery.attachments("q-1")).thenReturn(listOf(file))
        val view = newView().also { enter(it) }

        val clip = view.items.grid._getCellComponent(0, "attachments") as Button
        assertEquals("1 Anlage(n)", clip.text)
        assertEquals("Keine Anlage", (view.items.grid._getCellComponent(1, "attachments") as Button).text)
        clip._click()

        val dialog = _find<com.vaadin.flow.component.dialog.Dialog>().single { it.isOpened }
        assertTrue(dialog.headerTitle.contains("Position 1"))
        assertEquals(listOf("Schließen"), footerComponents(dialog).flatMap { descendants(it) + it }.filterIsInstance<Button>().map { it.text })
        assertTrue(dialog._find<com.vaadin.flow.component.upload.Upload>().isEmpty())
        assertEquals(1, dialog._find<com.vaadin.flow.component.grid.Grid<*>>().single().dataCommunicator.itemCount)
    }

    @Test
    fun `gross prices are hinted and a draft without number reads as draft`() {
        val view = showing(quoteDetail(number = null) { it.copy(showsGrossPrices = true) })

        assertEquals("Angebot Entwurf", view.hero.title.text)
        assertTrue(visibleText(view.overview.totals).contains("Bruttopreise angezeigt"))
    }

    @Test
    fun `accepted and rejected offers show their details`() {
        val accepted = showing(
            quoteDetail(QuoteStatus.ACCEPTED) {
                it.copy(acceptedOn = LocalDate.of(2026, 10, 5), customerOrderReference = "B-77", invoiceNumber = "RE-1")
            },
        )
        val text = visibleText(accepted.overview)
        assertTrue(text.contains("B-77"), text)
        assertTrue(text.contains("RE-1"), text)

        val rejected = showing(quoteDetail(QuoteStatus.REJECTED) { it.copy(rejectionReason = "zu teuer") })
        assertTrue(visibleText(rejected.overview).contains("zu teuer"))
    }

    @Test
    fun `the offered actions follow status and permission`() {
        assertEquals(
            listOf("quote-action-SUBMIT", "quote-action-EDIT"),
            actionIds(showing(quoteDetail(QuoteStatus.DRAFT))),
        )
        assertEquals(listOf("Annullieren"), overflowCaptions(showing(quoteDetail(QuoteStatus.DRAFT))))
        assertEquals(
            listOf("quote-action-SEND", "quote-action-RETURN_TO_DRAFT", "quote-action-EDIT"),
            actionIds(showing(quoteDetail(QuoteStatus.REVIEW))),
        )
        assertEquals(
            listOf("quote-action-REQUEST_INVOICE", "quote-action-REJECT", "quote-action-ACCEPT"),
            actionIds(showing(quoteDetail(QuoteStatus.SENT))),
        )
        assertEquals(listOf("quote-action-REQUEST_INVOICE"), actionIds(showing(quoteDetail(QuoteStatus.ACCEPTED))))
        listOf(QuoteStatus.INVOICED, QuoteStatus.REJECTED, QuoteStatus.CANCELLED).forEach {
            assertTrue(actionIds(showing(quoteDetail(it))).isEmpty(), it.name)
        }
        assertTrue(actionIds(showing(quoteDetail(QuoteStatus.DRAFT), AppPermission.QUOTE_VIEW)).isEmpty())
        assertTrue(overflowCaptions(showing(quoteDetail(QuoteStatus.SENT))).isEmpty())
    }

    @Test
    fun `the buttons are captioned by what they do`() {
        val review = showing(quoteDetail(QuoteStatus.REVIEW))
        assertEquals("E-Mail senden", review.hero.actions!!._get<Button> { id = "quote-action-SEND" }.text)

        val sent = showing(quoteDetail(QuoteStatus.SENT))
        assertEquals("Annehmen", sent.hero.actions!!._get<Button> { id = "quote-action-ACCEPT" }.text)
        assertEquals("Ablehnen", sent.hero.actions!!._get<Button> { id = "quote-action-REJECT" }.text)
    }

    @Test
    fun `the Excel export sits in the action row right before the mail send button`() {
        val ids = rowIds(showing(quoteDetail(QuoteStatus.REVIEW)))
        assertEquals(ids.indexOf("quote-action-SEND") - 1, ids.indexOf("quote-export-xlsx"), ids.toString())
        assertEquals(1, ids.count { it == "quote-export-xlsx" })

        val sentIds = rowIds(showing(quoteDetail(QuoteStatus.SENT)))
        assertEquals(sentIds.indexOf("quote-action-ACCEPT") - 1, sentIds.indexOf("quote-export-xlsx"), sentIds.toString())
    }

    @Test
    fun `the mail button looks like the invoices and every action button carries an icon`() {
        val review = showing(quoteDetail(QuoteStatus.REVIEW))
        val send = review.hero.actions!!._get<Button> { id = "quote-action-SEND" }
        assertEquals(ActionIcons.SEND_MAIL.create().element.getAttribute("icon"), send.icon.element.getAttribute("icon"))
        assertTrue(send.classNames.containsAll(listOf("ch-action-btn", "ch-btn--mail")))

        val draft = showing(quoteDetail(QuoteStatus.DRAFT))
        listOf("quote-action-EDIT", "quote-action-SUBMIT").forEach { buttonId ->
            val button = draft.hero.actions!!._get<Button> { id = buttonId }
            assertTrue(button.classNames.contains("ch-action-btn"), buttonId)
            assertTrue(button.icon != null, buttonId)
        }
    }

    @Test
    fun `exactly one action is primary whenever the status offers a way forward`() {
        val primaries = QuoteStatus.entries.map { status ->
            val detail = quoteDetail(status)
            detail to QuoteActionRules.layout(QuoteActionRules.available(detail, mayEdit = true, maySend = true))
        }
        primaries.forEach { (detail, layout) ->
            val all = listOfNotNull(layout.primary) + layout.secondary + layout.overflow
            assertEquals(QuoteActionRules.available(detail, true, true).toSet(), all.toSet())
            assertEquals(all.size, all.toSet().size)
            assertTrue(QuoteAction.CANCEL !in listOfNotNull(layout.primary) + layout.secondary)
            if (all.isNotEmpty() && all != listOf(QuoteAction.CANCEL)) assertTrue(layout.primary != null, "$detail")
        }
        val sentPrimary = primaries.first { it.first.status == QuoteStatus.SENT }
        assertEquals(QuoteAction.ACCEPT, sentPrimary.second.primary)
        assertEquals(listOf(QuoteAction.REJECT), sentPrimary.second.secondary)
    }

    @Test
    fun `the primary button carries the primary theme and the quiet ones do not`() {
        val view = showing(quoteDetail(QuoteStatus.DRAFT))
        val edit = view.hero.actions!!._get<Button> { id = "quote-action-EDIT" }
        val submit = view.hero.actions!!._get<Button> { id = "quote-action-SUBMIT" }

        assertTrue(edit.themeNames.contains("primary"))
        assertTrue(submit.themeNames.contains("tertiary"))
        assertFalse(submit.themeNames.contains("primary"))
    }

    @Test
    fun `the hero shows the status chip and the back arrow leads to the list`() {
        val view = showing(quoteDetail(QuoteStatus.SENT))

        assertEquals("Versendet", visibleText(view.hero.chips).trim())
        assertEquals("Angenommen", visibleText(showing(quoteDetail(QuoteStatus.ACCEPTED)).hero.chips).trim())
        assertTrue(view.hero.classNames.contains("cockpit-hero--sent"))

        view.hero._get<Button> { id = "quote-detail-back" }._click()
        assertEquals("quotes", currentPath())
    }

    @Test
    fun `edit opens the editor`() {
        showing(quoteDetail())

        _get<Button> { id = "quote-action-EDIT" }._click()

        assertEquals("quotes/q-1/edit", currentPath())
    }

    @Test
    fun `the overview and the positions tab offer edit shortcuts only while editing is allowed`() {
        for (status in listOf(QuoteStatus.DRAFT, QuoteStatus.REVIEW)) {
            val view = showing(quoteDetail(status))
            assertTrue(view.overview._find<Button> { id = "quote-overview-edit" }.isNotEmpty(), status.name)
            assertTrue(view.editItemsButton.parent.get().isVisible, status.name)
        }
        for (view in listOf(showing(quoteDetail(QuoteStatus.SENT)), showing(quoteDetail(), AppPermission.QUOTE_VIEW))) {
            assertTrue(view.overview._find<Button> { id = "quote-overview-edit" }.isEmpty())
            assertFalse(view.editItemsButton.parent.get().isVisible)
        }
    }

    @Test
    fun `the overview shortcut opens the header and the positions shortcut the positions`() {
        val view = showing(quoteDetail())

        view.overview._get<Button> { id = "quote-overview-edit" }._click()
        assertEquals("quotes/q-1/edit", currentPath())
        assertTrue(UI.getCurrent().internals.activeViewLocation.queryParameters.parameters.isEmpty())

        view.tabs.selectedIndex = 1
        view.editItemsButton._click()
        assertEquals("quotes/q-1/edit", currentPath())
        assertEquals(listOf("items"), UI.getCurrent().internals.activeViewLocation.queryParameters.parameters["tab"])
    }

    @Test
    fun `submitting for review reports the number and reloads`() {
        showing(quoteDetail())

        _get<Button> { id = "quote-action-SUBMIT" }._click()

        verify(submit).submit("q-1")
        verify(detailQuery, times(2)).find("q-1")
        assertTrue(notificationTexts().any { it.contains("AN-2026-0002") })
    }

    @Test
    fun `a submission without a number range shows the translated hint`() {
        whenever(submit.submit(any())).thenThrow(QuoteNoNumberRangeException())
        showing(quoteDetail())

        _get<Button> { id = "quote-action-SUBMIT" }._click()

        assertTrue(notificationTexts().any { it.contains("Nummernkreis") }, notificationTexts().toString())
        assertFalse(notificationTexts().any { it.contains("quote.error") })
    }

    @Test
    fun `a submission refused because the module is switched off shows the translated message`() {
        whenever(submit.submit(any())).thenThrow(QuoteModuleDisabledException())
        showing(quoteDetail())

        _get<Button> { id = "quote-action-SUBMIT" }._click()

        assertTrue(notificationTexts().any { it.contains("deaktiviert") }, notificationTexts().toString())
        assertFalse(notificationTexts().any { it.contains("settings.flag.quote") })
    }

    @Test
    fun `a refused submission only shows the reason`() {
        whenever(submit.submit(any())).thenThrow(QuoteServiceException("Pflichtangaben fehlen"))
        showing(quoteDetail())

        _get<Button> { id = "quote-action-SUBMIT" }._click()

        verify(detailQuery, times(1)).find("q-1")
        assertTrue(notificationTexts().any { it.contains("Pflichtangaben fehlen") })
    }

    @Test
    fun `sending is offered only in review and only with the send permission`() {
        assertEquals(
            listOf("quote-action-RETURN_TO_DRAFT", "quote-action-EDIT"),
            actionIds(showing(quoteDetail(QuoteStatus.REVIEW), AppPermission.QUOTE_VIEW, AppPermission.QUOTE_EDIT)),
        )
        assertEquals(
            listOf("quote-action-SEND"),
            actionIds(showing(quoteDetail(QuoteStatus.REVIEW), AppPermission.QUOTE_VIEW, AppPermission.QUOTE_SEND)),
        )
        assertTrue(actionIds(showing(quoteDetail(QuoteStatus.DRAFT), AppPermission.QUOTE_VIEW, AppPermission.QUOTE_SEND)).isEmpty())
    }

    @Test
    fun `a composed hand-over navigates to the mail compose page`() {
        RouteConfiguration.forSessionScope().setRoute("mail/compose/:draftId", ComposeStub::class.java)
        whenever(send.send("q-1")).thenReturn(QuoteDispatchHandover.Composed("d-1", "mail/compose/d-1"))
        showing(quoteDetail(QuoteStatus.REVIEW))

        _get<Button> { id = "quote-action-SEND" }._click()

        verify(send).send("q-1")
        assertEquals("mail/compose/d-1", currentPath())
    }

    @Test
    fun `a pending dispatch copy shows the notice and reloads`() {
        whenever(send.send("q-1")).thenReturn(QuoteDispatchHandover.DocumentPending("doc-1"))
        showing(quoteDetail(QuoteStatus.REVIEW))

        _get<Button> { id = "quote-action-SEND" }._click()

        verify(detailQuery, times(2)).find("q-1")
        assertTrue(notificationTexts().any { it.contains("Versandexemplar wird erstellt") })
    }

    @Test
    fun `a refused send only shows the reason`() {
        whenever(send.send(any())).thenThrow(QuoteServiceException("Keine Kunden-E-Mail"))
        showing(quoteDetail(QuoteStatus.REVIEW))

        _get<Button> { id = "quote-action-SEND" }._click()

        verify(detailQuery, times(1)).find("q-1")
        assertTrue(notificationTexts().any { it.contains("Keine Kunden-E-Mail") })
    }

    @Test
    fun `returning to draft needs a note`() {
        showing(quoteDetail(QuoteStatus.REVIEW))
        _get<Button> { id = "quote-action-RETURN_TO_DRAFT" }._click()

        val dialog = _get<QuoteTextDialog>()
        assertFalse(dialog.confirmButton.isEnabled)
        dialog.textArea.value = "  Preis prüfen  "
        assertTrue(dialog.confirmButton.isEnabled)
        dialog.confirmButton._click()

        verify(returnToDraft).returnToDraft("q-1", "Preis prüfen")
        assertFalse(dialog.isOpened)
        verify(detailQuery, times(2)).find("q-1")
    }

    @Test
    fun `cancelling needs a reason and stays open when refused`() {
        whenever(cancel.cancel(any(), any())).thenThrow(QuoteServiceException("Nicht mehr annullierbar"))
        showing(quoteDetail())
        clickCancel()

        val dialog = _get<QuoteTextDialog>()
        dialog.textArea.value = "Kunde springt ab"
        dialog.confirmButton._click()

        verify(cancel).cancel("q-1", "Kunde springt ab")
        assertTrue(dialog.isOpened)
        assertTrue(notificationTexts().any { it.contains("Nicht mehr annullierbar") })

        dialog.textArea.value = "  "
        assertFalse(dialog.confirmButton.isEnabled)
    }

    @Test
    fun `cancelling succeeds and reloads`() {
        showing(quoteDetail())
        clickCancel()
        val dialog = _get<QuoteTextDialog>()
        dialog.textArea.value = "Grund"
        dialog.confirmButton._click()

        verify(cancel).cancel("q-1", "Grund")
        assertFalse(dialog.isOpened)
    }

    @Test
    fun `accepting records the date and the order number`() {
        showing(quoteDetail(QuoteStatus.SENT))
        _get<Button> { id = "quote-action-ACCEPT" }._click()

        val dialog = _get<QuoteAcceptDialog>()
        assertEquals(LocalDate.of(2026, 10, 2), dialog.acceptedOn.value)
        dialog.orderReference.value = " B-1 "
        dialog.confirmButton._click()

        verify(accept).accept("q-1", LocalDate.of(2026, 10, 2), "B-1")
        assertFalse(dialog.isOpened)
    }

    @Test
    fun `accepting without an order number passes null and a cleared date blocks`() {
        showing(quoteDetail(QuoteStatus.SENT))
        _get<Button> { id = "quote-action-ACCEPT" }._click()
        val dialog = _get<QuoteAcceptDialog>()

        dialog.acceptedOn.value = null
        assertFalse(dialog.confirmButton.isEnabled)
        dialog.acceptedOn.value = LocalDate.of(2026, 10, 3)
        assertTrue(dialog.confirmButton.isEnabled)
        dialog.confirmButton._click()

        verify(accept).accept("q-1", LocalDate.of(2026, 10, 3), null)
    }

    @Test
    fun `rejecting needs a reason`() {
        showing(quoteDetail(QuoteStatus.SENT))
        _get<Button> { id = "quote-action-REJECT" }._click()
        val dialog = _get<QuoteRejectDialog>()

        assertFalse(dialog.confirmButton.isEnabled)
        dialog.reason.value = "  "
        assertFalse(dialog.confirmButton.isEnabled)
        dialog.reason.value = " zu teuer "
        assertTrue(dialog.confirmButton.isEnabled)
        dialog.confirmButton._click()

        verify(reject).reject("q-1", "zu teuer")
        assertFalse(dialog.isOpened)
    }

    @Test
    fun `the dialogs close without a call when cancelled`() {
        showing(quoteDetail(QuoteStatus.SENT))
        _get<Button> { id = "quote-action-ACCEPT" }._click()
        val acceptDialog = _get<QuoteAcceptDialog>()
        footerComponents(acceptDialog).flatMap { descendants(it) + it }.filterIsInstance<Button>().first { it.text == "Abbrechen" }._click()
        assertFalse(acceptDialog.isOpened)

        _get<Button> { id = "quote-action-REJECT" }._click()
        val rejectDialog = _get<QuoteRejectDialog>()
        footerComponents(rejectDialog).flatMap { descendants(it) + it }.filterIsInstance<Button>().first { it.text == "Abbrechen" }._click()
        assertFalse(rejectDialog.isOpened)

        verify(accept, never()).accept(any(), any(), anyOrNull())
        verify(reject, never()).reject(any(), any())
    }

    @Test
    fun `a refused acceptance keeps the dialog open`() {
        whenever(accept.accept(any(), any(), anyOrNull())).thenThrow(QuoteServiceException("Angebot nicht versendet"))
        showing(quoteDetail(QuoteStatus.SENT))
        _get<Button> { id = "quote-action-ACCEPT" }._click()
        val dialog = _get<QuoteAcceptDialog>()
        dialog.confirmButton._click()

        assertTrue(dialog.isOpened)
        assertTrue(notificationTexts().any { it.contains("Angebot nicht versendet") })
    }

    @Test
    fun `a refused rejection keeps the dialog open`() {
        whenever(reject.reject(any(), any())).thenThrow(QuoteServiceException("Angebot nicht versendet"))
        showing(quoteDetail(QuoteStatus.SENT))
        _get<Button> { id = "quote-action-REJECT" }._click()
        val dialog = _get<QuoteRejectDialog>()
        dialog.reason.value = "zu teuer"
        dialog.confirmButton._click()

        assertTrue(dialog.isOpened)
        assertTrue(notificationTexts().any { it.contains("Angebot nicht versendet") })
    }

    @Test
    fun `the protocol lists entries`() {
        val view = newView()
        enter(view)

        assertEquals(listOf("Rückruf vereinbart"), view.log.grid.dataProvider.fetch(Query()).map { it.text }.toList())
        assertFalse(view.log.emptyState.isVisible)
        verify(logQuery).log("q-1", emptySet())
    }

    @Test
    fun `an empty protocol shows its empty state`() {
        whenever(logQuery.log(any(), any())).thenReturn(emptyList())
        val view = newView()
        enter(view)

        assertTrue(view.log.emptyState.isVisible)
    }

    @Test
    fun `viewers cannot add notes`() {
        val view = newView(true, AppPermission.QUOTE_VIEW)
        enter(view)

        assertFalse(view.log.noteButton.isVisible)
    }

    @Test
    fun `optional data may be absent everywhere`() {
        val bare = quoteDetail(QuoteStatus.ACCEPTED) {
            it.copy(
                subject = null,
                party = quoteParty().copy(postalCode = null, city = null, street = null, customerNumber = null, vatId = null),
                items = listOf(quoteItem(1).copy(articleNumber = null, customerReference = null, customerArticleName = null)),
            )
        }
        val view = showing(bare)

        assertFalse(visibleText(view.overview).contains("Betreff"))
        assertEquals(
            listOf("Beratung 1"),
            descendants(view.items.grid._getCellComponent(0, "description")).filterIsInstance<com.vaadin.flow.component.html.Span>().map { it.text },
        )
        val rejected = showing(quoteDetail(QuoteStatus.REJECTED))
        assertFalse(visibleText(rejected.overview).contains("Ablehnungsgrund"))
    }

    @Test
    fun `an unreachable component while reloading after an action keeps the page`() {
        showing(quoteDetail())
        whenever(detailQuery.find("q-1")).thenThrow(QuoteServiceException("Komponente nicht erreichbar"))

        _get<Button> { id = "quote-action-SUBMIT" }._click()

        verify(submit).submit("q-1")
        assertTrue(notificationTexts().any { it.contains("Komponente nicht erreichbar") })
    }

    @Test
    fun `a cleared date cannot be confirmed even programmatically`() {
        showing(quoteDetail(QuoteStatus.SENT))
        _get<Button> { id = "quote-action-ACCEPT" }._click()
        val dialog = _get<QuoteAcceptDialog>()
        dialog.acceptedOn.value = null

        fireClick(dialog.confirmButton)

        verify(accept, never()).accept(any(), any(), anyOrNull())
        assertTrue(dialog.isOpened)
    }

    @Test
    fun `an unreachable protocol shows an empty grid and the reason`() {
        whenever(logQuery.log(any(), any())).thenThrow(QuoteServiceException("Logbuch nicht erreichbar"))
        val view = newView()
        enter(view)

        assertTrue(view.log.emptyState.isVisible)
        assertTrue(notificationTexts().any { it.contains("Logbuch nicht erreichbar") })
    }

    private fun order(status: QuoteInvoiceOrderStatus, modify: (QuoteInvoiceOrder) -> QuoteInvoiceOrder = { it }) =
        modify(QuoteInvoiceOrder("o-1", status, Instant.parse("2026-10-02T07:15:00Z")))

    @Test
    fun `the invoice order action needs the decision right and the right to create invoices`() {
        val sent = quoteDetail(QuoteStatus.SENT)
        val ids = { permissions: Array<AppPermission> -> actionIds(showing(sent, *permissions)) }

        assertTrue("quote-action-REQUEST_INVOICE" in ids(arrayOf(AppPermission.QUOTE_VIEW, AppPermission.QUOTE_EDIT, AppPermission.INVOICE_FULL_EDIT)))
        assertFalse("quote-action-REQUEST_INVOICE" in ids(arrayOf(AppPermission.QUOTE_VIEW, AppPermission.QUOTE_EDIT)))
        assertFalse("quote-action-REQUEST_INVOICE" in ids(arrayOf(AppPermission.QUOTE_VIEW, AppPermission.INVOICE_FULL_EDIT)))
    }

    @Test
    fun `the invoice order action is offered for sent and accepted offers without an open order only`() {
        QuoteStatus.entries.forEach { status ->
            val offered = "quote-action-REQUEST_INVOICE" in actionIds(showing(quoteDetail(status)))
            assertEquals(status == QuoteStatus.SENT || status == QuoteStatus.ACCEPTED, offered, status.name)
        }
        listOf(QuoteInvoiceOrderStatus.REQUESTED, QuoteInvoiceOrderStatus.CREATED).forEach {
            val detail = quoteDetail(QuoteStatus.ACCEPTED) { d -> d.copy(invoiceOrder = order(it)) }
            assertFalse("quote-action-REQUEST_INVOICE" in actionIds(showing(detail)), it.name)
        }
        val refused = quoteDetail(QuoteStatus.ACCEPTED) { it.copy(invoiceOrder = order(QuoteInvoiceOrderStatus.REJECTED)) }
        assertEquals("Erneut beauftragen", showing(refused).hero.actions!!._get<Button> { id = "quote-action-REQUEST_INVOICE" }.text)
    }

    @Test
    fun `ordering from a sent offer asks for the acceptance day and passes everything on`() {
        whenever(requestInvoice.request(any(), any())).thenReturn("o-1")
        showing(quoteDetail(QuoteStatus.SENT) { it.copy(customerOrderReference = "B-5") })
        _get<Button> { id = "quote-action-REQUEST_INVOICE" }._click()

        val dialog = _get<QuoteInvoiceOrderDialog>()
        assertTrue(dialog.acceptedOn.isVisible)
        assertEquals(LocalDate.of(2026, 10, 2), dialog.acceptedOn.value)
        assertEquals("B-5", dialog.orderReference.value)
        assertTrue(visibleText(dialog.hint).contains("Die Rechnung erhält ihre Nummer bei Anlage des Entwurfs."))
        dialog.periodFrom.value = LocalDate.of(2026, 9, 1)
        dialog.periodTo.value = LocalDate.of(2026, 9, 30)
        dialog.confirmButton._click()

        verify(requestInvoice).request("q-1", QuoteInvoiceOrderRequest(LocalDate.of(2026, 10, 2), "B-5", LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
        assertFalse(dialog.isOpened)
    }

    @Test
    fun `ordering from an accepted offer does not ask for the acceptance day`() {
        whenever(requestInvoice.request(any(), any())).thenReturn("o-1")
        showing(quoteDetail(QuoteStatus.ACCEPTED))
        _get<Button> { id = "quote-action-REQUEST_INVOICE" }._click()

        val dialog = _get<QuoteInvoiceOrderDialog>()
        assertFalse(dialog.acceptedOn.isVisible)
        dialog.periodFrom.value = LocalDate.of(2026, 9, 1)
        dialog.periodTo.value = LocalDate.of(2026, 9, 30)
        dialog.confirmButton._click()

        verify(requestInvoice).request("q-1", QuoteInvoiceOrderRequest(null, null, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
    }

    @Test
    fun `the order dialog refuses inconsistent dates and sends nothing`() {
        showing(quoteDetail(QuoteStatus.ACCEPTED))
        _get<Button> { id = "quote-action-REQUEST_INVOICE" }._click()
        val dialog = _get<QuoteInvoiceOrderDialog>()

        dialog.periodTo.value = LocalDate.of(2026, 9, 15)
        dialog.confirmButton._click()
        assertTrue(dialog.error.isVisible)
        assertEquals("Der Leistungszeitraum braucht Beginn und Ende.", dialog.error.text)

        dialog.periodFrom.value = LocalDate.of(2026, 9, 1)
        dialog.periodTo.value = LocalDate.of(2026, 8, 1)
        dialog.confirmButton._click()
        assertEquals("Das Ende des Leistungszeitraums liegt vor dem Beginn.", dialog.error.text)

        verify(requestInvoice, never()).request(any(), any())
        assertTrue(dialog.isOpened)
    }

    @Test
    fun `a refused order is ordered again with its dates prefilled`() {
        whenever(requestInvoice.request(any(), any())).thenReturn("o-2")
        val refused = order(QuoteInvoiceOrderStatus.REJECTED) {
            it.copy(servicePeriodFrom = LocalDate.of(2026, 9, 1), servicePeriodTo = LocalDate.of(2026, 9, 30), rejectionReason = "kein Nummernkreis")
        }
        showing(quoteDetail(QuoteStatus.ACCEPTED) { it.copy(invoiceOrder = refused) })
        _get<Button> { id = "quote-action-REQUEST_INVOICE" }._click()

        val dialog = _get<QuoteInvoiceOrderDialog>()
        assertEquals(LocalDate.of(2026, 9, 1), dialog.periodFrom.value)
        assertEquals(LocalDate.of(2026, 9, 30), dialog.periodTo.value)
        dialog.confirmButton._click()

        verify(requestInvoice).request("q-1", QuoteInvoiceOrderRequest(null, null, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
    }

    @Test
    fun `a refusal of the offer component keeps the order dialog open`() {
        whenever(requestInvoice.request(any(), any())).thenThrow(QuoteServiceException("abgelehnt"))
        showing(quoteDetail(QuoteStatus.ACCEPTED))
        _get<Button> { id = "quote-action-REQUEST_INVOICE" }._click()
        val dialog = _get<QuoteInvoiceOrderDialog>()

        dialog.confirmButton._click()

        assertTrue(dialog.isOpened)
    }

    @Test
    fun `no order shows no status line`() {
        assertFalse(showing(quoteDetail(QuoteStatus.SENT)).orderStatus.isVisible)
    }

    @Test
    fun `a pending order shows since when it waits`() {
        val view = showing(quoteDetail(QuoteStatus.ACCEPTED) { it.copy(invoiceOrder = order(QuoteInvoiceOrderStatus.REQUESTED)) })

        assertTrue(view.orderStatus.isVisible)
        assertTrue(visibleText(view.orderStatus).trim().startsWith("Rechnungsauftrag läuft seit"), visibleText(view.orderStatus))
    }

    @Test
    fun `an answered order links to the invoice`() {
        val answered = order(QuoteInvoiceOrderStatus.CREATED) { it.copy(invoiceId = 42L, invoiceNumber = "RE-2026-0042") }
        val view = showing(quoteDetail(QuoteStatus.INVOICED) { it.copy(invoiceOrder = answered) })

        val link = view.orderStatus._get<Button> { id = "quote-invoice-order-open" }
        assertEquals("Rechnung RE-2026-0042 öffnen", link.text)
    }

    @Test
    fun `clicking the invoice link opens the invoice`() {
        RouteConfiguration.forSessionScope().setRoute("invoice/:id", ComposeStub::class.java)
        val answered = order(QuoteInvoiceOrderStatus.CREATED) { it.copy(invoiceId = 42L, invoiceNumber = "RE-2026-0042") }
        val view = showing(quoteDetail(QuoteStatus.INVOICED) { it.copy(invoiceOrder = answered) })

        view.orderStatus._get<Button> { id = "quote-invoice-order-open" }._click()

        assertEquals("invoice/42", currentPath())
    }

    @Test
    fun `an answered order without invoice id stays on the page`() {
        val answered = order(QuoteInvoiceOrderStatus.CREATED)
        val view = showing(quoteDetail(QuoteStatus.INVOICED) { it.copy(invoiceOrder = answered) })

        view.orderStatus._get<Button> { id = "quote-invoice-order-open" }._click()

        assertTrue(view.orderStatus.isVisible)
    }

    @Test
    fun `a refused order shows its reason`() {
        val refused = order(QuoteInvoiceOrderStatus.REJECTED) { it.copy(rejectionReason = "kein Nummernkreis") }
        val view = showing(quoteDetail(QuoteStatus.ACCEPTED) { it.copy(invoiceOrder = refused) })

        assertEquals("Abgelehnt: kein Nummernkreis", visibleText(view.orderStatus).trim())
    }

    @Test
    fun `the accept dialog says that accepting creates no invoice`() {
        showing(quoteDetail(QuoteStatus.SENT))
        _get<Button> { id = "quote-action-ACCEPT" }._click()

        assertTrue(visibleText(_get<QuoteAcceptDialog>().hint).contains("legt keine Rechnung an"))
    }
}
