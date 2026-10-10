package de.littletrotter.application.export

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Pure unit tests for [InvoiceHeaderRowMapper.fromRow] — no database, hand-built `Array<Any?>`
 * rows in the exact column layout the mapper's own KDoc documents (75 columns, 0-indexed).
 */
class InvoiceHeaderRowMapperTest {

    /** Every column defaulted to `null` except invoice_date (NOT NULL at the DB level, see
     *  [InvoiceHeaderCoreRowMapper.map]'s own KDoc) — individual tests override only what they
     *  care about via [withOverrides]. Matches [InvoiceHeaderRowMapper.fromRow]'s documented
     *  0..74 layout. */
    private fun blankRow(): Array<Any?> = arrayOfNulls<Any?>(75).also { it[InvoiceHeaderRowIndex.INVOICE_DATE] = LocalDate.of(2026, 1, 1) }

    private fun Array<Any?>.withOverrides(vararg overrides: Pair<Int, Any?>): Array<Any?> =
        copyOf().also { copy -> overrides.forEach { (index, value) -> copy[index] = value } }

    @Test
    fun `maps identification, date and payment term columns field by field`() {
        val row = blankRow().withOverrides(
            0 to 42L,
            1 to "IP-",
            2 to 7L,
            3 to InvoiceTypeCode.CORRECTED_INVOICE,
            4 to InvoiceStatus.CANCELLED,
            5 to ValidationStatus.VALID,
            6 to InvoiceFormat.INTERNATIONAL,
            7 to "en",
            8 to DocumentEngine.FACTURX,
            9 to LocalDate.of(2026, 3, 1),
            10 to LocalDate.of(2026, 3, 2),
            11 to LocalDate.of(2026, 2, 1),
            12 to LocalDate.of(2026, 2, 28),
            13 to LocalDate.of(2026, 3, 15),
            14 to 14,
            15 to "Net 14",
            16 to BigDecimal("2.00"),
            17 to 7,
        )

        val result = InvoiceHeaderRowMapper.fromRow(row, "IP-2026-03-00042", invoiceNumberMalformed = false)

        assertThat(result.invoiceId).isEqualTo(42L)
        assertThat(result.invoiceNumber).isEqualTo("IP-2026-03-00042")
        assertThat(result.invoiceNumberMalformed).isFalse()
        assertThat(result.invoiceNumberPrefix).isEqualTo("IP-")
        assertThat(result.invoiceNumberSequence).isEqualTo(7L)
        assertThat(result.typeCode).isEqualTo(InvoiceTypeCode.CORRECTED_INVOICE)
        assertThat(result.status).isEqualTo(InvoiceStatus.CANCELLED)
        assertThat(result.validationStatus).isEqualTo(ValidationStatus.VALID)
        assertThat(result.invoiceFormat).isEqualTo(InvoiceFormat.INTERNATIONAL)
        assertThat(result.documentLanguage).isEqualTo("en")
        assertThat(result.documentEngine).isEqualTo(DocumentEngine.FACTURX)
        assertThat(result.invoiceDate).isEqualTo(LocalDate.of(2026, 3, 1))
        assertThat(result.deliveryDate).isEqualTo(LocalDate.of(2026, 3, 2))
        assertThat(result.servicePeriodFrom).isEqualTo(LocalDate.of(2026, 2, 1))
        assertThat(result.servicePeriodTo).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 3, 15))
        assertThat(result.paymentTermsDays).isEqualTo(14)
        assertThat(result.paymentTermSnapshotName).isEqualTo("Net 14")
        assertThat(result.paymentTermSnapshotDiscountPercent).isEqualByComparingTo(BigDecimal("2.00"))
        assertThat(result.paymentTermSnapshotDiscountDays).isEqualTo(7)
    }

    @Test
    fun `every enum falls back to its Invoice-matching default when its column is null`() {
        val result = InvoiceHeaderRowMapper.fromRow(blankRow(), rawInvoiceNumber = null, invoiceNumberMalformed = false)

        assertThat(result.invoiceNumber).isEmpty()
        assertThat(result.typeCode).isEqualTo(InvoiceTypeCode.COMMERCIAL_INVOICE)
        assertThat(result.status).isEqualTo(InvoiceStatus.DRAFT)
        assertThat(result.validationStatus).isEqualTo(ValidationStatus.NOT_VALIDATED)
        assertThat(result.invoiceFormat).isEqualTo(InvoiceFormat.GERMAN)
        assertThat(result.documentLanguage).isEqualTo("de")
        assertThat(result.documentEngine).isEqualTo(DocumentEngine.MUSTANG)
        assertThat(result.currency).isEqualTo(Currency.EUR)
        assertThat(result.netAmount).isEqualTo(Money.ZERO)
        assertThat(result.vatAmount).isEqualTo(Money.ZERO)
        assertThat(result.grossAmount).isEqualTo(Money.ZERO)
        assertThat(result.partySource).isEqualTo(PartySource.MASTER_DATA)
    }

    @Test
    fun `throws instead of silently defaulting a NULL invoice_date column`() {
        val row = blankRow().withOverrides(InvoiceHeaderRowIndex.INVOICE_DATE to null)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            InvoiceHeaderRowMapper.fromRow(row, rawInvoiceNumber = null, invoiceNumberMalformed = false)
        }
        assertThat(ex.message).contains("invoice_date")
    }

    @Test
    fun `decodes an enum supplied as its raw ordinal number`() {
        val row = blankRow().withOverrides(
            3 to InvoiceTypeCode.CREDIT_NOTE.ordinal,
            4 to InvoiceStatus.SENT.ordinal,
            61 to Currency.CHF.ordinal,
        )

        val result = InvoiceHeaderRowMapper.fromRow(row, rawInvoiceNumber = null, invoiceNumberMalformed = false)

        assertThat(result.typeCode).isEqualTo(InvoiceTypeCode.CREDIT_NOTE)
        assertThat(result.status).isEqualTo(InvoiceStatus.SENT)
        assertThat(result.currency).isEqualTo(Currency.CHF)
    }

    @Test
    fun `falls back to master-data seller and buyer fields when no snapshot id is present`() {
        val row = blankRow().withOverrides(
            18 to "Live Seller GmbH",
            19 to "DE111111111",
            27 to Country.FR,
            38 to "Live Buyer AG",
            39 to "DE222222222",
        )

        val result = InvoiceHeaderRowMapper.fromRow(row, rawInvoiceNumber = null, invoiceNumberMalformed = false)

        assertThat(result.partySource).isEqualTo(PartySource.MASTER_DATA)
        assertThat(result.sellerName).isEqualTo("Live Seller GmbH")
        assertThat(result.sellerVatId).isEqualTo("DE111111111")
        assertThat(result.sellerVatRegistrationCountry).isEqualTo("FR")
        assertThat(result.buyerName).isEqualTo("Live Buyer AG")
        assertThat(result.buyerVatId).isEqualTo("DE222222222")
    }

    @Test
    fun `prefers the frozen snapshot over master data when a snapshot id is present`() {
        val row = blankRow().withOverrides(
            18 to "Live Seller GmbH",
            28 to 900L, // sellerSnapshot.id present
            29 to "Frozen Seller GmbH",
            38 to "Live Buyer AG",
            44 to 901L, // buyerSnapshot.id present
            45 to "Frozen Buyer AG",
        )

        val result = InvoiceHeaderRowMapper.fromRow(row, rawInvoiceNumber = null, invoiceNumberMalformed = false)

        assertThat(result.partySource).isEqualTo(PartySource.SNAPSHOT)
        assertThat(result.sellerName).isEqualTo("Frozen Seller GmbH")
        assertThat(result.buyerName).isEqualTo("Frozen Buyer AG")
    }

    @Test
    fun `tolerantly parses money and bigdecimal columns supplied as BigDecimal, Money or numeric string`() {
        val row = blankRow().withOverrides(
            63 to BigDecimal("100.00"),
            64 to Money(BigDecimal("19.00")),
            65 to "119.00",
        )

        val result = InvoiceHeaderRowMapper.fromRow(row, rawInvoiceNumber = null, invoiceNumberMalformed = false)

        assertThat(result.netAmount).isEqualTo(Money(BigDecimal("100.00")))
        assertThat(result.vatAmount).isEqualTo(Money(BigDecimal("19.00")))
        assertThat(result.grossAmount).isEqualTo(Money(BigDecimal("119.00")))
    }

    @Test
    fun `tolerantly parses a plain BigDecimal column supplied as a raw non-BigDecimal Number`() {
        val row = blankRow().withOverrides(16 to 5) // paymentTermSnapshotDiscountPercent as a raw Int

        val result = InvoiceHeaderRowMapper.fromRow(row, rawInvoiceNumber = null, invoiceNumberMalformed = false)

        assertThat(result.paymentTermSnapshotDiscountPercent).isEqualByComparingTo(BigDecimal("5"))
    }

    @Test
    fun `maps references, storno, amount and audit columns`() {
        val row = blankRow().withOverrides(
            51 to "LW-123",
            52 to "PO-1",
            53 to "SO-1",
            54 to LocalDate.of(2026, 1, 5),
            55 to "OF-1",
            56 to LocalDate.of(2026, 1, 6),
            57 to "PROJ-1",
            58 to "CC-1",
            59 to "IP-2026-01-00001",
            60 to LocalDate.of(2026, 1, 10),
            62 to true,
            66 to 3,
            67 to 2,
            68 to true,
            69 to OffsetDateTime.parse("2026-01-01T10:00:00Z"),
            70 to "erika",
            71 to OffsetDateTime.parse("2026-01-02T11:00:00Z"),
            72 to "max",
            73 to 5L,
            74 to "Bitte zeitnah zahlen",
        )

        val result = InvoiceHeaderRowMapper.fromRow(row, rawInvoiceNumber = null, invoiceNumberMalformed = false)

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
        assertThat(result.reverseCharge).isTrue()
        assertThat(result.itemsCount).isEqualTo(3)
        assertThat(result.receiptsCount).isEqualTo(2)
        assertThat(result.needsRecalculation).isTrue()
        assertThat(result.createdAt).isEqualTo(OffsetDateTime.parse("2026-01-01T10:00:00Z"))
        assertThat(result.createdBy).isEqualTo("erika")
        assertThat(result.updatedAt).isEqualTo(OffsetDateTime.parse("2026-01-02T11:00:00Z"))
        assertThat(result.updatedBy).isEqualTo("max")
        assertThat(result.version).isEqualTo(5L)
        assertThat(result.freeText).isEqualTo("Bitte zeitnah zahlen")
    }

    @Test
    fun `carries the malformed marker flag through unchanged`() {
        val result = InvoiceHeaderRowMapper.fromRow(blankRow(), rawInvoiceNumber = "not-a-number", invoiceNumberMalformed = true)

        assertThat(result.invoiceNumber).isEqualTo("not-a-number")
        assertThat(result.invoiceNumberMalformed).isTrue()
    }
}
