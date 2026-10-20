package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class InvoiceDetailHeaderFieldMapperTest {

    private fun tuple(overrides: Map<String, Any?> = emptyMap()): MapTuple {
        val base = mapOf(
            "id" to 1L,
            "invoiceNumber" to "RE-1",
            "status" to 0,
            "invoiceDate" to LocalDate.of(2026, 1, 1),
            "version" to 7L,
            "dueDate" to LocalDate.of(2026, 1, 15),
            "servicePeriodFrom" to null,
            "servicePeriodTo" to null,
            "validationStatus" to 0,
            "invoiceFormat" to 0,
            "typeCode" to 0,
            "documentLanguage" to "de",
            "mailSent" to null,
            "mailSentAt" to null,
            "createdAt" to OffsetDateTime.parse("2026-09-01T08:00:00Z"),
        )
        return MapTuple(base + overrides)
    }

    @Test
    fun `maps every header column field by field`() {
        val result = InvoiceDetailHeaderFieldMapper.map(tuple())

        assertEquals(1L, result.id)
        assertEquals("RE-1", result.invoiceNumber)
        assertEquals(0, result.status)
        assertEquals(LocalDate.of(2026, 1, 1), result.invoiceDate)
        assertEquals(7L, result.version)
        assertEquals(LocalDate.of(2026, 1, 15), result.dueDate)
        assertEquals(0, result.validationStatus)
        assertEquals(0, result.invoiceFormat)
        assertEquals(0, result.typeCode)
        assertEquals("de", result.documentLanguage)
        assertEquals(OffsetDateTime.parse("2026-09-01T08:00:00Z"), result.createdAt)
    }

    @Test
    fun `a null version, mailSent and mailSentAt decode to null`() {
        val result = InvoiceDetailHeaderFieldMapper.map(tuple(mapOf("version" to null)))

        assertNull(result.version)
        assertNull(result.mailSent)
        assertNull(result.mailSentAt)
    }

    @Test
    fun `a null status, currency-adjacent ordinal columns decode to their tolerant defaults`() {
        val result = InvoiceDetailHeaderFieldMapper.map(
            tuple(mapOf("status" to null, "validationStatus" to null, "invoiceFormat" to null, "typeCode" to null)),
        )

        assertEquals(InvoiceStatus.DRAFT.ordinal, result.status)
        assertNull(result.validationStatus)
        assertNull(result.invoiceFormat)
        assertNull(result.typeCode)
    }

    @Test
    fun `a non-null mailSent and mailSentAt survive the projection`() {
        val sentAt = OffsetDateTime.parse("2026-09-07T10:15:30Z")
        val result = InvoiceDetailHeaderFieldMapper.map(tuple(mapOf("mailSent" to true, "mailSentAt" to sentAt)))

        assertEquals(true, result.mailSent)
        assertEquals(sentAt, result.mailSentAt)
    }
}
