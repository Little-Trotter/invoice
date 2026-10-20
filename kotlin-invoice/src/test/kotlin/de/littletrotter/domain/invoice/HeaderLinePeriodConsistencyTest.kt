package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem

/**
 * [Invoice.validateHeaderLinePeriodConsistency] only fires when both the header
 * period (BT-73/74) and at least one line period (BT-134/135) are present; either level alone is
 * legitimate on its own (EN 16931 and Nekar agree there), so those cases must pass silently.
 */
class HeaderLinePeriodConsistencyTest {

    private fun item(from: LocalDate?, to: LocalDate?, positionId: String = "1") = anInvoiceItem(
        positionId = positionId,
        description = "x",
        servicePeriodFrom = from,
        servicePeriodTo = to,
    )

    @Test
    fun `both levels present and consistent - no exception`() {
        val invoice = anInvoice(
            servicePeriodFrom = LocalDate.of(2026, 1, 1),
            servicePeriodTo = LocalDate.of(2026, 1, 31),
            items = listOf(item(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15)), item(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 31))),
        )

        assertDoesNotThrow { invoice.validateHeaderLinePeriodConsistency() }
    }

    @Test
    fun `both levels present, header start contradicts earliest line start - throws naming the dates`() {
        val invoice = anInvoice(
            servicePeriodFrom = LocalDate.of(2026, 2, 1),
            servicePeriodTo = LocalDate.of(2026, 2, 28),
            items = listOf(item(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 28))),
        )

        val ex = assertThrows(InvoiceValidationException::class.java) {
            invoice.validateHeaderLinePeriodConsistency()
        }
        assertEquals(InvoiceValidationError.HEADER_LINE_PERIOD_MISMATCH, ex.error)
        // names both the header dates and the computed line-derived dates
        assertEquals(listOf("2026-02-01", "2026-02-28", "2026-01-15", "2026-02-28"), ex.args)
    }

    @Test
    fun `both levels present, header end contradicts latest line end - throws naming the dates`() {
        val invoice = anInvoice(
            servicePeriodFrom = LocalDate.of(2026, 3, 1),
            servicePeriodTo = LocalDate.of(2026, 3, 20),
            items = listOf(item(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))),
        )

        val ex = assertThrows(InvoiceValidationException::class.java) {
            invoice.validateHeaderLinePeriodConsistency()
        }
        assertEquals(InvoiceValidationError.HEADER_LINE_PERIOD_MISMATCH, ex.error)
        assertEquals(listOf("2026-03-01", "2026-03-20", "2026-03-01", "2026-03-31"), ex.args)
    }

    @Test
    fun `header period only, no line period at all - single level, no check`() {
        val invoice = anInvoice(
            servicePeriodFrom = LocalDate.of(2026, 1, 1),
            servicePeriodTo = LocalDate.of(2026, 1, 31),
            items = listOf(item(null, null)),
        )

        assertDoesNotThrow { invoice.validateHeaderLinePeriodConsistency() }
    }

    @Test
    fun `line period only, no header period at all - single level, no check`() {
        val invoice = anInvoice(
            servicePeriodFrom = null,
            servicePeriodTo = null,
            items = listOf(item(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))),
        )

        assertDoesNotThrow { invoice.validateHeaderLinePeriodConsistency() }
    }

    @Test
    fun `no periods anywhere - no check`() {
        val invoice = anInvoice(
            servicePeriodFrom = null,
            servicePeriodTo = null,
            items = listOf(item(null, null)),
        )

        assertDoesNotThrow { invoice.validateHeaderLinePeriodConsistency() }
    }

    @Test
    fun `partial periods on opposite ends - no comparable pair, no exception`() {
        // header carries only a start (BT-73), lines carry only ends (BT-135): neither the
        // start pair nor the end pair is complete, so no contradiction can be established
        val invoice = anInvoice(
            servicePeriodFrom = LocalDate.of(2026, 4, 1),
            servicePeriodTo = null,
            items = listOf(item(null, LocalDate.of(2026, 4, 30))),
        )

        assertDoesNotThrow { invoice.validateHeaderLinePeriodConsistency() }
    }

    @Test
    fun `partial header, start-only mismatch - absent dates rendered as dash placeholders`() {
        val invoice = anInvoice(
            servicePeriodFrom = LocalDate.of(2026, 5, 2),
            servicePeriodTo = null,
            items = listOf(item(LocalDate.of(2026, 5, 1), null)),
        )

        val ex = assertThrows(InvoiceValidationException::class.java) {
            invoice.validateHeaderLinePeriodConsistency()
        }
        assertEquals(InvoiceValidationError.HEADER_LINE_PERIOD_MISMATCH, ex.error)
        assertEquals(listOf("2026-05-02", "–", "2026-05-01", "–"), ex.args)
    }

    @Test
    fun `partial header, end-only mismatch - absent dates rendered as dash placeholders`() {
        val invoice = anInvoice(
            servicePeriodFrom = null,
            servicePeriodTo = LocalDate.of(2026, 6, 15),
            items = listOf(item(null, LocalDate.of(2026, 6, 30))),
        )

        val ex = assertThrows(InvoiceValidationException::class.java) {
            invoice.validateHeaderLinePeriodConsistency()
        }
        assertEquals(InvoiceValidationError.HEADER_LINE_PERIOD_MISMATCH, ex.error)
        assertEquals(listOf("–", "2026-06-15", "–", "2026-06-30"), ex.args)
    }

    @Test
    fun `multiple lines - the earliest start and latest end across all lines are used`() {
        val invoice = anInvoice(
            servicePeriodFrom = LocalDate.of(2026, 1, 1),
            servicePeriodTo = LocalDate.of(2026, 3, 31),
            items = listOf(item(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "1"), item(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "2"), item(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "3")),
        )

        assertDoesNotThrow { invoice.validateHeaderLinePeriodConsistency() }
    }
}
