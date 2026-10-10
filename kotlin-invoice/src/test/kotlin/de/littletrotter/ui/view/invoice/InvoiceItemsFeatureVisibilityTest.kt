package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ItemDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pure visibility logic of the item toolbar, unit-level. WHY
 * [effectiveItemCategoryFilter] exists as a separate top-level internal function instead of living
 * inline in [InvoiceDetailView], see its own KDoc; the full view would need Karibu plus a DTO
 * factory for the same assertions, and both header builders share exactly this decision.
 *
 * TRAVEL_EXPENSE/EXPENSE/PER_DIEM were merged into [InvoiceItemCategory.TRIP]: a
 * TRIP also covers a plain hotel/taxi/mileage trip with no per-diem days at all, so
 * [ITEM_FILTER_OPTIONS] never hides a chip based on the per-diem feature switch — that switch now
 * only gates the "Verpflegungspauschalen" section inside `TripDialog` (see `TripDialogKaribuTest`),
 * a Karibu-level concern this pure-function test never touched even before the old,
 * separately-flagged per-diem add button was removed.
 */
class InvoiceItemsFeatureVisibilityTest {

    private fun item(id: Long, category: InvoiceItemCategory, order: Int = id.toInt()): ItemDto = ItemDto(
        id = id,
        positionId = id.toString(),
        description = "Pos $id",
        quantity = BigDecimal.ONE,
        unit = "H87",
        unitPriceNet = null,
        taxRate = null,
        taxRateRefId = null,
        netAmount = null,
        vatAmount = null,
        grossAmount = null,
        category = category.name,
        sortOrder = order,
    )

    // ── Chips ──────────────────────────────────────────────────────────────────

    @Test
    fun `every chip is offered, regardless of the per-diem feature flag`() {
        // A TRIP without per-diem days must never lose its own filter chip just because
        // the per-diem feature is off (only appendPerDiemAddButton's "add a day" button stays
        // gated on it).
        assertEquals(
            listOf(
                null,
                InvoiceItemCategory.PRODUCT,
                InvoiceItemCategory.UNIT_PRICE,
                InvoiceItemCategory.SERVICE,
                InvoiceItemCategory.TRIP,
            ),
            ITEM_FILTER_OPTIONS.map { it.category },
        )
    }

    // ── Active filter reset ─────────────────────────────────────────────────────

    @Test
    fun `the pre-existing empty-category reset still applies`() {
        val travel = listOf(item(1, InvoiceItemCategory.TRIP))
        assertEquals(null, effectiveItemCategoryFilter(InvoiceItemCategory.SERVICE, travel))
    }

    @Test
    fun `a visible filter with matching items keeps its category`() {
        val items = listOf(item(1, InvoiceItemCategory.TRIP), item(2, InvoiceItemCategory.SERVICE))
        assertEquals(InvoiceItemCategory.TRIP, effectiveItemCategoryFilter(InvoiceItemCategory.TRIP, items))
        assertEquals(InvoiceItemCategory.SERVICE, effectiveItemCategoryFilter(InvoiceItemCategory.SERVICE, items))
        assertEquals(null, effectiveItemCategoryFilter(null, items))
    }

    @Test
    fun `a null filter never filters the collection itself`() {
        val items = listOf(item(1, InvoiceItemCategory.TRIP), item(2, InvoiceItemCategory.SERVICE))
        assertEquals(null, effectiveItemCategoryFilter(null, items))
    }
}
