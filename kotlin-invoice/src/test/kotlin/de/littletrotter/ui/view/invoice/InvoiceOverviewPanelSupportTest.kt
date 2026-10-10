package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [InvoiceOverviewPanel.kt]'s standalone pure functions (the actual decision logic behind the
 * Overview tab's read-only rendering):
 * [resolveRecipientAddresses]'s per-recipient-id and billing-fallback branches,
 * [applyRecipientAddressChecks]'s four-way `when`, [b2bComplianceFindingLabel]'s definition
 * fallback chain and line-vs-header fork, [buildCategorySummaries]'s empty-group
 * filter, and [formatAddress]'s blank-parts filter. The Karibu-rendered [InvoiceOverviewPanel]
 * class itself is not covered here — its logic is entirely these functions plus straight-line
 * Vaadin construction.
 */
class InvoiceOverviewPanelSupportTest {

    private fun address(street: String? = "Hauptstr. 1", postal: String? = "12345", city: String? = "Berlin") =
        AddressDto(id = 1L, street = street, postalCode = postal, city = city, countryAlpha2 = "DE")

    // ── resolveRecipientAddresses ────────────────────────────────────────────

    @Test
    fun `with no recipient id, the current billing and delivery are kept as-is`() {
        val billing = address(street = "A")
        val delivery = address(street = "B")
        val result = resolveRecipientAddresses(
            null,
            ResolvedRecipientAddresses(billing, delivery),
            RecipientAddressLookup({ fail() }, { fail() }),
        )
        assertEquals(billing, result.billing)
        assertEquals(delivery, result.deliveryForDisplay)
    }

    @Test
    fun `with a recipient id, billing and delivery are looked up fresh, ignoring the current values`() {
        val fresh = address(street = "Fresh")
        val result = resolveRecipientAddresses(
            5L,
            ResolvedRecipientAddresses(address(street = "Stale"), null),
            RecipientAddressLookup({ fresh }, { null }),
        )
        assertEquals(fresh, result.billing)
    }

    @Test
    fun `deliveryForDisplay falls back to billing when there is no delivery override`() {
        val billing = address(street = "Billing")
        val result = resolveRecipientAddresses(
            5L,
            ResolvedRecipientAddresses(null, null),
            RecipientAddressLookup({ billing }, { null }),
        )
        assertEquals(billing, result.deliveryForDisplay)
    }

    @Test
    fun `deliveryForDisplay uses the real delivery address when one resolves`() {
        val delivery = address(street = "Delivery")
        val result = resolveRecipientAddresses(
            5L,
            ResolvedRecipientAddresses(null, null),
            RecipientAddressLookup({ address(street = "Billing") }, { delivery }),
        )
        assertEquals(delivery, result.deliveryForDisplay)
    }

    private fun fail(): Nothing = throw AssertionError("must not be called when recipientId is null")

    // ── applyRecipientAddressChecks ──────────────────────────────────────────

    private fun completenessCheck(key: String, complete: Boolean = false) = InvoiceCompletenessCheck(key, complete)

    @Test
    fun `applyRecipientAddressChecks derives recipientAddress as billing OR delivery complete`() {
        val checks = listOf(completenessCheck("invoiceDetail.summary.checklist.recipientAddress"))
        val resolved = ResolvedRecipientAddresses(billing = null, deliveryForDisplay = address())
        val result = applyRecipientAddressChecks(checks, resolved)
        assertTrue(result[0].isComplete, "delivery alone being complete must satisfy the combined check")
    }

    @Test
    fun `applyRecipientAddressChecks derives recipientBillingAddress from billing alone`() {
        val checks = listOf(completenessCheck("invoiceDetail.summary.checklist.recipientBillingAddress"))
        val resolved = ResolvedRecipientAddresses(billing = null, deliveryForDisplay = address())
        val result = applyRecipientAddressChecks(checks, resolved)
        assertEquals(false, result[0].isComplete, "billing check must not be satisfied by a complete delivery address")
    }

    @Test
    fun `applyRecipientAddressChecks derives recipientDeliveryAddress from delivery alone`() {
        val checks = listOf(completenessCheck("invoiceDetail.summary.checklist.recipientDeliveryAddress"))
        val resolved = ResolvedRecipientAddresses(billing = address(), deliveryForDisplay = null)
        val result = applyRecipientAddressChecks(checks, resolved)
        assertEquals(false, result[0].isComplete)
    }

    @Test
    fun `applyRecipientAddressChecks leaves an unrelated check untouched`() {
        val checks = listOf(completenessCheck("invoiceDetail.summary.checklist.invoiceNumber", complete = true))
        val result = applyRecipientAddressChecks(checks, ResolvedRecipientAddresses(null, null))
        assertTrue(result[0].isComplete, "unrelated keys must pass through the `else` branch unchanged")
    }

    @Test
    fun `an address missing any of street, postalCode or city is not complete`() {
        val checks = listOf(completenessCheck("invoiceDetail.summary.checklist.recipientBillingAddress"))
        val incomplete = address(postal = "")
        val result = applyRecipientAddressChecks(checks, ResolvedRecipientAddresses(billing = incomplete, deliveryForDisplay = null))
        assertEquals(false, result[0].isComplete)
    }

    // ── b2bComplianceFindingLabel ────────────────────────────────────────────

    private fun finding(
        businessTermCodes: List<String> = listOf("BT-19"),
        definition: String? = null,
        positionId: String? = null,
        ruleId: String = "RULE",
    ) = B2bFindingDto(ruleId = ruleId, businessTermCodes = businessTermCodes, definition = definition, positionId = positionId)

    @Test
    fun `definition prefers the profile's own wording`() {
        val label = b2bComplianceFindingLabel(finding(definition = "Explizite Definition"), { it }, { _, d -> d })
        assertEquals("Explizite Definition", label)
    }

    @Test
    fun `definition falls back to the first business term's official code when the profile has none`() {
        val label = b2bComplianceFindingLabel(finding(), { it }, { _, d -> d })
        assertEquals("BT-19", label)
    }

    @Test
    fun `definition falls back to the ruleId when there are no business terms at all`() {
        val label = b2bComplianceFindingLabel(finding(businessTermCodes = emptyList(), ruleId = "SOME.RULE"), { it }, { _, d -> d })
        assertEquals("SOME.RULE", label)
    }

    @Test
    fun `a second business term appends the ifEmptySuffix with its official code`() {
        val label = b2bComplianceFindingLabel(finding(businessTermCodes = listOf("BT-19", "BT-134")), { code -> "(weil $code fehlt)" }, { _, d -> d })
        assertEquals("BT-19 (weil BT-134 fehlt)", label)
    }

    @Test
    fun `a line finding routes through lineFindingLabel with the position id`() {
        val label = b2bComplianceFindingLabel(finding(positionId = "3"), { it }, { positionId, definition -> "Pos $positionId: $definition" })
        assertEquals("Pos 3: BT-19", label)
    }

    @Test
    fun `a header finding never calls lineFindingLabel`() {
        val label = b2bComplianceFindingLabel(finding(), { it }, { _, _ -> throw AssertionError("must not be called for a header finding") })
        assertEquals("BT-19", label)
    }

    // ── buildCategorySummaries ───────────────────────────────────────────────

    private fun item(category: InvoiceItemCategory, net: String = "10.00") = ItemDto(
        id = 1L, positionId = "1", description = "x", quantity = BigDecimal.ONE, unit = null,
        unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = Money(BigDecimal(net)), vatAmount = null, grossAmount = null, category = category.name,
    )

    @Test
    fun `an empty item list produces no category groups at all`() {
        assertTrue(buildCategorySummaries(emptyList()).isEmpty())
    }

    @Test
    fun `a group with no matching items is dropped, not shown with a zero count`() {
        val summaries = buildCategorySummaries(listOf(item(InvoiceItemCategory.SERVICE)))
        assertEquals(1, summaries.size, "only the services group should appear — goods/travel groups have no items")
        assertEquals("invoiceDetail.summary.dashboard.services", summaries[0].labelKey)
    }

    @Test
    fun `two PRODUCT items both roll into the goods group`() {
        // EXPENSE was merged into InvoiceItemCategory.TRIP, which now maps solely to
        // the travel group; the goods group is PRODUCT alone.
        val summaries = buildCategorySummaries(listOf(item(InvoiceItemCategory.PRODUCT), item(InvoiceItemCategory.PRODUCT)))
        assertEquals(1, summaries.size)
        assertEquals(2, summaries[0].count)
    }

    @Test
    fun `the net total sums every matching item's netAmount, treating a null netAmount as zero`() {
        val withNullNet = ItemDto(
            id = 2L, positionId = "2", description = "x", quantity = BigDecimal.ONE, unit = null,
            unitPriceNet = null, taxRate = null, taxRateRefId = null,
            netAmount = null, vatAmount = null, grossAmount = null, category = InvoiceItemCategory.SERVICE.name,
        )
        val summaries = buildCategorySummaries(listOf(item(InvoiceItemCategory.SERVICE, "10.00"), withNullNet))
        assertEquals(Money(BigDecimal("10.00")), summaries[0].netTotal)
    }

    // ── formatAddress ─────────────────────────────────────────────────────────

    @Test
    fun `formatAddress renders dash for a null address`() {
        assertEquals("-", formatAddress(null))
    }

    @Test
    fun `formatAddress joins only the non-blank parts`() {
        val partial = AddressDto(id = null, street = "Hauptstr. 1", postalCode = null, city = "Berlin", countryAlpha2 = "")
        assertEquals("Hauptstr. 1, Berlin", formatAddress(partial))
    }

    @Test
    fun `formatAddress renders dash when every part is blank`() {
        val blank = AddressDto(id = null, street = "", postalCode = null, city = "", countryAlpha2 = null)
        assertEquals("-", formatAddress(blank))
    }
}
