package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** exercises [InvoiceItemsTab] directly instead of going through [InvoiceDetailView]. */
class InvoiceDetailViewItemsTest {

    private var nextItemId = 1L

    private fun item(category: InvoiceItemCategory, sortOrder: Int = 0, positionId: String = ""): ItemDto = ItemDto(
        id = nextItemId++, positionId = positionId, description = "", quantity = BigDecimal.ONE,
        unit = null, unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        category = category.name, sortOrder = sortOrder,
    )

    private fun invoiceDto(items: List<ItemDto>): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    @Test
    fun `unified item filter is preserved across renders when items exist for it`() {
        val tab = testItemsTab().apply { activeItemCategoryFilter = InvoiceItemCategory.TRIP }
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.TRIP, sortOrder = 1),
            item(InvoiceItemCategory.TRIP, sortOrder = 2),
        ))

        tab.render(invoice)

        assertEquals(InvoiceItemCategory.TRIP, tab.activeItemCategoryFilter)
    }

    @Test
    fun `unified item filter resets to Alle when no items remain in the filtered category`() {
        val tab = testItemsTab().apply { activeItemCategoryFilter = InvoiceItemCategory.TRIP }
        val invoice = invoiceDto(listOf(item(InvoiceItemCategory.SERVICE, sortOrder = 1)))

        tab.render(invoice)

        assertEquals(null, tab.activeItemCategoryFilter)
    }

    @Test
    fun `sortedItemsForCategory filters by category and sort order`() {
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.SERVICE, sortOrder = 2, positionId = "2"),
            item(InvoiceItemCategory.PRODUCT, sortOrder = 5, positionId = "5"),
            item(InvoiceItemCategory.SERVICE, sortOrder = 1, positionId = "1"),
        ))

        val result = sortedItemsForCategory(invoice, InvoiceItemCategory.SERVICE)

        assertEquals(listOf("1", "2"), result.map { it.positionId })
    }

    @Test
    fun `countItemsByCategory returns zero for missing categories and counts existing ones`() {
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.SERVICE, sortOrder = 1),
            item(InvoiceItemCategory.SERVICE, sortOrder = 2),
            item(InvoiceItemCategory.TRIP, sortOrder = 3),
        ))

        val counts = countItemsByCategory(invoice)

        assertEquals(2, counts[InvoiceItemCategory.SERVICE])
        assertEquals(1, counts[InvoiceItemCategory.TRIP])
        assertEquals(0, counts[InvoiceItemCategory.PRODUCT])
    }

    @Test
    fun `countItemsByCategory counts multiple TRIP items`() {
        // TRAVEL_EXPENSE/EXPENSE/PER_DIEM were merged into TRIP; this used to pin
        // that PER_DIEM items count separately from TRAVEL_EXPENSE ones, a distinction that no
        // longer exists (there is only one travel-like category left).
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.TRIP, sortOrder = 1),
            item(InvoiceItemCategory.TRIP, sortOrder = 2),
        ))
        val counts = countItemsByCategory(invoice)
        assertEquals(2, counts[InvoiceItemCategory.TRIP])
        assertEquals(0, counts[InvoiceItemCategory.PRODUCT])
    }

    @Test
    fun `positionIdComparator sorts numerically, not lexicographically`() {
        val items = listOf("120", "5", "1", "42").map { item(InvoiceItemCategory.SERVICE, positionId = it) }

        val sorted = items.sortedWith(positionIdComparator())

        assertEquals(listOf("1", "5", "42", "120"), sorted.map { it.positionId })
    }

    @Test
    fun `positionIdComparator sorts non-numeric position ids last`() {
        val items = listOf("2", "n-a", "1").map { item(InvoiceItemCategory.SERVICE, positionId = it) }

        val sorted = items.sortedWith(positionIdComparator())

        assertEquals(listOf("1", "2", "n-a"), sorted.map { it.positionId })
    }

    // The items-tab header row (counter left, "Neu"-buttons right via
    // justify-content: space-between) used to sit flush against the tab content's right edge; the
    // fix is the item-toolbar-row CSS class (styles.css), asserted here since Karibu can't measure
    // the resulting pixel gap.
    @Test
    fun `item header row carries item-toolbar-row class on initial render`() {
        val tab = testItemsTab()
        val invoice = invoiceDto(listOf(item(InvoiceItemCategory.PRODUCT, sortOrder = 1)))

        tab.render(invoice)

        assertTrue(flatten(tab).any { it.classNames.contains("item-toolbar-row") })
    }

    @Test
    fun `item header row carries item-toolbar-row class after incremental refresh`() {
        val tab = testItemsTab()
        val invoice = invoiceDto(listOf(item(InvoiceItemCategory.PRODUCT, sortOrder = 1)))
        tab.render(invoice)

        tab.refresh(invoice)

        assertTrue(flatten(tab).any { it.classNames.contains("item-toolbar-row") })
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    // UI-5 slice 5 — refresh rebuilds its OWN copy of the filter-button row (a separate closure
    // from render's initial one, see the KDoc on both), and that copy's click listener had
    // never actually been clicked — every prior test either drives activeItemCategoryFilter
    // directly or only exercises render's own listener via a fresh render.
    @Test
    fun `clicking a filter button after an incremental refresh re-filters through refresh's own listener`() {
        val tab = testItemsTab()
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.PRODUCT, sortOrder = 1),
            item(InvoiceItemCategory.SERVICE, sortOrder = 2),
        ))
        tab.render(invoice)
        tab.refresh(invoice) // rebuilds headerSlot with refresh's own filter buttons

        val serviceLabel = "${de.littletrotter.ui.i18n.UiTranslations.tr(tab, "item.category.service")} (1)"
        val serviceButton = flatten(tab).filterIsInstance<Button>().single { it.text == serviceLabel }
        ComponentUtil.fireEvent(serviceButton, ClickEvent(serviceButton))

        assertEquals(InvoiceItemCategory.SERVICE, tab.activeItemCategoryFilter)
    }

    @Test
    fun `clicking the Alle filter button after an incremental refresh clears the category filter`() {
        val tab = testItemsTab().apply { activeItemCategoryFilter = InvoiceItemCategory.PRODUCT }
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.PRODUCT, sortOrder = 1),
            item(InvoiceItemCategory.SERVICE, sortOrder = 2),
        ))
        tab.render(invoice)
        tab.refresh(invoice)

        val allLabel = "${de.littletrotter.ui.i18n.UiTranslations.tr(tab, "invoiceDetail.filter.all")} (2)"
        val allButton = flatten(tab).filterIsInstance<Button>().single { it.text == allLabel }
        ComponentUtil.fireEvent(allButton, ClickEvent(allButton))

        assertEquals(null, tab.activeItemCategoryFilter)
    }

    private fun filterChips(component: Component): List<Button> =
        flatten(component).filterIsInstance<Button>().filter { it.classNames.contains("item-filter-chip") }

    // PO 14.09.2026 — the filter row must read as a filter (bordered chips, aria-pressed toggle
    // state, a title/aria-label per chip) instead of a plain list of counters.
    @Test
    fun `every filter chip carries aria-pressed matching the active filter on initial render`() {
        val tab = testItemsTab().apply { activeItemCategoryFilter = InvoiceItemCategory.SERVICE }
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.PRODUCT, sortOrder = 1),
            item(InvoiceItemCategory.SERVICE, sortOrder = 2),
        ))

        tab.render(invoice)

        val chips = filterChips(tab)
        assertTrue(chips.isNotEmpty())
        chips.forEach { chip ->
            val shouldBeActive = chip.classNames.contains("item-filter-chip--active")
            assertEquals(shouldBeActive.toString(), chip.element.getAttribute("aria-pressed"))
        }
        assertEquals(1, chips.count { it.classNames.contains("item-filter-chip--active") })
    }

    @Test
    fun `a zero-count category chip is disabled while Alle stays enabled`() {
        val tab = testItemsTab()
        val invoice = invoiceDto(listOf(item(InvoiceItemCategory.PRODUCT, sortOrder = 1)))

        tab.render(invoice)

        val chips = filterChips(tab)
        val allLabel = de.littletrotter.ui.i18n.UiTranslations.tr(tab, "invoiceDetail.filter.all")
        val tripLabel = de.littletrotter.ui.i18n.UiTranslations.tr(tab, "item.category.trip")
        val allChip = chips.single { it.text.startsWith("$allLabel (") }
        val tripChip = chips.single { it.text.startsWith("$tripLabel (") }

        assertTrue(allChip.isEnabled)
        assertTrue(!tripChip.isEnabled)
        assertTrue(!tripChip.element.getAttribute("title").isNullOrBlank())
    }

    @Test
    fun `clicking an enabled filter chip on initial render still filters the items`() {
        val tab = testItemsTab()
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.PRODUCT, sortOrder = 1),
            item(InvoiceItemCategory.SERVICE, sortOrder = 2),
        ))
        tab.render(invoice)

        val serviceLabel = de.littletrotter.ui.i18n.UiTranslations.tr(tab, "item.category.service")
        val serviceChip = filterChips(tab).single { it.text.startsWith("$serviceLabel (") }
        ComponentUtil.fireEvent(serviceChip, ClickEvent(serviceChip))

        assertEquals(InvoiceItemCategory.SERVICE, tab.activeItemCategoryFilter)
    }

    @Test
    fun `the filter chip row after an incremental refresh has the same chip set and state as after a full render`() {
        val tab = testItemsTab().apply { activeItemCategoryFilter = InvoiceItemCategory.PRODUCT }
        val invoice = invoiceDto(listOf(
            item(InvoiceItemCategory.PRODUCT, sortOrder = 1),
            item(InvoiceItemCategory.SERVICE, sortOrder = 2),
        ))
        tab.render(invoice)
        val beforeRefresh = filterChips(tab).map { chip ->
            Triple(chip.text, chip.isEnabled, chip.element.getAttribute("aria-pressed"))
        }

        tab.refresh(invoice)

        val afterRefresh = filterChips(tab).map { chip ->
            Triple(chip.text, chip.isEnabled, chip.element.getAttribute("aria-pressed"))
        }
        assertEquals(beforeRefresh, afterRefresh)
    }
}
