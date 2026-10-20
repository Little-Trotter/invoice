package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.dto.SellerVatRegistrationDto
import de.littletrotter.application.dto.SnapshotFieldChangeDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `InvoiceDetailViewOverview.kt`'s [buildEntryPane] had only its B2B-profile resolution
 * covered before this file ([InvoiceDetailViewB2bProfileTest]) plus its "Rechnungsart" ComboBox
 * ([InvoiceFormatComboBoxKaribuTest], which also already exercises the save button's success path
 * — not repeated here). Covers: the atomic reverse-charge decision field and its context-reset
 * behavior, "Stammdaten erneut übernehmen"'s three outcomes (error / no changes / changes-need-
 * confirmation), `requestExit`'s dirty-check branch, the save button's date-guard early return, and
 * the buyerReference override's initial-state fork plus its pencil-button unlock and its
 * not-yet-overridden re-suggest-on-recipient-change listener.
 *
 * Not attempted: actually confirming/cancelling a [ConfirmDialog] (no public API to fire its
 * confirm/cancel short of constructing and firing the internal event class by hand — no other test
 * in this module does that either) — only that the right dialog opens with the right header is
 * asserted, via its `header`/`message` element properties (`ConfirmDialog` exposes no getters).
 */
class InvoiceDetailViewOverviewEntryPaneTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val refs = ReferenceDataMocks()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val senderQueryPort: SenderQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val defaultSender = testSender(91L, "Test sender")
        val defaultRecipient = testRecipient(92L, "Test recipient")
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(defaultSender)
        whenever(recipientQueryPort.findRecipientById(92L)).thenReturn(defaultRecipient)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(defaultSender))
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

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
            ),
        )
    }

    private fun invoice(
        status: Int = InvoiceStatus.DRAFT.ordinal,
        sellerVatRegistrationCountry: String? = null,
        reverseCharge: Boolean = false,
        sender: PartyDto? = PartyDto(id = 91L, name = "Test sender"),
        recipient: PartyDto? = PartyDto(id = 92L, name = "Test recipient"),
        buyerReference: String? = null,
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = sender, recipient = recipient, items = emptyList(), latestGenerationJob = null,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry, reverseCharge = reverseCharge,
        buyerReference = buyerReference, version = 3L,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    /** Builds the entry pane and attaches Tab 1 ("Rechnung", the default-selected tab, holding
     *  everything under test here) to the live UI so its fields become reachable via [flatten]. */
    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto, onDone: () -> Unit = {}): EntryPaneResult {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = onDone)
        com.vaadin.flow.component.UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result
    }

    /** buyerReference/purchaseOrderReference/orderReference live on the "E-Rechnung" tab (built by
     *  `buildEinvoiceTab`, not Tab 1's own sections) — same TabSheet lazy-attach caveat as
     *  [InvoiceFormatComboBoxKaribuTest.buildPane]'s KDoc explains; switch and roundtrip once more
     *  before looking for anything from that tab. */
    private fun switchToEinvoiceTab(pane: EntryPaneResult) {
        val tabSheet = flatten(pane.component).filterIsInstance<com.vaadin.flow.component.tabs.TabSheet>().single()
        tabSheet.selectedIndex = 1
        MockVaadin.clientRoundtrip()
    }

    // The reverse-charge decision is a ReverseChargeField (a CustomField<Boolean>, binary,
    // never null); it is the only instance of its type on the pane, so no label filter is needed.
    private fun reverseChargeDecisionBox(component: Component): ReverseChargeField =
        flatten(component).filterIsInstance<ReverseChargeField>().single()

    private fun buttonByText(component: Component, text: String): Button =
        flatten(component).filterIsInstance<Button>().single { it.text == text }

    /** Stubs a recipient at [id] with a billing address in [country] (both lookups the entry pane
     *  needs: `findRecipientById` for the initial form value, `findBillingAddress` for the
     *  visibility rule) and returns the [PartyDto] the `invoice()` fixture expects. */
    private fun recipientWithVatCountry(id: Long, country: Country): PartyDto {
        val domainRecipient = testRecipient(id, "Kunde $country", customerType = CustomerType.BUSINESS)
        whenever(recipientQueryPort.findRecipientById(id)).thenReturn(domainRecipient)
        whenever(recipientQueryPort.findBillingAddress(id)).thenReturn(testAddress(country.alpha2))
        return PartyDto(id = id, name = domainRecipient.name)
    }

    // ── visibility: shown only for a writable invoice whose seller/recipient VAT countries
    // differ; hidden field always carries No ───────────────────────────────────────────────────

    @Test
    fun `the field is hidden for a domestic recipient sharing the seller's VAT country`() {
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipientWithVatCountry(201L, Country.DE)))

        assertFalse(reverseChargeDecisionBox(pane.component).isVisible)
        assertEquals(false, reverseChargeDecisionBox(pane.component).value)
    }

    @Test
    fun `the field is hidden while no recipient is selected yet`() {
        val view = createView()
        val pane = buildPane(view, invoice(recipient = null))

        assertFalse(reverseChargeDecisionBox(pane.component).isVisible)
    }

    @Test
    fun `the field is hidden for a recipient without a known VAT country`() {
        // The default fixture recipient (id 92) has no findBillingAddress stub — an unmapped
        // recipient country is exactly the "unknown" case the visibility rule must hide for.
        val view = createView()
        val pane = buildPane(view, invoice())

        assertFalse(reverseChargeDecisionBox(pane.component).isVisible)
    }

    @Test
    fun `the field is visible for a cross-border recipient and defaults to No`() {
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipientWithVatCountry(202L, Country.FR)))

        assertTrue(reverseChargeDecisionBox(pane.component).isVisible)
        assertEquals(false, reverseChargeDecisionBox(pane.component).value)
    }

    @Test
    fun `the field is hidden on a read-only invoice even for a cross-border recipient`() {
        val view = createView()
        val pane = buildPane(
            view,
            invoice(status = InvoiceStatus.ISSUED.ordinal, recipient = recipientWithVatCountry(203L, Country.FR)),
        )

        assertFalse(reverseChargeDecisionBox(pane.component).isVisible)
        assertFalse(buttonByText(pane.component, UiTranslations.tr("invoiceDetail.entry.refreshPartySnapshot")).isVisible)
    }

    // ── the decision travels in the atomic header command, never null ────────────────────────────

    @Test
    fun `an invoice already marked reverse-charge opens with yes selected for a cross-border recipient`() {
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipientWithVatCountry(204L, Country.FR), reverseCharge = true))

        assertTrue(reverseChargeDecisionBox(pane.component).isVisible)
        assertEquals(true, reverseChargeDecisionBox(pane.component).value)
    }

    @Test
    fun `a programmatic reverse-charge value change opens no confirm dialog`() {
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipientWithVatCountry(205L, Country.FR), reverseCharge = true))

        reverseChargeDecisionBox(pane.component).value = false

        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `selecting reverse-charge as a real client action opens no separate transaction dialog`() {
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipientWithVatCountry(206L, Country.FR), reverseCharge = true))
        val box = reverseChargeDecisionBox(pane.component)

        box._setValue(false)
        box._setValue(true)

        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    // ── reverse-charge decision persistence: untouched save, created-Yes survives, recipient-change
    // resets, command decision is never null ───────────────────────────────────────────────────────

    @Test
    fun `an untouched cross-border form saves an explicit No, not a dropped decision`() {
        val current = invoice(recipient = recipientWithVatCountry(207L, Country.FR))
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(java.time.LocalDate.of(2026, 6, 1))

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals(false, captor.firstValue.reverseChargeDecision, "an untouched cross-border form must submit an explicit No")
    }

    @Test
    fun `a Yes decided at creation time survives an untouched open and save, never a silent flip`() {
        val current = invoice(recipient = recipientWithVatCountry(208L, Country.FR), reverseCharge = true)
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        assertEquals(true, reverseChargeDecisionBox(pane.component).value, "sanity: opens with the creation-time Yes")
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(java.time.LocalDate.of(2026, 6, 1))

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals(true, captor.firstValue.reverseChargeDecision, "an untouched save must not flip the creation-time Yes")
    }

    @Test
    fun `a client-driven recipient change resets Yes to No and keeps the field visible for another cross-border recipient`() {
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipientWithVatCountry(209L, Country.FR), reverseCharge = true))
        val box = reverseChargeDecisionBox(pane.component)
        assertEquals(true, box.value, "sanity: the invoice opens with an explicit Yes")
        val recipientBox = recipientBoxOf(pane.component)
        recipientWithVatCountry(210L, Country.BE)

        recipientBox._setValue(testRecipient(210L, "Other cross-border recipient"))

        assertEquals(false, box.value, "a client-driven recipient change must reset the decision to No, never keep a stale Yes")
        assertTrue(box.isVisible, "still cross-border (BE vs. the seller's DE default) — the field must stay visible")
    }

    @Test
    fun `a client-driven recipient change to a domestic one hides the field, resets to No, and that persists`() {
        val current = invoice(recipient = recipientWithVatCountry(211L, Country.FR), reverseCharge = true)
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        val box = reverseChargeDecisionBox(pane.component)
        val recipientBox = recipientBoxOf(pane.component)
        whenever(recipientQueryPort.findBillingAddress(212L)).thenReturn(testAddress(Country.DE.alpha2))

        recipientBox._setValue(testRecipient(212L, "Domestic recipient"))

        assertFalse(box.isVisible, "a domestic recipient must hide the field")
        assertEquals(false, box.value, "hiding the field must reset the decision to No")

        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(java.time.LocalDate.of(2026, 6, 1))
        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals(false, captor.firstValue.reverseChargeDecision, "the reset to No triggered by the recipient change must reach the save command")
    }

    @Test
    fun `the save command never carries a null reverse-charge decision`() {
        val current = invoice()
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(java.time.LocalDate.of(2026, 6, 1))

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertNotNull(captor.firstValue.reverseChargeDecision, "B9 — never null, regardless of visibility")
    }

    // ── happy path: visible+No for a cross-border recipient, choose Yes, save, reload, then
    // switching to a domestic recipient hides the field again ──────────────────────────────────

    @Test
    fun `happy path - cross-border field visible with No, chosen Yes saves, and a domestic recipient hides it again`() {
        val current = invoice(recipient = recipientWithVatCountry(213L, Country.FR))
        val view = createView()
        val pane = buildPane(view, current)
        val box = reverseChargeDecisionBox(pane.component)
        assertTrue(box.isVisible, "cross-border recipient — the field must be visible")
        assertEquals(false, box.value, "the field must default to No")

        box._setValue(true)
        // recipientWithVatCountry itself stubs the facade via whenever(...) — must be resolved to
        // a local val first, never nested as an argument inside another whenever(...).thenReturn(...)
        // chain (that corrupts Mockito's argument-matcher stack: UnfinishedStubbing).
        val savedWithYes = invoice(recipient = recipientWithVatCountry(213L, Country.FR), reverseCharge = true)
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(savedWithYes)
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(java.time.LocalDate.of(2026, 6, 1))
        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals(true, captor.firstValue.reverseChargeDecision, "the chosen Yes must reach the save command")

        // Reload — a fresh view/pane built from the now-persisted Yes.
        val reloadedPane = buildPane(createView(), invoice(recipient = PartyDto(id = 213L, name = "Kunde FR"), reverseCharge = true))
        val reloadedBox = reverseChargeDecisionBox(reloadedPane.component)
        assertTrue(reloadedBox.isVisible)
        assertEquals(true, reloadedBox.value, "reload must show the persisted Yes")

        // Recipient changed to a domestic one — the field disappears, value resets to No.
        val recipientBox = recipientBoxOf(reloadedPane.component)
        whenever(recipientQueryPort.findBillingAddress(214L)).thenReturn(testAddress(Country.DE.alpha2))
        recipientBox._setValue(testRecipient(214L, "Domestic recipient"))

        assertFalse(reloadedBox.isVisible, "a domestic recipient must hide the field")
        assertEquals(false, reloadedBox.value)
    }

    // ── the "Termine" section heading now reads "Datumsangaben" ────────────────────────────────

    @Test
    fun `the dates section heading reads Datumsangaben, replacing the former Termine label`() {
        val view = createView()
        val pane = buildPane(view, invoice())

        val eyebrows = flatten(pane.component).filterIsInstance<com.vaadin.flow.component.html.Span>()
            .filter { it.hasClassName("bau-eyebrow") }
            .map { it.text }

        assertTrue(eyebrows.contains("Datumsangaben"), "expected the renamed heading among: $eyebrows")
        assertFalse(eyebrows.contains("Termine"), "the former German label must no longer appear anywhere")
    }

    // ── the currency box lives with the parties, not with free text and invoice note ───────

    private fun currencyBoxOf(component: Component): com.vaadin.flow.component.combobox.ComboBox<*> =
        flatten(component).filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.currency") }

    private fun sectionTitled(root: Component, titleKey: String): Component =
        flatten(root).filter { it.element.classList.contains("entry-section") }.single { section ->
            flatten(section).filterIsInstance<com.vaadin.flow.component.html.Span>()
                .any { it.hasClassName("bau-eyebrow") && it.text == UiTranslations.tr(titleKey) }
        }

    @Test
    fun `the currency box sits in the parties section, not in the notes section`() {
        val view = createView()
        val pane = buildPane(view, invoice())
        val tabSheet = flatten(pane.component).filterIsInstance<com.vaadin.flow.component.tabs.TabSheet>().single()
        val tab1 = tabSheet.getComponent(tabSheet.getTabAt(0))
        val tab3 = tabSheet.getComponent(tabSheet.getTabAt(2))

        val parties = sectionTitled(tab1, "invoiceDetail.entry.section.parties")
        val notes = sectionTitled(tab3, "invoiceDetail.entry.section.notes")

        val currencyLabel = UiTranslations.tr("invoiceDetail.currency")
        val partiesLabels = flatten(parties).filterIsInstance<com.vaadin.flow.component.HasLabel>().mapNotNull { it.label }
        val notesLabels = flatten(notes).filterIsInstance<com.vaadin.flow.component.HasLabel>().mapNotNull { it.label }
        assertTrue(partiesLabels.contains(currencyLabel), "currency must be in the parties section: $partiesLabels")
        assertFalse(notesLabels.contains(currencyLabel), "currency must not be in the notes section: $notesLabels")
        assertTrue(notesLabels.contains(UiTranslations.tr("invoiceDetail.invoiceNote")), "notes keep the invoice note: $notesLabels")
        assertTrue(flatten(notes).any { it.element.classList.contains("lt-freetext-block") }, "notes keep the free text")
    }

    @Test
    fun `the currency box follows sender in the parties form layout`() {
        val view = createView()
        val pane = buildPane(view, invoice())
        val parties = sectionTitled(pane.component, "invoiceDetail.entry.section.parties")
        val formLayout = flatten(parties).filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>().first()
        val labels = formLayout.children.toList().mapNotNull { (it as? com.vaadin.flow.component.HasLabel)?.label }
        assertEquals(
            listOf("invoiceDetail.summary.name", "invoiceDetail.card.sender", "invoiceDetail.currency").map { UiTranslations.tr(it) },
            labels,
        )
    }

    @Test
    fun `saving persists the currency chosen in its new position`() {
        val current = invoice()
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(java.time.LocalDate.of(2026, 6, 1))
        currencyBoxOf(pane.component).requireAs<com.vaadin.flow.component.combobox.ComboBox<de.littletrotter.shared.Currency>>()
            ._setValue(de.littletrotter.shared.Currency.CHF)

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals(de.littletrotter.shared.Currency.CHF, captor.firstValue.currency)
    }

    // ── refresh-snapshot button: error / no changes / changes-need-confirmation ────────────────

    @Test
    fun `refresh-snapshot surfaces an error and opens no dialog when the preview call fails`() {
        whenever(invoiceDetailQueryPort.previewPartySnapshotRefresh(1L)).thenThrow(RuntimeException("db down"))
        val view = createView()
        val pane = buildPane(view, invoice())

        buttonByText(pane.component, UiTranslations.tr("invoiceDetail.entry.refreshPartySnapshot")).click()

        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `refresh-snapshot opens no dialog when the preview has no changes`() {
        whenever(invoiceDetailQueryPort.previewPartySnapshotRefresh(1L)).thenReturn(PartySnapshotDiffDto(emptyList(), emptyList()))
        val view = createView()
        val pane = buildPane(view, invoice())

        buttonByText(pane.component, UiTranslations.tr("invoiceDetail.entry.refreshPartySnapshot")).click()

        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `refresh-snapshot opens a confirmation dialog when the preview has changes`() {
        whenever(invoiceDetailQueryPort.previewPartySnapshotRefresh(1L)).thenReturn(
            PartySnapshotDiffDto(listOf(SnapshotFieldChangeDto("Name", "Alt GmbH", "Neu GmbH")), emptyList()),
        )
        val view = createView()
        val pane = buildPane(view, invoice())

        buttonByText(pane.component, UiTranslations.tr("invoiceDetail.entry.refreshPartySnapshot")).click()

        val dlg = _find<ConfirmDialog>().single()
        assertEquals(UiTranslations.tr("invoiceDetail.entry.refreshPartySnapshot.confirm.title"), dlg.element.getProperty("header"))
    }

    // ── requestExit: dirty check ─────────────────────────────────────────────────────────────────

    @Test
    fun `requestExit calls onDone directly when the form has no unsaved changes`() {
        val view = createView()
        var doneCalled = false
        val pane = buildPane(view, invoice(), onDone = { doneCalled = true })

        pane.requestExit()

        assertTrue(doneCalled)
        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `requestExit opens a discard-changes confirmation when the form is dirty`() {
        val view = createView()
        var doneCalled = false
        val pane = buildPane(view, invoice(), onDone = { doneCalled = true })
        switchToEinvoiceTab(pane)
        flatten(pane.component).filterIsInstance<TextField>()
            .single { it.label == UiTranslations.tr("invoiceDetail.purchaseOrderReference") }
            ._setValue("PO-123")

        pane.requestExit()

        assertFalse(doneCalled, "onDone must wait for the discard confirmation, not fire immediately")
        assertTrue(_find<ConfirmDialog>().isNotEmpty())
    }

    // ── save button: date-guard early return ────────────────────────────────────────────────────

    @Test
    fun `save is blocked before any facade call when the service period is inverted`() {
        val view = createView()
        val pane = buildPane(view, invoice())
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.servicePeriodFrom") }
            ._setValue(java.time.LocalDate.of(2026, 6, 10))
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.servicePeriodTo") }
            ._setValue(java.time.LocalDate.of(2026, 6, 1))

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        verify(editInvoiceHeaderUseCase, never()).updateInvoiceHeader(any())
    }

    // ── date invariants — invoiceDate is a required, non-clearable field ─────────────────────────

    @Test
    fun `save is blocked and the invoice-date field is marked invalid when it is empty`() {
        val view = createView()
        val pane = buildPane(view, invoice())
        // invoice() itself already opens with no invoiceDate (InvoiceDetailDto.invoiceDate =
        // null, see the fixture above) — the field starts empty, exactly the "cleared" state
        // this pins.

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val invoiceDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
        assertTrue(invoiceDateField.isInvalid, "an empty required field must fail binder validation")
        verify(editInvoiceHeaderUseCase, never()).updateInvoiceHeader(any())
    }

    @Test
    fun `the invoice-date field is not clearable via a clear button`() {
        val view = createView()
        val pane = buildPane(view, invoice())

        val invoiceDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }

        assertFalse(invoiceDateField.isClearButtonVisible)
    }

    // ── date invariants — dueDate >= invoiceDate at the entry pane ───────────────────────────────

    @Test
    fun `a due date on or after the invoice date saves without any field error`() {
        val current = invoice()
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val view = createView()
        val pane = buildPane(view, current)
        val invoiceDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
        val dueDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.dueDate") }
        invoiceDateField._setValue(java.time.LocalDate.of(2026, 6, 1))
        dueDateField._setValue(java.time.LocalDate.of(2026, 6, 15))

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(any())
        assertFalse(invoiceDateField.isInvalid)
        assertFalse(dueDateField.isInvalid)
    }

    @Test
    fun `a due date before the invoice date is rejected and flashes both date fields, mirroring the backend rule`() {
        val current = invoice()
        // The real backend rule (Invoice.validate -> DUE_DATE_BEFORE_INVOICE_DATE) is exercised
        // end to end by InvoiceDateInvariantsE2EIT; this facade is mocked here (same idiom as
        // every other test in this class), so the inconsistent state is simulated by throwing
        // the same exception a real InvoiceLifecycleService.updateHeader call would raise for it.
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any()))
            .thenThrow(InvoiceValidationException(InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE))
        val view = createView()
        val pane = buildPane(view, current)
        val invoiceDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
        val dueDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.dueDate") }
        invoiceDateField._setValue(java.time.LocalDate.of(2026, 6, 10))
        dueDateField._setValue(java.time.LocalDate.of(2026, 6, 1))

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        assertTrue(invoiceDateField.isInvalid)
        assertTrue(dueDateField.isInvalid)
    }

    // ── buyerReference override: initial state, pencil unlock, recipient-change re-suggest ─────

    @Test
    fun `buyerReference starts read-only when it still matches the recipient's routingId`() {
        val recipient = PartyDto(id = 3L, name = "Kunde", routingId = "0203")
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipient, buyerReference = "0203"))
        switchToEinvoiceTab(pane)

        val field = flatten(pane.component).filterIsInstance<TextField>()
            .single { it.label == UiTranslations.tr("invoiceDetail.buyerReference") }
        assertTrue(field.isReadOnly)
    }

    @Test
    fun `buyerReference starts editable when it already diverges from the recipient's routingId`() {
        val recipient = PartyDto(id = 3L, name = "Kunde", routingId = "0203")
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipient, buyerReference = "manual-override"))
        switchToEinvoiceTab(pane)

        val field = flatten(pane.component).filterIsInstance<TextField>()
            .single { it.label == UiTranslations.tr("invoiceDetail.buyerReference") }
        assertFalse(field.isReadOnly)
    }

    @Test
    fun `the pencil button unlocks the buyerReference field for manual editing`() {
        val recipient = PartyDto(id = 3L, name = "Kunde", routingId = "0203")
        val view = createView()
        val pane = buildPane(view, invoice(recipient = recipient, buyerReference = "0203"))
        switchToEinvoiceTab(pane)
        val field = flatten(pane.component).filterIsInstance<TextField>()
            .single { it.label == UiTranslations.tr("invoiceDetail.buyerReference") }
        assertTrue(field.isReadOnly)

        val pencil = field.suffixComponent as Button
        pencil.click()

        assertFalse(field.isReadOnly)
    }

    @Test
    fun `changing the recipient re-suggests its routingId while the override is untouched`() {
        val oldRecipient = PartyDto(id = 3L, name = "Kunde A", routingId = "0203")
        val newRecipient = testRecipient(5L, "Kunde B", routingId = "0505")
        val view = createView()
        val pane = buildPane(view, invoice(recipient = oldRecipient, buyerReference = "0203"))

        // recipientBox lives on Tab 1 (partiesSection), attached by default — no tab switch needed
        // to reach it, unlike buyerReferenceField below.
        val recipientBox = flatten(pane.component).filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.summary.name") }
        recipientBox.requireAs<com.vaadin.flow.component.combobox.ComboBox<RecipientSummaryDto>>().value = newRecipient

        switchToEinvoiceTab(pane)
        val field = flatten(pane.component).filterIsInstance<TextField>()
            .single { it.label == UiTranslations.tr("invoiceDetail.buyerReference") }
        assertEquals("0505", field.value)
    }

    // ── VAT: sellerVatCountryBox pre-selection wiring ───────────────────────────────────────────
    // resolveSellerVatRegistrationCountry itself is unit-tested exhaustively in
    // TaxRuleUiSupportTest; these cover only the wiring: opening an
    // invoice with no stored value, recompute-on-recipient-change (overwriting an explicit choice),
    // and preserve-on-sender-change (not overwriting one).

    private fun sellerVatCountryBox(component: Component): com.vaadin.flow.component.combobox.ComboBox<Country> =
        flatten(component).filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.sellerVatRegistrationCountry") }
            .requireAs()

    private fun recipientBoxOf(component: Component): com.vaadin.flow.component.combobox.ComboBox<RecipientSummaryDto> =
        flatten(component).filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.summary.name") }
            .requireAs()

    private fun senderBoxOf(component: Component): com.vaadin.flow.component.combobox.ComboBox<SenderSummaryDto> =
        flatten(component).filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.card.sender") }
            .requireAs()

    private fun senderWithLuRegistration(id: Long = 9L, vatId: String? = null): SenderSummaryDto =
        testSender(id, "Sender", vatId = vatId, additionalVatRegistrations = listOf(SellerVatRegistrationDto("LU", "LU12345678")))

    private fun recipientAt(id: Long, country: Country): RecipientSummaryDto {
        val recipient = testRecipient(id, "Recipient")
        whenever(recipientQueryPort.findBillingAddress(id)).thenReturn(testAddress(country.alpha2))
        return recipient
    }

    @Test
    fun `opening an invoice with no stored value pre-selects the sender's registration matching the recipient's country`() {
        val sender = senderWithLuRegistration()
        val recipient = recipientAt(3L, Country.LU)
        whenever(senderQueryPort.findSenderById(9L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(recipient)
        val view = createView()

        val pane = buildPane(
            view,
            invoice(
                sender = PartyDto(id = 9L, name = "Sender"),
                recipient = PartyDto(id = 3L, name = "Recipient"),
            ),
        )
        switchToEinvoiceTab(pane) // sellerVatCountryBox lives on Tab 2 ("E-Rechnung")

        assertEquals(Country.LU, sellerVatCountryBox(pane.component).value)
    }

    @Test
    fun `opening an invoice with no stored value shows the DE default entry when no registration matches the recipient's country`() {
        // The field is never empty: the German default renders as a visible,
        // selected DE sentinel entry while the form state (and thus persistence) stays null.
        val sender = senderWithLuRegistration()
        val recipient = recipientAt(3L, Country.DE)
        whenever(senderQueryPort.findSenderById(9L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(recipient)
        val view = createView()

        val pane = buildPane(
            view,
            invoice(
                sender = PartyDto(id = 9L, name = "Sender"),
                recipient = PartyDto(id = 3L, name = "Recipient"),
            ),
        )
        switchToEinvoiceTab(pane)

        assertEquals(Country.DE, sellerVatCountryBox(pane.component).value, "the German default must be a visible DE entry, never an empty combo")
    }

    @Test
    fun `the field stays visible for a sender without any additional registrations`() {
        // "immer zeigen ist transparenter" — previously the box was hidden
        // unless the sender held foreign registrations; now which VAT ID the invoice is billed
        // under is legible on every invoice, as the lone DE entry.
        val sender = testSender(9L, "Sender")
        val recipient = recipientAt(3L, Country.DE)
        whenever(senderQueryPort.findSenderById(9L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(recipient)
        val view = createView()

        val pane = buildPane(
            view,
            invoice(
                sender = PartyDto(id = 9L, name = "Sender"),
                recipient = PartyDto(id = 3L, name = "Recipient"),
            ),
        )
        switchToEinvoiceTab(pane)

        val box = sellerVatCountryBox(pane.component)
        assertTrue(box.isVisible, "the field must render even without foreign registrations")
        assertEquals(Country.DE, box.value, "with no registrations the lone DE entry is selected")
    }

    @Test
    fun `the DE default entry is labelled with the sender profile's own German USt-IdNr`() {
        val sender = senderWithLuRegistration(vatId = "DE632865687")
        val recipient = recipientAt(3L, Country.DE)
        whenever(senderQueryPort.findSenderById(9L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(recipient)
        val view = createView()

        val pane = buildPane(
            view,
            invoice(
                sender = PartyDto(id = 9L, name = "Sender"),
                recipient = PartyDto(id = 3L, name = "Recipient"),
            ),
        )
        switchToEinvoiceTab(pane)
        val box = sellerVatCountryBox(pane.component)

        assertEquals("DE (DE632865687)", box.itemLabelGenerator.apply(Country.DE))
        assertEquals("LU (LU12345678)", box.itemLabelGenerator.apply(Country.LU), "foreign entries keep their registration's own USt-IdNr")
    }

    @Test
    fun `changing the recipient to a different country recomputes the selection over an explicit choice`() {
        val sender = senderWithLuRegistration()
        val oldRecipient = recipientAt(3L, Country.DE)
        val newRecipient = recipientAt(5L, Country.LU)
        whenever(senderQueryPort.findSenderById(9L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(oldRecipient)
        val view = createView()
        val pane = buildPane(
            view,
            invoice(
                sender = PartyDto(id = 9L, name = "Sender"),
                recipient = PartyDto(id = 3L, name = "Recipient"),
            ),
        )
        // recipientBox lives on Tab 1 (attached by default); sellerVatCountryBox on Tab 2 — grab
        // both references before switching (a held reference stays valid and its listeners keep
        // firing regardless of which tab is currently attached; only a fresh flatten()-lookup needs
        // the tab actually selected).
        val recipientBox = recipientBoxOf(pane.component)
        switchToEinvoiceTab(pane)
        val box = sellerVatCountryBox(pane.component)
        assertEquals(Country.DE, box.value, "DE recipient: the visible DE default entry is selected")

        recipientBox.value = newRecipient

        assertEquals(Country.LU, box.value, "recipient moved to LU — recomputed even though nothing was explicitly chosen before")

        recipientBox.value = oldRecipient

        assertEquals(Country.DE, box.value, "recipient moved back to DE — re-resolves to the visible DE default entry")
    }

    @Test
    fun `blanking the field as a client action falls back to the DE default entry`() {
        val sender = senderWithLuRegistration()
        val recipient = recipientAt(3L, Country.DE)
        whenever(senderQueryPort.findSenderById(9L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(recipient)
        val view = createView()
        val pane = buildPane(
            view,
            invoice(
                sender = PartyDto(id = 9L, name = "Sender"),
                recipient = PartyDto(id = 3L, name = "Recipient"),
            ),
        )
        switchToEinvoiceTab(pane)
        val box = sellerVatCountryBox(pane.component)
        box._setValue(Country.LU)

        box._setValue(null)

        assertEquals(Country.DE, box.value, "a client-side blanking must land on the DE default entry, never an empty combo")
    }

    @Test
    fun `reverse-charge field stays visible while sellerVatCountryBox visibly shows the DE default entry`() {
        // The critical constraint: the visible DE default is presentation only —
        // the persisted sellerVatRegistrationCountry stays null, so the reverse-charge field's own
        // seller/recipient country comparison (which reads sellerVatCountryBox.value, DE here) still sees a
        // cross-border pair against the FR recipient and shows the field.
        val recipientId = 13L
        val sender = senderWithLuRegistration()
        val domainRecipient = testRecipient(
            recipientId, "Kunde FR", vatId = "FR12345678901",
            customerType = CustomerType.BUSINESS, vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
        )
        whenever(senderQueryPort.findSenderById(9L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(recipientId)).thenReturn(domainRecipient)
        whenever(recipientQueryPort.findBillingAddress(recipientId))
            .thenReturn(testAddress(Country.FR.alpha2))
        val view = createView()

        val pane = buildPane(
            view,
            invoice(
                sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
                recipient = PartyDto(id = recipientId, name = "Kunde FR"),
            ),
        )

        assertTrue(reverseChargeDecisionBox(pane.component).isVisible, "the visible DE default must still count as a cross-border pair against the FR recipient")
        switchToEinvoiceTab(pane)
        assertEquals(Country.DE, sellerVatCountryBox(pane.component).value, "no FR registration held — the DE default entry stays selected")
    }

    @Test
    fun `an explicit selection is not overwritten when only the sender changes`() {
        val originalSender = senderWithLuRegistration(id = 9L)
        val otherSenderAlsoLu = senderWithLuRegistration(id = 10L)
        val recipient = recipientAt(3L, Country.DE)
        whenever(senderQueryPort.findSenderById(9L)).thenReturn(originalSender)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(recipient)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(originalSender, otherSenderAlsoLu))
        val view = createView()
        val pane = buildPane(
            view,
            invoice(
                sender = PartyDto(id = 9L, name = "Sender"),
                recipient = PartyDto(id = 3L, name = "Recipient"),
            ),
        )
        val senderBox = senderBoxOf(pane.component)
        switchToEinvoiceTab(pane)
        val box = sellerVatCountryBox(pane.component)
        box._setValue(Country.LU) // real client pick — DE recipient, so this is a deliberate override
        assertEquals(Country.LU, box.value, "sanity: the client pick itself must have taken effect")

        senderBox.value = otherSenderAlsoLu

        assertEquals(Country.LU, box.value, "explicit choice must survive a sender change while the recipient stays the same")
    }

    // ── recipientBox's lazy data provider (RecipientQueryPort.findRecipients/countRecipients) ───

    private fun recipientBoxDataProvider(component: Component): com.vaadin.flow.data.provider.DataProvider<RecipientSummaryDto, String> =
        recipientBoxOf(component).dataProvider.requireAs()

    private fun recipientBoxQuery(offset: Int, limit: Int, filter: String?) =
        com.vaadin.flow.data.provider.Query<RecipientSummaryDto, String>(offset, limit, emptyList(), null, filter)

    @Test
    fun `recipientBox's lazy data provider derives the page index from offset over limit`() {
        val recipient = testRecipient(9L, "Acme", customerNumber = "CUST-9")
        whenever(recipientQueryPort.findRecipients(2, 30, "acme")).thenReturn(listOf(recipient))
        whenever(recipientQueryPort.countRecipients("acme")).thenReturn(1L)
        val view = createView()
        val pane = buildPane(view, invoice())

        val result = recipientBoxDataProvider(pane.component).fetch(recipientBoxQuery(60, 30, "acme")).toList()
        val size = recipientBoxDataProvider(pane.component).size(recipientBoxQuery(60, 30, "acme"))

        assertEquals(listOf(recipient), result)
        assertEquals(1, size)
        verify(recipientQueryPort).findRecipients(2, 30, "acme")
    }

    @Test
    fun `recipientBox's lazy data provider treats a zero limit as page zero and a blank filter as no filter`() {
        val recipient = testRecipient(9L, "Acme", customerNumber = "CUST-9")
        whenever(recipientQueryPort.findRecipients(0, 0, null)).thenReturn(listOf(recipient))
        val view = createView()
        val pane = buildPane(view, invoice())

        val result = recipientBoxDataProvider(pane.component).fetch(recipientBoxQuery(5, 0, "   ")).toList()

        assertEquals(listOf(recipient), result)
        verify(recipientQueryPort).findRecipients(0, 0, null)
    }
}
