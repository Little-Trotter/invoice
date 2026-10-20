package de.littletrotter.xpdf.impl

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The structured fields of [InvoiceReportModel] — the only input the Typst template reads — keep
 * label and value apart and carry raw text, so the template alone decides weight, colour and size.
 */
class EngineNeutralModelFieldsTest {

    private val builder = InvoiceReportModelBuilder()

    @Test
    fun `info box rows keep label and value apart, in order`() {
        val model = builder.build(TypstParityFixtures.cases().first().invoice)

        assertEquals("PAR-2026-0001", model.rinfo.rows.first().value)
        assertEquals(listOf("DE123456789", "123/456/78901"), model.pinfo.rows.map { it.value })
    }

    @Test
    fun `identifier sublines drop a buyer id equal to the seller id`() {
        val distinct = TypstParityFixtures.detailItem(1)
        val same = distinct.copy(b2bValues = listOf(B2bValueDto(B2bTerm.BT_155, "ART-1"), B2bValueDto(B2bTerm.BT_156, "ART-1")))
        val rows = builder.build(TypstParityFixtures.invoice("ID-1", listOf(distinct, same))).items

        assertEquals(listOf(ReportItemIdLine("", "ART-4711"), ReportItemIdLine("Ref.", "KD-991")), rows[0].idLines)
        assertEquals(listOf(ReportItemIdLine("", "ART-1")), rows[1].idLines)
    }

    @Test
    fun `plain rows carry no sublines and are never sub-rows`() {
        val rows = builder.build(TypstParityFixtures.invoice("PL-1", listOf(TypstParityFixtures.plainItem(1)))).items

        assertEquals(1, rows.size)
        assertTrue(rows.single().idLines.isEmpty())
        assertEquals("", rows.single().tripSubline)
        assertFalse(rows.single().subRow)
    }

    @Test
    fun `trip rows carry the purpose subline once and flag every expense and per-diem row`() {
        val rows = builder.build(TypstParityFixtures.invoice("TR-1", InvoiceLayoutFixtures.job701Items())).items
        val trip = rows.single { it.pos == "002" }

        assertEquals("Zweck: Reisefahrt   Datum: 05.09.2026 – 12.09.2026", trip.tripSubline)
        assertFalse(trip.subRow)
        val subRows = rows.filter { it.pos.startsWith("002.") }
        assertEquals(5, subRows.size)
        assertTrue(subRows.all { it.subRow && it.tripSubline.isEmpty() && it.idLines.isEmpty() && it.buyerArticleDescriptionLine.isEmpty() })
    }

    @Test
    fun `footer contact lines carry the raw text, seat only with a register number`() {
        val full = builder.build(TypstParityFixtures.cases().first().invoice).footer
        assertEquals(listOf("Sitz: Bremen", "Tel. +49 421 987654-0", "rechnung@schmidt-sanitaer.example", "www.schmidt-sanitaer.example"), full.contactLines.map { it.text })
        assertEquals(listOf(false, true, true, true), full.contactLines.map { it.muted })

        val noRegister = TypstParityFixtures.sender().copy(commercialRegisterNumber = null, phone = null, website = null)
        val invoice = TypstParityFixtures.cases().first().invoice.copy(senderProfile = noRegister)
        val footer = builder.build(invoice).footer
        assertEquals(listOf("rechnung@schmidt-sanitaer.example"), footer.contactLines.map { it.text })
    }
}
