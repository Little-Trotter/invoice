package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [TripExpense] persistence against real Postgres (`PostgresTestResource`'s Testcontainers
 * instance, the full Flyway-migrated `V1__baseline_schema.sql`, not a hand-mirrored schema):
 * cascade delete / orphan removal (DDD — trip_expense has no repository of its own, only ever
 * reached through the owning [InvoiceItem]), the trip_expense freeze guard
 * (`trg_trip_expense_before_write`) and its journal coverage
 * (`capture_invoice_audit_event()`'s trip_expense branch). PO's live-DB-verification rule: a DB
 * trigger/journal change is never "done" on a static SQL read alone.
 *
 * Each test method commits (Quarkus does not roll back plain `@Transactional` in tests) — every fixture below uses a fresh [Invoice], so
 * there is nothing to clean up between methods.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class TripExpenseIT {

    @Inject
    lateinit var em: EntityManager

    // Each test method commits for real (Quarkus does not roll back plain @Transactional in
    // tests) against one shared database, and
    // tax_rate.code is UNIQUE — a per-instance counter would collide across test methods
    // (JUnit creates a fresh instance per method, so a counter always restarts at 0).
    private fun taxRate(): TaxRate = TaxRate().apply {
        code = "IT_TRIP_EXPENSE_${System.nanoTime()}"
        category = "S"
        name = "MwSt 19%"; rate = TaxRatePercent(BigDecimal("19.00")); country = de.littletrotter.shared.country.Country.DE
        validFrom = LocalDate.of(2024, 1, 1); isDefault = false; isSelectable = false
    }

    private fun tripItem(rate: TaxRate): InvoiceItem = InvoiceItem().apply {
        positionId = "1"
        category = InvoiceItemCategory.TRIP
        description = "Dienstreise Genf"
        quantity = BigDecimal.ONE; unit = InvoiceItemUnit.FLAT_RATE
        unitPriceNet = Money.ZERO
        taxRateRef = rate
    }

    private fun hotelExpense(price: String, sortOrder: Int = 0): TripExpense = TripExpense().apply {
        kind = TripExpenseKind.HOTEL
        description = "Hotel Nacht"
        travelDate = LocalDate.of(2026, 4, 5)
        quantity = BigDecimal.ONE; unit = InvoiceItemUnit.DAY
        unitPriceNet = Money.of(price)
        this.sortOrder = sortOrder
        calculateNetAmount()
    }

    private fun newInvoiceWithTripExpense(price: String = "100.00"): Pair<Invoice, InvoiceItem> {
        val rate = taxRate()
        em.persist(rate)
        val item = tripItem(rate)
        item.tripExpenses.add(hotelExpense(price).also { it.invoiceItem = item })
        item.calculateAmounts()
        val invoice = Invoice().apply { invoiceDate = LocalDate.now(); addItem(item); recalculateTotals() }
        em.persist(invoice)
        em.flush()
        return invoice to item
    }

    private fun tripExpenseCount(invoiceItemId: Long): Long =
        (em.createNativeQuery("SELECT COUNT(*) FROM trip_expense WHERE invoice_item_id = $invoiceItemId").singleResult as Number).toLong()

    private fun journalEventCount(invoiceId: Long, event: Int): Long =
        (
            em.createNativeQuery(
                "SELECT COUNT(*) FROM invoice_audit_journal WHERE invoice_id = $invoiceId AND event = $event",
            ).singleResult as Number
        ).toLong()

    // ── Insert / read-back / denormalized invoice_id ────────────────────────────────────────

    @Test
    @Transactional
    fun `a TRIP item's expense is persisted, read back and carries the denormalized invoice_id`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        em.clear()

        val loaded = em.find(Invoice::class.java, invoice.id)!!
        val loadedItem = loaded.itemList.single()
        assertEquals(InvoiceItemCategory.TRIP, loadedItem.category)
        assertEquals(1, loadedItem.tripExpenses.size)
        val expense = loadedItem.tripExpenses.single()
        assertEquals(TripExpenseKind.HOTEL, expense.kind)
        assertEquals(Money.of("100.00"), expense.netAmount)

        // trg_trip_expense_before_write populated invoice_id from invoice_item at INSERT time —
        // not part of the JPA mapping, read via a native query.
        val storedInvoiceId = (
            em.createNativeQuery("SELECT invoice_id FROM trip_expense WHERE id = ${expense.id}").singleResult as Number
            ).toLong()
        assertEquals(invoice.id, storedInvoiceId)
    }

    // ── Cascade delete / orphan removal ──────────────────────────────────────────────────────

    @Test
    @Transactional
    fun `deleting the owning invoice item cascades to its trip_expense rows`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        assertEquals(1L, tripExpenseCount(item.id!!))

        val loaded = em.find(Invoice::class.java, invoice.id)!!
        em.remove(loaded)
        em.flush()

        assertEquals(0L, tripExpenseCount(item.id!!))
        assertNull(em.find(Invoice::class.java, invoice.id))
    }

    @Test
    @Transactional
    fun `orphan removal deletes a trip_expense row dropped from the item's own collection`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        item.tripExpenses.add(hotelExpense("50.00", sortOrder = 1).also { it.invoiceItem = item })
        item.calculateAmounts()
        em.merge(invoice)
        em.flush()
        assertEquals(2L, tripExpenseCount(item.id!!))

        em.clear()
        val loadedItem = em.find(InvoiceItem::class.java, item.id)!!
        val toDrop = loadedItem.tripExpenses.first { it.sortOrder == 1 }
        loadedItem.tripExpenses.remove(toDrop)
        em.merge(loadedItem)
        em.flush()

        assertEquals(1L, tripExpenseCount(item.id!!))
    }

    // ── Journal coverage (, capture_invoice_audit_event()'s trip_expense branch) ─────────

    @Test
    @Transactional
    fun `inserting a trip_expense row writes a TRIP_EXPENSE_CREATED journal entry`() {
        val (invoice, item) = newInvoiceWithTripExpense()

        // 9 = TRIP_EXPENSE_CREATED (chk_invoice_audit_journal_event / InvoiceAuditEvent ordinal).
        assertTrue(journalEventCount(invoice.id!!, 9) >= 1L, "expected at least one TRIP_EXPENSE_CREATED journal row")
    }

    @Test
    @Transactional
    fun `updating a trip_expense row writes a TRIP_EXPENSE_UPDATED journal entry`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        val expense = item.tripExpenses.single()

        // item/expense are still managed in this same persistence context (no em.clear() call
        // happened) — a plain field mutation is enough for Hibernate's dirty checking; merge()
        // is only needed for a detached entity (see the orphan-removal test above, which does
        // clear() first).
        expense.description = "Hotel Nacht (korrigiert)"
        em.flush()

        // 10 = TRIP_EXPENSE_UPDATED.
        assertTrue(journalEventCount(invoice.id!!, 10) >= 1L, "expected at least one TRIP_EXPENSE_UPDATED journal row")
    }

    @Test
    @Transactional
    fun `deleting a trip_expense row writes a TRIP_EXPENSE_DELETED journal entry`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        val expenseId = item.tripExpenses.single().id!!

        // item is still managed here (no em.clear() call) — orphanRemoval picks up the mutated
        // collection directly, no merge() needed.
        item.tripExpenses.clear()
        em.flush()

        // 11 = TRIP_EXPENSE_DELETED.
        assertTrue(journalEventCount(invoice.id!!, 11) >= 1L, "expected at least one TRIP_EXPENSE_DELETED journal row")
        assertEquals(0L, tripExpenseCount(item.id!!))
        // the deleted row itself is gone, only the journal remembers it existed.
        assertEquals(0L, (em.createNativeQuery("SELECT COUNT(*) FROM trip_expense WHERE id = $expenseId").singleResult as Number).toLong())
    }

    // ── Freeze guard (, trg_trip_expense_before_write) ──────────────────────────────────

    private fun markIssued(invoiceId: Long) {
        // Bypasses the domain lifecycle on purpose — this test only needs OLD.status IN (2, 4)
        // to be true when the guard next fires; it does not exercise the invoice's own freeze
        // trigger (guard_invoice_freeze), which only fires on UPDATE and only cares about
        // invoice's OWN frozen columns.
        em.createNativeQuery("UPDATE invoice SET status = 2 WHERE id = $invoiceId").executeUpdate() // SENT
        em.flush()
    }

    @Test
    @Transactional
    fun `UPDATE of a trip_expense row is rejected once the owning invoice is issued`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        val expense = item.tripExpenses.single()
        markIssued(invoice.id!!)

        val ex = assertThrows(PersistenceException::class.java) {
            em.createNativeQuery("UPDATE trip_expense SET description = 'nachtraeglich geaendert' WHERE id = ${expense.id}").executeUpdate()
            em.flush()
        }
        assertTrue(ex.message?.contains("issued") == true || (ex.cause?.message?.contains("issued") == true), "expected the freeze guard's own message, got: ${ex.message}")
    }

    @Test
    fun `DELETE of a trip_expense row is rejected once the owning invoice is issued`() {
        // Setup and the doomed statement CANNOT share one @Transactional test-method transaction
        // with QuarkusTransaction.requiringNew() around only the doomed part: requiringNew()
        // suspends the (still uncommitted) outer transaction and opens a genuinely separate one
        // on another connection, which — under READ COMMITTED — cannot see the just-inserted,
        // not-yet-committed setup row at all. The DELETE then matches zero rows, the trigger
        // never fires per-row, and nothing throws (caught live against real Postgres: this test
        // failed exactly that way once Docker came back). Fix: every phase gets its own
        // requiringNew() call that commits for real when it returns — same pattern as
        // RecipientUpdateSaveIT — so each later phase sees the previous phase's committed writes.
        val (itemId, expenseId) = QuarkusTransaction.requiringNew().call<Pair<Long, Long>> {
            val (invoice, item) = newInvoiceWithTripExpense()
            markIssued(invoice.id!!)
            item.id!! to item.tripExpenses.single().id!!
        }

        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("DELETE FROM trip_expense WHERE id = $expenseId").executeUpdate()
            }
        }
        val count = QuarkusTransaction.requiringNew().call<Long> { tripExpenseCount(itemId) }
        assertEquals(1L, count, "the rejected DELETE must not have removed the row")
    }

    @Test
    fun `INSERT of a new trip_expense row succeeds even once the owning invoice is issued (RK-2 finding)`() {
        // A live-Postgres finding (InvoiceStornoServiceTripIT) — this used to assert the
        // opposite (INSERT rejected, mirroring UPDATE/DELETE). That made every storno of a trip
        // with expense lines impossible: InvoiceStornoService.createCreditNote() builds the
        // credit note already CANCELLED ("born terminal") and only then cascades its copied
        // TripExpense rows — the very first INSERT of those brand-new rows hit this guard before
        // the document had a chance to exist with any rows at all. INSERT is now always allowed
        // at the DB level, matching invoice_item's own trip-column guard
        // (guard_invoice_item_trip_freeze, `BEFORE UPDATE` only) — the application layer
        // (TripService.addTrip/updateTrip's isReadOnly() check) is the only gate against adding a
        // brand-new trip_expense row to an invoice a legitimate write path considers frozen.
        val itemId = QuarkusTransaction.requiringNew().call<Long> {
            val (invoice, item) = newInvoiceWithTripExpense()
            markIssued(invoice.id!!)
            item.id!!
        }

        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "INSERT INTO trip_expense (invoice_item_id, kind, description, quantity, unit, unit_price_net, net_amount, sort_order) " +
                    "VALUES ($itemId, 3, 'Taxi nachtraeglich', 1, 0, 20.00, 20.00, 9)",
            ).executeUpdate()
        }
        val count = QuarkusTransaction.requiringNew().call<Long> { tripExpenseCount(itemId) }
        assertEquals(2L, count, "the INSERT succeeds; the invoice_id column is still populated by the trigger")
        val (populatedInvoiceId, expectedInvoiceId) = QuarkusTransaction.requiringNew().call<Pair<Long, Long>> {
            val populated = (em.createNativeQuery("SELECT invoice_id FROM trip_expense WHERE invoice_item_id = $itemId AND sort_order = 9").singleResult as Number).toLong()
            val expected = (em.createNativeQuery("SELECT invoice_id FROM invoice_item WHERE id = $itemId").singleResult as Number).toLong()
            populated to expected
        }
        assertEquals(expectedInvoiceId, populatedInvoiceId, "NEW.invoice_id is still populated on INSERT, only the freeze check was dropped for it")
    }

    @Test
    @Transactional
    fun `UPDATE of a trip_expense row still succeeds on a DRAFT invoice`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        val expense = item.tripExpenses.single()

        em.createNativeQuery("UPDATE trip_expense SET description = 'noch im Entwurf geaendert' WHERE id = ${expense.id}").executeUpdate()
        em.flush()

        val description = (
            em.createNativeQuery("SELECT description FROM trip_expense WHERE id = ${expense.id}").singleResult as String
            )
        assertEquals("noch im Entwurf geaendert", description)
    }

    // ── Freeze guard on invoice_item's two new trip columns (trg_invoice_item_trip_freeze) ────

    @Test
    @Transactional
    fun `invoice_item's trip_fixed_net_price is rejected once the owning invoice is issued`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        markIssued(invoice.id!!)

        val ex = assertThrows(PersistenceException::class.java) {
            em.createNativeQuery("UPDATE invoice_item SET trip_fixed_net_price = 999.99 WHERE id = ${item.id}").executeUpdate()
            em.flush()
        }
        assertTrue(ex.message?.contains("trip fields") == true || (ex.cause?.message?.contains("trip fields") == true), "expected the freeze guard's own message, got: ${ex.message}")
    }

    @Test
    @Transactional
    fun `invoice_item's trip_detailed_on_invoice is rejected once the owning invoice is issued`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        markIssued(invoice.id!!)

        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery("UPDATE invoice_item SET trip_detailed_on_invoice = true WHERE id = ${item.id}").executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `an unrelated invoice_item column stays writable once the invoice is issued (narrow guard)`() {
        val (invoice, item) = newInvoiceWithTripExpense()
        markIssued(invoice.id!!)

        // trg_invoice_item_trip_freeze only guards the two trip columns — description stays
        // as writable as before (defense-in-depth must never block a legal operation).
        em.createNativeQuery("UPDATE invoice_item SET description = 'Reisebeschreibung ergaenzt' WHERE id = ${item.id}").executeUpdate()
        em.flush()

        val description = em.createNativeQuery("SELECT description FROM invoice_item WHERE id = ${item.id}").singleResult as String
        assertEquals("Reisebeschreibung ergaenzt", description)
    }
}
