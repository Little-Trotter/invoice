package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._doubleClickItem
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.TravelExpenseDto
import de.littletrotter.application.dto.TripDto
import de.littletrotter.application.dto.TripExpenseDto
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.DataTable
import java.math.BigDecimal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Positions-grid behaviour specific to a TRIP row: the summary subline, the disabled tax
 * combo, the filter chip and add button, and delete cascading through the same use case call as
 * every other category (no per-diem-specific branch left, that button is gone with the deleted
 * bridge dialogs — see [InvoiceItemsFeatureVisibilityTest]'s own updated KDoc).
 *
 * Exercises [InvoiceItemGrid]/[InvoiceItemsTab] directly instead of going through
 * [InvoiceDetailView].
 */
class InvoiceDetailViewItemsTripTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val perDiemRateQueries: PerDiemRateQueryPort = mock()
    private val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
    private val host = Div()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(emptyList())
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(null)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newGrid(): InvoiceItemGrid = InvoiceItemGrid(taxRateQueries, mock(), mock(), time = FIXED_TIME_SOURCE)

    private fun newTab(): InvoiceItemsTab = testItemsTab(editInvoiceItemsUseCase = editInvoiceItemsUseCase, taxRateQueries = taxRateQueries)

    private fun tripItem(
        id: Long = 1L,
        expenseCount: Int = 2,
        perDiemDayCount: Int = 1,
        fixedNetPrice: Money? = null,
        detailedOnInvoice: Boolean = false,
    ): ItemDto = ItemDto(
        id = id, positionId = "1", description = "Kundenbesuch München",
        quantity = BigDecimal.ONE, unit = "FLAT_RATE", unitPriceNet = Money.of("500.00"),
        taxRate = BigDecimal("19.00"), taxRateRefId = 5L,
        netAmount = Money.of("500.00"), vatAmount = null, grossAmount = null,
        category = InvoiceItemCategory.TRIP.name,
        trip = TripDto(
            detailedOnInvoice = detailedOnInvoice, fixedNetPrice = fixedNetPrice,
            calculatedNet = Money.of("500.00"),
            expenses = (1..expenseCount).map {
                TripExpenseDto(
                    id = it.toLong(), invoiceItemId = id, kind = "OTHER", description = "Posten $it",
                    travelDate = null, quantity = BigDecimal.ONE, unit = "FLAT_RATE",
                    unitPriceNet = Money.of("50.00"), netAmount = Money.of("50.00"),
                )
            },
            perDiemDays = (1..perDiemDayCount).map {
                TravelExpenseDto(
                    id = it.toLong(), invoiceItemId = id, travelDate = java.time.LocalDate.of(2026, 6, it),
                    countryAlpha2 = "DE", isArrivalDeparture = true, breakfastDeduction = false,
                    lunchDeduction = false, dinnerDeduction = false, calculatedAmount = Money.of("28.00"),
                )
            },
        ),
    )

    private fun invoiceDto(items: List<ItemDto>, status: Int = 0): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun componentAt(grid: DataTable<ItemDto>, columnIndex: Int, item: ItemDto): Component =
        grid.columns[columnIndex].componentFor(item)

    // ── Description cell: the trip summary subline ──────────────────────────

    @Test
    fun `a trip row's description cell shows a summary subline with the expense and per-diem counts`() {
        val item = tripItem(expenseCount = 2, perDiemDayCount = 1)
        val grid = newGrid().build(host, invoiceDto(listOf(item)), listOf(item))

        val cell = componentAt(grid, 1, item)
        val texts = flatten(cell).filterIsInstance<com.vaadin.flow.component.html.Span>().map { it.text }

        assertTrue(texts.any { it.contains("2") && it.contains("Posten") }, "expected an expense count segment in: $texts")
        assertTrue(texts.any { it.contains("1") && it.contains("🍴") }, "expected a per-diem day count segment in: $texts")
        assertTrue(texts.any { it.contains("Rechnerisch") }, "no fixed price -> calculated segment: $texts")
    }

    @Test
    fun `a fixed-price trip shows Festpreis instead of Rechnerisch in the summary subline`() {
        val item = tripItem(expenseCount = 0, perDiemDayCount = 0, fixedNetPrice = Money.of("300.00"))
        val grid = newGrid().build(host, invoiceDto(listOf(item)), listOf(item))

        val cell = componentAt(grid, 1, item)
        val texts = flatten(cell).filterIsInstance<com.vaadin.flow.component.html.Span>().map { it.text }

        assertTrue(texts.any { it.contains("Festpreis") }, "expected a Festpreis segment in: $texts")
        assertFalse(texts.any { it == "0 Posten" }, "a fixed-price-only trip must not show a zero-expense segment")
    }

    // ── Unit price cell: placeholder for trips ───────────────────────────────

    @Test
    fun `a trip row's unit price cell shows a placeholder, not the domain-forced net amount`() {
        val item = tripItem()
        val grid = newGrid().build(host, invoiceDto(listOf(item)), listOf(item))

        val row = grid._getFormattedRow(item)

        assertTrue(row.any { it == "-" }, "expected the unit-price placeholder cell in: $row")
    }

    // ── Inline editor's tax combo, defensively disabled for a trip ──────────

    private fun attachAndOpenEditor(grid: DataTable<ItemDto>, item: ItemDto) {
        grid.setItems(listOf(item))
        com.vaadin.flow.component.UI.getCurrent().add(grid)
        MockVaadin.clientRoundtrip()
        grid.editor.editItem(item)
        MockVaadin.clientRoundtrip()
    }

    @Test
    fun `the inline editor's tax combo is disabled for a trip row`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(
            listOf(TaxRateDto(id = 5L, code = "S19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), name = "S19", isActive = true, isSelectable = true)),
        )
        val item = tripItem()
        val invoice = invoiceDto(listOf(item))
        val grid = newGrid().build(host, invoice, listOf(item), enableInlineEdit = true)

        attachAndOpenEditor(grid, item)

        val taxBox = grid.editor.binder.fields.toList().filterIsInstance<ComboBox<TaxRateDto>>().single()
        assertFalse(taxBox.isEnabled, "a trip never picks its own tax rate")
    }

    @Test
    fun `double-clicking a trip row still opens TripDialog, never the inline editor`() {
        val item = tripItem()
        val invoice = invoiceDto(listOf(item))
        val tab = newTab()
        tab.render(invoice)
        val grid = tab.itemsGrid!!
        grid.setItems(listOf(item))
        com.vaadin.flow.component.UI.getCurrent().add(tab)
        MockVaadin.clientRoundtrip()

        grid._doubleClickItem(0)
        MockVaadin.clientRoundtrip()

        assertFalse(grid.editor.isOpen, "a TRIP row must never open the inline editor")
        assertTrue(_get<Dialog>().isOpened, "it must open TripDialog instead")
    }

    // ── Filter chips / add button ────────────────────────────────────────────

    @Test
    fun `the trip filter chip uses the shared item-category key, and no per-diem chip exists any more`() {
        assertEquals(
            listOf("invoiceDetail.filter.all", "item.category.product", "item.category.unitPrice", "item.category.service", "item.category.trip"),
            ITEM_FILTER_OPTIONS.map { it.labelKey },
        )
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(InvoiceItemCategory::class)
    fun `each category opens its editor with one click`(category: InvoiceItemCategory) {
        val tab = newTab()
        tab.render(invoiceDto(emptyList()))
        val buttons = flatten(tab).filterIsInstance<Button>()
            .filter { it.element.hasAttribute("data-add-category") }
        assertEquals(listOf("PRODUCT", "UNIT_PRICE", "SERVICE", "TRIP"),
            buttons.map { it.element.getAttribute("data-add-category") })
        buttons.single { it.element.getAttribute("data-add-category") == category.name }.click()
        val dialog = _get<Dialog>()
        assertTrue(dialog.isOpened)
        assertFalse(flatten(dialog).filterIsInstance<Button>().any {
            it.text == de.littletrotter.ui.i18n.UiTranslations.tr(tab, "app.continue")
        }, "the category chooser must not appear")
        val titleKey = when (category) {
            InvoiceItemCategory.PRODUCT, InvoiceItemCategory.UNIT_PRICE -> "invoiceDetail.goods.dialog.title"
            InvoiceItemCategory.SERVICE -> "invoiceDetail.service.dialog.title"
            InvoiceItemCategory.TRIP -> "invoiceDetail.trip.dialog.title"
        }
        assertEquals(de.littletrotter.ui.i18n.UiTranslations.tr(tab, titleKey), dialog.headerTitle)
    }

    @Test
    fun `read-only invoices have no add-category buttons`() {
        val tab = newTab()
        tab.render(invoiceDto(emptyList(), status = de.littletrotter.domain.invoice.InvoiceStatus.ISSUED.ordinal))
        assertTrue(flatten(tab).filterIsInstance<Button>()
            .none { it.element.hasAttribute("data-add-category") })
    }

    // ── Delete cascades via the same use case call as every other category ──

    @Test
    fun `deleting a trip position calls the same delete use case method as any other category`() {
        val item = tripItem()
        val other = tripItem(id = 2L).copy(positionId = "2")
        val invoice = invoiceDto(listOf(item, other))
        whenever(editInvoiceItemsUseCase.deleteItemFromInvoiceByPosition(eq(1L), eq("1"), eq(invoice.version))).thenReturn(invoiceDto(listOf(other)))
        val tab = newTab()
        tab.render(invoice)
        val grid = tab.itemsGrid!!
        val actionsColumnIndex = grid.columns.size - 1
        val actions = componentAt(grid, actionsColumnIndex, item) as HorizontalLayout
        val deleteButton = actions.children.toList().filterIsInstance<Button>()[1]

        deleteButton.click()

        verify(editInvoiceItemsUseCase).deleteItemFromInvoiceByPosition(1L, "1", invoice.version)
    }

    // ── Read-only: view action for a TRIP row ────────────────────────────────

    @Test
    fun `a read-only invoice's item grid shows exactly one view action for a TRIP row`() {
        val item = tripItem()
        val invoice = invoiceDto(listOf(item), status = de.littletrotter.domain.invoice.InvoiceStatus.ISSUED.ordinal)
        val grid = newGrid().build(host, invoice, listOf(item))
        val actionsColumnIndex = grid.columns.size - 1

        val actions = (componentAt(grid, actionsColumnIndex, item) as HorizontalLayout).children.toList().filterIsInstance<Button>()

        assertEquals(1, actions.size, "no edit/delete, only the view action")
        assertEquals(
            de.littletrotter.ui.i18n.UiTranslations.tr("button.view"),
            actions.single().element.getAttribute("aria-label"),
        )
    }

    @Test
    fun `clicking a read-only invoice's view action opens TripDialog`() {
        val item = tripItem()
        val invoice = invoiceDto(listOf(item), status = de.littletrotter.domain.invoice.InvoiceStatus.ISSUED.ordinal)
        val tab = newTab()
        tab.render(invoice)
        val grid = tab.itemsGrid!!
        val actionsColumnIndex = grid.columns.size - 1
        val viewButton = (componentAt(grid, actionsColumnIndex, item) as HorizontalLayout)
            .children.toList().filterIsInstance<Button>().single()

        viewButton.click()

        assertTrue(_get<Dialog>().isOpened)
    }
}
