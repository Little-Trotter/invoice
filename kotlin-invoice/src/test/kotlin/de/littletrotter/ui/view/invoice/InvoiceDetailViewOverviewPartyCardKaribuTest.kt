package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.dto.SellerVatRegistrationDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.dto.SnapshotFieldChangeDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.country.Country
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [InvoiceEntryPartyCard] (extracted from `InvoiceDetailViewOverview.kt`): the confirmed
 * party-snapshot refresh and its failure, the diff message, the seller-VAT entry labels and a
 * persisted seller-VAT override surviving a sender change. Setup mirrors
 * [InvoiceDetailViewOverviewEntryPaneTest].
 */
class InvoiceDetailViewOverviewPartyCardKaribuTest {

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
        val invoiceDetailQueryPort = this.invoiceDetailQueryPort
        val recipientQueryPort = this.recipientQueryPort
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
        sender: PartyDto? = PartyDto(id = 91L, name = "Test sender"),
        recipient: PartyDto? = PartyDto(id = 92L, name = "Test recipient"),
        sellerVatRegistrationCountry: String? = null,
        invoiceNumber: String = "INV-1",
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = invoiceNumber, status = InvoiceStatus.DRAFT.ordinal, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = sender, recipient = recipient, items = emptyList(), latestGenerationJob = null,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry, version = 3L,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto, onDone: () -> Unit = {}): EntryPaneResult {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = onDone)
        com.vaadin.flow.component.UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result
    }

    private fun buttonByText(component: Component, text: String): Button =
        flatten(component).filterIsInstance<Button>().single { it.text == text }

    private fun tabSheet(pane: EntryPaneResult): TabSheet = flatten(pane.component).filterIsInstance<TabSheet>().single()

    private fun comboByLabel(component: Component, labelKey: String): ComboBox<Any> =
        flatten(component).filterIsInstance<ComboBox<*>>().single { it.label == UiTranslations.tr(labelKey) }.requireAs()

    private fun refreshButton(pane: EntryPaneResult): Button =
        buttonByText(pane.component, UiTranslations.tr("invoiceDetail.entry.refreshPartySnapshot"))

    private fun ConfirmDialog.fireConfirm() = ComponentUtil.fireEvent(this, ConfirmDialog.ConfirmEvent(this, false))

    private fun senderWithLuRegistration(id: Long): SenderSummaryDto =
        testSender(id, "Sender $id", vatId = "DE123456789", additionalVatRegistrations = listOf(SellerVatRegistrationDto("LU", "LU12345678")))

    private val sellerNameChange = PartySnapshotDiffDto(
        listOf(SnapshotFieldChangeDto("Name", "Alt GmbH", "Neu GmbH")),
        listOf(SnapshotFieldChangeDto("Street", null, "Hauptstr. 1")),
    )

    @Test
    fun `happy path - confirming the refresh dialog applies the refreshed party snapshots to the view`() {
        whenever(invoiceDetailQueryPort.previewPartySnapshotRefresh(1L)).thenReturn(sellerNameChange)
        val refreshed = invoice(invoiceNumber = "INV-REFRESHED")
        whenever(editInvoiceHeaderUseCase.refreshPartySnapshots(1L)).thenReturn(refreshed)
        val view = createView()
        val pane = buildPane(view, invoice())
        refreshButton(pane).click()

        _find<ConfirmDialog>().single().fireConfirm()

        verify(editInvoiceHeaderUseCase).refreshPartySnapshots(1L)
        assertEquals("INV-REFRESHED", view.invoice?.invoiceNumber)
    }

    @Test
    fun `boundary - a refresh failing after confirmation is reported and leaves the view's invoice untouched`() {
        whenever(invoiceDetailQueryPort.previewPartySnapshotRefresh(1L)).thenReturn(sellerNameChange)
        whenever(editInvoiceHeaderUseCase.refreshPartySnapshots(1L)).thenThrow(IllegalStateException("snapshot refresh rejected"))
        val view = createView()
        val before = view.invoice
        val pane = buildPane(view, invoice())
        refreshButton(pane).click()

        _find<ConfirmDialog>().single().fireConfirm()

        verify(editInvoiceHeaderUseCase).refreshPartySnapshots(1L)
        assertSame(before, view.invoice)
    }

    @Test
    fun `boundary - the refresh confirmation lists seller and buyer changes and dashes a missing old value`() {
        whenever(invoiceDetailQueryPort.previewPartySnapshotRefresh(1L)).thenReturn(sellerNameChange)
        val pane = buildPane(createView(), invoice())

        refreshButton(pane).click()

        val message = _find<ConfirmDialog>().single().element.getProperty("message")
        val lines = message.lines()
        assertEquals(2, lines.size, message)
        assertTrue(lines[0].startsWith(UiTranslations.tr("invoiceDetail.card.sender")), lines[0])
        assertTrue(lines[0].contains("'Alt GmbH' → 'Neu GmbH'"), lines[0])
        assertTrue(lines[1].startsWith(UiTranslations.tr("invoiceDetail.summary.name")), lines[1])
        assertTrue(lines[1].contains("'—' → 'Hauptstr. 1'"), lines[1])
    }

    @Test
    fun `boundary - seller VAT entries show the matching VAT ID and a bare code for an unregistered country`() {
        val sender = senderWithLuRegistration(91L)
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(sender)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(sender))
        val pane = buildPane(createView(), invoice())
        tabSheet(pane).selectedIndex = 1
        MockVaadin.clientRoundtrip()
        val box = comboByLabel(pane.component, "invoiceDetail.sellerVatRegistrationCountry")

        assertEquals("LU (LU12345678)", box.itemLabelGenerator.apply(Country.LU))
        assertEquals("DE (DE123456789)", box.itemLabelGenerator.apply(Country.DE))
        assertEquals("FR", box.itemLabelGenerator.apply(Country.FR))
    }

    @Test
    fun `boundary - a persisted seller VAT override survives a sender change while the recipient stays`() {
        val original = senderWithLuRegistration(91L)
        val other = senderWithLuRegistration(93L)
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(original)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(original, other))
        val pane = buildPane(createView(), invoice(sellerVatRegistrationCountry = "LU"))
        val senderBox = comboByLabel(pane.component, "invoiceDetail.card.sender")
        tabSheet(pane).selectedIndex = 1
        MockVaadin.clientRoundtrip()
        val box = comboByLabel(pane.component, "invoiceDetail.sellerVatRegistrationCountry")
        assertEquals(Country.LU, box.value, "sanity: the persisted override is shown on open")

        senderBox.value = other

        assertEquals(Country.LU, box.value)
    }

    // ── A live sender change with NO explicit override must re-derive the pre-selection
    // (the sibling test above pins the opposite: an override survives) ──────────────────────────

    @Test
    fun `boundary - a live sender change with no explicit VAT override re-derives the pre-selection for the new sender's registrations`() {
        val original = senderWithLuRegistration(91L)
        val other = testSender(93L, "Sender 93", vatId = "DE987654321")
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(original)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(original, other))
        whenever(recipientQueryPort.findBillingAddress(92L)).thenReturn(testAddress(Country.LU.alpha2))
        val pane = buildPane(createView(), invoice(sellerVatRegistrationCountry = null))
        val senderBox = comboByLabel(pane.component, "invoiceDetail.card.sender")
        tabSheet(pane).selectedIndex = 1
        MockVaadin.clientRoundtrip()
        val box = comboByLabel(pane.component, "invoiceDetail.sellerVatRegistrationCountry")
        assertEquals(Country.LU, box.value, "sanity: the live pre-selection follows the recipient's LU billing country")

        senderBox.value = other

        assertEquals(
            Country.DE, box.value,
            "the new sender has no LU registration, so the pre-selection must re-derive to the default, not keep the stale LU pick",
        )
    }
}
