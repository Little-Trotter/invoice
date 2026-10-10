package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * PO decision 2026-09-01 () — pins every row of that spec's
 * case table against the pure functions in `InvoiceDateRules.kt`. Section headers below match the
 * spec's own table headers ("Rule 1/2", "Service-end resolution", "Rule 3", "Rule 4") and each
 * test names the case number it covers so a failing test maps back to the spec directly.
 */
class InvoiceDateRulesTest {

    private fun item(from: LocalDate?, to: LocalDate?, positionId: String = "1") = InvoiceItem().apply {
        this.positionId = positionId
        description = "x"
        servicePeriodFrom = from
        servicePeriodTo = to
    }

    // ─── Rule 1/2 — invoice date against the service end (case table rows 1-7) ─────────────────

    @Nested
    inner class Rule1And2 {

        @Test
        fun `case 1 - no service end, any invoice date - no error, unchanged`() {
            val invoiceDate = LocalDate.of(2026, 8, 31)

            assertFalse(invoiceDateIsBeforeServiceEnd(invoiceDate, null))
            assertEquals(invoiceDate, correctedInvoiceDate(invoiceDate, null))
        }

        @Test
        fun `case 2 - service end set, invoice date null - no error, stays null`() {
            val serviceEnd = LocalDate.of(2026, 8, 31)

            assertFalse(invoiceDateIsBeforeServiceEnd(null, serviceEnd))
            assertNull(correctedInvoiceDate(null, serviceEnd))
        }

        @Test
        fun `case 3 - invoice date one day before service end - error, corrected forward`() {
            val serviceEnd = LocalDate.of(2026, 8, 31)
            val invoiceDate = LocalDate.of(2026, 8, 30)

            assertTrue(invoiceDateIsBeforeServiceEnd(invoiceDate, serviceEnd))
            assertEquals(serviceEnd, correctedInvoiceDate(invoiceDate, serviceEnd))
        }

        @Test
        fun `case 4 - invoice date equals service end - no error, unchanged (same day allowed)`() {
            val serviceEnd = LocalDate.of(2026, 8, 31)
            val invoiceDate = LocalDate.of(2026, 8, 31)

            assertFalse(invoiceDateIsBeforeServiceEnd(invoiceDate, serviceEnd))
            assertEquals(invoiceDate, correctedInvoiceDate(invoiceDate, serviceEnd))
        }

        @Test
        fun `case 5 - invoice date after service end - no error, unchanged`() {
            val serviceEnd = LocalDate.of(2026, 8, 31)
            val invoiceDate = LocalDate.of(2026, 9, 1)

            assertFalse(invoiceDateIsBeforeServiceEnd(invoiceDate, serviceEnd))
            assertEquals(invoiceDate, correctedInvoiceDate(invoiceDate, serviceEnd))
        }

        @Test
        fun `case 6 - period 20_08 to 02_09, invoice date 01_09 - error, corrected to period end`() {
            val serviceEnd = LocalDate.of(2026, 9, 2) // resolved end of a 20.08.-02.09. period
            val invoiceDate = LocalDate.of(2026, 9, 1)

            assertTrue(invoiceDateIsBeforeServiceEnd(invoiceDate, serviceEnd))
            assertEquals(serviceEnd, correctedInvoiceDate(invoiceDate, serviceEnd))
        }

        @Test
        fun `case 7 - period 20_08 to 02_09, invoice date 02_09 - no error, unchanged`() {
            val serviceEnd = LocalDate.of(2026, 9, 2)
            val invoiceDate = LocalDate.of(2026, 9, 2)

            assertFalse(invoiceDateIsBeforeServiceEnd(invoiceDate, serviceEnd))
            assertEquals(invoiceDate, correctedInvoiceDate(invoiceDate, serviceEnd))
        }

        @Test
        fun `correction never moves a date backwards even if called again on an already-corrected date`() {
            val serviceEnd = LocalDate.of(2026, 8, 31)
            val corrected = correctedInvoiceDate(LocalDate.of(2026, 8, 20), serviceEnd)
            assertEquals(serviceEnd, corrected)

            // Applying the correction a second time (idempotency) must not move it any further.
            assertEquals(serviceEnd, correctedInvoiceDate(corrected, serviceEnd))
        }
    }

    // ─── Service-end resolution (case table rows 8-12) ─────────────────────────────────────────

    @Nested
    inner class ServiceEndResolution {

        @Test
        fun `case 8 - delivery date wins over header period end and line ends`() {
            val invoice = Invoice().apply {
                deliveryDate = LocalDate.of(2026, 9, 5)
                servicePeriodTo = LocalDate.of(2026, 9, 2)
                addItem(item(null, LocalDate.of(2026, 9, 9)))
            }

            assertEquals(LocalDate.of(2026, 9, 5), invoice.resolveServiceEnd())
        }

        @Test
        fun `case 9 - no delivery date - header period end wins over line ends`() {
            val invoice = Invoice().apply {
                servicePeriodTo = LocalDate.of(2026, 9, 2)
                addItem(item(null, LocalDate.of(2026, 9, 9)))
            }

            assertEquals(LocalDate.of(2026, 9, 2), invoice.resolveServiceEnd())
        }

        @Test
        fun `case 10 - no delivery date, no header period end - latest line end wins`() {
            val invoice = Invoice().apply {
                addItem(item(null, LocalDate.of(2026, 9, 3), "1"))
                addItem(item(null, LocalDate.of(2026, 9, 9), "2"))
            }

            assertEquals(LocalDate.of(2026, 9, 9), invoice.resolveServiceEnd())
        }

        @Test
        fun `case 11 - a line with only a start date contributes that point as its end`() {
            val invoice = Invoice().apply {
                addItem(item(LocalDate.of(2026, 9, 7), null))
            }

            assertEquals(LocalDate.of(2026, 9, 7), invoice.resolveServiceEnd())
        }

        @Test
        fun `case 12 - none of the three sources present - null`() {
            val invoice = Invoice()

            assertNull(invoice.resolveServiceEnd())
        }
    }

    // ─── Rule 3 — due date warnings (case table rows 13-19) ────────────────────────────────────

    @Nested
    inner class Rule3DueDateWarning {

        @Test
        fun `case 13 - due date well after both invoice date and service end - no warning`() {
            val result = dueDateWarning(
                invoiceDate = LocalDate.of(2026, 9, 1),
                serviceEnd = LocalDate.of(2026, 8, 31),
                dueDate = LocalDate.of(2026, 9, 15),
            )
            assertEquals(DueDateWarning.NONE, result)
        }

        @Test
        fun `case 14 - due date equals service end but is before the invoice date - warning`() {
            val result = dueDateWarning(
                invoiceDate = LocalDate.of(2026, 9, 1),
                serviceEnd = LocalDate.of(2026, 8, 31),
                dueDate = LocalDate.of(2026, 8, 31),
            )
            assertEquals(DueDateWarning.BEFORE_INVOICE_DATE, result)
        }

        @Test
        fun `case 15 - due date equals invoice date - no warning, same day is fine`() {
            val result = dueDateWarning(
                invoiceDate = LocalDate.of(2026, 9, 1),
                serviceEnd = LocalDate.of(2026, 8, 31),
                dueDate = LocalDate.of(2026, 9, 1),
            )
            assertEquals(DueDateWarning.NONE, result)
        }

        @Test
        fun `case 16 - due date after invoice date but before the service end - warning`() {
            val result = dueDateWarning(
                invoiceDate = LocalDate.of(2026, 9, 1),
                serviceEnd = LocalDate.of(2026, 9, 5),
                dueDate = LocalDate.of(2026, 9, 3),
            )
            assertEquals(DueDateWarning.BEFORE_SERVICE_END, result)
        }

        @Test
        fun `case 17 - no service end, due date before invoice date - warning`() {
            val result = dueDateWarning(
                invoiceDate = LocalDate.of(2026, 9, 1),
                serviceEnd = null,
                dueDate = LocalDate.of(2026, 8, 31),
            )
            assertEquals(DueDateWarning.BEFORE_INVOICE_DATE, result)
        }

        @Test
        fun `case 18 - no due date set - no warning`() {
            val result = dueDateWarning(
                invoiceDate = LocalDate.of(2026, 9, 1),
                serviceEnd = LocalDate.of(2026, 8, 31),
                dueDate = null,
            )
            assertEquals(DueDateWarning.NONE, result)
        }

        @Test
        fun `case 19 - no invoice date, due date after service end - no warning`() {
            val result = dueDateWarning(
                invoiceDate = null,
                serviceEnd = LocalDate.of(2026, 8, 31),
                dueDate = LocalDate.of(2026, 9, 15),
            )
            assertEquals(DueDateWarning.NONE, result)
        }

        @Test
        fun `no invoice date and due date before service end - warning fires on service end alone`() {
            // Not a row of the spec's own table, but required for branch coverage of the
            // (serviceEnd != null && dueDate.isBefore(serviceEnd)) branch when invoiceDate is
            // null (so the BEFORE_INVOICE_DATE branch above it cannot fire at all).
            val result = dueDateWarning(
                invoiceDate = null,
                serviceEnd = LocalDate.of(2026, 9, 5),
                dueDate = LocalDate.of(2026, 9, 1),
            )
            assertEquals(DueDateWarning.BEFORE_SERVICE_END, result)
        }

        @Test
        fun `due date on or after invoice date and on or after service end - no warning (else branch)`() {
            // Exercises the final `else -> NONE` branch explicitly: both comparisons evaluated
            // and neither fires.
            val result = dueDateWarning(
                invoiceDate = LocalDate.of(2026, 9, 1),
                serviceEnd = LocalDate.of(2026, 9, 1),
                dueDate = LocalDate.of(2026, 9, 1),
            )
            assertEquals(DueDateWarning.NONE, result)
        }

        @Test
        fun `no invoice date and no service end, due date set - no warning, nothing to compare against`() {
            // Branch coverage: reaches the third `when` branch (invoiceDate is null, so the
            // first comparison short-circuits false) with serviceEnd ALSO null, so the
            // `serviceEnd != null` half of that branch's own short-circuit must evaluate false
            // too - the one combination none of the case-table rows above exercise on its own.
            val result = dueDateWarning(
                invoiceDate = null,
                serviceEnd = null,
                dueDate = LocalDate.of(2026, 9, 1),
            )
            assertEquals(DueDateWarning.NONE, result)
        }
    }

    // ─── Rule 4 — due date derivation (case table rows 20-23) ──────────────────────────────────

    @Nested
    inner class Rule4DerivedDueDate {

        @Test
        fun `case 20 - invoice date plus 14 days - recomputed`() {
            val result = derivedDueDate(LocalDate.of(2026, 9, 1), 14)
            assertEquals(LocalDate.of(2026, 9, 15), result)
        }

        @Test
        fun `case 21 - payment term of 0 days - due immediately, same day as invoice date`() {
            val result = derivedDueDate(LocalDate.of(2026, 9, 1), 0)
            assertEquals(LocalDate.of(2026, 9, 1), result)
        }

        @Test
        fun `case 22 - no invoice date - null result so the caller leaves the due date unchanged`() {
            val result = derivedDueDate(null, 14)
            assertNull(result)
        }

        @Test
        fun `case 23 - invoice date plus 14 days regardless of whether a previous due date existed`() {
            // derivedDueDate itself never sees "the previous due date" - that is the caller's
            // concern (only overwrite when non-null, see InvoiceLifecycleService.applyInvoiceDate
            // AutoCorrection); the pure function's result for a null-previous-due-date caller is
            // identical to case 20's.
            val result = derivedDueDate(LocalDate.of(2026, 9, 1), 14)
            assertEquals(LocalDate.of(2026, 9, 15), result)
        }
    }
}
