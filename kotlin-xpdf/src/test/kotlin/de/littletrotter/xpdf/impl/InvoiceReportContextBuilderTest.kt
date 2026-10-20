package de.littletrotter.xpdf.impl

import de.littletrotter.shared.*
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.xpdf.b2bInvoiceDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal

class InvoiceReportContextBuilderTest {

  @Test
  fun `boundary - an unparseable ISO date falls back to the raw string instead of throwing`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-DATE-1"),
      dates = InvoiceDatesDto(invoiceDate = "not-a-real-date"),
      items = listOf(InvoiceItemDto(taxRatePercent = BigDecimal.ZERO, description = "Service", quantity = BigDecimal.ONE, netAmount = Money(BigDecimal("100.00")))),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), grossAmount = Money(BigDecimal("100.00"))),
    )
    val invoiceCtx = renderer.buildContext(invoice).invoice

    assertEquals("not-a-real-date", invoiceCtx.date)
  }

  // ═══ Task G tests ═══

  /**
   * G.5 — German number format: 1234.50 → 1.234,50 in the context map.
   */
  @Test
  fun `fmt produces German number format with grouping`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-FMT-1"),
      dates = InvoiceDatesDto(invoiceDate = "2026-04-03"),
      items = listOf(
        InvoiceItemDto(
          description = "Großer Auftrag",
          quantity = BigDecimal("10"),
          unitPriceNet = Money(BigDecimal("1234.50")),
          netAmount = Money(BigDecimal("12345.00")),
          vatAmount = Money(BigDecimal("2345.55")),
          taxRatePercent = BigDecimal("19"),
        )
      ),
      totals = InvoiceTotalsDto(
        netAmount = Money(BigDecimal("12345.00")),
        vatAmount = Money(BigDecimal("2345.55")),
        grossAmount = Money(BigDecimal("14690.55")),
      ),
    )
    val ctx = renderer.buildContext(invoice)
    val invoiceCtx = ctx.invoice
    // German format: grouping with dot, decimal comma
    assertEquals("12.345,00", invoiceCtx.netTotal)
    assertEquals("2.345,55", invoiceCtx.vatTotal)
    assertEquals("14.690,55", invoiceCtx.grossTotal)

    val firstItem = invoiceCtx.items.first()
    assertEquals("1.234,50", firstItem.unitPrice)
    assertEquals("12.345,00", firstItem.lineTotal)
  }

  /**
   * G.6 — Tax breakdown should appear when there are multiple tax rates.
   * Single tax rate should NOT produce a breakdown section.
   */
  /**
   * G.4 — "Rechnungsdatum:" line should not appear under the betreff.
   * G.3 — orderReference should appear in the rinfo block.
   */
  @Test
  fun `orderReference appears in context`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-REF-1"),
      dates = InvoiceDatesDto(invoiceDate = "2026-04-03", servicePeriodTo = "2026-03-31"),
      references = InvoiceReferencesDto(orderReference = "ANG-2026-042"),
      items = listOf(
        InvoiceItemDto(
          description = "Service",
          quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("100.00")),
          netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal("0.00")),
          taxRatePercent = BigDecimal("0"),
        )
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("0.00")), grossAmount = Money(BigDecimal("100.00"))),
    )
    val ctx = renderer.buildContext(invoice)
    val invoiceCtx = ctx.invoice
    assertEquals("ANG-2026-042", invoiceCtx.orderReference)
  }

  /**
   * G.7 — Payment block context should build even without IBAN; the `hasIban` flag guides
   * presentation in the template.
   */
  @Test
  fun `context builds without iban, sender iban key stays null`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-NOIBAN-1"),
      dates = InvoiceDatesDto(invoiceDate = "2026-04-03"),
      // senderProfile has no IBAN
      senderProfile = PartyDto(name = "Test GmbH"),
      items = listOf(
        InvoiceItemDto(
          description = "Service",
          quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("100.00")),
          netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal("0.00")),
          taxRatePercent = BigDecimal("0"),
        )
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("0.00")), grossAmount = Money(BigDecimal("100.00"))),
    )
    val ctx = renderer.buildContext(invoice)
    val sender = ctx.sender
    assertNull(sender.iban, "sender iban key must stay null, not crash, when unset")
  }

  /**
   * G.6 — Tax breakdown: single rate → null (no breakdown section).
   */
  @Test
  fun `tax breakdown is null for single tax rate`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-TAX-1"),
      dates = InvoiceDatesDto(invoiceDate = "2026-04-03"),
      items = listOf(
        InvoiceItemDto(
          description = "Item 1",
          quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("100.00")),
          netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal("19.00")),
          taxRatePercent = BigDecimal("19"),
        )
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("19.00")), grossAmount = Money(BigDecimal("119.00"))),
    )
    val ctx = renderer.buildContext(invoice)
    val invoiceCtx = ctx.invoice
    assertNull(invoiceCtx.taxBreakdown, "Single tax rate should not produce a breakdown")
  }

  /**
   * G.6 — Tax breakdown: two rates → breakdown with both rows.
   */
  @Test
  fun `tax breakdown has two rows for multiple tax rates`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-TAX-2"),
      dates = InvoiceDatesDto(invoiceDate = "2026-04-03"),
      items = listOf(
        InvoiceItemDto(
          description = "Standard",
          quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("100.00")),
          netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal("19.00")),
          taxRatePercent = BigDecimal("19"),
        ),
        InvoiceItemDto(
          description = "Ermäßigt",
          quantity = BigDecimal("2"),
          unitPriceNet = Money(BigDecimal("50.00")),
          netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal("7.00")),
          taxRatePercent = BigDecimal("7"),
        ),
      ),
      totals = InvoiceTotalsDto(
        netAmount = Money(BigDecimal("200.00")),
        vatAmount = Money(BigDecimal("26.00")),
        grossAmount = Money(BigDecimal("226.00")),
      ),
    )
    val ctx = renderer.buildContext(invoice)
    val invoiceCtx = ctx.invoice
    val breakdown = invoiceCtx.taxBreakdown!!
    val rows = breakdown.rows
    assertEquals(2, rows.size)

    val row1 = rows[0]  // 7% (sorted)
    assertEquals("7", row1.rate)
    assertEquals("100,00", row1.net)
    assertEquals("7,00", row1.vat)

    val row2 = rows[1]  // 19% (sorted)
    assertEquals("19", row2.rate)
    assertEquals("100,00", row2.net)
    assertEquals("19,00", row2.vat)
  }

  /**
   * G.5 — Date should be formatted as dd.MM.yyyy (German), not ISO.
   */
  @Test
  fun `dates are formatted in German dd MM yyyy format`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-DATE-1"),
      dates = InvoiceDatesDto(invoiceDate = "2026-04-03", dueDate = "2026-05-03"),
      items = listOf(
        InvoiceItemDto(
          description = "Service",
          quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("100.00")),
          netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal("0.00")),
          taxRatePercent = BigDecimal("0"),
        )
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("0.00")), grossAmount = Money(BigDecimal("100.00"))),
    )
    val ctx = renderer.buildContext(invoice)
    val invoiceCtx = ctx.invoice
    // German date format: dd.MM.yyyy
    assertEquals("03.04.2026", invoiceCtx.date)
    assertEquals("03.05.2026", invoiceCtx.dueDate)
  }

  // ═══ Document-language-aware formatting ═══

  private fun invoiceWithLanguage(language: String?, netAmount: BigDecimal = BigDecimal("1234.50")): InvoiceDto = InvoiceDto(
    invoiceNumber = InvoiceNumberDto("INV-R3-1"),
    dates = InvoiceDatesDto(invoiceDate = "2026-08-15", dueDate = "2026-08-30"),
    meta = InvoiceMetaDto(documentLanguage = language),
    items = listOf(
      InvoiceItemDto(
        description = "Beratung", quantity = BigDecimal("1"),
        unitPriceNet = Money(netAmount), netAmount = Money(netAmount),
        vatAmount = Money(BigDecimal("0.00")), taxRatePercent = BigDecimal("0"),
      )
    ),
    totals = InvoiceTotalsDto(netAmount = Money(netAmount), vatAmount = Money(BigDecimal("0.00")), grossAmount = Money(netAmount)),
  )

  @Test
  fun `R3-T15 - amounts follow the document language (German grouping vs English grouping)`() {
    val renderer = InvoiceReportContextBuilder()
    val deCtx = renderer.buildContext(invoiceWithLanguage("de"))
    val enCtx = renderer.buildContext(invoiceWithLanguage("en"))

    assertEquals("1.234,50", deCtx.invoice.netTotal)
    assertEquals("1,234.50", enCtx.invoice.netTotal)
  }

  @Test
  fun `R3-T16 - dates follow the document language`() {
    val renderer = InvoiceReportContextBuilder()
    val deCtx = renderer.buildContext(invoiceWithLanguage("de"))
    val enCtx = renderer.buildContext(invoiceWithLanguage("en"))

    assertEquals("15.08.2026", deCtx.invoice.date)
    assertEquals("15 Aug 2026", enCtx.invoice.date)
  }

  @Test
  fun `R3-T17 - payment due text fills all three branches in both languages`() {
    val renderer = InvoiceReportContextBuilder()

    fun dueText(language: String, dueDate: String?, days: Int): String {
      val base = invoiceWithLanguage(language)
      val invoice = base.copy(dates = base.dates.copy(dueDate = dueDate, paymentTermsDays = days))
      val ctx = renderer.buildContext(invoice)
      return ctx.invoice.paymentDueText as String
    }

    assertEquals("Zahlbar bis 30.08.2026", dueText("de", "2026-08-30", 0))
    assertEquals("Payable by 30 Aug 2026", dueText("en", "2026-08-30", 0))
    // PO decision 3 (06.09.2026) — the wording-drift question (PDF said "Zahlbar
    // innerhalb N Tagen netto", the XML said "...innerhalb von N Tagen..." before this package)
    // is resolved the other way from this package's own original proposal: the PDF's
    // pre-existing wording (no "von") wins, the wire follows the print image. No exception to
    // "no German document moves" survives — this sentence is now the fallback for an invoice
    // with no payment term at all in any case (see PaymentTermTextIdentityTest).
    assertEquals("Zahlbar innerhalb 14 Tagen netto", dueText("de", null, 14))
    assertEquals("Payable within 14 days net", dueText("en", null, 14))
    assertEquals("Zahlbar sofort", dueText("de", null, 0))
    assertEquals("Payable immediately", dueText("en", null, 0))
  }

  @Test
  fun `R3-T18 - unit label on an item follows the document language, unece code stays language-independent`() {
    val renderer = InvoiceReportContextBuilder()
    val deInvoice = invoiceWithLanguage("de").copy(
      items = listOf(InvoiceItemDto(taxRatePercent = BigDecimal.ZERO, description = "Beratung", quantity = BigDecimal("2"), unitLabel = "Stunden", unitCode = "HUR"))
    )
    val enInvoice = invoiceWithLanguage("en").copy(
      items = listOf(InvoiceItemDto(taxRatePercent = BigDecimal.ZERO, description = "Consulting", quantity = BigDecimal("2"), unitLabel = "hours", unitCode = "HUR"))
    )

    val deItem = renderer.buildContext(deInvoice).invoice.items.first()
    val enItem = renderer.buildContext(enInvoice).invoice.items.first()

    assertEquals("Stunden", deItem.unitLabel)
    assertEquals("hours", enItem.unitLabel)
  }

  @Test
  fun `R3-T20 - a large English amount is not truncated in the formatted string`() {
    val renderer = InvoiceReportContextBuilder()
    val ctx = renderer.buildContext(invoiceWithLanguage("en", netAmount = BigDecimal("123456.78")))
    assertEquals("123,456.78", ctx.invoice.netTotal)
  }

  @Test
  fun `R3-T13 - the Reverse-Charge note text comes from the AE item's exemption reason text`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("en")
    val invoice = base.copy(
      totals = base.totals.copy(reverseCharge = true),
      items = listOf(
        InvoiceItemDto(taxRatePercent = BigDecimal.ZERO,
          description = "Beratung", quantity = BigDecimal("1"),
          netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal.ZERO),
          taxCategory = "AE", exemptionReasonText = "Steuerschuldnerschaft aus § 3a Abs. 2 UStG",
        )
      ),
    )
    val ctx = renderer.buildContext(invoice)
    assertEquals("Steuerschuldnerschaft aus § 3a Abs. 2 UStG", ctx.invoice.reverseChargeNoteText)
  }

  @Test
  fun `R3-T14 - without an exemption reason text on the AE item, reverseChargeNoteText is null`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("en")
    val invoice = base.copy(
      totals = base.totals.copy(reverseCharge = true),
      items = listOf(
        InvoiceItemDto(taxRatePercent = BigDecimal.ZERO,
          description = "Beratung", quantity = BigDecimal("1"),
          netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal.ZERO),
          taxCategory = "AE", exemptionReasonText = null,
        )
      ),
    )
    val ctx = renderer.buildContext(invoice)
    assertNull(ctx.invoice.reverseChargeNoteText)
  }

  @Test
  fun `resolveRenderLanguage defaults to de for null, blank or unknown values`() {
    val renderer = InvoiceReportContextBuilder()
    assertEquals("de", renderer.resolveRenderLanguage(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = null))))
    assertEquals("de", renderer.resolveRenderLanguage(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = ""))))
    assertEquals("de", renderer.resolveRenderLanguage(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = "fr"))))
    assertEquals("en", renderer.resolveRenderLanguage(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = "en"))))
  }

  // ═══ B3 — object-model proof: B2B values reach the render context ═══
  //
  // Analogous to `En16931TermsExportTest` (kotlin-xpdf/impl/mustang) at the object-model level,
  // here for the PDF rendering pipeline instead of the XML export: checks that `buildContext`
  // carries the line item numbers (BT-155/BT-156, `InvoiceItemDto.b2bValues`) unchanged into the
  // data structure the template consumes.

  @Test
  fun `B3 - LINE-FREE BT-155BT-156 landen als sellerItemId und buyerItemId in der Positionszeile`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-B2B-3"),
      dates = InvoiceDatesDto(invoiceDate = "2026-08-13"),
      items = listOf(
        InvoiceItemDto(taxRatePercent = BigDecimal.ZERO,
          description = "Transportleistung",
          quantity = BigDecimal.ONE,
          netAmount = Money(BigDecimal("100.00")),
          b2bValues = listOf(B2bValueDto(B2bTerm.BT_155, "ART-001"), B2bValueDto(B2bTerm.BT_156, "KD-ART-99")),
        ),
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), grossAmount = Money(BigDecimal("100.00"))),
    )
    val firstItem = renderer.buildContext(invoice).invoice.items.first()

    assertEquals("ART-001", firstItem.sellerItemId)
    assertEquals("KD-ART-99", firstItem.buyerItemId)
  }

  @Test
  fun `boundary - sellerItemId falls back to the linked article number when BT-155 was never captured`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-B2B-4"),
      dates = InvoiceDatesDto(invoiceDate = "2026-08-13"),
      items = listOf(
        InvoiceItemDto(taxRatePercent = BigDecimal.ZERO,
          description = "Transportleistung",
          quantity = BigDecimal.ONE,
          netAmount = Money(BigDecimal("100.00")),
          articleNumber = "SYS-042",
          b2bValues = emptyList(),
        ),
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), grossAmount = Money(BigDecimal("100.00"))),
    )
    val firstItem = renderer.buildContext(invoice).invoice.items.first()

    assertEquals("SYS-042", firstItem.sellerItemId)
  }

  @Test
  fun `boundary - a trip expense with no unit label renders as an empty string, not null`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-RK-1"),
      dates = InvoiceDatesDto(invoiceDate = "2026-08-13"),
      items = listOf(
        InvoiceItemDto(taxRatePercent = BigDecimal.ZERO,
          description = "Reisekosten",
          quantity = BigDecimal.ONE,
          netAmount = Money(BigDecimal("50.00")),
          category = InvoiceItemCategory.TRIP,
          trip = TripExportDto(
            detailedOnInvoice = true,
            fixedNetPrice = null,
            calculatedNet = Money(BigDecimal("50.00")),
            expenses = listOf(
              TripExpenseExportDto(
                kindLabelKey = "trip.kind.other",
                description = "Sonstiges",
                quantity = BigDecimal.ONE,
                unitLabel = null,
                unitPriceNet = Money(BigDecimal("50.00")),
                netAmount = Money(BigDecimal("50.00")),
              ),
            ),
          ),
        ),
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("50.00")), grossAmount = Money(BigDecimal("50.00"))),
    )
    val firstItem = renderer.buildContext(invoice).invoice.items.first()
    val firstExpense = firstItem.tripExpenses.first()

    assertEquals("", firstExpense.unitLabel)
  }

  // ── payment-due-text: a blank (non-null) dueDate is a distinct branch from a null one ────

  @Test
  fun `an empty-string dueDate is treated the same as a missing one, in both languages`() {
    val renderer = InvoiceReportContextBuilder()
    val de = invoiceWithLanguage("de").let { it.copy(dates = it.dates.copy(dueDate = "", paymentTermsDays = 0)) }
    val en = invoiceWithLanguage("en").let { it.copy(dates = it.dates.copy(dueDate = "", paymentTermsDays = 0)) }

    assertEquals("Zahlbar sofort", renderer.buildContext(de).invoice.paymentDueText)
    assertEquals("Payable immediately", renderer.buildContext(en).invoice.paymentDueText)
  }

  // ── cancellation-print-image — a cancellation document carries no payment wording at all ─────────────

  @Test
  fun `a cancellation document gets no payment-due text in either language`() {
    val renderer = InvoiceReportContextBuilder()
    listOf("de", "en").forEach { language ->
      val base = invoiceWithLanguage(language)
      val cancellation = base.copy(cancellation = true, dates = base.dates.copy(dueDate = null, paymentTermsDays = 0))

      val inv = renderer.buildContext(cancellation).invoice

      assertNull(inv.paymentDueText, "a storno ($language) must not resolve to the 'immediately' fallback")
      assertEquals(false, inv.hasPaymentTerms, "a storno ($language) must never show a payment row")
    }
  }

  @Test
  fun `cancellation wins over a stray due date - no payment text and no payment row either way`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("de")
    val cancellation = base.copy(cancellation = true, dates = base.dates.copy(dueDate = "2026-08-30", paymentTermsDays = 14))

    val inv = renderer.buildContext(cancellation).invoice

    assertNull(inv.paymentDueText)
    assertEquals(false, inv.hasPaymentTerms)
  }

  // ── PO decisions of 06.09.2026, extended 28.09.2026 — a chosen payment term's own resolved
  //    name wins over the date-composed fallback and, when a due date is also known, the card
  //    additionally names it; cancellation still wins over even a stray payment term name. ───────────

  @Test
  fun `a chosen payment term's resolved name wins over the date-composed fallback and names the due date`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("de")
    val invoice = base.copy(dates = base.dates.copy(paymentTermName = "30 Tage netto", dueDate = "2026-08-30", paymentTermsDays = 30))

    assertEquals("30 Tage netto · fällig am 30.08.2026", renderer.buildContext(invoice).invoice.paymentDueText)
  }

  @Test
  fun `a chosen payment term's resolved name names the due date in English too`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("en")
    val invoice = base.copy(dates = base.dates.copy(paymentTermName = "Net 30 days", dueDate = "2026-08-30", paymentTermsDays = 30))

    assertEquals("Net 30 days · due on 30 Aug 2026", renderer.buildContext(invoice).invoice.paymentDueText)
  }

  @Test
  fun `a chosen payment term's resolved name without a due date stays the bare name`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("de")
    val invoice = base.copy(dates = base.dates.copy(paymentTermName = "30 Tage netto", dueDate = null, paymentTermsDays = 30))

    assertEquals("30 Tage netto", renderer.buildContext(invoice).invoice.paymentDueText)
  }

  @Test
  fun `cancellation wins even over a stray payment term name`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("de")
    val cancellation = base.copy(cancellation = true, dates = base.dates.copy(paymentTermName = "30 Tage netto"))

    assertNull(renderer.buildContext(cancellation).invoice.paymentDueText, "a storno must never show payment wording, even a stray resolved name")
  }

  // ── cash discount (Skonto) payment-block sentence — spec §5.3/§6 ─────────────────────────

  @Test
  fun `cash discount sentence in German matches the spec's exact wording`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("de")
    val invoice = base.copy(
      cashDiscount = InvoiceCashDiscountDto(BigDecimal("2"), 7, "2026-10-05", Money.of("12.09"), Money.of("592.57")),
    )

    assertEquals(
      "Bei Zahlung bis 05.10.2026: 2 % Skonto (12,09 €), zahlbar 592,57 €",
      renderer.buildContext(invoice).invoice.cashDiscountText,
    )
  }

  @Test
  fun `cash discount sentence in English strips the percent's trailing zero and drops the percent-sign space`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("en")
    val invoice = base.copy(
      cashDiscount = InvoiceCashDiscountDto(BigDecimal("2.5"), 7, "2026-10-05", Money.of("12.09"), Money.of("592.57")),
    )

    // "d MMM yyyy" (dateFormatterEn, see R3-T16 above) never zero-pads the day — "5 Oct 2026",
    // not "05 Oct 2026"; the currency stays "amount €" like every other total in this builder
    // (amount()/printedCurrencyUnit in Labels.kt — a non-breaking space, not the spec table's
    // illustrative prefixed "€592.57").
    assertEquals(
      "If paid by 5 Oct 2026: 2.5% discount (12.09 €), payable 592.57 €",
      renderer.buildContext(invoice).invoice.cashDiscountText,
    )
  }

  @Test
  fun `no cash discount sentence without a computed discount`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = invoiceWithLanguage("de")

    assertNull(renderer.buildContext(invoice).invoice.cashDiscountText)
  }

  @Test
  fun `no cash discount sentence on a cancellation even with a discount set`() {
    val renderer = InvoiceReportContextBuilder()
    val base = invoiceWithLanguage("de")
    val cancellation = base.copy(
      cancellation = true,
      cashDiscount = InvoiceCashDiscountDto(BigDecimal("2"), 7, "2026-10-05", Money.of("12.09"), Money.of("592.57")),
    )

    assertNull(
      renderer.buildContext(cancellation).invoice.cashDiscountText,
      "a storno must never invite early payment, even with a stray discount on the row",
    )
  }

  // ── hasPaymentTerms — all three combinations of dueDate / paymentTermsDays ───────────────

  @Test
  fun `hasPaymentTerms is true with a due date and zero payment term days`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = invoiceWithLanguage("de").let { it.copy(dates = it.dates.copy(dueDate = "2026-08-30", paymentTermsDays = 0)) }
    assertEquals(true, renderer.buildContext(invoice).invoice.hasPaymentTerms)
  }

  @Test
  fun `hasPaymentTerms is true with payment term days and no due date`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = invoiceWithLanguage("de").let { it.copy(dates = it.dates.copy(dueDate = null, paymentTermsDays = 14)) }
    assertEquals(true, renderer.buildContext(invoice).invoice.hasPaymentTerms)
  }

  @Test
  fun `hasPaymentTerms is false with neither a due date nor payment term days`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = invoiceWithLanguage("de").let { it.copy(dates = it.dates.copy(dueDate = null, paymentTermsDays = 0)) }
    assertEquals(false, renderer.buildContext(invoice).invoice.hasPaymentTerms)
  }

  // ── tax breakdown grouping: same-rate tie-break by category ────

  @Test
  fun `tax breakdown rows with the same rate are tie-broken by category`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-TR-2"),
      dates = InvoiceDatesDto(invoiceDate = "2026-08-13"),
      items = listOf(
        InvoiceItemDto(
          description = "Z-item", quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("100.00")), netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal("0.00")), taxRatePercent = BigDecimal("0"), taxCategory = "Z",
        ),
        InvoiceItemDto(
          description = "E-item", quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("50.00")), netAmount = Money(BigDecimal("50.00")),
          vatAmount = Money(BigDecimal("0.00")), taxRatePercent = BigDecimal("0"), taxCategory = "E",
        ),
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("150.00")), vatAmount = Money(BigDecimal("0.00")), grossAmount = Money(BigDecimal("150.00"))),
    )
    val rows = renderer.buildContext(invoice).invoice.taxBreakdown!!.rows

    assertEquals(2, rows.size)
    // both rows share rate "0" -> tie-broken by category, alphabetically: E before Z
    assertEquals("E", rows[0].category)
    assertEquals("Z", rows[1].category)
  }

  // ═══ taxBreakdown grouping ═══

  @Test
  fun `two AE items sharing the same raw exemption reason text still merge into one row on an English document`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-TR-3"),
      dates = InvoiceDatesDto(invoiceDate = "2026-09-06"),
      meta = InvoiceMetaDto(documentLanguage = "en"),
      items = listOf(
        InvoiceItemDto(
          description = "A", quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("100.00")), netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal.ZERO), taxRatePercent = BigDecimal.ZERO,
          taxCategory = "AE", exemptionReasonCode = "VATEX-EU-AE",
          exemptionReasonText = "Steuerschuldnerschaft aus § 3a Abs. 2 UStG.",
        ),
        InvoiceItemDto(
          description = "B", quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("50.00")), netAmount = Money(BigDecimal("50.00")),
          vatAmount = Money(BigDecimal.ZERO), taxRatePercent = BigDecimal.ZERO,
          taxCategory = "AE", exemptionReasonCode = "VATEX-EU-AE",
          exemptionReasonText = "Steuerschuldnerschaft aus § 3a Abs. 2 UStG.",
        ),
        // A second, distinct group (rate/category) — needed only so `taxBreakdown` itself is
        // populated (the wire-up guard is `rows.size > 1`); the assertions below are entirely
        // about the AE row staying a single merged row.
        InvoiceItemDto(
          description = "C", quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("30.00")), netAmount = Money(BigDecimal("30.00")),
          vatAmount = Money(BigDecimal("5.70")), taxRatePercent = BigDecimal("19"), taxCategory = "S",
        ),
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("180.00")), vatAmount = Money(BigDecimal("5.70")), grossAmount = Money(BigDecimal("185.70"))),
    )
    val rows = renderer.buildContext(invoice).invoice.taxBreakdown!!.rows

    // A single merged AE row carrying the sum of both AE items' net amounts.
    assertEquals(2, rows.size)
    val aeRow = rows.single { it.category == "AE" }
    assertEquals("150.00", aeRow.net)
  }

  @Test
  fun `boundary - two tax breakdown groups sharing the same rate sort with a null category before a named one`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-TR-4"),
      dates = InvoiceDatesDto(invoiceDate = "2026-09-06"),
      items = listOf(
        InvoiceItemDto(
          description = "A", quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("100.00")), netAmount = Money(BigDecimal("100.00")),
          vatAmount = Money(BigDecimal("19.00")), taxRatePercent = BigDecimal("19"), taxCategory = null,
        ),
        InvoiceItemDto(
          description = "B", quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("50.00")), netAmount = Money(BigDecimal("50.00")),
          vatAmount = Money(BigDecimal("9.50")), taxRatePercent = BigDecimal("19"), taxCategory = "S",
        ),
      ),
      totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("150.00")), vatAmount = Money(BigDecimal("28.50")), grossAmount = Money(BigDecimal("178.50"))),
    )
    val rows = renderer.buildContext(invoice).invoice.taxBreakdown!!.rows

    assertEquals(2, rows.size)
    assertEquals(null, rows[0].category)
    assertEquals("S", rows[1].category)
  }

  // ═══ formatServicePeriod ═══

  private fun invoiceWithServicePeriod(from: String?, to: String?, language: String? = null): InvoiceDto = InvoiceDto(
    invoiceNumber = InvoiceNumberDto("INV-SP-1"),
    dates = InvoiceDatesDto(invoiceDate = "2026-04-03", servicePeriodFrom = from, servicePeriodTo = to),
    meta = InvoiceMetaDto(documentLanguage = language),
    items = listOf(InvoiceItemDto(taxRatePercent = BigDecimal.ZERO, description = "Service", quantity = BigDecimal.ONE, netAmount = Money(BigDecimal("100.00")))),
    totals = InvoiceTotalsDto(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("0.00")), grossAmount = Money(BigDecimal("100.00"))),
  )

  @Test
  fun `servicePeriod is null when neither from nor to is set`() {
    val renderer = InvoiceReportContextBuilder()
    val invoiceCtx = renderer.buildContext(invoiceWithServicePeriod(null, null)).invoice
    assertNull(invoiceCtx.servicePeriod)
  }

  @Test
  fun `servicePeriod is a from-to range in German when both dates are set`() {
    val renderer = InvoiceReportContextBuilder()
    val invoiceCtx = renderer.buildContext(invoiceWithServicePeriod("2026-03-01", "2026-03-31")).invoice
    assertEquals("01.03.2026 – 31.03.2026", invoiceCtx.servicePeriod)
  }

  @Test
  fun `servicePeriod falls back to just the end date when only 'to' is set`() {
    val renderer = InvoiceReportContextBuilder()
    val invoiceCtx = renderer.buildContext(invoiceWithServicePeriod(null, "2026-03-31")).invoice
    assertEquals("31.03.2026", invoiceCtx.servicePeriod)
  }

  @Test
  fun `servicePeriod reads 'seit' (since) in German when only 'from' is set`() {
    val renderer = InvoiceReportContextBuilder()
    val invoiceCtx = renderer.buildContext(invoiceWithServicePeriod("2026-03-01", null)).invoice
    assertEquals("seit 01.03.2026", invoiceCtx.servicePeriod)
  }

  @Test
  fun `servicePeriod reads 'since' in English when only 'from' is set`() {
    val renderer = InvoiceReportContextBuilder()
    val invoiceCtx = renderer.buildContext(invoiceWithServicePeriod("2026-03-01", null, language = "en")).invoice
    assertEquals("since 1 Mar 2026", invoiceCtx.servicePeriod)
  }

  // ═══ merged from the former PdfRendererFactoryImplTest (PAGIN, file split — that class only
  // ever tested InvoiceReportContextBuilder, never the deleted one-getter factory itself) ═══

  @Test
  fun `buildContext exposes freeText servicePeriod and paymentDueText`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-2026-03-00101"),
      dates = InvoiceDatesDto(
        invoiceDate = "2026-03-21",
        servicePeriodFrom = "2026-03-01",
        servicePeriodTo = "2026-03-31",
        dueDate = "2026-04-20",
        paymentTermsDays = 30,
      ),
      meta = InvoiceMetaDto(freeText = "Bitte Projektnummer angeben.\nDanke!"),
      references = InvoiceReferencesDto(buyerReference = "REF-42", orderReference = "ANGEBOT-7"),
      recipient = PartyDto(customerNumber = "K-1001"),
      senderProfile = PartyDto(iban = "DE02120300000000202051", bankName = "Musterbank"),
      totals = InvoiceTotalsDto(
        netAmount = Money(BigDecimal("200.00")),
        vatAmount = Money(BigDecimal("38.00")),
        grossAmount = Money(BigDecimal("238.00")),
      ),
      items = listOf(
        InvoiceItemDto(taxRatePercent = BigDecimal.ZERO,
          description = "Report",
          quantity = BigDecimal.ONE,
          unitPriceNet = Money(BigDecimal("200.00")),
          netAmount = Money(BigDecimal("200.00")),
        ),
      ),
    )

    val ctx = renderer.buildContext(invoice)
    val inv = ctx.invoice
    val rcp = ctx.recipient

    // §4.1 — free text passed through
    assertEquals("Bitte Projektnummer angeben.\nDanke!", inv.freeText)
    // §4.2 — service period from-to, formatted in German
    assertEquals("01.03.2026 – 31.03.2026", inv.servicePeriod)
    // §4.3 — payment due date: dueDate takes precedence
    assertEquals("Zahlbar bis 20.04.2026", inv.paymentDueText)
    // Info-box fields kept separate
    assertEquals("REF-42", inv.buyerReference)
    assertEquals("ANGEBOT-7", inv.orderReference)
    assertEquals("K-1001", rcp.customerNumber)

    val sender = ctx.sender
    assertEquals("Musterbank", sender.bankName)
  }

  @Test
  fun `buildContext exposes commercial register fields`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-1"),
      senderProfile = PartyDto(
        registerCourt = "Amtsgericht Berlin",
        commercialRegisterNumber = "HRB 123456",
      ),
    )

    val sender = renderer.buildContext(invoice).sender

    assertEquals("Amtsgericht Berlin", sender.registerCourt)
    assertEquals("HRB 123456", sender.commercialRegisterNumber)
  }

  @Test
  fun `buildContext exposes the matching foreign VAT registration alongside the home vatId`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-2"),
      recipient = PartyDto(name = "Buyer", country = "LU"),
      meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU"),
      senderProfile = PartyDto(
        vatId = "DE123456789",
        additionalVatRegistrations = listOf(
          de.littletrotter.shared.VatRegistrationDto("LU", "LU12345678"),
        ),
      ),
    )

    val sender = renderer.buildContext(invoice).sender

    assertEquals("DE123456789", sender.vatId)
    assertEquals("LU12345678", sender.registrationVatId)
    assertEquals("LU", sender.registrationVatCountry)
  }

  @Test
  fun `buildContext carries the registration tax number and its label key`() {
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-TN"),
      recipient = PartyDto(name = "Buyer", country = "LU"),
      meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU"),
      senderProfile = PartyDto(
        vatId = "DE123456789",
        additionalVatRegistrations = listOf(
          VatRegistrationDto("LU", "LU12345678", "1990010112345", "foreignTaxNumber.label.matricule"),
        ),
      ),
    )

    val sender = InvoiceReportContextBuilder().buildContext(invoice).sender

    assertEquals("1990010112345", sender.registrationTaxNumber)
    assertEquals("foreignTaxNumber.label.matricule", sender.registrationTaxNumberLabelKey)
  }

  @Test
  fun `buildContext ignores a registration whose country is not the recipient's`() {
    val registrations = listOf(VatRegistrationDto("LU", "LU12345678", "1990010112345", "foreignTaxNumber.label.matricule"))
    listOf(PartyDto(name = "Buyer"), PartyDto(name = "Buyer", country = "FR"), null).forEach { recipient ->
      val invoice = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("INV-R3A"),
        recipient = recipient,
        meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU"),
        senderProfile = PartyDto(vatId = "DE123456789", additionalVatRegistrations = registrations),
      )

      val sender = InvoiceReportContextBuilder().buildContext(invoice).sender

      assertEquals("DE123456789", sender.vatId)
      assertNull(sender.registrationVatId)
      assertNull(sender.registrationTaxNumber)
    }
  }

  @Test
  fun `buildContext leaves registration keys null without a sellerVatRegistrationCountry`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-3"),
      senderProfile = PartyDto(
        vatId = "DE123456789",
        additionalVatRegistrations = listOf(
          de.littletrotter.shared.VatRegistrationDto("LU", "LU12345678"),
        ),
      ),
    )

    val sender = renderer.buildContext(invoice).sender

    assertEquals("DE123456789", sender.vatId)
    assertNull(sender.registrationVatId)
    assertNull(sender.registrationVatCountry)
  }

  @Test
  fun `buildContext leaves registration keys null when no additionalVatRegistrations entry matches`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-4"),
      meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU"),
      senderProfile = PartyDto(vatId = "DE123456789"),
    )

    val sender = renderer.buildContext(invoice).sender

    assertEquals("DE123456789", sender.vatId)
    assertNull(sender.registrationVatId)
    assertNull(sender.registrationVatCountry)
  }

  @Test
  fun `buildContext trims sellerVatRegistrationCountry when matching registrations (ensures consistency with resolveActiveSellerVatId)`() {
    val renderer = InvoiceReportContextBuilder()
    val invoice = InvoiceDto(
      invoiceNumber = InvoiceNumberDto("INV-TRIM-1"),
      recipient = PartyDto(name = "Buyer", country = "LU"),
      meta = InvoiceMetaDto(sellerVatRegistrationCountry = " LU "), // Note: spaces before and after
      senderProfile = PartyDto(
        vatId = "DE123456789",
        additionalVatRegistrations = listOf(
          de.littletrotter.shared.VatRegistrationDto("LU", "LU12345678"), // Exact match without spaces
        ),
      ),
    )

    val sender = renderer.buildContext(invoice).sender

    // Should match despite whitespace around the country code
    assertEquals("LU12345678", sender.registrationVatId, "Should trim country code and find matching registration")
    assertEquals("LU", sender.registrationVatCountry)
  }

  // ═══ The printed currency comes from the invoice, never from the document language ═══

  // InvoiceTotalsDto.currency is a typed Currency now (was a raw Int? ordinal); the
  // null/out-of-range wire fallback this helper used to exercise via a bare Int moved to
  // CurrencyOrdinalDeserializer and is pinned there (kotlin-shared's CurrencyTest).
  private fun invoiceWithCurrency(currency: Currency = Currency.EUR, language: String? = "de"): InvoiceDto = InvoiceDto(
    invoiceNumber = InvoiceNumberDto("INV-R8-1"),
    dates = InvoiceDatesDto(invoiceDate = "2026-09-07"),
    meta = InvoiceMetaDto(documentLanguage = language),
    totals = InvoiceTotalsDto(currency = currency, netAmount = Money(BigDecimal("12.00"))),
  )

  @Test
  fun `R8 - a CHF invoice in German carries the currencyCode CHF, not the language-derived Euro sign`() {
    val ctx = InvoiceReportContextBuilder().buildContext(invoiceWithCurrency(Currency.CHF, language = "de"))

    assertEquals("CHF", ctx.invoice.currencyCode)
  }

  @Test
  fun `R8 - an EUR invoice in German carries the ISO code EUR, not the Euro sign`() {
    val ctx = InvoiceReportContextBuilder().buildContext(invoiceWithCurrency(Currency.EUR, language = "de"))

    assertEquals("EUR", ctx.invoice.currencyCode)
  }

  @Test
  fun `R8 - an EUR invoice in English still carries EUR, unchanged`() {
    val ctx = InvoiceReportContextBuilder().buildContext(invoiceWithCurrency(Currency.EUR, language = "en"))

    assertEquals("EUR", ctx.invoice.currencyCode)
  }

  @Test
  fun `R8 - an invoice with the default (unset) currency carries EUR`() {
    val ctx = InvoiceReportContextBuilder().buildContext(invoiceWithCurrency())

    assertEquals("EUR", ctx.invoice.currencyCode)
  }

  @Test
  fun `R8 - every one of the six currencies resolves to its own ISO code in the context`() {
    Currency.entries.forEach { currency ->
      val ctx = InvoiceReportContextBuilder().buildContext(invoiceWithCurrency(currency))
      assertEquals(currency.code, ctx.invoice.currencyCode, "currency $currency")
    }
  }

  @Test
  fun `T2 - freeText stays verbatim`() {
    val freeText = "**Wichtig** ++unterstrichen++ ~~alt~~\n1. Eins\n    - Unterpunkt"
    val invoice = InvoiceDto(meta = InvoiceMetaDto(freeText = freeText))

    val invoiceCtx = InvoiceReportContextBuilder().buildContext(invoice).invoice

    assertEquals(freeText, invoiceCtx.freeText)
  }

  // ═══ B7 (B3 Part C) — Free-text context: the freeText value is exactly invoice.meta.freeText,
  //     nothing appended, independent of the B2B header term values. ═══

  @Test
  fun `B7 - freeText equals invoice meta freeText verbatim with a B2B profile and FREE header term values set, no term value leaks in`() {
    val renderer = InvoiceReportContextBuilder()
    val freeText = "Bitte Auftragsnummer und Projektstand angeben.\nMit freundlichen Grüßen"
    val invoice = b2bInvoiceDto(b2bValues = true).copy(meta = InvoiceMetaDto(freeText = freeText))

    val invoiceCtx = renderer.buildContext(invoice).invoice

    // the exact free text, nothing appended beneath it
    assertEquals(freeText, invoiceCtx.freeText)

    // none of the B2B header term values (b2bInvoiceDto's FREE terms) are concatenated in
    val printedFreeText = invoiceCtx.freeText as String
    listOf(
      "V-2026-0815", // BT-12
      "OBJ-4711", // BT-18
      "KST-4711", // BT-19
      "Frau Meier, Kreditorenbuchhaltung", // BT-56
      "meier@acme.example", // BT-58
    ).forEach { termValue ->
      assertFalse(printedFreeText.contains(termValue), "freeText must not contain the B2B term value '$termValue'")
    }
  }

  @Test
  fun `B7 - freeText is null and hasFreeText-equivalent stays empty without a free text, B2B values do not fall back into the free-text slot`() {
    val renderer = InvoiceReportContextBuilder()
    val invoiceNull = b2bInvoiceDto(b2bValues = true).copy(meta = InvoiceMetaDto(freeText = null))
    val invoiceBlank = b2bInvoiceDto(b2bValues = true).copy(meta = InvoiceMetaDto(freeText = "   "))

    val nullCtx = renderer.buildContext(invoiceNull).invoice
    val blankCtx = renderer.buildContext(invoiceBlank).invoice

    assertNull(nullCtx.freeText, "no free text on the invoice must stay null, not fall back to any B2B value")
    assertEquals("   ", blankCtx.freeText, "a blank free text is passed through as-is, not swapped for a B2B value")
  }

  @Test
  fun `B7 - freeText passes through verbatim without a B2B profile, including line breaks and leading-trailing whitespace`() {
    val renderer = InvoiceReportContextBuilder()
    // b2bValues = false -> no B2B profile/terms at all (community case)
    val freeText = "  Erste Zeile.\nZweite Zeile mit  doppeltem Leerzeichen.\nDritte Zeile.  "
    val invoice = b2bInvoiceDto(b2bValues = false).copy(meta = InvoiceMetaDto(freeText = freeText))

    val invoiceCtx = renderer.buildContext(invoice).invoice

    // current behaviour: buildContext does not trim or reformat freeText in any way — the
    // context builder's "freeText" field is a raw pass-through.
    assertEquals(freeText, invoiceCtx.freeText)
  }
}
