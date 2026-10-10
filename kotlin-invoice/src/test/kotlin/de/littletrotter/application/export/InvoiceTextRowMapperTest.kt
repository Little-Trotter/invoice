package de.littletrotter.application.export

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class InvoiceTextRowMapperTest {

    private fun blankRow(): Array<Any?> = arrayOfNulls(75)
    private val seed = InvoiceHeaderExportRow(invoiceId = 1L, invoiceDate = LocalDate.now())

    @Test
    fun `maps references, storno, audit and the free-text column`() {
        val row = blankRow()
        row[51] = "LW-123"
        row[52] = "PO-1"
        row[53] = "SO-1"
        row[54] = LocalDate.of(2026, 1, 5)
        row[55] = "OF-1"
        row[56] = LocalDate.of(2026, 1, 6)
        row[57] = "PROJ-1"
        row[58] = "CC-1"
        row[59] = "IP-2026-01-00001"
        row[60] = LocalDate.of(2026, 1, 10)
        row[69] = OffsetDateTime.parse("2026-01-01T10:00:00Z")
        row[70] = "erika"
        row[71] = OffsetDateTime.parse("2026-01-02T11:00:00Z")
        row[72] = "max"
        row[73] = 5L
        row[74] = "Bitte zeitnah zahlen"

        val result = InvoiceTextRowMapper.apply(seed, row)

        assertThat(result.buyerReference).isEqualTo("LW-123")
        assertThat(result.purchaseOrderReference).isEqualTo("PO-1")
        assertThat(result.orderReference).isEqualTo("SO-1")
        assertThat(result.orderReferenceDate).isEqualTo(LocalDate.of(2026, 1, 5))
        assertThat(result.offerReference).isEqualTo("OF-1")
        assertThat(result.offerReferenceDate).isEqualTo(LocalDate.of(2026, 1, 6))
        assertThat(result.projectNumber).isEqualTo("PROJ-1")
        assertThat(result.costCenter).isEqualTo("CC-1")
        assertThat(result.originalInvoiceNumber).isEqualTo("IP-2026-01-00001")
        assertThat(result.originalInvoiceDate).isEqualTo(LocalDate.of(2026, 1, 10))
        assertThat(result.createdAt).isEqualTo(OffsetDateTime.parse("2026-01-01T10:00:00Z"))
        assertThat(result.createdBy).isEqualTo("erika")
        assertThat(result.updatedAt).isEqualTo(OffsetDateTime.parse("2026-01-02T11:00:00Z"))
        assertThat(result.updatedBy).isEqualTo("max")
        assertThat(result.version).isEqualTo(5L)
        assertThat(result.freeText).isEqualTo("Bitte zeitnah zahlen")
    }

    @Test
    fun `every text and audit field defaults to null on a blank row`() {
        val result = InvoiceTextRowMapper.apply(seed, blankRow())

        assertThat(result.buyerReference).isNull()
        assertThat(result.orderReferenceDate).isNull()
        assertThat(result.createdAt).isNull()
        assertThat(result.version).isNull()
        assertThat(result.freeText).isNull()
    }

    @Test
    fun `a non-OffsetDateTime createdAt or updatedAt column decodes to null`() {
        val row = blankRow()
        row[69] = "not-a-datetime"
        row[71] = 42L

        val result = InvoiceTextRowMapper.apply(seed, row)

        assertThat(result.createdAt).isNull()
        assertThat(result.updatedAt).isNull()
    }

    @Test
    fun `does not disturb fields outside the texts group`() {
        val seeded = seed.copy(sellerName = "Live Seller GmbH")

        val result = InvoiceTextRowMapper.apply(seeded, blankRow())

        assertThat(result.sellerName).isEqualTo("Live Seller GmbH")
    }
}
