package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit

/**
 * PO decision 2026-08-31 — [Invoice.ensureInvoiceDateNotBeforeServiceEnd], § 14 Abs. 4 Nr. 6 UStG:
 * a → ISSUED transition must not carry an invoice date that lies BEFORE the resolved service end.
 * Same-day is explicitly allowed (the PO's own two normative examples are reproduced verbatim
 * below). These tests call the guard directly against a fixed [Invoice.invoiceDate] rather than
 * relying on `LocalDate.now()` —
 * [de.littletrotter.application.invoice.InvoiceLifecycleService.transition] is what stamps
 * "today" onto [Invoice.invoiceDate] before calling this guard (see that method's own KDoc); this
 * test suite
 * only pins the guard's own comparison logic.
 */
class InvoiceDateSendGuardTest {

    private fun item(from: LocalDate?, to: LocalDate?, positionId: String = "1") = anInvoiceItem(
        positionId = positionId,
        description = "x",
        servicePeriodFrom = from,
        servicePeriodTo = to,
    )

    // ─── PO example 1 — single-day service via BT-72 (delivery date) ───────────────────────────

    @Test
    fun `PO example - single-day service 31_08, invoiceDate 31_08 - same day is allowed`() {
        val invoice = anInvoice(invoiceDate = LocalDate.of(2026, 8, 31), deliveryDate = LocalDate.of(2026, 8, 31))

        assertDoesNotThrow { invoice.ensureInvoiceDateNotBeforeServiceEnd() }
    }

    @Test
    fun `delivery date one day after invoiceDate - blocked citing section 14 UStG`() {
        val invoice = anInvoice(invoiceDate = LocalDate.of(2026, 8, 31), deliveryDate = LocalDate.of(2026, 9, 1))

        val ex = assertThrows(InvoiceDateBeforeServiceEndException::class.java) {
            invoice.ensureInvoiceDateNotBeforeServiceEnd()
        }
        assertTrue(ex.message!!.contains("§ 14 Abs. 4 Nr. 6 UStG"), "message must cite the legal basis: ${ex.message}")
        assertTrue(ex.message!!.contains("2026-08-31"))
        assertTrue(ex.message!!.contains("2026-09-01"))
    }

    // ─── PO example 2 — header service period (BT-73/BT-74) ────────────────────────────────────

    @Test
    fun `PO example - period 20_08 to 02_09, invoiceDate 02_09 - sendable from the period end`() {
        val invoice = anInvoice(
            invoiceDate = LocalDate.of(2026, 9, 2),
            servicePeriodFrom = LocalDate.of(2026, 8, 20),
            servicePeriodTo = LocalDate.of(2026, 9, 2),
        )

        assertDoesNotThrow { invoice.ensureInvoiceDateNotBeforeServiceEnd() }
    }

    @Test
    fun `PO example - period 20_08 to 02_09, invoiceDate 01_09 - blocked, period end still in the future`() {
        val invoice = anInvoice(
            invoiceDate = LocalDate.of(2026, 9, 1),
            servicePeriodFrom = LocalDate.of(2026, 8, 20),
            servicePeriodTo = LocalDate.of(2026, 9, 2),
        )

        val ex = assertThrows(InvoiceDateBeforeServiceEndException::class.java) {
            invoice.ensureInvoiceDateNotBeforeServiceEnd()
        }
        assertTrue(ex.message!!.contains("§ 14 Abs. 4 Nr. 6 UStG"))
    }

    @Test
    fun `delivery date takes priority over header period end even when both are set`() {
        // deliveryDate (BT-72) alone already satisfies same-day; a header period end further in
        // the future must not be consulted at all once BT-72 is present.
        val invoice = anInvoice(
            invoiceDate = LocalDate.of(2026, 8, 31),
            deliveryDate = LocalDate.of(2026, 8, 31),
            servicePeriodFrom = LocalDate.of(2026, 8, 1),
            servicePeriodTo = LocalDate.of(2026, 12, 31),
        )

        assertDoesNotThrow { invoice.ensureInvoiceDateNotBeforeServiceEnd() }
    }

    // ─── line-period-only fallback (no BT-72, no header BT-74) ─────────────────────────────────

    @Test
    fun `only line periods - latest line end across all lines is used, same-day allowed`() {
        val invoice = anInvoice(
            invoiceDate = LocalDate.of(2026, 8, 31),
            items = listOf(item(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), "1"), item(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 31), "2")),
        )

        assertDoesNotThrow { invoice.ensureInvoiceDateNotBeforeServiceEnd() }
    }

    @Test
    fun `only line periods - a later line end than invoiceDate blocks`() {
        val invoice = anInvoice(
            invoiceDate = LocalDate.of(2026, 8, 30),
            items = listOf(item(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), "1"), item(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 31), "2")),
        )

        assertThrows(InvoiceDateBeforeServiceEndException::class.java) {
            invoice.ensureInvoiceDateNotBeforeServiceEnd()
        }
    }

    @Test
    fun `a line with only a single-point period contributes that point as its end`() {
        val invoice = anInvoice().edit {
            invoiceDate = LocalDate.of(2026, 8, 31)
        }.apply {
            // single-day line item: only servicePeriodFrom set, no servicePeriodTo
            addItem(item(LocalDate.of(2026, 8, 31), null, "1"))
        }

        assertDoesNotThrow { invoice.ensureInvoiceDateNotBeforeServiceEnd() }
    }

    @Test
    fun `a line with only a single-point period one day after invoiceDate blocks`() {
        val invoice = anInvoice(
            invoiceDate = LocalDate.of(2026, 8, 30),
            items = listOf(item(LocalDate.of(2026, 8, 31), null, "1")),
        )

        assertThrows(InvoiceDateBeforeServiceEndException::class.java) {
            invoice.ensureInvoiceDateNotBeforeServiceEnd()
        }
    }

    // ─── no dates at all — another rule's job, this guard is a no-op ───────────────────────────

    @Test
    fun `no service date anywhere - no-op, CORE_SERVICE_PERIOD_XOR handles the missing-date case`() {
        val invoice = anInvoice(invoiceDate = LocalDate.of(2026, 8, 31), items = listOf(item(null, null, "1")))

        assertDoesNotThrow { invoice.ensureInvoiceDateNotBeforeServiceEnd() }
    }

    // date invariants (PO 2026-09-02) — Invoice.invoiceDate is no longer nullable, so the
    // "no invoiceDate set" no-op branch this used to pin (`invoiceDate ?: return`,
    // InvoiceDateSendGuard.kt) was removed as unreachable; only the service-end side can still be
    // absent, already covered above ("no service date anywhere").

    @Test
    fun `ensureSendDateNotBeforeServiceEnd compares the given send date, not the stored invoiceDate`() {
        val invoice = anInvoice(invoiceDate = LocalDate.of(2026, 1, 1), deliveryDate = LocalDate.of(2026, 9, 30))

        assertDoesNotThrow { invoice.ensureSendDateNotBeforeServiceEnd(LocalDate.of(2026, 9, 30)) }
        assertThrows(InvoiceDateBeforeServiceEndException::class.java) {
            invoice.ensureSendDateNotBeforeServiceEnd(LocalDate.of(2026, 9, 29))
        }
    }
}
