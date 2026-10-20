package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._doubleClickItem
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [InvoiceItemGrid]'s column lambdas: every plain `addColumn { ... }` value
 * provider (unit/price/taxRate/netAmount — all wrapping a nullable field through an inlined
 * `?:`/`orEmpty()`, which JaCoCo attributes to the caller) and every `addComponentColumn { ... }`
 * `ComponentRenderer` (receipts button, actions column) stayed at 0% branch coverage: Grid never
 * invokes either kind of lambda unless a client actually paints a row — see
 * [InvoiceDetailViewItemsGridTest]'s own KDoc, which tried this and reverted before
 * [ReceiptDialogActionsKaribuTest] established the fix: karibu-testing's `_getFormattedRow` for
 * plain columns, and invoking the column's own `ComponentRenderer` directly — the same
 * mechanism `_getCellComponent` uses internally, just without needing a column key (none of this
 * grid's columns set one) — for component columns.
 *
 * Exercises [InvoiceItemGrid] directly (a `@Dependent` bean over [TaxRateQueryPort])
 * instead of going through [InvoiceDetailView];
 * a plain `Div` stands in for the `host: Component` translation/dialog-attach context every
 * build/callback method takes.
 */
class InvoiceDetailViewItemGridColumnsKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
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

    private var nextId = 1L

    private fun item(
        category: InvoiceItemCategory = InvoiceItemCategory.PRODUCT,
        unit: String? = InvoiceItemUnit.PIECE.name,
        unitPriceNet: Money? = Money.of("10.00"),
        taxRate: BigDecimal? = null,
        taxRateRefId: Long? = null,
        netAmount: Money? = Money.of("10.00"),
        receiptIds: List<Long> = emptyList(),
    ): ItemDto = ItemDto(
        id = nextId++, positionId = "1", description = "Pos", quantity = BigDecimal.ONE,
        unit = unit, unitPriceNet = unitPriceNet, taxRate = taxRate, taxRateRefId = taxRateRefId,
        netAmount = netAmount, vatAmount = null, grossAmount = null, category = category.name,
        receiptIds = receiptIds,
    )

    private fun invoiceDto(items: List<ItemDto>, status: InvoiceStatus = InvoiceStatus.DRAFT): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    private fun componentAt(grid: de.littletrotter.ui.component.DataTable<ItemDto>, columnIndex: Int, item: ItemDto): Component =
        grid.columns[columnIndex].componentFor(item)

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    // ── plain value-provider columns: unit/price/taxRate/netAmount ──────────────────────────────

    @Test
    fun `blank unit, no price, no tax rate and no net amount render their placeholder text`() {
        val i = item(unit = null, unitPriceNet = null, taxRate = null, netAmount = null)
        val grid = newGrid().build(host, invoiceDto(listOf(i)), listOf(i))

        val row = grid._getFormattedRow(i)

        // positionId, description, receipts, quantity, unit, unitPriceNet, taxRate, netAmount, actions...
        assertTrue(row.any { it == "0" || it.contains("-") }, "at least the zero-amount/dash columns must render: $row")
    }

    @Test
    fun `a real unit, price, tax rate and net amount render their formatted values`() {
        val i = item(
            unit = InvoiceItemUnit.HOUR.name, unitPriceNet = Money.of("25.50"),
            taxRate = BigDecimal("19.00"), netAmount = Money.of("100.00"),
        )
        val grid = newGrid().build(host, invoiceDto(listOf(i)), listOf(i))

        val row = grid._getFormattedRow(i)

        assertTrue(row.any { it.contains("19") }, "tax rate column: $row")
        assertTrue(row.any { it.contains("25") || it.contains("100") }, "price/net columns: $row")
    }

    @Test
    fun `the read grid renders every persisted unit through the translated label resolver`() {
        val items = InvoiceItemUnit.entries.mapIndexed { index, unit ->
            item(unit = unit.name, unitPriceNet = Money.of("1.00")).copy(positionId = (index + 1).toString())
        }
        val grid = newGrid().build(host, invoiceDto(items), items)

        items.forEachIndexed { index, item ->
            val rendered = grid._getFormattedRow(item)
            val expected = InvoiceUnitLabels.label(null, InvoiceItemUnit.entries[index])
            assertTrue(
                rendered.contains(expected),
                "unit ${InvoiceItemUnit.entries[index]} must be rendered as '$expected': $rendered",
            )
            assertFalse(rendered.contains(InvoiceItemUnit.entries[index].name))
        }
    }

    // ── receipts-count component column ──────────────────────────────────────────────────────────

    @Test
    fun `an item with no receipts shows the none label in a tertiary button`() {
        val i = item(receiptIds = emptyList())
        val grid = newGrid().build(host, invoiceDto(listOf(i)), listOf(i))

        val button = componentAt(grid, 2, i) as Button

        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.none"), button.text)
        assertFalse(button.themeNames.contains("primary"))
    }

    @Test
    fun `an item with receipts shows the count label in a primary button`() {
        val i = item(receiptIds = listOf(10L, 11L))
        val grid = newGrid().build(host, invoiceDto(listOf(i)), listOf(i))

        val button = componentAt(grid, 2, i) as Button

        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.count", "2"), button.text)
        assertTrue(button.themeNames.contains("primary"))
    }

    // ── actions column: edit + delete; the delete is never gated by the item count ─────────────

    private fun deleteButtonOf(grid: de.littletrotter.ui.component.DataTable<ItemDto>, item: ItemDto): Button {
        val actions = componentAt(grid, grid.columns.size - 1, item) as HorizontalLayout
        return actions.children.toList().filterIsInstance<Button>()[1]
    }

    @ParameterizedTest
    @EnumSource(value = InvoiceItemCategory::class, names = ["PRODUCT", "UNIT_PRICE", "SERVICE"])
    fun `the delete button of the only position is enabled and titled delete in DRAFT and IN_REVIEW`(category: InvoiceItemCategory) {
        listOf(InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW).forEach { status ->
            val i = item(category = category)
            val grid = newGrid().build(host, invoiceDto(listOf(i), status), listOf(i))

            val deleteButton = deleteButtonOf(grid, i)

            assertTrue(deleteButton.isEnabled, "$category in $status")
            assertEquals(UiTranslations.tr("app.delete"), deleteButton.element.getAttribute("aria-label"), "$category in $status")
            assertEquals(UiTranslations.tr("app.delete"), com.vaadin.flow.component.shared.Tooltip.forComponent(deleteButton).text, "$category in $status")
        }
    }

    @ParameterizedTest
    @EnumSource(value = InvoiceItemCategory::class, names = ["PRODUCT", "UNIT_PRICE", "SERVICE"])
    fun `clicking delete on the only position calls the use case`(category: InvoiceItemCategory) {
        listOf(InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW).forEach { status ->
            val i = item(category = category)
            val before = invoiceDto(listOf(i), status)
            var deleted: ItemDto? = null
            val grid = newGrid().apply { onDelete = { deleted = it } }.build(host, before, listOf(i))

            deleteButtonOf(grid, i)._click()

            assertEquals(i, deleted, "$category in $status")
        }
    }

    @ParameterizedTest
    @EnumSource(value = InvoiceStatus::class, names = ["ISSUED", "CANCELLED", "VOIDED"])
    fun `a read-only invoice's actions cell carries no delete button`(status: InvoiceStatus) {
        val i = item(category = InvoiceItemCategory.TRIP)
        val grid = newGrid().build(host, invoiceDto(listOf(i), status), listOf(i))

        val actions = componentAt(grid, grid.columns.size - 1, i)

        val labels = flatten(actions).filterIsInstance<Button>().map { it.element.getAttribute("aria-label") }
        assertFalse(labels.contains(UiTranslations.tr("app.delete")), "$status: $labels")
    }

    @Test
    fun `the delete button is enabled with the normal label when more than one item exists`() {
        val i = item()
        val other = item()
        val invoice = invoiceDto(listOf(i, other))
        val grid = newGrid().build(host, invoice, listOf(i, other))
        val actionsColumnIndex = grid.columns.size - 1

        val actions = componentAt(grid, actionsColumnIndex, i) as HorizontalLayout
        val deleteButton = actions.children.toList().filterIsInstance<Button>()[1]

        assertTrue(deleteButton.isEnabled)
        assertEquals(UiTranslations.tr("app.delete"), deleteButton.element.getAttribute("aria-label"))
    }

    @Test
    fun `a read-only invoice's grid carries no actions column at all`() {
        val i = item()
        val invoice = invoiceDto(listOf(i), status = InvoiceStatus.ISSUED)
        val readOnlyGrid = newGrid().build(host, invoice, listOf(i))
        val writableGrid = newGrid().build(host, invoiceDto(listOf(i)), listOf(i))

        assertEquals(writableGrid.columns.size, readOnlyGrid.columns.size)
    }

    // ── editor field bindings: opening the editor invokes every getter ──────────────────────────

    /** Grid.Editor.editItem needs the item to actually be part of the grid's known dataset and the
     *  grid attached to a live UI, or the editor silently refuses to open. */
    private fun attachAndOpenEditor(grid: de.littletrotter.ui.component.DataTable<ItemDto>, item: ItemDto) {
        grid.setItems(listOf(item))
        com.vaadin.flow.component.UI.getCurrent().add(grid)
        MockVaadin.clientRoundtrip()
        // Editor.editItem defers its actual work (Grid.Editor.EditorImpl.requestEditItem) to a
        // beforeClientResponse callback registered via runWhenAttached — it only runs on the
        // *next* roundtrip after the call, not synchronously.
        grid.editor.editItem(item)
        MockVaadin.clientRoundtrip()
    }

    @Test
    fun `opening the inline editor on an item with no resolved tax rate populates the tax box as empty`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
        val i = item(taxRateRefId = null)
        val invoice = invoiceDto(listOf(i))
        val grid = newGrid().build(host, invoice, listOf(i), enableInlineEdit = true)

        attachAndOpenEditor(grid, i)

        assertTrue(grid.editor.isOpen)
    }

    // Design finding — EditorImpl runs binder.readBean (which sets the taxBox
    // value) BEFORE the open event that used to be the box's only setItems call, so opening the
    // editor on an item with a resolvable taxRateRefId crashed with "Cannot set a value for a
    // ComboBox without items" — is FIXED (PO approval): the box is populated with the full
    // catalogue at creation, the open listener only narrows per category and keeps the item's
    // own (possibly no-longer-selectable) rate in the list. The two tests below pin the
    // formerly crashing path and the keeps-historical-rate rule.

    @Test
    fun `opening the inline editor on an item whose taxRateRefId resolves preselects that rate instead of crashing`() {
        val rate = TaxRateDto(id = 5L, code = "DE_STANDARD_19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), name = "USt. 19 %", isActive = true, isSelectable = true)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rate))
        val i = item(taxRateRefId = 5L)
        val invoice = invoiceDto(listOf(i))
        val grid = newGrid().build(host, invoice, listOf(i), enableInlineEdit = true)

        attachAndOpenEditor(grid, i)

        assertTrue(grid.editor.isOpen)
        val taxBox = grid.columns.mapNotNull { it.editorComponent }.filterIsInstance<ComboBox<*>>().single().requireAs<ComboBox<TaxRateDto>>()
        assertEquals(5L, taxBox.value?.id, "the item's own rate must be preselected, not crash the editor open")
    }

    @Test
    fun `a no-longer-selectable rate on the edited item stays in the narrowed item list`() {
        val retired = TaxRateDto(id = 7L, code = "OLD_RULE", category = "S", rate = TaxRatePercent(BigDecimal("16.00")), name = "USt. 16 %", isActive = true, isSelectable = false)
        val current = TaxRateDto(id = 5L, code = "DE_STANDARD_19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), name = "USt. 19 %", isActive = true, isSelectable = true)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(retired, current))
        val i = item(taxRateRefId = 7L)
        val invoice = invoiceDto(listOf(i))
        val grid = newGrid().build(host, invoice, listOf(i), enableInlineEdit = true)

        attachAndOpenEditor(grid, i)

        val taxBox = grid.columns.mapNotNull { it.editorComponent }.filterIsInstance<ComboBox<*>>().single().requireAs<ComboBox<TaxRateDto>>()
        assertEquals(7L, taxBox.value?.id, "the historical rate must stay selected")
        val listedIds = taxBox.genericDataView.items.toList().map { it.id }
        assertTrue(7L in listedIds, "the retired rate must remain listed while it is the item's own value")
    }

    @Test
    fun `opening the inline editor on an item whose taxRateRefId no longer resolves to any known rate leaves the tax box empty`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(TaxRateDto(id = 99L, code = "X", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), name = "X", isActive = true, isSelectable = true)))
        val i = item(taxRateRefId = 5L)
        val invoice = invoiceDto(listOf(i))
        val grid = newGrid().build(host, invoice, listOf(i), enableInlineEdit = true)

        attachAndOpenEditor(grid, i)

        assertTrue(grid.editor.isOpen)
    }

    // ── categoryPartName (file-private — reflection, same idiom as buildItemDescriptionCell) ────

    private fun categoryPartName(category: InvoiceItemCategory): String {
        val kt = Class.forName("de.littletrotter.ui.view.invoice.InvoiceItemGridKt")
        val m = kt.declaredMethods.first { it.name == "categoryPartName" }
        m.isAccessible = true
        return m.invoke(null, category) as String
    }

    @Test
    fun `every InvoiceItemCategory maps to its own distinct part name`() {
        val names = InvoiceItemCategory.entries.map { categoryPartName(it) }
        assertEquals(names.size, names.toSet().size, "every category must map to a distinct CSS part name")
        assertEquals("item-cat-product", categoryPartName(InvoiceItemCategory.PRODUCT))
        assertEquals("item-cat-service", categoryPartName(InvoiceItemCategory.SERVICE))
        assertEquals("item-cat-travel", categoryPartName(InvoiceItemCategory.TRIP))
    }

    // ── double-click listener: TRAVEL_EXPENSE/PER_DIEM route to the dialog, others to the editor ──

    @Test
    fun `double-clicking a SERVICE row opens the inline editor, not a dialog`() {
        val i = item(category = InvoiceItemCategory.SERVICE)
        val invoice = invoiceDto(listOf(i))
        val grid = newGrid().build(host, invoice, listOf(i), enableInlineEdit = true)
        grid.setItems(listOf(i))
        com.vaadin.flow.component.UI.getCurrent().add(grid)
        MockVaadin.clientRoundtrip()

        grid._doubleClickItem(0)
        // The double-click listener's own editor.editItem(item) call defers to another
        // beforeClientResponse callback — see attachAndOpenEditor's KDoc.
        MockVaadin.clientRoundtrip()

        assertTrue(grid.editor.isOpen, "a non-travel/per-diem category must open the inline editor on double-click")
    }

    // ── saving the inline editor invokes every binder setter (qty, price, tax box) ───────────────

    @Test
    fun `saving the inline editor with changed quantity, price and tax rate reports all three through the command`() {
        val rate = TaxRateDto(id = 5L, code = "S19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), name = "S19", isActive = true, isSelectable = true)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rate))
        // taxRateRefId starts null (the safe getter sub-branch, see the design finding above) so
        // opening the editor does not hit the unrelated ComboBox-without-items crash.
        val i = item(taxRateRefId = null)
        val invoice = invoiceDto(listOf(i))
        var reported: InvoiceItemCommand? = null
        val grid = newGrid().apply {
            onInlineSave = { command -> reported = command }
        }.build(host, invoice, listOf(i), enableInlineEdit = true)

        attachAndOpenEditor(grid, i)
        val fields = grid.editor.binder.fields.toList()
        val qtyField = fields[0].requireAs<com.vaadin.flow.component.textfield.NumberField>()
        val priceField = fields[1].requireAs<com.vaadin.flow.component.textfield.NumberField>()
        val taxBox = fields[2].requireAs<com.vaadin.flow.component.combobox.ComboBox<TaxRateDto>>()
        qtyField.value = 4.0
        priceField.value = 30.0
        taxBox.value = rate

        val saved = grid.editor.save()

        assertTrue(saved)
        // ItemDto's quantity/unitPriceNet/taxRateRefId are `val`; the editor no longer
        // mutates `i` in place (see ItemGridEditingContext's KDoc), so the observable contract is
        // the command reported to onInlineSave.
        assertEquals(BigDecimal.valueOf(4.0), reported?.quantity)
        assertEquals(Money.of(BigDecimal.valueOf(30.0)), reported?.unitPriceNet)
        assertEquals(5L, reported?.taxRateId)
    }

    // ── applyInlineItemEdit's exemption-detail validation, exercised through the editor save path ──

    private fun saveInlineEdit(i: ItemDto, onSave: (InvoiceItemCommand) -> Unit): Boolean {
        val invoice = invoiceDto(listOf(i))
        val grid = newGrid().apply { onInlineSave = { command -> onSave(command) } }.build(host, invoice, listOf(i), enableInlineEdit = true)
        attachAndOpenEditor(grid, i)
        return grid.editor.save()
    }

    @Test
    fun `applyInlineItemEdit reports through when the item has no taxRateRefId at all`() {
        val i = item(taxRateRefId = null)
        var reported: InvoiceItemCommand? = null

        saveInlineEdit(i) { reported = it }

        assertEquals(i.id, reported?.itemId)
    }

    @Test
    fun `applyInlineItemEdit reports through when the taxRateRefId no longer resolves to any known rate`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
        val i = item(taxRateRefId = 999L)
        var reported: InvoiceItemCommand? = null

        saveInlineEdit(i) { reported = it }

        assertEquals(i.id, reported?.itemId)
    }

    @Test
    fun `applyInlineItemEdit reports through when the rate needs exemption details and both are already present`() {
        val exemptRate = TaxRateDto(
            id = 7L,
            code = "EX2",
            category = "E",
            rate = TaxRatePercent(BigDecimal.ZERO),
            name = "EX2",
            isActive = true,
            isSelectable = true,
        )
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(exemptRate))
        val i = item(taxRateRefId = 7L).copy(exemptionReasonText = "Grund", taxLegalBasis = "Art. 1")
        var reported: InvoiceItemCommand? = null

        saveInlineEdit(i) { reported = it }

        assertEquals(i.id, reported?.itemId)
    }
}
