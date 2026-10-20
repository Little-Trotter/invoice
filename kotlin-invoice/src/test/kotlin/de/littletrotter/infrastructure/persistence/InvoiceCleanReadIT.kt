package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * L2 — reading an invoice must not write it.
 *
 * The reported symptom was that every print job ended in the DLQ with a
 * `StaleObjectStateException` on `update invoice … where id=? and version=?`. Measured on a live
 * stack: invoice 649 went from `version` 0 to 5 while merely being read through the publish path,
 * and `invoice_audit_journal` recorded those writes with `detail = {}` — the trigger saw no
 * changed column, Hibernate had written the row anyway. Once a row's version moves under a reader
 * that still holds it, any second writer in the same flush is stale by construction.
 *
 * This IT pins the invariant behind that, at the only layer where it is decidable: a transaction
 * that loads an [Invoice] and changes nothing must leave `version` alone. It uses a real Postgres
 * (Testcontainers) because the whole question is what Hibernate's dirty check emits at flush time
 * — a mock persistence layer cannot answer it.
 *
 * The row is created through native SQL rather than the domain aggregate on purpose: every column
 * this test cares about (all eight `references` columns) has to be genuinely NULL, which is
 * exactly the shape a plain draft has and exactly the shape that used to trip the dirty check.
 * Building the entity graph instead would fill fields the reproduction depends on being empty.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceCleanReadIT {

    @Inject
    lateinit var em: EntityManager

    private fun insertInvoice(withReferences: Boolean): Long = QuarkusTransaction.requiringNew().call {
        val sql = if (withReferences) {
            "INSERT INTO invoice (version, invoice_date, buyer_reference, cost_center) VALUES (0, '2026-06-15', 'BR-1', 'CC-1') RETURNING id"
        } else {
            "INSERT INTO invoice (version, invoice_date) VALUES (0, '2026-06-15') RETURNING id"
        }
        (em.createNativeQuery(sql).singleResult as Number).toLong()
    }

    private fun versionOf(id: Long): Long = QuarkusTransaction.requiringNew().call {
        val value = em.createNativeQuery("SELECT version FROM invoice WHERE id = :id")
            .setParameter("id", id)
            .singleResult
        (value as Number).toLong()
    }

    private fun auditEntriesFor(id: Long): Long = QuarkusTransaction.requiringNew().call {
        val value = em.createNativeQuery(
            "SELECT COUNT(*) FROM invoice_audit_journal WHERE invoice_id = :id AND event = 1",
        ).setParameter("id", id).singleResult
        (value as Number).toLong()
    }

    /** Loads the row and touches nothing — the flush at commit must have nothing to write. */
    private fun readOnly(id: Long) = QuarkusTransaction.requiringNew().run {
        assertNotNull(em.find(Invoice::class.java, id), "fixture row must be loadable")
    }

    /** What the real readers do — the detail DTO mapper, the PDF preview and the reload after a
     *  conflict all read a reference field. On a plain draft that read used to materialise the
     *  empty composite and assign it, which the dirty check then wrote as an UPDATE. */
    private fun readReference(id: Long) = QuarkusTransaction.requiringNew().run {
        val invoice = checkNotNull(em.find(Invoice::class.java, id)) { "fixture row must be loadable" }
        assertNull(invoice.references.buyerReference, "the fixture draft carries no buyer reference")
    }

    private fun buyerReferenceOf(id: Long): String? = QuarkusTransaction.requiringNew().call {
        em.createNativeQuery("SELECT buyer_reference FROM invoice WHERE id = :id")
            .setParameter("id", id)
            .singleResult as String?
    }

    @Test
    fun `reading a reference field on a draft leaves its version alone`() {
        val id = insertInvoice(withReferences = false)
        val before = versionOf(id)

        repeat(3) { readReference(id) }

        assertEquals(
            before,
            versionOf(id),
            "reading a reference field bumped @Version — the UI then fails every edit with a stale " +
                "expectedVersion, because each page load, preview tick and reload moves the row",
        )
        assertEquals(0L, auditEntriesFor(id), "a reference read must not produce an UPDATE entry in invoice_audit_journal")
    }

    /** The write path must still reach the columns now that reads no longer materialise. */
    @Test
    fun `assigning a buyer reference on a draft still reaches the column`() {
        val id = insertInvoice(withReferences = false)

        QuarkusTransaction.requiringNew().run {
            checkNotNull(em.find(Invoice::class.java, id)).references.assignBuyerReference("BR-9")
        }

        assertEquals("BR-9", buyerReferenceOf(id))
        assertEquals(1L, auditEntriesFor(id), "exactly one UPDATE for the one real write")
    }

    @Test
    fun `loading a draft invoice without touching it leaves its version alone`() {
        val id = insertInvoice(withReferences = false)
        val before = versionOf(id)

        readOnly(id)

        assertEquals(
            before,
            versionOf(id),
            "a transaction that only READ the invoice bumped its @Version — every reader then " +
                "invalidates every other holder of that row, which is what put the print jobs in the DLQ",
        )
        assertEquals(
            0L,
            auditEntriesFor(id),
            "a pure read must not produce an UPDATE entry in invoice_audit_journal either",
        )
    }

    /**
     * The control case. If the version only moves for a row whose `references` columns are all
     * NULL, the cause is the null-composite repair, not something generic about reading invoices —
     * this is what separates the two explanations instead of leaving it to argument.
     */
    @Test
    fun `the same holds for an invoice that carries reference values`() {
        val id = insertInvoice(withReferences = true)
        val before = versionOf(id)

        readOnly(id)

        assertEquals(before, versionOf(id), "reading an invoice WITH references must not write it either")
    }

    /** Reading twice must not accumulate either — the first read is not allowed to "fix up" the
     *  row into a state that later reads then leave alone. */
    @Test
    fun `repeated reads never accumulate versions`() {
        val id = insertInvoice(withReferences = false)
        val before = versionOf(id)

        repeat(3) { readOnly(id) }

        assertEquals(before, versionOf(id), "three reads must be as harmless as one")
    }
}
