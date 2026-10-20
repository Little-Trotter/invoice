package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.InvoiceFormModuleRegistry
import de.littletrotter.application.invoice.InvoiceFormatModule
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.Money
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The entry pane's "Rechnungsart" `ComboBox` ([InvoiceFormatModule], rendered
 * via `ModuleFieldSection` into [de.littletrotter.ui.view.invoice.buildEntryPane]'s format
 * section). Covers exactly the required behavior: the field is always
 * visible (never hidden), its enabled/disabled state mirrors
 * [de.littletrotter.domain.invoice.isInvoiceFormatEditable] (the same predicate
 * `InvoiceLifecycleService.updateHeader` enforces server-side — see
 * `InvoiceLifecycleServiceTest`'s `updateHeader`/`setReverseChargeDecision` tests for that half),
 * and a selected value actually reaches [UpdateInvoiceHeaderCommand.invoiceFormat] on save.
 */
class InvoiceFormatComboBoxKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    // Component.getChildren() (not a HasComponents-gated cast) — TabSheet, e.g., holds each
    // tab's content in the Element tree without implementing HasComponents, so a HasComponents
    // cast (the InvoiceExportPanel Karibu test's pattern, fine there since InvoiceExportPanel
    // never nests a TabSheet) would silently stop descending at the first TabSheet and never see
    // the "E-Rechnung" tab's contents at all.
    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { child -> flatten(child) }

    private fun createView(
        detailQueryPort: InvoiceDetailQueryPort = mock(),
        refs: ReferenceDataMocks = ReferenceDataMocks(),
        recipientQueryPort: RecipientQueryPort = mock(),
        senderQueryPort: SenderQueryPort = mock(),
        editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock(),
    ): InvoiceDetailView {
        val registry = mock<InvoiceFormModuleRegistry>()
        whenever(registry.modules()).thenReturn(listOf(InvoiceFormatModule()))
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
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
                invoiceFormModuleRegistry = registry,
            ),
        )
    }

    private fun invoice(
        sellerVatRegistrationCountry: Country? = null,
        reverseCharge: Boolean = false,
        invoiceFormat: InvoiceFormat = InvoiceFormat.GERMAN,
        sender: PartyDto? = null,
        recipient: PartyDto? = null,
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-2026-08-00001", status = InvoiceStatus.DRAFT.ordinal,
        // invoiceDateField is a required entry-pane field;
        // an empty (null) invoiceDate here would block binder.writeBeanIfValid before the save
        // tests below ever reach the (mocked) facade call.
        invoiceDate = LocalDate.now(), netAmount = null, vatAmount = null, grossAmount = null,
        sender = sender, recipient = recipient, items = emptyList(), latestGenerationJob = null,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry?.alpha2,
        reverseCharge = reverseCharge,
        invoiceFormat = invoiceFormat.ordinal,
    )

    // The save button's click listener blocks on senderBox.value == null / recipientBox.value ==
    // null (mandatory-field validation added by d400b69a0) before ever building
    // UpdateInvoiceHeaderCommand. Both boxes are populated from formState via
    // senderQueryPort.findSenderById/recipientQueryPort.findRecipientById during buildEntryPane's binder.readBean —
    // mirrors the stubbing pattern in InvoiceDetailViewEntryPaneSaveKaribuTest.
    private fun stubSenderAndRecipient(recipientQueryPort: RecipientQueryPort, senderQueryPort: SenderQueryPort) {
        val sender = testSender(91L, "Test sender")
        val recipient = testRecipient(92L, "Test recipient")
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(92L)).thenReturn(recipient)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(sender))
    }

    // Mockito's default answer for an unstubbed Long?-returning method is 0, not null — left
    // unstubbed, findB2bProfileIdForInvoice(1L) == 0 (non-null) wrongly triggers e-invoice guided
    // mode, which adds a "missing buyer reference" validation issue that blocks the save before it
    // ever reaches editInvoiceHeaderUseCase.updateInvoiceHeader. Same workaround as
    // InvoiceDetailViewEntryPaneSaveKaribuTest.
    private fun stubNoB2bProfile(refs: ReferenceDataMocks) {
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
    }

    private fun formatCombo(component: Component): ComboBox<Any?> =
        flatten(component).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceModule.invoiceFormat") }
            .requireAs()

    // TabSheet only actually attaches the currently-selected tab's content into the real Element
    // tree once the TabSheet itself is attached to a live UI (TabSheet.ensureSelectedTabContentAttached,
    // deferred via runWhenAttached/beforeClientResponse) — component.getChildren() sees nothing
    // below it otherwise, tab content included. Attaching pane.component to the mocked UI here is
    // what makes the "E-Rechnung" tab's ModuleFieldSection fields (the format ComboBox included)
    // show up in the traversal at all.
    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto): EntryPaneResult {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = {})
        com.vaadin.flow.component.UI.getCurrent().add(result.component)
        // TabSheet only actually attaches the selected tab's content into the real Element tree
        // via a deferred beforeClientResponse callback, run at the end of a real request; Karibu's
        // clientRoundtrip() is what flushes that here, or getChildren() would see nothing past the
        // TabSheet.
        MockVaadin.clientRoundtrip()
        // The format ComboBox (InvoiceFormatModule) lives on the "E-Rechnung" tab (tab index 1),
        // not the initially-selected "Rechnung" tab — TabSheet only ever attaches the *selected*
        // tab's content (see above), so it must be switched to before it becomes reachable at all.
        val tabSheet = flatten(result.component).filterIsInstance<com.vaadin.flow.component.tabs.TabSheet>().single()
        tabSheet.selectedIndex = 1
        MockVaadin.clientRoundtrip()
        return result
    }

    // ── visibility ───────────────────────────────────────────────────────────

    @Test
    fun `the format ComboBox is always rendered, even when forced to GERMAN`() {
        val view = createView()
        val pane = buildPane(view, invoice())

        val combo = formatCombo(pane.component)

        assertTrue(combo.isVisible)
    }

    // ── enable rule (mirrors isInvoiceFormatEditable) ───────────────────────────

    @Test
    fun `the format ComboBox is disabled with an explanatory helper text when neither condition holds`() {
        val view = createView()
        val pane = buildPane(view, invoice(sellerVatRegistrationCountry = null, reverseCharge = false))

        val combo = formatCombo(pane.component)

        assertFalse(combo.isEnabled)
        assertEquals(UiTranslations.tr("invoiceModule.invoiceFormat.forcedGerman"), combo.helperText)
    }

    @Test
    fun `the format ComboBox is enabled when a seller VAT registration is active`() {
        val view = createView()
        val pane = buildPane(view, invoice(sellerVatRegistrationCountry = Country.LU, reverseCharge = false))

        val combo = formatCombo(pane.component)

        assertTrue(combo.isEnabled)
    }

    @Test
    fun `the format ComboBox is enabled when reverseCharge is true even without a seller VAT registration`() {
        val view = createView()
        val pane = buildPane(view, invoice(sellerVatRegistrationCountry = null, reverseCharge = true))

        val combo = formatCombo(pane.component)

        assertTrue(combo.isEnabled)
    }

    // ── RC default: INTERNATIONAL ────────────────────────────────────────────

    @Test
    fun `once reverseCharge preselected INTERNATIONAL server-side, the ComboBox shows it and stays editable`() {
        // Simulates the state right after InvoiceLifecycleService.setReverseChargeDecision(true)
        // applied its one-time INTERNATIONAL default (see InvoiceLifecycleServiceTest) and the
        // entry pane was rebuilt from that persisted state.
        val view = createView()
        val pane = buildPane(view, invoice(sellerVatRegistrationCountry = null, reverseCharge = true, invoiceFormat = InvoiceFormat.INTERNATIONAL))

        val combo = formatCombo(pane.component)

        assertTrue(combo.isEnabled)
        assertEquals("INTERNATIONAL", combo.value)
    }

    // ── the choice persists through the save command ────────────────────────

    @Test
    fun `switching back to GERMAN while editable and saving forwards GERMAN, not the old value, into the command`() {
        val refs = ReferenceDataMocks()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val senderQueryPort = mock<SenderQueryPort>()
        val editInvoiceHeaderUseCase = mock<EditInvoiceHeaderUseCase>()
        stubSenderAndRecipient(recipientQueryPort, senderQueryPort)
        stubNoB2bProfile(refs)
        val current = invoice(
            sellerVatRegistrationCountry = null, reverseCharge = true, invoiceFormat = InvoiceFormat.INTERNATIONAL,
            sender = PartyDto(id = 91L, name = "Test sender"), recipient = PartyDto(id = 92L, name = "Test recipient"),
        )
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(
            InvoiceDetailDto(
                id = 1L, invoiceNumber = "INV-2026-08-00001", status = InvoiceStatus.DRAFT.ordinal,
                invoiceDate = null, netAmount = Money.ZERO, vatAmount = Money.ZERO, grossAmount = Money.ZERO,
                sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
            ),
        )
        val view = createView(
            refs = refs, recipientQueryPort = recipientQueryPort,
            senderQueryPort = senderQueryPort, editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
        )
        val pane = buildPane(view, current)
        val combo = formatCombo(pane.component)
        assertTrue(combo.isEnabled, "sanity: field must be editable for the user to change it at all")

        combo.value = "GERMAN"
        val saveBtn = flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }
        saveBtn.click()

        val captor = argumentCaptor<UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals("GERMAN", captor.firstValue.invoiceFormat)
    }

    @Test
    fun `leaving INTERNATIONAL untouched and saving forwards INTERNATIONAL into the command`() {
        val refs = ReferenceDataMocks()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val senderQueryPort = mock<SenderQueryPort>()
        val editInvoiceHeaderUseCase = mock<EditInvoiceHeaderUseCase>()
        stubSenderAndRecipient(recipientQueryPort, senderQueryPort)
        stubNoB2bProfile(refs)
        val current = invoice(
            sellerVatRegistrationCountry = null, reverseCharge = true, invoiceFormat = InvoiceFormat.INTERNATIONAL,
            sender = PartyDto(id = 91L, name = "Test sender"), recipient = PartyDto(id = 92L, name = "Test recipient"),
        )
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(
            InvoiceDetailDto(
                id = 1L, invoiceNumber = "INV-2026-08-00001", status = InvoiceStatus.DRAFT.ordinal,
                invoiceDate = null, netAmount = Money.ZERO, vatAmount = Money.ZERO, grossAmount = Money.ZERO,
                sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
            ),
        )
        val view = createView(
            refs = refs, recipientQueryPort = recipientQueryPort,
            senderQueryPort = senderQueryPort, editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
        )
        val pane = buildPane(view, current)

        val saveBtn = flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }
        saveBtn.click()

        val captor = argumentCaptor<UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals("INTERNATIONAL", captor.firstValue.invoiceFormat)
    }
}
