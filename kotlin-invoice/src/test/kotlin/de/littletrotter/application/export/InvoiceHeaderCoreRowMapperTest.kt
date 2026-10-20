package de.littletrotter.application.export

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class InvoiceHeaderCoreRowMapperTest {

    private fun blankRow(): Array<Any?> = arrayOfNulls(74)

    /** [blankRow] plus a non-NULL invoice_date — the column is NOT NULL in the schema, so every
     *  test below that isn't itself exercising that constraint needs a real value here. */
    private fun blankRowWithInvoiceDate(): Array<Any?> = blankRow().also { it[InvoiceHeaderRowIndex.INVOICE_DATE] = LocalDate.of(2026, 1, 1) }

    @Test
    fun `maps identification, dates and payment term columns field by field`() {
        val row = blankRow()
        row[0] = 42L
        row[1] = "IP-"
        row[2] = 7L
        row[3] = InvoiceTypeCode.CORRECTED_INVOICE
        row[4] = InvoiceStatus.CANCELLED
        row[8] = LocalDate.of(2026, 3, 1)
        row[15] = BigDecimal("2.00")

        val result = InvoiceHeaderCoreRowMapper.map(row, "IP-2026-03-00042", invoiceNumberMalformed = false)

        assertThat(result.invoiceId).isEqualTo(42L)
        assertThat(result.invoiceNumber).isEqualTo("IP-2026-03-00042")
        assertThat(result.invoiceNumberPrefix).isEqualTo("IP-")
        assertThat(result.invoiceNumberSequence).isEqualTo(7L)
        assertThat(result.typeCode).isEqualTo(InvoiceTypeCode.CORRECTED_INVOICE)
        assertThat(result.status).isEqualTo(InvoiceStatus.CANCELLED)
        assertThat(result.invoiceDate).isEqualTo(LocalDate.of(2026, 3, 1))
        assertThat(result.paymentTermSnapshotDiscountPercent).isEqualByComparingTo(BigDecimal("2.00"))
    }

    @Test
    fun `every enum falls back to its Invoice-matching default on a blank row`() {
        val result = InvoiceHeaderCoreRowMapper.map(blankRowWithInvoiceDate(), rawInvoiceNumber = null, invoiceNumberMalformed = false)

        assertThat(result.invoiceNumber).isEmpty()
        assertThat(result.typeCode).isEqualTo(InvoiceTypeCode.COMMERCIAL_INVOICE)
        assertThat(result.status).isEqualTo(InvoiceStatus.DRAFT)
        assertThat(result.validationStatus).isEqualTo(ValidationStatus.NOT_VALIDATED)
        assertThat(result.invoiceFormat).isEqualTo(InvoiceFormat.GERMAN)
        assertThat(result.documentLanguage).isEqualTo("de")
        assertThat(result.paymentTermsDays).isEqualTo(14)
    }

    @Test
    fun `throws instead of silently defaulting a NULL invoice_date column`() {
        // invoice_date is NOT NULL at the DB level; a NULL here means the projection's column
        // index drifted out of sync with InvoiceHeaderRowIndex, which a fallback date (the old
        // 1970-01-01 sentinel) would hide as a wrong-but-plausible row instead of failing loudly.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            InvoiceHeaderCoreRowMapper.map(blankRow(), rawInvoiceNumber = null, invoiceNumberMalformed = false)
        }
        assertThat(ex.message).contains("invoice_date")
    }

    @Test
    fun `decodes an enum supplied as its raw ordinal number`() {
        val row = blankRowWithInvoiceDate()
        row[3] = InvoiceTypeCode.CREDIT_NOTE.ordinal
        row[4] = InvoiceStatus.ISSUED.ordinal

        val result = InvoiceHeaderCoreRowMapper.map(row, rawInvoiceNumber = null, invoiceNumberMalformed = false)

        assertThat(result.typeCode).isEqualTo(InvoiceTypeCode.CREDIT_NOTE)
        assertThat(result.status).isEqualTo(InvoiceStatus.ISSUED)
    }

    @Test
    fun `carries the malformed marker flag through unchanged`() {
        val result = InvoiceHeaderCoreRowMapper.map(blankRowWithInvoiceDate(), rawInvoiceNumber = "not-a-number", invoiceNumberMalformed = true)

        assertThat(result.invoiceNumber).isEqualTo("not-a-number")
        assertThat(result.invoiceNumberMalformed).isTrue()
    }
}
