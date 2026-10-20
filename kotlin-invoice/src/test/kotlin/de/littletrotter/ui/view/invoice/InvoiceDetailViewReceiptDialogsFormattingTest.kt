package de.littletrotter.ui.view.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * R4 — [formatReceiptUploadedAt] rendered `yyyy-MM-dd HH:mm` (ISO order) while every sibling
 * timestamp in the application (e.g. [formatReceiptDocumentDate], `HotelRatesTab`'s
 * `validFrom`/`validTo` columns) renders `dd.MM.yyyy`. German is the display language, so an ISO
 * timestamp reads as day-then-month to a German user (a "2026-09-04" upload date could be misread
 * as day 20, month 26 — which doesn't even exist — showing how out of place it looks).
 */
class InvoiceDetailViewReceiptDialogsFormattingTest {

    @Test
    fun `formatReceiptUploadedAt renders the German day-month-year order, not ISO`() {
        // +2 is already Berlin's own CEST offset on this date, so the wall time is unchanged —
        // this test is purely about the day-month-year order, not the zone conversion (see the
        // next test for that).
        val uploadedAt = OffsetDateTime.of(2026, 9, 4, 14, 30, 0, 0, ZoneOffset.ofHours(2))

        val text = formatReceiptUploadedAt(uploadedAt)

        assertEquals("04.09.2026 14:30", text)
    }

    @Test
    fun `formatReceiptUploadedAt converts to German wall time regardless of the offset carried`() {
        // Stored/received in UTC (e.g. a container without a TZ override) — must still show
        // Europe/Berlin wall time, same rule GermanTime documents.
        val uploadedAt = OffsetDateTime.of(2026, 1, 15, 9, 30, 0, 0, ZoneOffset.UTC)

        val text = formatReceiptUploadedAt(uploadedAt)

        assertEquals("15.01.2026 10:30", text)
    }

    @Test
    fun `formatReceiptUploadedAt renders the dash placeholder for a null timestamp`() {
        assertEquals("-", formatReceiptUploadedAt(null))
    }

    @Test
    fun `formatReceiptDocumentDate renders the German day-month-year order`() {
        assertEquals("05.01.2026", formatReceiptDocumentDate(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `formatReceiptDocumentDate renders the dash placeholder for a null date`() {
        assertEquals("-", formatReceiptDocumentDate(null))
    }
}
