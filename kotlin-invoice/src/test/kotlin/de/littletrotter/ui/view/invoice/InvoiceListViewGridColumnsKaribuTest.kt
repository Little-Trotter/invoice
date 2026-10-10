package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.NativeButton
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.StatusGlyph
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [InvoiceListGridColumns] in isolation: column set and cell texts, the legend headers,
 * the validation tile's navigation callback and the column-key/sort-field mapping.
 */
class InvoiceListViewGridColumnsKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()
    private val clickedValidationIds = mutableListOf<Long>()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newColumns() = InvoiceListGridColumns(InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)) { clickedValidationIds += it }

    private fun configuredGrid(columns: InvoiceListGridColumns): DataTable<InvoiceOverviewDto> =
        DataTable(InvoiceOverviewDto::class.java).also { columns.configure(it) }

    private fun row(status: InvoiceStatus = InvoiceStatus.SENT, invoiceNumber: String = "RE-2026-00001") = InvoiceOverviewDto(
        id = 7L,
        invoiceNumber = invoiceNumber,
        typeCode = null,
        sellerName = null,
        sellerCity = null,
        recipientName = null,
        grossAmount = null,
        paymentLabel = null,
        freeText = null,
        itemsCount = 3,
        invoiceDate = null,
        status = status,
        validationStatus = ValidationStatus.VALID,
    )

    @Test
    fun `happy path - configure builds every keyed column and renders a row from the overview dto`() {
        val grid = configuredGrid(newColumns())
        grid.setItems(listOf(row().copy(invoiceDate = LocalDate.of(2026, 8, 26), sellerName = "Seller", recipientName = "Acme")))

        val keys = grid.columns.mapNotNull { it.key }
        assertEquals(
            listOf("invoiceNumber", "invoiceDate", "seller", "recipient", "status", "grossAmount", "itemsCount", "validationStatus"),
            keys,
        )
        assertEquals(9, grid.columns.size, "plus the unkeyed reverse-charge column")
        val cells = grid._getFormattedRow(0)
        assertEquals("RE-2026-00001", cells[0])
        assertEquals("Seller", cells[2])
        assertEquals("Acme", cells[3])
        assertEquals("3", cells[6])
    }

    @Test
    fun `boundary - blank number, missing date, seller, recipient and amount render placeholders instead of failing`() {
        val grid = configuredGrid(newColumns())
        grid.setItems(listOf(row(invoiceNumber = "")))

        val cells = grid._getFormattedRow(0)
        assertEquals("-", cells[0])
        assertEquals("—", cells[1])
        assertEquals("", cells[2])
        assertEquals("-", cells[3])
        assertTrue(cells[5].startsWith("0,00"), "a missing gross amount renders as zero, got ${cells[5]}")
    }

    @Test
    fun `boundary - a finalized invoice row offers no mutating action, only the read-only validation navigation`() {
        val columns = newColumns()
        for (status in listOf(InvoiceStatus.SENT, InvoiceStatus.CANCELLED)) {
            val statusTile: Component = columns.buildStatusChip(status, null)
            assertTrue(statusTile is StatusGlyph, "the status cell is a passive glyph for $status")
            assertTrue(statusTile.children.noneMatch { it is Button || it is NativeButton }, "no action inside the status cell for $status")
        }
        val validationTile = columns.buildValidationCell(ValidationStatus.VALID, 7L) as NativeButton

        validationTile._click()

        assertEquals(listOf(7L), clickedValidationIds, "the only clickable cell navigates to the validation area")
    }

    @Test
    fun `boundary - the status and validation header icons open their legend dialogs`() {
        val grid = configuredGrid(newColumns())

        (grid.getColumnByKey("status").headerComponent as Button)._click()
        assertEquals(1, _find<Dialog>().size, "the status legend opens")

        (grid.getColumnByKey("validationStatus").headerComponent as Button)._click()
        assertEquals(2, _find<Dialog>().size, "the validation legend opens next to it")
    }

    @Test
    fun `boundary - sort mapping is a round trip and an unknown or absent column key sorts by date`() {
        for (field in InvoiceOverviewSortField.entries) {
            val key = InvoiceListGridColumns.columnKeyForSortField(field)
            assertEquals(field, InvoiceListGridColumns.sortFieldForColumnKey(key), "round trip for $field")
        }
        assertEquals(InvoiceOverviewSortField.INVOICE_DATE, InvoiceListGridColumns.sortFieldForColumnKey("'; DROP TABLE invoice; --"))
        assertEquals(InvoiceOverviewSortField.INVOICE_DATE, InvoiceListGridColumns.sortFieldForColumnKey(null))
    }

    @Test
    fun `happy path - a failed document generation adds the warning marker with tooltip to the status cell`() {
        val cell = newColumns().buildStatusCell(row().copy(documentGenerationFailed = true))

        val marker = cell._find<StatusGlyph>().single { it.hasClassName("document-generation-failed-marker") }
        assertEquals(2, cell._find<StatusGlyph>().size, "status chip plus warning marker")
        assertTrue(marker.element.getAttribute("title").isNotBlank())
        assertEquals(marker.element.getAttribute("title"), marker.element.getAttribute("aria-label"))
    }

    @Test
    fun `boundary - no failed generation renders the bare status tile without a marker`() {
        val cell = newColumns().buildStatusCell(row())

        assertTrue(cell is StatusGlyph)
        assertTrue(cell._find<StatusGlyph>().none { it.hasClassName("document-generation-failed-marker") })
    }
}
