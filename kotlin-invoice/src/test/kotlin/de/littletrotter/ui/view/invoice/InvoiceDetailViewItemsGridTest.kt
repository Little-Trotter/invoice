package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.GridScrollBox
import java.math.BigDecimal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [InvoiceItemGrid] ([InvoiceItemGrid.build], [InvoiceItemsTab.refresh], the inline
 * exemption guard, [ItemDto.toUpdateCommand], the private description-cell builder) had only the
 * filter-reset/sort/count helpers covered before this file (see [InvoiceDetailViewItemsTest],
 * which this file complements rather than duplicates — none of its scenarios are repeated here).
 *
 * Covers: [InvoiceItemsTab.refresh]'s three early-return guards and its rebuild-vs-reuse branch,
 * the inline-edit exemption guard, [ItemDto.toUpdateCommand]'s per-diem mapping (including both
 * `requireNotNull` guards), the private description-cell builder's category/blank/BT-156 branches
 * (invoked via reflection — same idiom as [InvoiceStatisticsViewKaribuTest]'s field reflection,
 * there is no other way to reach a private instance method), and [InvoiceItemGrid.build]'s
 * read-only/writable action-column split (by column count).
 *
 * Exercises [InvoiceItemGrid]/[InvoiceItemsTab] directly instead of going through
 * [InvoiceDetailView].
 *
 * Not covered here (tried, reverted — no established pattern in this module, see
 * [ReceiptDialogActionsKaribuTest]'s class KDoc on the same limitation): the receipts-count label
 * and the delete-button's enabled state, both built inside `addComponentColumn` `ComponentRenderer`
 * lambdas that are never eagerly invoked at grid-construction time — confirmed empirically here
 * (`flatten(grid)` cannot see them; `NoSuchElementException` on every such lookup), they only run
 * once Vaadin actually serializes visible rows to a client, which none of this module's other
 * Karibu tests trigger for a `Grid`'s component columns either.
 */
class InvoiceDetailViewItemsGridTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
    private val host = Div()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newGrid(): InvoiceItemGrid = InvoiceItemGrid(taxRateQueries, mock(), mock(), time = FIXED_TIME_SOURCE)

    private fun newTab(): InvoiceItemsTab = testItemsTab(editInvoiceItemsUseCase = editInvoiceItemsUseCase, taxRateQueries = taxRateQueries)

    private var nextItemId = 1L

    private fun item(
        category: InvoiceItemCategory = InvoiceItemCategory.PRODUCT,
        sortOrder: Int = 0,
        positionId: String = "1",
        taxRateRefId: Long? = null,
        receiptIds: List<Long> = emptyList(),
        itemDescription: String? = null,
    ): ItemDto = ItemDto(
        id = nextItemId++, positionId = positionId, description = "Pos $positionId", quantity = BigDecimal.ONE,
        unit = InvoiceItemUnit.PIECE.name, unitPriceNet = Money(BigDecimal("10.00")), taxRate = null,
        taxRateRefId = taxRateRefId, netAmount = null, vatAmount = null, grossAmount = null,
        category = category.name, sortOrder = sortOrder, receiptIds = receiptIds, itemDescription = itemDescription,
    )

    private fun invoiceDto(items: List<ItemDto>, status: Int = 0): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    private fun rate(code: String, category: String) = TaxRateDto(
        id = code.hashCode().toLong(),
        code = code,
        category = category,
        rate = TaxRatePercent(BigDecimal("19.00")),
        name = code,
        isActive = true,
        isSelectable = true,
        exemptionReasonText = if (category == "E" || category == "O") "Reason" else null,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    // ── toUpdateCommand ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `toUpdateCommand maps all editable fields and leaves creation-only fields null`() {
        val i = item(category = InvoiceItemCategory.SERVICE, taxRateRefId = 42L).copy(
            quantity = BigDecimal("3"),
            unitPriceNet = Money(BigDecimal("15.00")),
            taxRate = BigDecimal("19.00"),
        )

        val cmd = i.toUpdateCommand()

        assertEquals(i.id, cmd.itemId)
        assertEquals(InvoiceItemCategory.SERVICE, cmd.category)
        assertEquals(BigDecimal("3"), cmd.quantity)
        assertEquals(Money(BigDecimal("15.00")), cmd.unitPriceNet)
        assertEquals(42L, cmd.taxRateId)
        assertNull(cmd.articleId, "articleId is creation-only")
        assertNull(cmd.exemptionReasonTextOverride, "exemption overrides are creation-only")
    }

    // The three per-diem-entry toUpdateCommand tests were deleted: InvoiceItemCommand carries
    // no per-diem entries at all any more (they only ever occur inside a TripCommand),
    // and a TRIP position never reaches toUpdateCommand/updateItem in the first place
    // (InvoiceLifecycleService.updateItem rejects a TRIP target outright; the inline grid editor
    // never opens for a TRIP row, see InvoiceItemGrid's own double-click guard). The per-diem-day
    // mapping/validation this tested moved to TripCommandValidationTest.

    // ── applyInlineItemEdit (private, invoked via reflection) ───────────────────────────────────

    private fun applyInlineItemEdit(grid: InvoiceItemGrid, item: ItemDto) {
        val m = InvoiceItemGrid::class.java.declaredMethods.first { it.name == "applyInlineItemEdit" }
        m.isAccessible = true
        m.invoke(grid, host, item)
    }

    @Test
    fun `applyInlineItemEdit blocks the save when the resolved rate needs exemption details that are missing`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rate("EX", "E")))
        var reported: InvoiceItemCommand? = null
        val grid = newGrid().apply { onInlineSave = { command -> reported = command } }
        val i = item(taxRateRefId = "EX".hashCode().toLong())

        applyInlineItemEdit(grid, i)

        assertNull(reported)
    }

    @Test
    fun `applyInlineItemEdit saves through when no exemption details are required`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rate("STD", "S")))
        var reported: InvoiceItemCommand? = null
        val grid = newGrid().apply { onInlineSave = { command -> reported = command } }
        val i = item(taxRateRefId = "STD".hashCode().toLong())

        applyInlineItemEdit(grid, i)

        assertEquals(i.id, reported?.itemId)
    }

    // ── InvoiceItemsTab.refresh: early-return guards ────────────────────────────────────────────

    @Test
    fun `refresh returns immediately when no data provider has been built yet`() {
        val tab = newTab()
        val invoice = invoiceDto(listOf(item()))

        tab.refresh(invoice) // no exception, itemsDataProvider is null

        assertNull(tab.itemsGridSlot)
    }

    @Test
    fun `refresh returns immediately when the grid slot is missing`() {
        val tab = newTab()
        tab.itemsDataProvider = ListDataProvider(mutableListOf())
        tab.itemsGridSlot = null
        tab.itemsHeaderSlot = Div()

        tab.refresh(invoiceDto(listOf(item()))) // must not throw despite a real provider
    }

    @Test
    fun `refresh returns immediately when the header slot is missing`() {
        val tab = newTab()
        tab.itemsDataProvider = ListDataProvider(mutableListOf())
        tab.itemsGridSlot = Div()
        tab.itemsHeaderSlot = null

        tab.refresh(invoiceDto(listOf(item()))) // must not throw
    }

    // ── refresh: rebuild vs. reuse, empty vs. non-empty ─────────────────────────────────────────

    @Test
    fun `refresh replaces the empty-state paragraph with a grid once items exist`() {
        val tab = newTab()
        tab.render(invoiceDto(emptyList())) // seeds provider/slots via the empty-state branch
        val slot = tab.itemsGridSlot!!
        assertTrue(flatten(slot).any { it is Paragraph }, "starts in the empty-state branch")

        tab.refresh(invoiceDto(listOf(item())))

        assertTrue(flatten(slot).none { it is Paragraph }, "must rebuild into a real grid once items exist")
        assertTrue(flatten(slot).any { it is de.littletrotter.ui.component.DataTable<*> })
    }

    @Test
    fun `refresh reuses an already-attached grid instead of rebuilding it`() {
        val tab = newTab()
        tab.render(invoiceDto(listOf(item(positionId = "1"))))
        val gridBefore = tab.itemsGrid

        tab.refresh(invoiceDto(listOf(item(positionId = "1"), item(positionId = "2"))))

        assertEquals(gridBefore, tab.itemsGrid, "an already-attached grid must be reused, not rebuilt")
    }

    @Test
    fun `the items grid scrolls sideways in its own box, sized by the stylesheet instead of an inline width`() {
        val tab = newTab()
        tab.render(invoiceDto(listOf(item())))
        val grid = tab.itemsGrid!!

        assertTrue(grid.parent.get() is GridScrollBox)
        assertNull(grid.width)
    }

    @Test
    fun `refresh falls back to the empty message once the filtered category has no items left`() {
        val tab = newTab()
        tab.render(invoiceDto(listOf(item(category = InvoiceItemCategory.SERVICE))))
        val slot = tab.itemsGridSlot!!

        tab.refresh(invoiceDto(emptyList()))

        assertTrue(flatten(slot).any { it is Paragraph })
    }

    @Test
    fun `refresh resets a stale category filter that no longer matches any item`() {
        val tab = newTab().apply { activeItemCategoryFilter = InvoiceItemCategory.TRIP }
        tab.itemsDataProvider = ListDataProvider(mutableListOf())
        tab.itemsGridSlot = Div()
        tab.itemsHeaderSlot = Div()

        tab.refresh(invoiceDto(listOf(item(category = InvoiceItemCategory.SERVICE))))

        assertNull(tab.activeItemCategoryFilter)
    }

    // ── InvoiceItemGrid.build: read-only vs. writable action column ────────────────────────────

    // Spec (trip-details-read-only) — a read-only invoice now gets the actions column too (a view
    // action for a TRIP row, nothing for every other row), so the two grids have the same column
    // count; only the writable grid's actions column carries edit/delete.
    @Test
    fun `a read-only invoice's item grid has the same column count as a writable one`() {
        val i = item()

        val readOnlyGrid = newGrid().build(
            host,
            invoiceDto(listOf(i), status = de.littletrotter.domain.invoice.InvoiceStatus.SENT.ordinal),
            listOf(i),
        )
        val writableGrid = newGrid().build(host, invoiceDto(listOf(i)), listOf(i))

        assertEquals(writableGrid.columns.size, readOnlyGrid.columns.size, "both grids carry an actions column now")
    }

    @Test
    fun `a read-only invoice's item grid shows no action at all for a non-TRIP row`() {
        val i = item(category = InvoiceItemCategory.PRODUCT)
        val invoice = invoiceDto(listOf(i), status = de.littletrotter.domain.invoice.InvoiceStatus.SENT.ordinal)
        val grid = newGrid().build(host, invoice, listOf(i))
        val actionsColumnIndex = grid.columns.size - 1

        val cell = grid.columns[actionsColumnIndex].componentFor(i) as com.vaadin.flow.component.orderedlayout.HorizontalLayout

        assertTrue(
            cell.children.toList().filterIsInstance<com.vaadin.flow.component.button.Button>().isEmpty(),
            "a non-TRIP row keeps no action while read-only, its row already shows everything it has",
        )
    }

    // ── description cell (private, invoked via reflection) ─────────────────────────────────────

    private fun buildDescriptionCell(item: ItemDto, bt156Defined: Boolean, bt156Values: Map<Long, String?>): Component {
        val grid = newGrid()
        val m = InvoiceItemGrid::class.java.declaredMethods.first { it.name == "buildItemDescriptionCell" }
        m.isAccessible = true
        return m.invoke(grid, host, item, bt156Defined, bt156Values) as Component
    }

    @Test
    fun `description cell for a non-PRODUCT item shows only the plain description, no sublines`() {
        val i = item(category = InvoiceItemCategory.SERVICE, itemDescription = "should not show")

        val cell = buildDescriptionCell(i, bt156Defined = true, bt156Values = mapOf(i.id to "ART-1"))

        assertEquals(1, flatten(cell).filterIsInstance<Span>().size)
    }

    @Test
    fun `description cell for a PRODUCT item adds a subline for a non-blank item description`() {
        val i = item(category = InvoiceItemCategory.PRODUCT, itemDescription = "Long form")

        val cell = buildDescriptionCell(i, bt156Defined = false, bt156Values = emptyMap())

        val spans = flatten(cell).filterIsInstance<Span>()
        assertTrue(spans.any { it.text == "Long form" })
    }

    @Test
    fun `description cell shows a placeholder for BT-156 when the profile defines it but no value is set`() {
        val i = item(category = InvoiceItemCategory.PRODUCT)

        val cell = buildDescriptionCell(i, bt156Defined = true, bt156Values = emptyMap())

        val spans = flatten(cell).filterIsInstance<Span>()
        assertTrue(spans.any { it.text?.endsWith("—") == true }, "an undefined value must render the placeholder when the profile defines the term")
    }

    @Test
    fun `description cell shows the actual BT-156 value when present even if the profile does not define it`() {
        val i = item(category = InvoiceItemCategory.PRODUCT)

        val cell = buildDescriptionCell(i, bt156Defined = false, bt156Values = mapOf(i.id to "ART-42"))

        val spans = flatten(cell).filterIsInstance<Span>()
        assertTrue(spans.any { it.text?.endsWith("ART-42") == true })
    }

    @Test
    fun `description cell shows no BT-156 line at all when undefined and no value is present`() {
        val i = item(category = InvoiceItemCategory.PRODUCT)

        val cell = buildDescriptionCell(i, bt156Defined = false, bt156Values = emptyMap())

        assertEquals(1, flatten(cell).filterIsInstance<Span>().size, "only the base description span")
    }
}
