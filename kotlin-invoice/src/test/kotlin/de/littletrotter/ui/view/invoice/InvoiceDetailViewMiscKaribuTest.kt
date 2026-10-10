package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.UiEventBus
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * the view-level (not cockpit-related) tests formerly colocated in
 * `InvoiceDetailViewCockpitActionsKaribuTest.kt`: `defaultTaxRateFor`, `formatDate`/
 * `formatDecimal`, `deleteItem` and `exitEntryMode`/`refreshTotals`'s entry-context span. None of
 * these read a cockpit port — they stayed with the cockpit test file only by historical
 * coincidence, not by concern.
 */
class InvoiceDetailViewMiscKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val refs = ReferenceDataMocks()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        clearNotifications()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun createView(): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val generationJobQueryService = mock<GenerationJobQueryService>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryService = generationJobQueryService,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(
                    mock<InvoiceReceiptQueryPort>().apply { whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList())) },
                    mock(),
                    ReceiptDialogLauncher(
                        mock<InvoiceReceiptQueryPort>().apply { whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList())) },
                        mock(),
                        invoiceDetailQueryPort,
                    ),
                ),
                exportPanel = testExportPanel(invoiceDetailQueryPort, recipientQueryPort, generationJobQueryService),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
    }

    private fun dto(
        id: Long = 1L,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
        netAmount: Money? = null,
        vatAmount: Money? = null,
        grossAmount: Money? = null,
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-1", status = status.ordinal, invoiceDate = null,
        netAmount = netAmount, vatAmount = vatAmount, grossAmount = grossAmount,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    // ── defaultTaxRateFor: isDefault short-circuit vs. rate-value fallback vs. neither ───────────
    // defaultTaxRateFor: moved off InvoiceDetailView into InvoiceItemDialogs (the only reader).

    private fun rate(id: Long, rateValue: String, isDefault: Boolean = false) = TaxRateDto(
        id = id,
        rate = TaxRatePercent(BigDecimal(rateValue)),
        isDefault = isDefault,
        code = "R$id",
        name = "R$id",
    )

    private fun itemDialogs(): InvoiceItemDialogs {
        val (_, dialogs) = ItemDialogTestFixtures.itemComponents(refs.taxRateQueries)
        return dialogs
    }

    @Test
    fun `defaultTaxRateFor returns null when the catalog is empty`() {
        whenever(refs.taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
        assertEquals(null, itemDialogs().defaultTaxRateFor(BigDecimal("19")))
    }

    @Test
    fun `defaultTaxRateFor prefers the isDefault-flagged rate over a value match`() {
        val defaultRate = rate(1L, "7.00", isDefault = true)
        val valueMatchRate = rate(2L, "19.00", isDefault = false)
        whenever(refs.taxRateQueries.findAllTaxRates()).thenReturn(listOf(valueMatchRate, defaultRate))
        assertEquals(defaultRate, itemDialogs().defaultTaxRateFor(BigDecimal("19.00")))
    }

    @Test
    fun `defaultTaxRateFor falls back to a value match when no rate is flagged isDefault`() {
        val valueMatchRate = rate(2L, "19", isDefault = false)
        whenever(refs.taxRateQueries.findAllTaxRates()).thenReturn(listOf(valueMatchRate))
        assertEquals(valueMatchRate, itemDialogs().defaultTaxRateFor(BigDecimal("19")))
    }

    @Test
    fun `defaultTaxRateFor returns null when neither isDefault nor a value match exists`() {
        val other = rate(3L, "7", isDefault = false)
        whenever(refs.taxRateQueries.findAllTaxRates()).thenReturn(listOf(other))
        assertEquals(null, itemDialogs().defaultTaxRateFor(BigDecimal("19")))
    }

    // ── formatDate: null vs. a real date ──────────────────────────────────────────────────────────

    @Test
    fun `formatDate renders the dash placeholder for a null date`() {
        val view = createView()
        assertEquals("-", view.formatDate(null))
    }

    @Test
    fun `formatDate renders a real date in localized medium form`() {
        val view = createView()
        val text = view.formatDate(LocalDate.of(2026, 3, 15))
        assertTrue(text.isNotBlank() && text != "-", text)
    }

    // ── deleteItem: success updates the invoice, failure shows an error (both e.message states) ──
    // defaultTaxRateFor: moved off InvoiceDetailView into InvoiceItemDialogs (the only reader). reporting
    // through InvoiceDetailState.applyChange rather than the view's own `invoice` setter.

    private fun itemDto(positionId: String = "1") = ItemDto(
        id = 1L, positionId = positionId, description = "Pos", quantity = BigDecimal.ONE,
        unit = null, unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null,
    )

    private fun boundItemDialogs(editInvoiceItemsUseCase: de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase): Pair<InvoiceItemDialogs, InvoiceDetailState> {
        val (_, dialogs) = ItemDialogTestFixtures.itemComponents(refs.taxRateQueries, editInvoiceItemsUseCase)
        val state = InvoiceDetailState()
        dialogs.bind(state)
        return dialogs to state
    }

    @Test
    fun `deleteItem success reports the use case's response through InvoiceDetailState`() {
        val editInvoiceItemsUseCase: de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase = mock()
        val invoice = dto()
        val (dialogs, state) = boundItemDialogs(editInvoiceItemsUseCase)
        whenever(editInvoiceItemsUseCase.deleteItemFromInvoiceByPosition(1L, "1")).thenReturn(dto().copy(invoiceNumber = "INV-AFTER-DELETE"))

        dialogs.deleteItem(createView(), invoice, itemDto())

        assertEquals("INV-AFTER-DELETE", state.dto?.invoiceNumber)
    }

    @Test
    fun `deleteItem failure with a message shows it in the error notification`() {
        val editInvoiceItemsUseCase: de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase = mock()
        val invoice = dto()
        val (dialogs, _) = boundItemDialogs(editInvoiceItemsUseCase)
        // The real service (InvoiceItemMutationService.deleteItemByPosition) throws this domain
        // exception when the invoice can no longer be edited; UiNotifications.resolveMessage maps
        // it to a fixed, translated message (not the raw exception text) — a plain RuntimeException
        // would fall through to the generic fallback instead, so it no longer proves this path.
        whenever(editInvoiceItemsUseCase.deleteItemFromInvoiceByPosition(1L, "1")).doThrow(de.littletrotter.domain.exception.InvoiceImmutableException(invoice.invoiceNumber))

        dialogs.deleteItem(createView(), invoice, itemDto())

        val text = flattenNotification()
        assertTrue(text.contains(de.littletrotter.ui.i18n.UiTranslations.tr("message.error.invoiceImmutable")), text)
    }

    @Test
    fun `deleteItem failure with no message falls back to the blank-message rendering`() {
        val editInvoiceItemsUseCase: de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase = mock()
        val invoice = dto()
        val (dialogs, _) = boundItemDialogs(editInvoiceItemsUseCase)
        whenever(editInvoiceItemsUseCase.deleteItemFromInvoiceByPosition(1L, "1")).doThrow(RuntimeException())

        dialogs.deleteItem(createView(), invoice, itemDto())

        // No assertion on exact text (a null-message RuntimeException's rendering is an
        // implementation detail elsewhere) — the meaningful assertion is that the null-message
        // branch runs to completion without throwing and still shows exactly one toast.
        assertEquals(1, getNotifications().size)
    }

    // ── formatDecimal: null vs. a real value ──────────────────────────────────────────────────────

    @Test
    fun `formatDecimal renders zero for a null value`() {
        val view = createView()
        assertEquals("0", view.formatDecimal(null))
    }

    @Test
    fun `formatDecimal renders a real value stripped of trailing zeros`() {
        val view = createView()
        assertEquals("1.5", view.formatDecimal(BigDecimal("1.50")))
    }

    // ── exitEntryMode: the null-invoice guard ─────────────────────────────────────────────────────

    @Test
    fun `exitEntryMode is a no-op render when no invoice has been loaded`() {
        val view = createView()
        view.overviewEditMode = true

        view.exitEntryMode()

        assertFalse(view.overviewEditMode)
        assertEquals(0, view.children.count())
    }

    @Test
    fun `exitEntryMode re-renders the loaded invoice and leaves entry mode`() {
        val view = createView()
        val invoice = dto()
        view.renderInvoice(invoice)
        view.overviewEditMode = true
        view.pendingRefreshAfterEdit = true

        view.exitEntryMode()

        assertFalse(view.overviewEditMode)
        assertFalse(view.pendingRefreshAfterEdit)
        assertTrue(view.children.count() > 0)
    }

    // ── refreshTotals: the entryGrossSpan null-safe update (the cockpit span is now the
    // ── InvoiceCockpitHero's own concern, rebuilt wholesale by refreshAfterDataChange) ───────────

    @Test
    fun `refreshTotals also updates the entry context span while in entry mode`() {
        val view = createView()
        val invoice = dto(netAmount = null, vatAmount = null, grossAmount = Money.of("75.00"))
        // entryGrossSpan is only ever built by entering entry mode after a prior non-entry render.
        view.renderInvoice(invoice)
        view.enterEntryMode(invoice)

        view.refreshTotals(invoice)

        assertTrue(view.entryGrossSpan?.text?.contains("75") == true, view.entryGrossSpan?.text)
    }

    private fun flattenNotification(): String {
        val notification = getNotifications().single()
        return notification.element.textRecursively
    }
}
