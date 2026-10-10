package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceOptimisticLockTestHelper
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration tests for [Invoice] and [InvoiceItem] entity CRUD operations using the exact same
 * JPA entities, `EntityManager` calls, and Flyway-managed schema that production uses.
 *
 * Each test method exercises one phase of the entity lifecycle (INSERT → UPDATE → DELETE) so
 * that the full round-trip through Hibernate and PostgreSQL is covered.  The concurrent-update
 * test at the end reproduces the ARJUNA016053 bug that was observed in production.
 *
 * ## Why @QuarkusTest with PostgresTestResource
 * Using the real Quarkus context (Hibernate ORM, JTA/Narayana, Flyway) means:
 * - Tables are created by the exact same Flyway migrations as production (V001 … V90x).
 * - Entity mappings, converters, and constraints are exercised end-to-end.
 * - The `@Version` interceptor in Hibernate runs on every `em.merge()`, producing the same
 *   version-WHERE UPDATE that triggered ARJUNA016053 in production.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceVersionConflictIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var helper: InvoiceOptimisticLockTestHelper

    // ── INSERT ────────────────────────────────────────────────────────────────

    /**
     * Persisting a new [Invoice] with one [InvoiceItem] must:
     * - assign database IDs to both entities
     * - initialise `version = 0` on both rows (production schema default)
     *
     * A `version` of 0 is the starting point for all optimistic-lock checks; if the column were
     * missing or defaulted to NULL, Hibernate would skip the version WHERE clause and the
     * ARJUNA016053 protection would be silently disabled.
     */
    @Test
    @Transactional
    fun `INSERT - persist Invoice with Item initialises version to 0 on both rows`() {
        val invoice = Invoice().apply {
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().apply {
                positionId = "1"
                description = "Sprint 15 Development"
                quantity = BigDecimal("8.0")
                unitPriceNet = Money(BigDecimal("120.00"))
            })
        }

        em.persist(invoice)
        em.flush()

        assertNotNull(invoice.id, "Invoice must receive a DB-assigned id")
        assertEquals(1, invoice.itemList.size)
        assertNotNull(invoice.itemList[0].id, "InvoiceItem must receive a DB-assigned id")

        assertEquals(0L, invoice.version, "Newly persisted Invoice must have version = 0")
        assertEquals(0L, invoice.itemList[0].version, "Newly persisted InvoiceItem must have version = 0")
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    /**
     * Merging a managed [Invoice] after a status transition must increment `version` by exactly 1.
     *
     * Hibernate translates `em.merge()` into:
     * ```sql
     * UPDATE invoice SET status = ?, ..., version = <v+1> WHERE id = ? AND version = <v>
     * ```
     * A successful update returns `rowCount = 1`; Hibernate increments the in-memory version
     * field accordingly.  This is the "happy path" that precedes ARJUNA016053: the first writer
     * to commit always succeeds here.
     */
    @Test
    @Transactional
    fun `UPDATE - em merge after status transition increments version by 1`() {
        val invoice = Invoice().apply {
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().apply {
                positionId = "1"
                description = "Beratung"
                quantity = BigDecimal.ONE
                unitPriceNet = Money(BigDecimal("150.00"))
            })
        }
        em.persist(invoice)
        em.flush()
        val versionAfterInsert = invoice.version ?: 0L

        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        em.merge(invoice)
        em.flush()
        em.clear() // evict from first-level cache to force a real DB read

        val reloaded = em.find(Invoice::class.java, invoice.id)
        assertEquals(
            versionAfterInsert + 1,
            reloaded.version,
            "Version must increment by 1 on every successful merge",
        )
        assertEquals(InvoiceStatus.IN_REVIEW, reloaded.status)
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    /**
     * Removing an [Invoice] must cascade to all its [InvoiceItem] children.
     *
     * In production, cascade-delete is critical to avoid orphaned `invoice_item` rows (and the
     * associated `ON DELETE CASCADE` on `invoice_item.invoice_id` enforces this at DB level too).
     * The test verifies both layers: the entity is gone from the JPA context AND the item row is
     * gone from the table.
     */
    @Test
    @Transactional
    fun `DELETE - em remove cascades to InvoiceItems`() {
        val invoice = Invoice().apply {
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().apply {
                positionId = "1"
                description = "Reiskosten"
                quantity = BigDecimal.ONE
                unitPriceNet = Money(BigDecimal("50.00"))
            })
        }
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        val itemId = invoice.itemList[0].id!!

        em.remove(invoice)
        em.flush()
        em.clear()

        assertNull(em.find(Invoice::class.java, invoiceId), "Invoice must be gone after em.remove()")
        assertNull(em.find(InvoiceItem::class.java, itemId), "InvoiceItem must be cascade-deleted")
    }

    // ── CONCURRENT UPDATE → ARJUNA016053 ─────────────────────────────────────

    /**
     * Reproduces the production ARJUNA016053 scenario using real JPA entities:
     *
     * Two concurrent threads both call `em.find()` on the same [Invoice] inside separate JTA
     * transactions → each captures `version = 0`.  Both then call `em.merge()` to transition the
     * status.  Whichever thread commits first succeeds and bumps `version` to 1.  The second
     * thread's `em.merge()` issues `UPDATE … WHERE version = 0` and finds 0 rows → Hibernate
     * throws [org.hibernate.StaleObjectStateException] → Narayana rolls back the transaction and
     * logs **ARJUNA016053: Could not commit transaction**.
     *
     * The test asserts that exactly one transaction fails with a stale-version exception anywhere
     * in the cause chain, which is the full-stack equivalent of the ARJUNA016053 log entry.
     */
    @Test
    fun `UPDATE concurrent - stale em merge triggers StaleObjectStateException (ARJUNA016053)`() {
        val invoiceId = helper.createDraftInvoice()

        val loadedLatch = CountDownLatch(2)
        val startSignal = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // Both threads attempt the same valid domain transition so the domain guard does not
        // reject either call; the loser is determined solely by the DB-level version check.
        val f1 = executor.submit(Callable {
            runCatching {
                helper.loadAndTransition(invoiceId, InvoiceStatus.IN_REVIEW, loadedLatch, startSignal)
            }.exceptionOrNull()
        })
        val f2 = executor.submit(Callable {
            runCatching {
                helper.loadAndTransition(invoiceId, InvoiceStatus.IN_REVIEW, loadedLatch, startSignal)
            }.exceptionOrNull()
        })

        assertTrue(loadedLatch.await(10, TimeUnit.SECONDS), "Both threads must load the entity within timeout")
        startSignal.countDown()

        val failures = listOf(
            f1.get(15, TimeUnit.SECONDS),
            f2.get(15, TimeUnit.SECONDS),
        ).filterNotNull()
        executor.shutdown()

        assertEquals(1, failures.size, "Exactly one transaction must fail; the other must succeed")
        assertTrue(
            hasOptimisticLockInChain(failures.single()),
            "Expected StaleObjectStateException or OptimisticLockException in cause chain",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun hasOptimisticLockInChain(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is jakarta.persistence.OptimisticLockException ||
                cause is org.hibernate.StaleObjectStateException
            ) return true
            cause = cause.cause
        }
        return false
    }
}
