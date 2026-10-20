package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._doubleClickItem
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.github.mvysny.kaributesting.v10._upload
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentCommand
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderUseCase
import de.littletrotter.application.quote.port.inbound.EditQuoteItemsUseCase
import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.QuoteByNumberQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteHeaderField
import de.littletrotter.application.quote.port.inbound.QuoteHeaderFieldQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.RemoveQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile
import de.littletrotter.application.security.AppPermission
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.ui.component.GridScrollBox
import de.littletrotter.ui.view.invoice.InvoiceListView
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class QuoteEditorViewKaribuTest {

    private val detailQuery: QuoteDetailQueryPort = mock()
    private val byNumberQuery: QuoteByNumberQueryPort = byNumberAnsweringLike(detailQuery)
    private val headerFields: QuoteHeaderFieldQueryPort = mock()
    private val articles: QuoteArticleLookupQueryPort = mock()
    private val attachmentsQuery: QuoteAttachmentQueryPort = mock()
    private val editHeader: EditQuoteHeaderUseCase = mock()
    private val editItems: EditQuoteItemsUseCase = mock()
    private val attach: AttachQuoteFileUseCase = mock()
    private val removeAttachment: RemoveQuoteAttachmentUseCase = mock()
    private val assignAttachment: AssignQuoteAttachmentUseCase = mock()
    private val library: de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort = mock()
    private val attachLibrary: de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentUseCase = mock()
    private val submit: de.littletrotter.application.quote.port.inbound.SubmitQuoteForReviewUseCase = mock()

    private val article = QuoteArticleOption(
        "a-9", "A-9", "Workshop", InvoiceItemCategory.SERVICE, InvoiceItemUnit.DAY, Money.of("800.00"), VatCategory.STANDARD, BigDecimal("19.00"), customerReference = "K-9", customerArticleName = "Kundenwerk",
    )
    private val attachment = QuoteAttachment(
        "f-1", QuoteAttachmentKind.CUSTOMER, "angebot.pdf", "application/pdf", 2048, "a".repeat(64), Instant.parse("2026-10-01T09:00:00Z"),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        registerQuoteRoutes()
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail())
        whenever(headerFields.headerFields("q-1")).thenReturn(
            listOf(QuoteHeaderField(13, mandatory = true), QuoteHeaderField(10, mandatory = true, defaultValue = "LW-1"), QuoteHeaderField(12, mandatory = false)),
        )
        whenever(articles.articles(any(), any(), any(), any())).thenReturn(listOf(article))
        whenever(attachmentsQuery.attachments("q-1")).thenReturn(listOf(attachment))
        whenever(attachmentsQuery.content("q-1", "f-1")).thenReturn(QuoteAttachmentContent("angebot.pdf", "application/pdf", byteArrayOf(1, 2, 3)))
        whenever(attach.attach(any(), any())).thenReturn(StoredQuoteFile("f-2"))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(enabled: Boolean = true, vararg granted: AppPermission = ALL_QUOTE_PERMISSIONS) = QuoteEditorView(
        QuoteEditorQueries(detailQuery, byNumberQuery, headerFields, articles, attachmentsQuery, library, ReceiptReadabilityQueryPort { emptyList() }),
        QuoteEditorCommands(editHeader, editItems, attach, removeAttachment, assignAttachment, attachLibrary, submit),
        QuoteAvailability(enabled),
        rolesWith(*granted),
    )

    private fun enter(view: QuoteEditorView, id: String? = "AN-2026-0001"): BeforeEnterEvent {
        val event = quoteEvent(id, "quotes/$id/edit")
        UI.getCurrent().add(view)
        view.beforeEnter(event)
        return event
    }

    private fun editor(tab: Int = 0): QuoteEditorView = newView().also {
        enter(it)
        it.tabs.selectedIndex = tab
    }

    private fun savedHeader(): EditQuoteHeaderCommand =
        argumentCaptor<EditQuoteHeaderCommand>().also { verify(editHeader).editHeader(it.capture()) }.firstValue

    private fun cell(view: QuoteEditorView, row: Int, key: String): Component = view.items.grid._getCellComponent(row, key)

    private fun openRow(view: QuoteEditorView, row: Int) {
        view.items.grid.editor.editItem(view.items.grid.dataCommunicator.getItem(row))
        MockVaadin.clientRoundtrip()
    }

    private fun editorOf(view: QuoteEditorView, key: String): Component = view.items.grid.getColumnByKey(key).editorComponent as Component

    private fun saveRow(view: QuoteEditorView) {
        view.items.grid.editor.save()
        MockVaadin.clientRoundtrip()
    }

    private fun committedLines(): List<QuoteItemInput> =
        argumentCaptor<List<QuoteItemInput>>().also { verify(editItems).editItems(org.mockito.kotlin.eq("q-1"), it.capture()) }.firstValue

    private fun mixedKinds() = whenever(detailQuery.find("q-1")).thenReturn(
        quoteDetail { it.copy(items = listOf(quoteItem(1), quoteItem(2).copy(category = InvoiceItemCategory.PRODUCT, unit = InvoiceItemUnit.PIECE))) },
    )

    private fun click(component: Component) = ComponentUtil.fireEvent(component, ClickEvent(component))

    private fun chips(view: QuoteEditorView) = descendants(view.items.chips).filterIsInstance<NativeButton>()

    private fun openDialog(): Dialog = _find<Dialog>().single { it.isOpened }

        // ── entry ──────────────────────────────────────────────────────────────────

    @Test
    fun `without the component or the edit permission the view forwards to the invoice list`() {
        verify(enter(newView(enabled = false))).forwardTo(InvoiceListView::class.java)
        verify(enter(newView(true, AppPermission.QUOTE_VIEW))).forwardTo(InvoiceListView::class.java)
        verify(detailQuery, never()).find(any())
    }

    @Test
    fun `an unknown offer or a missing id forwards to the offer list`() {
        whenever(detailQuery.find("q-404")).thenThrow(QuoteNotFoundException("Angebot nicht gefunden"))
        verify(enter(newView(), "q-404")).forwardTo(QuoteListView::class.java)
        verify(enter(newView(), null)).forwardTo(QuoteListView::class.java)
    }

    @Test
    fun `an offer past review is read only with a link to the detail`() {
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail(QuoteStatus.SENT))
        val view = editor(0)

        assertFalse(view.tabs.isVisible)
        assertTrue(view.notice.isVisible)
        assertEquals(listOf("quotes/AN-2026-0001"), view.notice._find<RouterLink>().map { it.href })
        assertTrue(visibleText(view.notice).contains("Nur Entwürfe"))
    }

    @Test
    fun `an offer in review is editable`() {
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail(QuoteStatus.REVIEW))
        val view = editor(0)

        assertTrue(view.tabs.isVisible)
        assertFalse(view.notice.isVisible)
    }

    @Test
    fun `a draft shows four tabs`() {
        val view = newView().also { enter(it) }

        assertTrue(view.tabs.isVisible)
        assertFalse(view.notice.isVisible)
        assertEquals(listOf("Kopf", "Positionen", "", "Belege"), (0 until view.tabs.tabCount).map { view.tabs.getTabAt(it).label })
        assertEquals("Angebot AN-2026-0001", view.hero.title.text)
        assertEquals(0, view.tabs.selectedIndex)
        assertTrue(visibleText(view.hero.chips).contains("Entwurf"))
        assertEquals(null, view.hero.actions)
    }

    @Test
    fun `the tab query parameter opens the positions and anything else the header`() {
        fun enterWith(path: String) = newView().also {
            val event = quoteEvent("AN-2026-0001", path)
            UI.getCurrent().add(it)
            it.beforeEnter(event)
        }

        assertEquals(1, enterWith("quotes/AN-2026-0001/edit?tab=items").tabs.selectedIndex)
        assertEquals(0, enterWith("quotes/AN-2026-0001/edit").tabs.selectedIndex)
        assertEquals(0, enterWith("quotes/AN-2026-0001/edit?tab=unknown").tabs.selectedIndex)
    }

    @Test
    fun `the e-quote caption counts the missing mandatory references live`() {
        val view = newView().also { enter(it) }
        val caption = view.tabs.getTabAt(2)
        assertEquals("E-Angebot", visibleText(caption).trim())
        assertFalse(view.equoteOpen.isVisible)

        view.references.fields.getValue(13).value = ""
        assertTrue(view.equoteOpen.isVisible)
        assertEquals("(1 offen)", view.equoteOpen.text)
        view.references.fields.getValue(10).value = ""
        assertEquals("(2 offen)", view.equoteOpen.text)
        view.references.fields.getValue(13).value = "B-1"
        view.references.fields.getValue(10).value = "LW-2"
        assertFalse(view.equoteOpen.isVisible)
    }

    @Test
    fun `the e-quote caption starts with the open count of an incomplete offer`() {
        whenever(headerFields.headerFields("q-1")).thenReturn(listOf(QuoteHeaderField(14, mandatory = true), QuoteHeaderField(15, mandatory = true)))
        val view = newView().also { enter(it) }

        assertEquals("(2 offen)", view.equoteOpen.text)
    }

    // ── Kopf ───────────────────────────────────────────────────────────────────

    @Test
    fun `the header tab shows the offer and the frozen document language read only`() {
        val view = editor(0)
        val header = view.header

        assertEquals("Beratung Oktober", header.subject.value)
        assertEquals(LocalDate.of(2026, 10, 31), header.validUntil.value)
        assertEquals(QuotePriceDisplay.AUTO, header.priceDisplay.value)
        assertEquals("de", header.language.value)
        assertTrue(header.language.isReadOnly)
        assertEquals("Hinweis", header.noteText.markup)
        assertEquals("Einleitung", header.introText.markup)
        assertEquals("Schluss", header.closingText.markup)
    }

    @Test
    fun `the price display options are labelled`() {
        val view = editor(0)

        assertEquals("Nettopreise", view.header.priceDisplay.itemLabelGenerator.apply(QuotePriceDisplay.NET))
    }

    @Test
    fun `saving sends the header with markup texts and the entered references`() {
        val view = editor(0)
        view.header.subject.value = "  Neuer Betreff  "
        view.header.priceDisplay.value = QuotePriceDisplay.GROSS
        view.header.noteText.markup = ""
        view.header.introText.markup = "Guten Tag"
        view.saveButton._click()

        val command = savedHeader()
        assertEquals("q-1", command.quoteId)
        assertEquals("Neuer Betreff", command.subject)
        assertEquals(QuotePriceDisplay.GROSS, command.priceDisplay)
        assertEquals(LocalDate.of(2026, 10, 31), command.validUntil)
        assertEquals(null, command.noteText)
        assertEquals("Guten Tag", command.introText)
        assertEquals("Schluss", command.closingText)
        assertEquals(mapOf(13 to "B-4711", 10 to "LW-1"), command.b2bValues)
        assertEquals(de.littletrotter.ui.i18n.UiTranslations.tr("message.saved"), view.saveButton.text)
        assertTrue(notificationTexts().none { it.contains("gespeichert") })
        verify(detailQuery, times(2)).find("q-1")
    }

    @Test
    fun `submitting a draft saves the edits, submits it and opens the detail`() {
        whenever(submit.submit("q-1")).thenReturn("AN-2026-0001")
        val view = editor(0)
        view.header.subject.value = "Neuer Betreff"
        view.submitButton._click()

        assertEquals("Neuer Betreff", savedHeader().subject)
        verify(submit).submit("q-1")
        assertTrue(notificationTexts().any { it.contains("AN-2026-0001") })
        assertEquals("quotes/AN-2026-0001", UI.getCurrent().internals.activeViewLocation.path)
    }

    @Test
    fun `submitting a draft without edits only submits`() {
        whenever(submit.submit("q-1")).thenReturn("AN-2026-0001")
        val view = editor(0)
        view.submitButton._click()

        verify(editHeader, never()).editHeader(any())
        verify(submit).submit("q-1")
    }

    @Test
    fun `an offer in review has no submit action`() {
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail(QuoteStatus.REVIEW))
        val view = editor(0)

        assertFalse(view.submitButton.isVisible)
    }

    @Test
    fun `the submit action sits in the hero for a draft`() {
        val view = editor(0)

        assertTrue(view.submitButton.isVisible)
        assertEquals("Zur Prüfung", view.submitButton.text)
        assertTrue(view.hero.editorActions.children.toList().contains(view.submitButton))
    }

    @Test
    fun `a refused save keeps the editor open and does not submit`() {
        whenever(editHeader.editHeader(any())).thenThrow(QuoteServiceException("Angebot ist gesperrt"))
        val view = editor(0)
        view.header.subject.value = "Geändert"
        view.submitButton._click()

        verify(submit, never()).submit(any())
        assertTrue(notificationTexts().any { it.contains("Angebot ist gesperrt") })
    }

    @Test
    fun `a refused submit is shown and the editor stays`() {
        whenever(submit.submit("q-1")).thenThrow(QuoteServiceException("Pflichtangaben fehlen"))
        val view = editor(0)
        view.submitButton._click()

        assertTrue(notificationTexts().any { it.contains("Pflichtangaben fehlen") })
        assertTrue(notificationTexts().none { it.contains("zur Prüfung eingereicht") })
    }

    @Test
    fun `submitting without a validity date is refused locally`() {
        val view = editor(0)
        view.header.validUntil.value = null
        view.submitButton._click()

        verify(submit, never()).submit(any())
        assertTrue(notificationTexts().any { it.contains("Gültig bis") })
    }

    @Test
    fun `a blank subject is saved as none`() {
        val view = editor(0)
        view.header.subject.value = "   "
        view.saveButton._click()

        assertEquals(null, savedHeader().subject)
    }

    @Test
    fun `saving without a validity date is refused locally`() {
        val view = editor(0)
        view.header.validUntil.value = null
        view.saveButton._click()

        verify(editHeader, never()).editHeader(any())
        assertTrue(notificationTexts().any { it.contains("Gültig bis") })
    }

    @Test
    fun `a refusal by the component is shown and nothing is reloaded`() {
        whenever(editHeader.editHeader(any())).thenThrow(QuoteServiceException("Angebot ist gesperrt"))
        val view = editor(0)
        view.header.subject.value = "Geändert"
        view.saveButton._click()

        assertTrue(notificationTexts().any { it.contains("Angebot ist gesperrt") })
        verify(detailQuery, times(1)).find("q-1")
    }

    @Test
    fun `chips offer the number the validity and the entered references`() {
        val view = editor(0)
        val chips = descendants(view.header.introText.chips).filterIsInstance<Button>()

        val labels = chips.map { it.element.textRecursively }
        assertTrue(labels.any { it.contains("Angebotsnummer") }, labels.toString())
        assertTrue(labels.any { it.contains("Gültig bis") }, labels.toString())
        assertTrue(labels.any { it.contains("Bestellnummer des Empfängers") }, labels.toString())
        assertTrue(labels.any { it.contains("Rechnungsempfänger-Referenz") }, labels.toString())
        chips.first()._click()
    }

    @Test
    fun `each chip row is captioned and sits directly above its own editor`() {
        val view = editor(0)

        listOf(view.header.noteText, view.header.introText, view.header.closingText).forEach { block ->
            val children = block.children.toList()
            val row = children.first() as Div
            assertEquals("Platzhalter einfügen", (row.children.toList().first() as Span).text)
            assertTrue(row.isVisible)
            assertTrue(row.children.toList().contains(block.chips))
            assertEquals(row, children[0])
            assertEquals(block.editor, children[1])
        }
    }

    @Test
    fun `a bookmarked id route forwards to the number route and keeps the query`() {
        val id = "3f2b8c1e-5a47-4d0e-9b61-0c7a1d2e4f58"
        whenever(detailQuery.find(id)).thenReturn(quoteDetail())
        val event = quoteEvent(id, "quotes/$id/edit?tab=items")
        val view = newView()
        UI.getCurrent().add(view)

        view.beforeEnter(event)

        verify(event).forwardTo(
            org.mockito.kotlin.eq("quotes/AN-2026-0001/edit"),
            org.mockito.kotlin.argThat<com.vaadin.flow.router.QueryParameters> { parameters["tab"] == listOf("items") },
        )
    }

    @Test
    fun `the header form is as wide as its container up to a maximum`() {
        val view = editor(0)

        assertEquals("100%", view.header.width)
        assertEquals("72rem", view.header.maxWidth)
        assertEquals("100%", view.header.priceDisplay.width)
        assertEquals("100%", view.tabs.width)
        // The grid itself takes its column width from the stylesheet; its scroll box fills the tab.
        assertEquals("100%", (view.items.grid.parent.get() as GridScrollBox).width)
        assertEquals(null, view.items.grid.width)
    }

    @Test
    fun `the items tab tells how the row editor opens, in the invoice's words, right below the grid`() {
        val view = editor()

        val children = view.items.children.toList()
        val box = view.items.grid.parent.get() as GridScrollBox
        val hint = children[children.indexOf(box) + 1] as com.vaadin.flow.component.html.Span
        assertEquals("Doppelklick auf eine Zeile bearbeitet Menge, Einzelpreis und USt direkt.", hint.text)
        assertTrue(hint.hasClassName("hint-text"))
    }

    @Test
    fun `the number chip is offered and chips follow reference edits`() {
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail { it.copy(b2bValues = emptyList()) })
        val view = editor(0)
        val before = descendants(view.header.noteText.chips).filterIsInstance<Button>().map { it.element.textRecursively }
        assertTrue(before.any { it.contains("Angebotsnummer") })

        view.references.fields.getValue(12).value = "V-1"

        val after = descendants(view.header.noteText.chips).filterIsInstance<Button>().map { it.element.textRecursively }
        assertTrue(after.any { it.contains("Vertragsreferenz") }, after.toString())
    }

    @Test
    fun `the preview renders the stored markup`() {
        val view = editor(0)
        view.header.introText.markup = "Guten Tag"

        assertTrue(view.header.introText._find<com.vaadin.flow.component.Html>().single().innerHtml.contains("Guten Tag"))
        view.header.introText.markup = ""
        assertTrue(view.header.introText._find<com.vaadin.flow.component.Html>().isEmpty())
    }

    // ── Referenzen ─────────────────────────────────────────────────────────────

    @Test
    fun `references are prefilled from the offer then from defaults and mandatory gaps only warn`() {
        val view = editor(0)

        assertEquals("B-4711", view.references.fields.getValue(13).value)
        assertEquals("LW-1", view.references.fields.getValue(10).value)
        assertEquals("", view.references.fields.getValue(12).value)
        assertFalse(view.references.warning.isVisible)
        assertEquals(emptyList<Int>(), view.references.missingMandatory())

        view.references.fields.getValue(13).value = ""
        assertTrue(view.references.warning.isVisible)
        assertTrue(view.references.warning.text.contains("Bestellnummer des Empfängers"), view.references.warning.text)
        assertEquals(listOf(13), view.references.missingMandatory())
        assertEquals(mapOf(10 to "LW-1"), view.references.values())
    }

    @Test
    fun `saving with a mandatory reference missing still saves and warns`() {
        val view = editor(0)
        view.references.fields.getValue(13).value = ""
        view.saveButton._click()

        savedHeader()
        assertTrue(notificationTexts().any { it.contains("Pflichtangaben fehlen noch") })
    }

    @Test
    fun `unknown business terms are labelled by their code`() {
        whenever(headerFields.headerFields("q-1")).thenReturn(listOf(QuoteHeaderField(99, mandatory = false)))
        val view = editor(0)

        assertEquals("BT-99", view.references.fields.getValue(99).label)
    }

    @Test
    fun `mandatory references show plain labels with the code and an explanation`() {
        whenever(headerFields.headerFields("q-1")).thenReturn(
            listOf(QuoteHeaderField(19, mandatory = true), QuoteHeaderField(58, mandatory = true), QuoteHeaderField(56, mandatory = false)),
        )
        val view = editor(0)

        val field = view.references.fields.getValue(19)
        assertEquals("Kontierungsreferenz des Empfängers (BT-19)", field.label)
        assertTrue(field.helperText.startsWith("Kostenstelle oder Buchungskonto"), field.helperText)
        assertEquals("Diese Pflichtangaben fehlen noch: Kontierungsreferenz des Empfängers (BT-19), E-Mail-Adresse des Ansprechpartners beim Empfänger (BT-58)", view.references.warning.text)
        assertTrue(view.references.intro.text.startsWith("Angaben, die der Empfänger"))
    }

    @Test
    fun `terms without an explanation get no helper text`() {
        whenever(headerFields.headerFields("q-1")).thenReturn(listOf(QuoteHeaderField(99, mandatory = false)))
        val view = editor(0)

        assertEquals(null, view.references.fields.getValue(99).helperText)
    }

    @Test
    fun `optional references sit behind a second tab that counts them`() {
        whenever(headerFields.headerFields("q-1")).thenReturn(
            listOf(QuoteHeaderField(13, mandatory = true), QuoteHeaderField(12, mandatory = false), QuoteHeaderField(56, mandatory = false)),
        )
        val view = editor(0)
        val sheet = view.references.subTabs!!

        assertEquals("Weitere Angaben (2)", view.references.optionalTab!!.label)
        assertEquals(2, sheet.tabCount)
        assertEquals("Pflichtangaben", sheet.getTabAt(0).label)
        assertEquals(0, sheet.selectedIndex)
        assertTrue(view.references.mandatoryBox.children.anyMatch { it === view.references.fields.getValue(13) })
        assertTrue(view.references.optionalBox.children.anyMatch { it === view.references.fields.getValue(12) })
        assertTrue(view.references.optionalBox.children.anyMatch { it === view.references.fields.getValue(56) })
    }

    @Test
    fun `without optional references there are no inner tabs`() {
        whenever(headerFields.headerFields("q-1")).thenReturn(listOf(QuoteHeaderField(13, mandatory = true)))
        val view = editor(0)

        assertEquals(null, view.references.subTabs)
        assertEquals(view.references, view.references.mandatoryBox.parent.get())
        assertEquals(0, view.references.mandatoryBox.children.filter { it === view.references.noMandatoryHint }.count())
    }

    @Test
    fun `without mandatory references a hint explains the empty first tab`() {
        whenever(headerFields.headerFields("q-1")).thenReturn(listOf(QuoteHeaderField(12, mandatory = false)))
        val view = editor(0)

        assertEquals("Für diesen Empfänger sind keine Pflichtangaben hinterlegt.", view.references.noMandatoryHint.text)
        assertTrue(view.references.mandatoryBox.children.anyMatch { it === view.references.noMandatoryHint })
        assertEquals("Weitere Angaben (1)", view.references.optionalTab!!.label)
        assertFalse(view.references.warning.isVisible)
    }

    // ── Positionen ─────────────────────────────────────────────────────────────

    @Test
    fun `the grid has the invoices column order with the actions last`() {
        val grid = editor(1).items.grid

        assertEquals(
            listOf("position", "description", "attachments", "quantity", "unit", "unitPrice", "vat", "net", "actions"),
            grid.columns.map { it.key },
        )
        assertEquals(
            listOf("Pos.", "Beschreibung", "Anlagen", "Menge", "Einheit", "Einzelpreis", "USt.", "Netto", ""),
            grid.columns.map { it.headerText },
        )
        val actions = grid.getColumnByKey("actions")
        assertTrue(actions.isAutoWidth)
        assertEquals(0, actions.flexGrow)
        assertTrue(grid.getColumnByKey("description").flexGrow > 0)
        listOf("quantity", "unitPrice", "vat", "net").forEach { assertEquals("bau-num", grid.getColumnByKey(it).partNameGenerator?.apply(null), it) }
    }

    @Test
    fun `the items tab aligns the totals to the right edge`() {
        val view = editor(1)

        assertFalse(view.items.totals.classNames.contains("quote-totals--end"))
        assertEquals(
            com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END,
            view.items.getAlignSelf(view.items.totals),
        )
    }

    @Test
    fun `the items tab lists the lines the totals and the summary`() {
        val view = editor(1)

        assertEquals(2, (view.items.grid.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<*>).items.size)
        assertTrue(visibleText(view.items.totals).contains("297,50"))
        assertEquals("2,5", visibleText(cell(view, 0, "quantity")))
        assertTrue(visibleText(cell(view, 0, "net")).contains("250,00"))
        assertEquals("2 Position(en) · Netto 500,00 EUR", view.items.summary.text.replace('\u00A0', ' '))
    }

    @Test
    fun `the toolbar keeps the filter left and the three add buttons right in the invoice order and never offers a trip`() {
        val view = editor(1)

        assertEquals(JustifyContentMode.BETWEEN, view.items.toolbar.justifyContentMode)
        assertEquals(2, view.items.toolbar.componentCount)
        val right = view.items.toolbar.getComponentAt(1) as HorizontalLayout
        assertEquals(view.items.addButtons, right.children.toList())
        assertEquals(listOf("PRODUCT", "UNIT_PRICE", "SERVICE"), view.items.addButtons.map { it.element.getAttribute("data-add-category") })
        assertEquals(listOf("Material hinzufügen", "Einheitspreis hinzufügen", "Arbeitsstunden hinzufügen"), view.items.addButtons.map { it.text })
        assertTrue(view.items.addButtons.none { it.element.getAttribute("data-add-category") == "TRIP" })
        assertTrue(view.items.toolbar.getComponentAt(0).element.textRecursively.contains("Position(en)"))
    }

    @Test
    fun `the sticky bar holds only back and save and the header tab comes first`() {
        val view = editor(1)

        assertEquals(listOf("Zurück", "Speichern"), view.saveBar._find<Button>().map { it.text })
        assertEquals("Kopf", view.tabs.getTabAt(0).label)
    }

    @Test
    fun `a chip per kind counts the lines and narrows the grid`() {
        mixedKinds()
        val view = editor(1)

        assertEquals(listOf("Material 1", "Arbeitsstunden 1"), chips(view).map { it.element.textRecursively.trim() })
        click(chips(view).first())
        assertEquals(listOf(2), (view.items.grid.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<QuoteItem>).items.map { it.position })
        assertEquals(InvoiceItemCategory.PRODUCT, view.items.filter)
        click(chips(view).first())
        assertEquals(2, (view.items.grid.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<QuoteItem>).items.size)
        assertEquals(null, view.items.filter)
    }

    @Test
    fun `a filter whose kind has no lines left is reset`() {
        mixedKinds()
        val view = editor(1)
        click(chips(view).first())
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail())
        click(cell(view, 0, "actions").children.toList()[1])

        assertEquals(null, view.items.filter)
    }

    @Test
    fun `editing the quantity inline stores all lines at once without the save bar`() {
        val view = editor(1)
        openRow(view, 0)
        (editorOf(view, "quantity") as NumberField).value = 4.0
        saveRow(view)

        val lines = committedLines()
        assertEquals(listOf("i-1", "i-2"), lines.map { it.id })
        assertEquals(BigDecimal.valueOf(4.0), lines[0].quantity)
        assertEquals(BigDecimal("2.50"), lines[1].quantity)
        assertEquals("a-1", lines[0].articleId)
        verify(detailQuery, times(2)).find("q-1")
        assertTrue(notificationTexts().any { it.contains("Positionen gespeichert") })
        assertFalse(view.saveButton.isEnabled)
        assertFalse(view.dirtyDot.isVisible)
    }

    @Test
    fun `price rate and tax category are committed inline too`() {
        val view = editor(1)
        openRow(view, 1)
        (editorOf(view, "unitPrice") as NumberField).value = 10.0
        saveRow(view)
        val priceLines = argumentCaptor<List<QuoteItemInput>>().also { verify(editItems).editItems(org.mockito.kotlin.eq("q-1"), it.capture()) }.firstValue
        assertEquals(Money.of("10.00"), priceLines[1].unitPrice)

        openRow(view, 0)
        val tax = editorOf(view, "vat")
        descendants(tax).filterIsInstance<NumberField>().single().value = 7.0
        descendants(tax).filterIsInstance<ComboBox<*>>().single().let { (it as ComboBox<VatCategory>).value = VatCategory.EXEMPT }
        saveRow(view)
        val all = argumentCaptor<List<QuoteItemInput>>().also { verify(editItems, times(2)).editItems(org.mockito.kotlin.eq("q-1"), it.capture()) }.allValues
        assertEquals(BigDecimal.valueOf(7.0), all[1][0].vatRate)
        assertEquals(VatCategory.EXEMPT, all[1][0].vatCategory)
    }

    @Test
    fun `rows without an open editor show plain text and a cancelled edit sends nothing`() {
        val view = editor(1)
        listOf("quantity", "unitPrice", "vat").forEach { key ->
            assertTrue(descendants(cell(view, 0, key)).none { it is NumberField || it is ComboBox<*> }, key)
        }

        openRow(view, 0)
        (editorOf(view, "quantity") as NumberField).value = 9.0
        view.items.grid.editor.cancel()

        assertFalse(view.items.grid.editor.isOpen)
        verify(editItems, never()).editItems(any(), any())
    }

    @Test
    fun `a double click opens the row editor and the second click on another row keeps it`() {
        val view = editor(1)
        val grid = view.items.grid

        grid._doubleClickItem(0)
        MockVaadin.clientRoundtrip()
        assertTrue(grid.editor.isOpen)
        assertEquals("i-1", grid.editor.item.id)
        grid._doubleClickItem(1)
        MockVaadin.clientRoundtrip()
        assertEquals("i-1", grid.editor.item.id)
    }

    @Test
    fun `the editor actions save and cancel the open row`() {
        val view = editor(1)
        val grid = view.items.grid
        openRow(view, 0)
        val buttons = descendants(editorOf(view, "actions")).filterIsInstance<com.vaadin.flow.component.button.Button>()
        (editorOf(view, "quantity") as NumberField).value = 3.0

        click(buttons[0])
        MockVaadin.clientRoundtrip()
        assertFalse(grid.editor.isOpen)
        assertEquals(BigDecimal.valueOf(3.0), committedLines()[0].quantity)

        openRow(view, 0)
        click(descendants(editorOf(view, "actions")).filterIsInstance<com.vaadin.flow.component.button.Button>()[1])
        assertFalse(grid.editor.isOpen)
    }

    @Test
    fun `a cleared or impossible inline value is warned about and never sent`() {
        val view = editor(1)
        openRow(view, 0)
        (editorOf(view, "quantity") as NumberField).value = 0.0
        saveRow(view)
        assertTrue(notificationTexts().any { it.contains("Position 1") && it.contains("Menge") })
        openRow(view, 1)
        (editorOf(view, "unitPrice") as NumberField).value = null
        saveRow(view)
        assertTrue(notificationTexts().any { it.contains("Position 2") && it.contains("Einzelpreis") })
        openRow(view, 1)
        (editorOf(view, "unitPrice") as NumberField).value = -1.0
        saveRow(view)
        assertTrue(view.items.grid.editor.isOpen, "a negative price fails the field's own minimum")
        view.items.grid.editor.cancel()
        MockVaadin.clientRoundtrip()
        openRow(view, 0)
        descendants(editorOf(view, "vat")).filterIsInstance<NumberField>().single().value = null
        saveRow(view)
        assertTrue(notificationTexts().any { it.contains("Position 1") && it.contains("Umsatzsteuersatz") })

        verify(editItems, never()).editItems(any(), any())
    }

    @Test
    fun `a refusal of an inline edit is shown and keeps the loaded lines`() {
        whenever(editItems.editItems(any(), any())).thenThrow(QuoteServiceException("Version veraltet"))
        val view = editor(1)
        openRow(view, 0)
        (editorOf(view, "quantity") as NumberField).value = 4.0
        saveRow(view)

        assertTrue(notificationTexts().any { it.contains("Version veraltet") })
        verify(detailQuery, times(1)).find("q-1")
        assertEquals(2, (view.items.grid.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<*>).items.size)
    }

    @Test
    fun `deleting a line stores the remaining lines at once`() {
        val view = editor(1)
        click(cell(view, 0, "actions").children.toList()[1])

        assertEquals(listOf("i-2"), committedLines().map { it.id })
    }

    @Test
    fun `an add button opens the dialog of its kind and OK appends the line to the complete list`() {
        val view = editor(1)
        click(view.items.addButtons[0])
        val dialog = openDialog()
        dialog._get<TextArea>().value = "Neues Material"
        dialog._get<NumberField> { label = "Einzelpreis netto (EUR)" }.value = 5.0
        dialog._get<Button> { text = "Speichern" }._click()

        val lines = committedLines()
        assertEquals(listOf("i-1", "i-2", null), lines.map { it.id })
        assertEquals("Neues Material", lines.last().description)
        assertEquals(InvoiceItemCategory.PRODUCT, lines.last().category)
        assertEquals(InvoiceItemUnit.PIECE, lines.last().unit)
    }

    @Test
    fun `the service button opens the service dialog with its radio units`() {
        val view = editor(1)
        click(view.items.addButtons[2])
        val dialog = openDialog()

        assertEquals(4, dialog._get<RadioButtonGroup<InvoiceItemUnit>>().listDataView.itemCount)
    }

    @Test
    fun `the unit price button opens the goods dialog for unit prices`() {
        val view = editor(1)
        click(view.items.addButtons[1])
        val dialog = openDialog()
        dialog._get<TextArea>().value = "Estrich"
        dialog._get<NumberField> { label = "Einzelpreis netto (EUR)" }.value = 20.0
        dialog._get<Button> { text = "Speichern" }._click()

        assertEquals(InvoiceItemCategory.UNIT_PRICE, committedLines().last().category)
    }

    @Test
    fun `the edit action opens the dialog of the lines kind with its values and replaces the line`() {
        val view = editor(1)
        click(cell(view, 0, "actions").children.toList()[0])
        val dialog = openDialog()

        assertEquals("Beratung 1", dialog._get<TextArea>().value)
        dialog._get<TextArea>().value = "Beratung neu"
        dialog._get<Button> { text = "Speichern" }._click()

        val lines = committedLines()
        assertEquals(listOf("i-1", "i-2"), lines.map { it.id })
        assertEquals("Beratung neu", lines[0].description)
        assertEquals("Beratung 2", lines[1].description)
    }

    @Test
    fun `editing a material line opens the goods dialog`() {
        mixedKinds()
        val view = editor(1)
        click(cell(view, 1, "actions").children.toList()[0])

        assertEquals(0, openDialog()._find<RadioButtonGroup<*>>().size)
    }

    @Test
    fun `the paperclip shows the attachments count of each line`() {
        whenever(attachmentsQuery.attachments("q-1")).thenReturn(listOf(attachment.copy(itemId = "i-2", itemPosition = 2)))
        val view = editor(1)

        assertEquals("Keine Anlage", (cell(view, 0, "attachments") as Button).text)
        assertEquals("1 Anlage(n)", (cell(view, 1, "attachments") as Button).text)
    }

    @Test
    fun `the paperclip opens the lines attachment dialog`() {
        val view = editor(1)
        click(cell(view, 1, "attachments"))

        assertTrue(openDialog().headerTitle.contains("Position 2"))
    }

    @Test
    fun `saved lines refresh the attachment choices of the other tab`() {
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail(), quoteDetail { it.copy(items = it.items + quoteItem(3)) })
        val view = editor(1)
        openRow(view, 0)
        (editorOf(view, "quantity") as NumberField).value = 4.0
        saveRow(view)

        assertEquals(3, view.attachments.itemBox.listDataView.itemCount)
    }

    @Test
    fun `switching the tab refreshes the counts and the attachment choices`() {
        val view = editor(1)
        org.mockito.kotlin.clearInvocations(attachmentsQuery)
        view.tabs.selectedIndex = 3

        verify(attachmentsQuery, org.mockito.kotlin.atLeast(2)).attachments("q-1")
    }

    // ── Anlagen ────────────────────────────────────────────────────────────────

    @Test
    fun `the attachments tab lists files with kind and size`() {
        val view = editor(3)

        assertEquals(listOf(attachment), (view.attachments.grid.dataProvider as com.vaadin.flow.data.provider.ListDataProvider<QuoteAttachment>).items.toList())
        assertFalse(view.attachments.emptyState.isVisible)
        assertEquals(QuoteAttachmentKind.CUSTOMER, view.attachments.kind.value)
        val row = view.attachments.grid._getFormattedRow(0)
        assertEquals("angebot.pdf", view.attachments.grid._getCellComponent(0, "name")._find<com.vaadin.flow.component.html.Span>().first().text)
        assertEquals("Für den Empfänger", row[1])
        assertEquals("2 KB", row[3])
    }

    @Test
    fun `an empty attachment list shows its empty state`() {
        whenever(attachmentsQuery.attachments("q-1")).thenReturn(emptyList())
        val view = editor(3)

        assertTrue(view.attachments.emptyState.isVisible)
    }

    @Test
    fun `an uploaded file is attached with the chosen kind and the list is reloaded`() {
        val view = editor(3)
        org.mockito.kotlin.clearInvocations(attachmentsQuery)
        // A tab sheet attaches tab content lazily, and the upload test tooling needs an attached component.
        val tab = QuoteAttachmentsTab(view, QuoteAttachmentsTabPorts(attachmentsQuery, attach, removeAttachment, assignAttachment, library, attachLibrary, ReceiptReadabilityQueryPort { emptyList() }))
        UI.getCurrent().add(tab)
        tab.bind("q-1")
        tab.subTabs.selectedTab = tab.uploadTab
        MockVaadin.clientRoundtrip()
        tab.kind.value = QuoteAttachmentKind.INTERNAL
        tab.upload._upload("kalkulation.xlsx", "application/vnd.ms-excel", byteArrayOf(9, 8))

        val upload = argumentCaptor<QuoteAttachmentUpload>().also { verify(attach).attach(org.mockito.kotlin.eq("q-1"), it.capture()) }.firstValue
        assertEquals(QuoteAttachmentKind.INTERNAL, upload.kind)
        assertEquals("kalkulation.xlsx", upload.fileName)
        assertEquals("application/vnd.ms-excel", upload.mimeType)
        assertEquals(listOf<Byte>(9, 8), upload.content.toList())
        verify(attachmentsQuery, times(2)).attachments("q-1")
        assertTrue(notificationTexts().any { it.contains("kalkulation.xlsx") })
    }

    @Test
    fun `a file without a media type is stored as binary`() {
        val view = editor(3)
        view.attachments.onUploaded("x.bin", null, byteArrayOf(1))

        val upload = argumentCaptor<QuoteAttachmentUpload>().also { verify(attach).attach(any(), it.capture()) }.firstValue
        assertEquals("application/octet-stream", upload.mimeType)
    }

    @Test
    fun `a refused upload is shown and the list stays`() {
        whenever(attach.attach(any(), any())).thenThrow(QuoteServiceException("Datei zu groß"))
        val view = editor(3)
        org.mockito.kotlin.clearInvocations(attachmentsQuery)
        view.attachments.onUploaded("x.pdf", "application/pdf", byteArrayOf(1))

        assertTrue(notificationTexts().any { it.contains("Datei zu groß") })
        verify(attachmentsQuery, never()).attachments("q-1")
    }

    @Test
    fun `the position column shows the item position or a dash and sits after the file name`() {
        val linked = attachment.copy(id = "f-2", itemId = "i-2", itemPosition = 2)
        whenever(attachmentsQuery.attachments("q-1")).thenReturn(listOf(attachment, linked))
        val view = editor(3)

        val keys = view.attachments.grid.columns.map { it.key }
        assertTrue(keys.indexOf("name") < keys.indexOf("item"))
        assertEquals("actions", keys.last())
        assertEquals("–", view.attachments.grid._getFormattedRow(0)[keys.indexOf("item")])
        assertEquals("2", view.attachments.grid._getFormattedRow(1)[keys.indexOf("item")])
    }

    @Test
    fun `assigning through the dialog sends the chosen item and reloads`() {
        val view = editor(3)
        org.mockito.kotlin.clearInvocations(attachmentsQuery)
        val actions = view.attachments.grid._getCellComponent(0, "actions")
        descendants(actions).filterIsInstance<Button>()[0]._click()
        val dialog = _get<com.vaadin.flow.component.dialog.Dialog>()
        val box = descendants(dialog).filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<QuoteItem>>().single()
        box.value = box.listDataView.items.toList()[1]
        _find<Button> { text = "Speichern" }.last()._click()

        verify(assignAttachment).assign(AssignQuoteAttachmentCommand("q-1", "f-1", "i-2"))
        verify(attachmentsQuery, times(1)).attachments("q-1")
    }

    @Test
    fun `clearing the item in the dialog assigns the offer only`() {
        val view = editor(3)
        view.attachments.assign(attachment, null)

        verify(assignAttachment).assign(AssignQuoteAttachmentCommand("q-1", "f-1", null))
    }

    @Test
    fun `cancelling the assign dialog sends nothing`() {
        val view = editor(3)
        val actions = view.attachments.grid._getCellComponent(0, "actions")
        descendants(actions).filterIsInstance<Button>()[0]._click()
        val dialog = _get<com.vaadin.flow.component.dialog.Dialog>()
        _get<Button> { text = "Abbrechen" }._click()

        verify(assignAttachment, org.mockito.kotlin.never()).assign(any())
    }

    @Test
    fun `an upload carries the chosen item`() {
        val view = editor(3)
        val tab = QuoteAttachmentsTab(view, QuoteAttachmentsTabPorts(attachmentsQuery, attach, removeAttachment, assignAttachment, library, attachLibrary, ReceiptReadabilityQueryPort { emptyList() }))
        UI.getCurrent().add(tab)
        tab.bind("q-1", quoteDetail().items)
        tab.subTabs.selectedTab = tab.uploadTab
        MockVaadin.clientRoundtrip()
        tab.itemBox.value = quoteDetail().items.first { it.position == 2 }
        tab.upload._upload("plan.pdf", "application/pdf", byteArrayOf(1))

        val upload = argumentCaptor<QuoteAttachmentUpload>().also { verify(attach).attach(org.mockito.kotlin.eq("q-1"), it.capture()) }.firstValue
        assertEquals("i-2", upload.itemId)
    }

    @Test
    fun `a refused assignment is shown`() {
        whenever(assignAttachment.assign(any())).thenThrow(QuoteServiceException("Position entfallen"))
        val view = editor(3)
        view.attachments.assign(attachment, null)

        assertTrue(notificationTexts().any { it.contains("Position entfallen") })
    }

    @Test
    fun `an attachment is removed and the list reloaded`() {
        val view = editor(3)
        org.mockito.kotlin.clearInvocations(attachmentsQuery)
        val actions = view.attachments.grid._getCellComponent(0, "actions")
        descendants(actions).filterIsInstance<Button>().last()._click()

        verify(removeAttachment).remove("q-1", "f-1")
        verify(attachmentsQuery, times(1)).attachments("q-1")
    }

    @Test
    fun `a refused removal is shown`() {
        whenever(removeAttachment.remove(any(), any())).thenThrow(QuoteServiceException("Nicht mehr vorhanden"))
        val view = editor(3)
        val actions = view.attachments.grid._getCellComponent(0, "actions")
        descendants(actions).filterIsInstance<Button>().last()._click()

        assertTrue(notificationTexts().any { it.contains("Nicht mehr vorhanden") })
    }

    @Test
    fun `the download wires an anchor and serves the bytes of the attachment`() {
        val view = editor(3)
        val actions = view.attachments.grid._getCellComponent(0, "actions")
        val anchor = descendants(actions).filterIsInstance<com.vaadin.flow.component.html.Anchor>().last()
        assertNotNull(anchor.href)
        assertTrue(anchor.element.hasAttribute("download"))

        val response = view.attachments.downloadResponse(attachment)

        assertEquals("angebot.pdf", response.fileName)
        assertEquals("application/pdf", response.contentType)
        assertEquals(3L, response.contentLength)
        assertEquals(listOf<Byte>(1, 2, 3), response.inputStream.readAllBytes().toList())
        assertFalse(response.hasError())
    }

    @Test
    fun `a download of a vanished attachment answers empty and does not fail`() {
        whenever(attachmentsQuery.content(any(), any())).thenThrow(QuoteNotFoundException("Anlage nicht gefunden"))
        val view = editor(3)

        val response = view.attachments.downloadResponse(attachment)

        assertFalse(response.hasError())
        assertEquals(0L, response.contentLength)
    }

    @Test
    fun `an unreachable field definition list leaves the references empty and says why`() {
        whenever(headerFields.headerFields("q-1")).thenThrow(QuoteServiceException("Felder nicht erreichbar"))
        val view = editor(0)

        assertTrue(view.references.fields.isEmpty())
        assertTrue(notificationTexts().any { it.contains("Felder nicht erreichbar") })
    }

    @Test
    fun `a draft without texts opens with empty editors and saves defaults`() {
        whenever(detailQuery.find("q-1")).thenReturn(
            quoteDetail { it.copy(subject = null, noteText = null, introText = null, closingText = null) },
        )
        val view = editor(0)
        view.header.priceDisplay.value = null
        view.saveButton._click()

        assertEquals("", view.header.subject.value)
        assertEquals("", view.header.closingText.markup)
        val command = savedHeader()
        assertEquals(QuotePriceDisplay.AUTO, command.priceDisplay)
        assertEquals(null, command.subject)
        assertEquals(null, command.introText)
        assertEquals(null, command.closingText)
        assertEquals(null, command.noteText)
    }

    @Test
    fun `an unreachable attachment list shows an empty list and the reason`() {
        whenever(attachmentsQuery.attachments("q-1")).thenThrow(QuoteServiceException("Anlagen nicht erreichbar"))
        val view = editor(3)

        assertTrue(view.attachments.emptyState.isVisible)
        assertTrue(notificationTexts().any { it.contains("Anlagen nicht erreichbar") })
    }

    // ── Speicherleiste ─────────────────────────────────────────────────────────

    @Test
    fun `a clean editor has a disabled save button and no dirty hint`() {
        val view = editor()

        assertFalse(view.saveButton.isEnabled)
        assertFalse(view.dirtyDot.isVisible)
        assertFalse(view.dirtyLabel.isVisible)
        assertEquals("Speichern", view.saveButton.text)
        assertEquals("Zurück", view.backButton.text)
        assertEquals("Ungespeicherte Änderungen", view.dirtyLabel.text)
        assertTrue(view.saveBar.hasClassName("lt-actionbar-sticky"))
    }

    @Test
    fun `editing the header enables the save button and shows the dirty hint`() {
        val view = editor()
        view.header.subject.value = "Anders"
        assertTrue(view.saveButton.isEnabled)
        assertTrue(view.dirtyDot.isVisible)
        assertTrue(view.dirtyLabel.isVisible)

        view.header.subject.value = "Beratung Oktober"
        assertFalse(view.saveButton.isEnabled)
    }

    @Test
    fun `saving stores the header only and clears the dirty state`() {
        val view = editor()
        view.header.subject.value = "Neu"
        view.saveButton._click()

        assertEquals("Neu", savedHeader().subject)
        verify(editItems, never()).editItems(any(), any())
        assertFalse(view.saveButton.isEnabled)
        assertEquals(de.littletrotter.ui.i18n.UiTranslations.tr("message.saved"), view.saveButton.text)
        assertTrue(notificationTexts().none { it.contains("gespeichert") })
    }

    @Test
    fun `a refused header save keeps the header dirty`() {
        whenever(editHeader.editHeader(any())).thenThrow(QuoteServiceException("Angebot ist gesperrt"))
        val view = editor()
        view.header.subject.value = "Neu"
        view.saveButton._click()

        assertTrue(view.saveButton.isEnabled)
        assertTrue(view.header.isDirty())
    }

    @Test
    fun `a reference edit counts as a header edit`() {
        val view = editor()
        view.references.fields.getValue(12).value = "V-1"

        assertTrue(view.saveButton.isEnabled)
    }

    @Test
    fun `an unchanged part is not sent`() {
        val view = editor()
        view.header.subject.value = "Neu"
        view.saveButton._click()

        verify(editHeader).editHeader(any())
        verify(editItems, never()).editItems(any(), any())
    }

    @Test
    fun `done without edits goes straight to the detail`() {
        val view = editor()
        view.backButton._click()

        assertTrue(UI.getCurrent().children.noneMatch { it is ConfirmDialog })
        assertEquals("quotes/AN-2026-0001", currentPath())
    }

    @Test
    fun `done with edits asks first and discarding goes to the detail`() {
        val view = editor()
        view.header.subject.value = "Neu"
        view.backButton._click()

        val dialog = _find<ConfirmDialog>().single()
        assertTrue(dialog.isOpened)
        assertFalse(currentPath() == "quotes/AN-2026-0001")
        ComponentUtil.fireEvent(dialog, ConfirmDialog.ConfirmEvent(dialog, false))

        assertEquals("quotes/AN-2026-0001", currentPath())
    }

    @Test
    fun `the back arrow of the title row guards unsaved edits like done`() {
        val view = editor()
        view.header.subject.value = "Neu"
        view.hero._get<Button> { id = "quote-detail-back" }._click()

        assertEquals(1, _find<ConfirmDialog>().size)
    }

    @Test
    fun `done can be answered with keep editing`() {
        val view = editor()
        view.header.subject.value = "Neu"
        view.backButton._click()
        val dialog = _find<ConfirmDialog>().single()
        ComponentUtil.fireEvent(dialog, ConfirmDialog.CancelEvent(dialog, false))

        assertTrue(view.saveButton.isEnabled)
    }

    @Test
    fun `a read only offer hides the save bar`() {
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail(QuoteStatus.SENT))
        val view = editor()

        assertFalse(view.saveBar.isVisible)
        assertEquals("Angebot AN-2026-0001", view.hero.title.text)
    }

    @Test
    fun `the header tab groups customer and data on a twelve column grid`() {
        val view = editor(0)

        assertEquals("Muster GmbH", view.header.customerName.value)
        assertTrue(view.header.customerName.isReadOnly)
        val form = view.header._find<Div> { classes = "quote-form-grid" }.single()
        assertEquals(
            listOf("quote-form-col--customer", "quote-form-col--data", "quote-form-col--full", "quote-form-col--full", "quote-form-col--full"),
            form.children.map { it.element.getAttribute("class") }.toList(),
        )
    }
}
