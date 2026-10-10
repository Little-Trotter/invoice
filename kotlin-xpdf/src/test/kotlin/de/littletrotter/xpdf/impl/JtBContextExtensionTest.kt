package de.littletrotter.xpdf.impl

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Proves the invoice data context provides:
 *   - per item: BT-134/BT-135 (Positions-Leistungszeitraum), BT-154 (itemDescription)
 * reach [InvoiceReportContextBuilder.buildContext] and
 * `InvoiceReportRowBuilders.buildItems`, and that an absent value reads as blank/null — never a
 * crash — both at the typed [ItemRenderContext] layer and the typed [ReportItemRow] layer a
 * custom template (e.g. the Handwerker one) actually consumes.
 */
class JtBContextExtensionTest {

    private val renderer = InvoiceReportContextBuilder()

    private fun item(
        servicePeriodFrom: String? = null,
        servicePeriodTo: String? = null,
        itemDescription: String? = null,
        sellerId: String? = null,
        buyerId: String? = null,
        articleNumber: String? = null,
        buyerArticleDescription: String? = null,
    ): InvoiceItemDto = InvoiceItemDto(
        positionId = "1",
        description = "Montage Heizungsanlage",
        quantity = BigDecimal("1"),
        unitPriceNet = Money(BigDecimal("500.00")),
        netAmount = Money(BigDecimal("500.00")),
        vatAmount = Money(BigDecimal("95.00")),
        taxRatePercent = BigDecimal("19.00"),
        servicePeriodFrom = servicePeriodFrom,
        servicePeriodTo = servicePeriodTo,
        itemDescription = itemDescription,
        b2bValues = buildList {
            sellerId?.let { add(B2bValueDto(B2bTerm.BT_155, it)) }
            buyerId?.let { add(B2bValueDto(B2bTerm.BT_156, it)) }
        },
        articleNumber = articleNumber,
        buyerArticleDescription = buyerArticleDescription,
    )

    private fun invoice(
        items: List<InvoiceItemDto>,
        purchaseOrderReference: String? = null,
        projectNumber: String? = null,
    ): InvoiceDto {
        val net = items.sumOf { it.netAmount.amount }
        val vat = items.sumOf { it.vatAmount.amount }
        return InvoiceDto(
            invoiceNumber = InvoiceNumberDto("JTB-2026-0001"),
            dates = InvoiceDatesDto(invoiceDate = "2026-08-20"),
            references = InvoiceReferencesDto(
                purchaseOrderReference = purchaseOrderReference,
                projectNumber = projectNumber,
            ),
            items = items,
            totals = InvoiceTotalsDto(
                netAmount = Money(net), vatAmount = Money(vat), grossAmount = Money(net.add(vat)),
            ),
        )
    }

    private fun itemContext(ctx: RenderContext): ItemRenderContext = ctx.invoice.items.first()

    // ── Item-level: BT-134/BT-135 (Leistungszeitraum) ──

    @Test
    fun `item servicePeriodFrom and servicePeriodTo are formatted (G3) in the raw context`() {
        val ctx = renderer.buildContext(
            invoice(listOf(item(servicePeriodFrom = "2026-08-01", servicePeriodTo = "2026-08-15"))),
        )
        val raw = itemContext(ctx)
        assertEquals("01.08.2026", raw.servicePeriodFrom)
        assertEquals("15.08.2026", raw.servicePeriodTo)
    }

    @Test
    fun `item without a captured Leistungszeitraum reads as null, not a crash`() {
        val ctx = renderer.buildContext(invoice(listOf(item())))
        val raw = itemContext(ctx)
        assertNull(raw.servicePeriodFrom)
        assertNull(raw.servicePeriodTo)
    }

    @Test
    fun `item itemDescription (BT-154) passes through raw, null when not captured`() {
        val withText = itemContext(renderer.buildContext(invoice(listOf(item(itemDescription = "Zusatztext BT-154")))))
        assertEquals("Zusatztext BT-154", withText.itemDescription)

        val without = itemContext(renderer.buildContext(invoice(listOf(item()))))
        assertNull(without.itemDescription)
    }

    // ── Typed layer (InvoiceReportRowBuilders.buildItems / ReportItemRow) ──

    @Test
    fun `buildItems exposes servicePeriod, itemDescription and both article numbers as raw fields`() {
        val ctx = renderer.buildContext(
            invoice(
                listOf(
                    item(
                        servicePeriodFrom = "2026-08-01",
                        servicePeriodTo = "2026-08-15",
                        itemDescription = "Zusatztext BT-154",
                        sellerId = "ART-1",
                        buyerId = "KD-ART-1",
                    ),
                ),
            ),
        )
        val row = buildItems(ctx.invoice, "de").single()

        assertEquals("01.08.2026 – 15.08.2026", row.servicePeriod)
        assertEquals("Zusatztext BT-154", row.itemDescription)
        assertEquals("ART-1", row.idLines[0].value)
        assertEquals("KD-ART-1", row.idLines[1].value)
        assertEquals("Montage Heizungsanlage", row.description)
    }

    @Test
    fun `buildItems yields blank (not null-crashing) raw fields when nothing was captured`() {
        val ctx = renderer.buildContext(invoice(listOf(item())))
        val row = buildItems(ctx.invoice, "de").single()

        assertEquals("", row.servicePeriod)
        assertEquals("", row.itemDescription)
        assertEquals(emptyList<ReportItemIdLine>(), row.idLines)
    }

    // ── finding-fixes (2026-08-31, PO order) — system article number on every invoice ──

    @Test
    fun `raw sellerItemId falls back to the linked article's own number when no BT-155 was captured`() {
        val ctx = renderer.buildContext(invoice(listOf(item(articleNumber = "ART-SYS-1"))))
        val raw = itemContext(ctx)
        assertEquals("ART-SYS-1", raw.sellerItemId, "ohne erfasstes BT-155 zeigt die Rechnung die Systemnummer des Artikels")
    }

    @Test
    fun `raw sellerItemId prefers an explicitly captured BT-155 over the article's own number`() {
        val ctx = renderer.buildContext(invoice(listOf(item(sellerId = "MANUELL-1", articleNumber = "ART-SYS-1"))))
        val raw = itemContext(ctx)
        assertEquals("MANUELL-1", raw.sellerItemId, "ein erfasstes BT-155 (ggf. vom Artikel abweichend editiert) hat Vorrang")
    }

    @Test
    fun `raw sellerItemId is null when neither BT-155 nor a linked article exist`() {
        val raw = itemContext(renderer.buildContext(invoice(listOf(item()))))
        assertNull(raw.sellerItemId)
    }

    @Test
    fun `buildItems shows the article's own number as the first id line when no BT-155 was captured`() {
        val ctx = renderer.buildContext(invoice(listOf(item(articleNumber = "ART-SYS-1"))))
        assertEquals("ART-SYS-1", buildItems(ctx.invoice, "de").single().idLines.first().value)
    }

    // ── finding-fixes (2026-08-31, PO order) — customer article number plus its description ──

    @Test
    fun `raw buyerArticleDescription passes through, null when not resolved`() {
        val withDescription = itemContext(
            renderer.buildContext(invoice(listOf(item(buyerId = "K-42", buyerArticleDescription = "Kabelbinder 200mm")))),
        )
        assertEquals("Kabelbinder 200mm", withDescription.buyerArticleDescription)

        val without = itemContext(renderer.buildContext(invoice(listOf(item(buyerId = "K-42")))))
        assertNull(without.buyerArticleDescription)
    }

    @Test
    fun `service period with only a from-date still renders (no dash, no crash)`() {
        val ctx = renderer.buildContext(invoice(listOf(item(servicePeriodFrom = "2026-08-01"))))
        val row = buildItems(ctx.invoice, "de").single()
        assertEquals("01.08.2026", row.servicePeriod)
    }

    // ── Header-level: purchaseOrderReference and projectNumber stay their own keys ──

    @Test
    fun `existing purchaseOrderReference and projectNumber keys are untouched (no duplication)`() {
        val ctx = renderer.buildContext(
            invoice(listOf(item()), purchaseOrderReference = "BST-1", projectNumber = "PRJ-1"),
        )
        assertEquals("BST-1", ctx.invoice.purchaseOrderReference)
        assertEquals("PRJ-1", ctx.invoice.projectNumber)
    }
}
