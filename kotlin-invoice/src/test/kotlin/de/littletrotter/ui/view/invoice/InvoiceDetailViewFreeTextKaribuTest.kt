package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Html
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.UI
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.InvoiceFormModuleRegistry
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.components.freetext.FreeTextChips
import de.littletrotter.ui.components.freetext.HtmlFreeTextEditor
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The "Rechnung" tab's notes section: [HtmlFreeTextEditor] (the mail editor's surface) and
 * [FreeTextChips] (service period / project number / FREE-HEADER B2B term chips). Covers the chip
 * row content, click-to-insert behavior, character limit and the value round trip to the saved
 * command in the layers Karibu can reach; the browser-side caret insertion and the editor's own
 * styling in light and dark mode are Playwright-only.
 */
class InvoiceDetailViewFreeTextKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val refs = ReferenceDataMocks()
    // ProjectNumberModule (a real, not mocked, InvoiceFormModule) is what renders the live
    // "projectNumber" widget wireFreeTextChips reads from — an unstubbed
    // InvoiceFormModuleRegistry.modules() defaults to an empty list, which would silently leave
    // einvoiceTab.projectNumberField null in every test here.
    private val invoiceFormModuleRegistry: InvoiceFormModuleRegistry = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val defaultSender = testSender(91L, "Test sender")
        val defaultRecipient = testRecipient(92L, "Test recipient")
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(defaultSender)
        whenever(recipientQueryPort.findRecipientById(92L)).thenReturn(defaultRecipient)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(defaultSender))
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
        whenever(invoiceFormModuleRegistry.modules())
            .thenReturn(listOf(de.littletrotter.application.invoice.ProjectNumberModule()))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val recipientQueryPort: RecipientQueryPort = mock()
    private val senderQueryPort: SenderQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()

    private fun createView(): InvoiceDetailView {
        val generationJobQueryPort = mock<GenerationJobQueryPort>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock(), mock(), ReceiptDialogLauncher(mock(), mock(), invoiceDetailQueryPort, mock())),
                exportPanel = testExportPanel(invoiceDetailQueryPort, generationJobQueryPort),
            ),
            invoiceEntryPane = testEntryPane(
                recipientQueryPort = recipientQueryPort,
                senderQueryPort = senderQueryPort,
                invoiceDetailQueryPort = invoiceDetailQueryPort,
                editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
                refs = refs,
                invoiceFormModuleRegistry = invoiceFormModuleRegistry,
            ),
        )
    }

    private fun invoice(
        status: Int = InvoiceStatus.DRAFT.ordinal,
        freeText: String? = null,
        servicePeriodFrom: LocalDate? = null,
        servicePeriodTo: LocalDate? = null,
        projectNumber: String? = null,
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 91L, name = "Test sender"), recipient = PartyDto(id = 92L, name = "Test recipient"),
        items = emptyList(), latestGenerationJob = null, version = 3L,
        freeText = freeText, servicePeriodFrom = servicePeriodFrom, servicePeriodTo = servicePeriodTo,
        projectNumber = projectNumber,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun Component.hasClass(name: String): Boolean =
        (this as? com.vaadin.flow.component.HasStyle)?.hasClassName(name) == true

    /** Builds the entry pane and selects the "Rechnung"-Tab's "Notizen" tab (index 2 — the third
     *  `tabSheet.add(...)` call in [buildEntryPane]) so [HtmlFreeTextEditor]/[FreeTextChips] become
     *  reachable via [flatten] — same TabSheet lazy-attach caveat
     *  [InvoiceDetailViewOverviewEntryPaneTest.switchToEinvoiceTab] documents for Tab 2. */
    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto): EntryPaneResult {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = {})
        com.vaadin.flow.component.UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        val tabSheet = flatten(result.component).filterIsInstance<com.vaadin.flow.component.tabs.TabSheet>().single()
        tabSheet.selectedIndex = 2
        MockVaadin.clientRoundtrip()
        return result
    }

    private fun freeTextEditor(component: Component): HtmlFreeTextEditor =
        flatten(component).filterIsInstance<HtmlFreeTextEditor>().single()

    // Element.callJsFunction("name", args) compiles to "return $0.name($1, …)" with parameters
    // [element, arg1, …]; Karibu runs no browser, so the queued invocations are the observable
    // effect of a caret insertion or a read-only switch.
    private fun pendingCalls(functionName: String): List<List<Any?>> {
        val ui = UI.getCurrent()
        ui.internals.stateTree.runExecutionsBeforeClientResponse()
        return ui.internals.dumpPendingJavaScriptInvocations()
            .filter { it.invocation.expression.contains(".$functionName(") }
            .map { it.invocation.parameters }
    }

    private fun freeTextChips(component: Component): FreeTextChips =
        flatten(component).filterIsInstance<FreeTextChips>().single()

    private fun chipButtons(component: Component): List<Button> =
        flatten(freeTextChips(component)).filterIsInstance<Button>()

    /** The chip's two caption parts: (short label, value). */
    private fun captionParts(chip: Button): Pair<String, String> {
        val spans = chip.children.toList().filterIsInstance<com.vaadin.flow.component.html.Span>()
        assertEquals(2, spans.size, "a chip caption is exactly two elements")
        assertTrue(spans[0].hasClassName("lt-freetext-chip__label"))
        assertTrue(spans[1].hasClassName("lt-freetext-chip__value"))
        return spans[0].text to spans[1].text
    }

    /** Profile 5 with a BT-18 invoice-header field whose long definition text must never show. */
    private fun stubFreeHeaderTermBt18(value: String) {
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(5L)
        val profile = B2bProfileDto(id = 5L, name = "Neckar")
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile))
        whenever(refs.b2bProfileQueries.findB2bProfileById(5L)).thenReturn(profile)
        val field = B2bProfileFieldDto(term = B2bTerm.BT_18, businessDefinition = "Invoiced object identifier")
        whenever(refs.b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(5L)).thenReturn(listOf(field))
        whenever(refs.b2bFieldValueQueries.findB2bGuidedFieldValues(1L)).thenReturn(
            mapOf(
                "BT-18" to de.littletrotter.application.dto.ResolvedB2bFieldValueDto(
                    value = value,
                    origin = de.littletrotter.domain.reference.B2bFieldValueOrigin.INVOICE,
                    inheritedValue = null,
                ),
            ),
        )
    }

    /** Selects tab [index] (0 = "Rechnung", 2 = "Notizen") and round-trips, so that tab's own
     *  fields become [flatten]-reachable — see [buildPane]'s KDoc. */
    private fun selectTab(pane: EntryPaneResult, index: Int) {
        val tabSheet = flatten(pane.component).filterIsInstance<com.vaadin.flow.component.tabs.TabSheet>().single()
        tabSheet.selectedIndex = index
        MockVaadin.clientRoundtrip()
    }

    // ── chip row content ────────────────────────────────────────────────────────────────────────

    @Test
    fun `B1 - no B2B profile and no service period or project number renders no chips`() {
        val view = createView()
        val pane = buildPane(view, invoice())

        assertTrue(chipButtons(pane.component).isEmpty())
        assertFalse(freeTextChips(pane.component).isVisible, "an empty chip row must not reserve layout space")
    }

    @Test
    fun `B2 - exactly one candidate filled renders exactly one chip`() {
        val view = createView()
        val pane = buildPane(view, invoice(projectNumber = "PRJ-42"))

        val buttons = chipButtons(pane.component)
        assertEquals(1, buttons.size)
        assertEquals(UiTranslations.tr("invoiceModule.projectNumber") to "PRJ-42", captionParts(buttons.single()))
    }

    @Test
    fun `editing the project number in the header refreshes the chip row`() {
        val view = createView()
        val pane = buildPane(view, invoice())
        assertTrue(chipButtons(pane.component).isEmpty())

        val label = UiTranslations.tr("invoiceModule.projectNumber")
        val field = (0..1).firstNotNullOf { index ->
            selectTab(pane, index)
            flatten(pane.component).filterIsInstance<com.vaadin.flow.component.textfield.TextField>().firstOrNull { it.label == label }
        }
        field.value = "PRJ-NEW"
        selectTab(pane, 2)

        assertEquals(UiTranslations.tr("invoiceModule.projectNumber") to "PRJ-NEW", captionParts(chipButtons(pane.component).single()))
    }

    @Test
    fun `the chip caption shows the short label and the value in two elements, title is the plain action sentence`() {
        val view = createView()
        val pane = buildPane(view, invoice(projectNumber = "4711"))

        val chip = chipButtons(pane.component).single()

        assertEquals("Projektnummer" to "4711", captionParts(chip))
        assertEquals("Projektnummer: \u201E4711\" in den Freitext einf\u00FCgen", chip.element.getAttribute("title"))
        assertEquals("Projektnummer: 4711", chip.element.getAttribute("aria-label"))
    }

    @Test
    fun `a 200 character value is present in full in the aria-label and the value element`() {
        val long = "x".repeat(200)
        val view = createView()
        val pane = buildPane(view, invoice(projectNumber = long))

        val chip = chipButtons(pane.component).single()

        assertEquals(long, captionParts(chip).second)
        assertTrue(chip.element.getAttribute("aria-label").endsWith(long))
        assertTrue(chip.element.getAttribute("title").contains(long))
    }

    @Test
    fun `service period and project number both filled render two chips`() {
        val view = createView()
        val pane = buildPane(
            view,
            invoice(servicePeriodFrom = LocalDate.of(2026, 8, 1), servicePeriodTo = LocalDate.of(2026, 8, 10), projectNumber = "PRJ-7"),
        )

        assertEquals(2, chipButtons(pane.component).size)
    }

    // ── a FREE/HEADER B2B term value renders a chip, generically sourced ─────────────────────────

    @Test
    fun `a FREE header B2B term value entered in the inner wizard renders a chip with the short label`() {
        stubFreeHeaderTermBt18("IA-1")
        val view = createView()

        val pane = buildPane(view, invoice())

        val buttons = chipButtons(pane.component)
        assertEquals(1, buttons.size)
        assertEquals("Objektnr." to "IA-1", captionParts(buttons.single()))
        assertFalse(
            buttons.single().element.outerHTML.contains("Invoiced object identifier"),
            "the profile field's long definition text must not appear on the chip",
        )
    }

    @Test
    fun `an abbreviated chip carries the full name and the value in its tooltip and aria-label`() {
        stubFreeHeaderTermBt18("IA-1")
        val view = createView()

        val pane = buildPane(view, invoice())

        val chip = chipButtons(pane.component).single()
        val title = chip.element.getAttribute("title")
        assertTrue(title.contains("Objektnummer"), "the tooltip names the full term: $title")
        assertTrue(title.contains("IA-1"), "the tooltip shows the value: $title")
        assertEquals("Objektnummer: IA-1", chip.element.getAttribute("aria-label"))
    }

    @Test
    fun `clicking an abbreviated B2B chip inserts exactly the value, no label and no colon`() {
        stubFreeHeaderTermBt18("Herr Müller")
        val view = createView()
        val pane = buildPane(view, invoice())

        chipButtons(pane.component).single().click()

        assertEquals(listOf("Herr Müller"), pendingCalls("insertText").map { it[1] })
    }

    // ── click inserts at the caret, never overwrites ────────────────────────────────────────────

    @Test
    fun `B4 - clicking a chip inserts exactly the value at the caret and leaves existing content alone`() {
        val view = createView()
        val pane = buildPane(view, invoice(freeText = "Bestehender Text", projectNumber = "PRJ-42"))
        val editor = freeTextEditor(pane.component)
        pendingCalls("insertText")

        chipButtons(pane.component).single().click()

        assertEquals(listOf("PRJ-42"), pendingCalls("insertText").map { it[1] })
        assertEquals("Bestehender Text", editor.value)
    }

    @Test
    fun `clicking the same chip twice inserts the value twice`() {
        val view = createView()
        val pane = buildPane(view, invoice(projectNumber = "PRJ-42"))
        val button = chipButtons(pane.component).single()

        button.click()
        button.click()

        assertEquals(listOf("PRJ-42", "PRJ-42"), pendingCalls("insertText").map { it[1] })
    }

    // ── a chip-imported B2B term is remembered, so the automatic merge does not append it again ──

    @Test
    fun `a clicked B2B term chip is passed to the save as already imported`() {
        stubFreeHeaderTermBt18("IA-1")
        val current = invoice().copy(buyerReference = "BR-1") // a B2B profile makes the buyer reference mandatory
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(org.mockito.kotlin.any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        chipButtons(pane.component).single().click()
        // The browser reports the inserted text back as the editor's new value.
        freeTextEditor(pane.component).value = "IA-1"
        selectTab(pane, 0)
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(LocalDate.of(2026, 6, 1))

        flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }.click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        org.mockito.kotlin.verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals(setOf("BT-18"), captor.firstValue.manuallyImportedB2bCodes)
        assertEquals("IA-1", captor.firstValue.freeText)
    }

    @Test
    fun `an untouched B2B term chip is not marked as imported on save`() {
        stubFreeHeaderTermBt18("IA-1")
        val current = invoice().copy(buyerReference = "BR-1") // a B2B profile makes the buyer reference mandatory
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(org.mockito.kotlin.any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        selectTab(pane, 0)
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(LocalDate.of(2026, 6, 1))

        flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }.click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        org.mockito.kotlin.verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertTrue(captor.firstValue.manuallyImportedB2bCodes.isEmpty())
    }

    // ── the invoice uses the mail editor, with the 4000-character cap ────────────────────────────

    @Test
    fun `the free text is edited with the mail editor surface, labelled and capped at the markup limit`() {
        val view = createView()
        val pane = buildPane(view, invoice())
        val editor = freeTextEditor(pane.component)

        assertEquals(4000, FreeTextMarkup.MAX_LENGTH)
        assertTrue(editor.hasClass("lt-html-freetext-editor"))
        val label = flatten(editor).single { it.hasClass("lt-html-freetext-editor__label") }
        assertEquals(UiTranslations.tr("invoiceDetail.freeText"), label.element.text)
        val invoiceNote = flatten(pane.component).filterIsInstance<com.vaadin.flow.component.textfield.TextArea>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceNote") }
        assertEquals(4000, invoiceNote.maxLength)
    }

    @Test
    fun `the editor keeps 4000 characters and cuts 4001, with a live counter`() {
        val view = createView()
        val pane = buildPane(view, invoice())
        val editor = freeTextEditor(pane.component)

        editor.value = "a".repeat(FreeTextMarkup.MAX_LENGTH + 1)

        assertEquals(FreeTextMarkup.MAX_LENGTH, editor.value.length)
        val counter = flatten(editor).filterIsInstance<com.vaadin.flow.component.html.Span>()
            .single { it.hasClassName("lt-html-freetext-editor__counter") }
        assertEquals("4000 / 4000", counter.text)
    }

    // ── the value round-trips through the binder to the saved command ────────────────────────────

    private fun saveAndCaptureFreeText(pane: EntryPaneResult): String? {
        selectTab(pane, 0)
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(LocalDate.of(2026, 6, 1))

        flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }.click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        org.mockito.kotlin.verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        return captor.firstValue.freeText
    }

    @Test
    fun `B9 - every format and list kind in the editor is exactly what the binder reads back on save`() {
        val current = invoice()
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(org.mockito.kotlin.any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        val markdown = "**Wichtig** *bitte beachten* ++unterstrichen++ ~~alt~~\n- Punkt eins\n    1. Unterpunkt\n2. Zwei"
        freeTextEditor(pane.component).value = markdown

        assertEquals(markdown, saveAndCaptureFreeText(pane))
    }

    @Test
    fun `a stored free text is shown in the editor when the pane opens`() {
        val view = createView()
        val pane = buildPane(view, invoice(freeText = "**Alt** Text"))

        assertEquals("**Alt** Text", freeTextEditor(pane.component).value)
    }

    @Test
    fun `the editor switches to read-only through setReadOnly`() {
        val view = createView()
        val pane = buildPane(view, invoice())
        val field = FreeTextEditorField(freeTextEditor(pane.component))
        pendingCalls("setReadOnly")

        field.isReadOnly = true

        assertEquals(listOf<Any?>(true), pendingCalls("setReadOnly").map { it[1] })
        assertTrue(field.isReadOnly)
    }

    // ── read-only invoice: the entry pane (editor + chips) is not built at all ────────────────────
    //
    // buildEntryPane (and with it HtmlFreeTextEditor/FreeTextChips) is only ever invoked from
    // InvoiceDetailView.renderInvoice while `!current.isReadOnly()`; the read-only rule renders
    // InvoiceOverviewPanel instead, which shows the free text formatted — no editor, no chips.

    private fun freeTextHtmls(panel: Component): List<Html> =
        flatten(panel).filterIsInstance<Html>().filter { it.element.classList.contains("lt-ft") }

    @Test
    fun `B5 - a read-only invoice renders the free text formatted, no editor or chips`() {
        whenever(detailQueryPort.findCompletenessChecks(org.mockito.kotlin.any())).thenReturn(emptyList())
        whenever(detailQueryPort.findB2bComplianceFindings(org.mockito.kotlin.any())).thenReturn(emptyList())
        val panel = InvoiceOverviewPanel(detailQueryPort, mock())

        panel.render(invoice(status = InvoiceStatus.ISSUED.ordinal, freeText = "**Wichtig** Text\n- Punkt"))

        assertTrue(flatten(panel).filterIsInstance<HtmlFreeTextEditor>().isEmpty(), "a read-only invoice must render no editor")
        assertTrue(flatten(panel).filterIsInstance<FreeTextChips>().isEmpty(), "a read-only invoice must render no chips")
        val html = freeTextHtmls(panel).single()
        assertTrue(html.element.outerHTML.contains("<b>Wichtig</b>"), html.element.outerHTML)
        assertFalse(html.element.outerHTML.contains("**"), "the markers must not be shown")
    }

    @Test
    fun `B5 - a read-only invoice without free text shows the placeholder instead of a formatted block`() {
        whenever(detailQueryPort.findCompletenessChecks(org.mockito.kotlin.any())).thenReturn(emptyList())
        whenever(detailQueryPort.findB2bComplianceFindings(org.mockito.kotlin.any())).thenReturn(emptyList())
        val panel = InvoiceOverviewPanel(detailQueryPort, mock())

        panel.render(invoice(status = InvoiceStatus.ISSUED.ordinal, freeText = "  "))

        assertTrue(freeTextHtmls(panel).isEmpty())
    }
}
