package de.littletrotter.xpdf.impl.mustang

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemDayExportDto
import de.littletrotter.shared.TripExpenseExportDto
import de.littletrotter.shared.TripExportDto
import de.littletrotter.shared.TripWorkbookRefDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.Base64

/**
 * The TRIP-specific slice of [MustangTransactionBuilder]/[MustangExportModelMapper]: one
 * BG-25 line per trip, its BT-127 note (built from the export DTO, truncated at 1000
 * characters — README border case 7), BG-26 from the trip period, and the BG-24 workbook
 * attachment (MIME-aware, alongside receipts through the same unified attachment loop).
 */
class MustangTransactionBuilderTripTest {

    private fun tx(invoice: InvoiceDto, documentStore: DocumentStore? = null) =
        MustangTransactionBuilder(documentStore).build(MustangExportModelMapper.toModel(invoice))

    private fun expense(kindLabelKey: String, description: String, quantity: String, unitLabel: String, net: String) =
        TripExpenseExportDto(
            kindLabelKey = kindLabelKey,
            description = description,
            quantity = BigDecimal(quantity),
            unitLabel = unitLabel,
            unitPriceNet = Money(BigDecimal(net)),
            netAmount = Money(BigDecimal(net)),
        )

    private fun perDiemDay(claimable: String) = PerDiemDayExportDto(
        isArrivalDeparture = false,
        breakfastDeduction = false,
        lunchDeduction = false,
        dinnerDeduction = false,
        claimableAmount = Money(BigDecimal(claimable)),
    )

    private fun tripItem(
        detailedOnInvoice: Boolean = true,
        expenses: List<TripExpenseExportDto> = emptyList(),
        perDiemDays: List<PerDiemDayExportDto> = emptyList(),
        servicePeriodFrom: String? = "2026-03-12",
        servicePeriodTo: String? = "2026-03-14",
        workbookRef: TripWorkbookRefDto? = null,
        resolvedWorkbookBase64: String? = null,
    ) = InvoiceItemDto(
        positionId = "3",
        description = "Dienstreise Nürnberg",
        quantity = BigDecimal.ONE,
        unitPriceNet = Money(BigDecimal("450.00")),
        netAmount = Money(BigDecimal("450.00")),
        vatAmount = Money(BigDecimal("85.50")),
        grossAmount = Money(BigDecimal("535.50")),
        taxRatePercent = BigDecimal("19.00"),
        servicePeriodFrom = servicePeriodFrom,
        servicePeriodTo = servicePeriodTo,
        category = InvoiceItemCategory.TRIP,
        trip = TripExportDto(
            detailedOnInvoice = detailedOnInvoice,
            fixedNetPrice = null,
            calculatedNet = Money(BigDecimal("450.00")),
            expenses = expenses,
            perDiemDays = perDiemDays,
            workbookRef = workbookRef,
            resolvedWorkbookBase64 = resolvedWorkbookBase64,
        ),
    )

    @Test
    fun `a trip position produces exactly one BG-25 line, no extra rows`() {
        val t = tx(InvoiceDto(items = listOf(tripItem())))
        assertEquals(1, t.getZFItems().size)
    }

    // ── BG-26 — the trip period reaches the line via the ordinary service-period fields ─────

    @Test
    fun `BG-26 - the trip period is exported as the line's detailed delivery period`() {
        val t = tx(InvoiceDto(items = listOf(tripItem(servicePeriodFrom = "2026-03-12", servicePeriodTo = "2026-03-14"))))
        val item = t.getZFItems().single()
        assertEquals(java.sql.Date.valueOf("2026-03-12"), item.detailedDeliveryPeriodFrom)
        assertEquals(java.sql.Date.valueOf("2026-03-14"), item.detailedDeliveryPeriodTo)
    }

    // ── BT-127 — the line note built from the trip's own expenses ───────────────────────────

    @Test
    fun `a detailed trip with expenses gets a BT-127 note naming each expense`() {
        val t = tx(
            InvoiceDto(
                items = listOf(
                    tripItem(
                        detailedOnInvoice = true,
                        expenses = listOf(
                            expense("trip.kind.hotel", "Hotel XYZ", "2", "Nächte", "158.88"),
                            expense("trip.kind.transport", "Zugticket Berlin", "1", "Fahrt", "23.36"),
                        ),
                    ),
                ),
            ),
        )
        val notes = t.getZFItems().single().notes
        assertEquals(1, notes?.size)
        val note = notes!!.single()
        assertTrue(note.contains("Unterkunft"), "hotel kind missing: $note")
        assertTrue(note.contains("Hotel XYZ"), "hotel description missing: $note")
        assertTrue(note.contains("158,88"), "hotel amount missing: $note")
        assertTrue(note.contains("Fahrkarten"), "transport kind missing: $note")
        assertTrue(note.contains("23,36"), "transport amount missing: $note")
    }

    @Test
    fun `a grouped (not detailed) trip gets no BT-127 note even with expenses`() {
        val t = tx(
            InvoiceDto(
                items = listOf(
                    tripItem(detailedOnInvoice = false, expenses = listOf(expense("trip.kind.hotel", "Hotel XYZ", "1", "Nacht", "79.44"))),
                ),
            ),
        )
        assertNull(t.getZFItems().single().notes)
    }

    @Test
    fun `README border case 11 - detailed but no expenses and no per-diem days gets no BT-127 note`() {
        val t = tx(InvoiceDto(items = listOf(tripItem(detailedOnInvoice = true, expenses = emptyList()))))
        assertNull(t.getZFItems().single().notes)
    }

    @Test
    fun `a non-TRIP item never gets a BT-127 note`() {
        val plain = InvoiceItemDto(
            positionId = "1", description = "Consulting", quantity = BigDecimal.ONE,
            unitPriceNet = Money(BigDecimal("100")), netAmount = Money(BigDecimal("100")),
            category = InvoiceItemCategory.SERVICE,
            taxRatePercent = BigDecimal("19.00"),
        )
        val t = tx(InvoiceDto(items = listOf(plain)))
        assertNull(t.getZFItems().single().notes)
    }

    @Test
    fun `README border case 7 - 100 expenses truncate the BT-127 note at 1000 characters with the reference suffix`() {
        val manyExpenses = (1..100).map { n -> expense("trip.kind.other", "Posten Nummer $n mit einer laengeren Beschreibung", "1", "Stk", "1.00") }
        val t = tx(InvoiceDto(items = listOf(tripItem(detailedOnInvoice = true, expenses = manyExpenses))))

        val note = t.getZFItems().single().notes!!.single()
        assertEquals(1000, note.length)
        assertTrue(note.endsWith("… siehe Anlage"), "truncation suffix missing: ${note.takeLast(30)}")
    }

    @Test
    fun `per-diem days are summarised in the BT-127 note alongside expenses`() {
        val t = tx(
            InvoiceDto(
                items = listOf(
                    tripItem(detailedOnInvoice = true, expenses = emptyList(), perDiemDays = listOf(perDiemDay("21.00"), perDiemDay("21.00"))),
                ),
            ),
        )
        val note = t.getZFItems().single().notes!!.single()
        assertTrue(note.contains("2"), "day count missing: $note")
        assertTrue(note.contains("42,00"), "summed per-diem amount missing: $note")
    }

    // ── BG-24 — the resolved trip workbook joins the same attachment array as receipts ──────

    @Test
    fun `a resolved trip workbook is attached with the XLSX MIME type and its own file name`() {
        val ref = TripWorkbookRefDto(key = "lt:tripwb:abc", sha256 = "abc", fileName = "RE-1-dienstreise-3.xlsx")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val t = tx(InvoiceDto(items = listOf(tripItem(workbookRef = ref, resolvedWorkbookBase64 = base64))))

        val attachments = t.additionalReferencedDocuments
        assertEquals(1, attachments!!.size)
        val attachment = attachments.single()
        assertEquals("RE-1-dienstreise-3.xlsx", attachment.filename)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", attachment.mimetype)
        assertEquals("RE-1-dienstreise-3.xlsx", attachment.description)
        assertTrue(bytes.contentEquals(attachment.data), "attachment bytes must be the decoded base64 content")
    }

    @Test
    fun `a workbookRef without resolvedWorkbookBase64 (unresolved) produces no attachment`() {
        val ref = TripWorkbookRefDto(key = "lt:tripwb:abc", sha256 = "abc", fileName = "RE-1-dienstreise-3.xlsx")
        val t = tx(InvoiceDto(items = listOf(tripItem(workbookRef = ref, resolvedWorkbookBase64 = null))))
        assertNull(t.additionalReferencedDocuments)
    }

    @Test
    fun `receipts and the trip workbook both reach the wire through the one unified attachment loop`() {
        val ref = TripWorkbookRefDto(key = "lt:tripwb:abc", sha256 = "abc", fileName = "RE-1-dienstreise-3.xlsx")
        val base64 = Base64.getEncoder().encodeToString(byteArrayOf(9, 9))
        val invoice = InvoiceDto(
            items = listOf(tripItem(workbookRef = ref, resolvedWorkbookBase64 = base64)),
            receipts = listOf(de.littletrotter.shared.ReceiptDto(fileName = "vertrag.pdf", objectKey = "r/vertrag.pdf")),
        )
        val store: DocumentStore = mock()
        whenever(store.get(eq("r/vertrag.pdf"))).thenReturn(byteArrayOf(5, 6))

        val attachments = tx(invoice, store).additionalReferencedDocuments
        assertEquals(2, attachments!!.size)
        assertTrue(attachments.any { it.filename == "vertrag.pdf" && it.mimetype == "application/pdf" }, "receipt missing")
        assertTrue(attachments.any { it.filename == "RE-1-dienstreise-3.xlsx" }, "workbook missing")
    }
}
