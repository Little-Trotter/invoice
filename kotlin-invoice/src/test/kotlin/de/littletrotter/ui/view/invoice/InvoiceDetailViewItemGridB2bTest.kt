package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * PO 2026-08-25 — for a PRODUCT position, BT-156 (buyer's article number) and BT-154 (item
 * description) must always be visible in the positions grid when the recipient's B2B profile
 * defines the term, or when a value is already present even without one (see the description
 * cell builder's own KDoc in `InvoiceDetailViewItems.kt`). These tests exercise the actual
 * [de.littletrotter.ui.component.DataTable] column (via its [com.vaadin.flow.data.renderer.ComponentRenderer], the same idiom
 * [de.littletrotter.ui.component.DataTableKaribuTest] uses) rather than a private helper, so a
 * rendering-path regression is caught here too.
 */
class InvoiceDetailViewItemGridB2bTest {

    private val host = Div()

    private fun grid(b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort, b2bFieldValueQueries: B2bFieldValueQueryPort): InvoiceItemGrid =
        InvoiceItemGrid(mock<TaxRateQueryPort>(), b2bFieldDefinitionQueries, b2bFieldValueQueries, time = FIXED_TIME_SOURCE)

    private fun profileField(term: de.littletrotter.shared.b2b.B2bTerm) = B2bProfileFieldDto(
        term = term,
        placement = B2bPlacement.INVOICE,
        businessDefinition = "Definition of ${term.officialCode}",
    )

    private fun invoiceDto(id: Long, items: List<ItemDto>): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-$id", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    private fun itemDto(id: Long, category: InvoiceItemCategory, description: String, itemDescription: String? = null): ItemDto = ItemDto(
        id = id, positionId = id.toString(), description = description,
        quantity = java.math.BigDecimal.ONE, unit = null, unitPriceNet = null,
        taxRate = null, taxRateRefId = null, netAmount = null, vatAmount = null, grossAmount = null,
        category = category.name, itemDescription = itemDescription,
    )

    /** Position column is index 0, the description column (built by `buildItemDescriptionCell`,
     *  private to `InvoiceDetailViewItems.kt`, so not linkable from here) is index 1 — see
     *  [buildItemGrid]. */
    private fun descriptionCellFor(itemGrid: InvoiceItemGrid, invoice: InvoiceDetailDto, item: ItemDto): Component {
        val grid = itemGrid.build(host, invoice, invoice.items)
        return grid.columns[1].componentFor(item)
    }

    /** The base description (BT-153) is always the cell's first child; everything after it is a
     *  B2B subline. */
    private fun sublines(cell: Component): List<String> =
        (cell as Div).children.toList().drop(1).map { (it as Span).text }

    @Test
    fun `PRODUCT row shows both BT-154 and BT-156 sublines when the profile defines BT-156`() {
        val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
        val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
        val item = itemDto(1L, InvoiceItemCategory.PRODUCT, "Hydraulikpumpe", "Typ X, 12 bar")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(42L)).thenReturn(1L)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(1L)).thenReturn(listOf(profileField(de.littletrotter.shared.b2b.B2bTerm.BT_156)))
        whenever(b2bFieldValueQueries.findB2bFieldValuesForOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(1L)))
            .thenReturn(mapOf(1L to mapOf("BT-156" to "4711")))

        val texts = sublines(descriptionCellFor(grid(b2bFieldDefinitionQueries, b2bFieldValueQueries), invoice, item))

        assertTrue(texts.any { it == "Typ X, 12 bar" }, "BT-154 subline expected, got $texts")
        assertTrue(texts.any { it.contains("4711") }, "BT-156 subline expected, got $texts")
    }

    @Test
    fun `PRODUCT row shows an em-dash placeholder for BT-156 when the profile defines it but no value is stored`() {
        val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
        val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
        val item = itemDto(2L, InvoiceItemCategory.PRODUCT, "Hydraulikpumpe")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(42L)).thenReturn(1L)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(1L)).thenReturn(listOf(profileField(de.littletrotter.shared.b2b.B2bTerm.BT_156)))
        whenever(b2bFieldValueQueries.findB2bFieldValuesForOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(2L))).thenReturn(emptyMap())

        val texts = sublines(descriptionCellFor(grid(b2bFieldDefinitionQueries, b2bFieldValueQueries), invoice, item))

        assertEquals(1, texts.size, "no BT-154 value, only the BT-156 placeholder line: $texts")
        assertTrue(texts[0].endsWith("—"), "expected an em-dash placeholder, got '${texts[0]}'")
    }

    @Test
    fun `PRODUCT row shows nothing extra when the profile defines neither term and no values are present`() {
        val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
        val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
        val item = itemDto(3L, InvoiceItemCategory.PRODUCT, "Hydraulikpumpe")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(42L)).thenReturn(null)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(null)).thenReturn(emptyList())
        whenever(b2bFieldValueQueries.findB2bFieldValuesForOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(3L))).thenReturn(emptyMap())

        val texts = sublines(descriptionCellFor(grid(b2bFieldDefinitionQueries, b2bFieldValueQueries), invoice, item))

        assertEquals(0, texts.size, "nothing to show: no profile definition, no value: $texts")
    }

    @Test
    fun `non-PRODUCT row never shows B2B sublines even when the profile defines BT-156`() {
        val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
        val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
        val item = itemDto(4L, InvoiceItemCategory.SERVICE, "Beratung", "Details, die für Waren gedacht wären")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(42L)).thenReturn(1L)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(1L)).thenReturn(listOf(profileField(de.littletrotter.shared.b2b.B2bTerm.BT_156)))

        val texts = sublines(descriptionCellFor(grid(b2bFieldDefinitionQueries, b2bFieldValueQueries), invoice, item))

        assertEquals(0, texts.size, "the PO requirement is scoped to PRODUCT positions only")
    }

    @Test
    fun `BT-156 value still shows when present even though no profile defines the term`() {
        val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
        val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
        val item = itemDto(5L, InvoiceItemCategory.PRODUCT, "Hydraulikpumpe")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(42L)).thenReturn(null)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(null)).thenReturn(emptyList())
        whenever(b2bFieldValueQueries.findB2bFieldValuesForOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(5L)))
            .thenReturn(mapOf(5L to mapOf("BT-156" to "AB-9")))

        val texts = sublines(descriptionCellFor(grid(b2bFieldDefinitionQueries, b2bFieldValueQueries), invoice, item))

        assertEquals(1, texts.size)
        assertTrue(texts[0].contains("AB-9"))
    }

    @Test
    fun `the B2B profile-fields and value lookups run exactly once per grid build, never per row`() {
        val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
        val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
        val item1 = itemDto(6L, InvoiceItemCategory.PRODUCT, "A")
        val item2 = itemDto(7L, InvoiceItemCategory.PRODUCT, "B")
        val invoice = invoiceDto(42L, listOf(item1, item2))
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(42L)).thenReturn(1L)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(1L)).thenReturn(listOf(profileField(de.littletrotter.shared.b2b.B2bTerm.BT_156)))
        whenever(b2bFieldValueQueries.findB2bFieldValuesForOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(6L, 7L))).thenReturn(emptyMap())

        val grid = grid(b2bFieldDefinitionQueries, b2bFieldValueQueries).build(host, invoice, invoice.items)
        // Force both rows' cells to render — the lookups above must still have run only once,
        // during buildItemGrid itself, not once per createComponent call.
        val column = grid.columns[1]
        column.componentFor(item1)
        column.componentFor(item2)

        verify(b2bFieldValueQueries, times(1)).findB2bProfileIdForInvoice(42L)
        verify(b2bFieldDefinitionQueries, times(1)).findB2bInvoiceItemFields(1L)
        verify(b2bFieldValueQueries, times(1)).findB2bFieldValuesForOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(6L, 7L))
    }
}
