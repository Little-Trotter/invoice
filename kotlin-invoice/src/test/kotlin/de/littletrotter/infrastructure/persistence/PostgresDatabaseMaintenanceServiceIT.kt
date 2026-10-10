package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.TempDocumentStoreResource
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobRecoveryService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.application.system.MaintenanceExecutionFence
import de.littletrotter.application.system.MaintenanceExecutionFencedException
import de.littletrotter.application.system.MaintenanceOperationOutcome
import de.littletrotter.application.system.MaintenanceOperations
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * AP-IMP-6 — [PostgresDatabaseMaintenanceService] had no test at all against a real
 * PostgreSQL before this class: every existing reference ([MaintenanceQueueProcessorTest],
 * `SystemViewKaribuTest`, `SystemViewTest`) exercises it only through a Mockito/
 * `FakeMaintenanceService` stand-in.
 *
 * `restrictToAnnotatedClass = true`: [performRecalculateTotals] and [performQueueIssuedGenerationJobs]
 * both sweep the WHOLE invoice table, not just rows this class created. Sharing the module's
 * common `PostgresTestResource` container with every other IT class made both sweeps see (and
 * abort on, or attach `generation_job` rows to) other classes' fixtures — real cross-class
 * failures, not flakes (see the class-level test-isolation writeup this fixed). This class
 * therefore gets its own Postgres container, freshly Flyway-migrated, seen by no other test class;
 * "the sweep runs against a real, whole database" still holds, it is just no longer a database
 * anyone else also writes to. Every N12 fixture invoice below still carries a distinctive tag in
 * its recipient/sender names purely as a within-class convention (assert on OUR OWN tagged
 * invoice's resulting job row, never on a global count across the whole table), not because the
 * database is shared with other classes anymore.
 *
 * N12 — [performQueueIssuedGenerationJobs] is exercised the same way: it, too, sweeps the whole
 * (now class-private) invoice table, so its own tests assert only on a dedicated, distinctively
 * tagged invoice's resulting state, never on the sweep's global counts.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(TempDocumentStoreResource::class)
class PostgresDatabaseMaintenanceServiceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var service: DatabaseMaintenanceService

    @Inject
    lateinit var operations: MaintenanceOperations

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var generationJobRecoveryService: GenerationJobRecoveryService

    @Inject
    lateinit var generationJobRepository: GenerationJobRepository

    @Inject
    lateinit var documentStore: DocumentStore

    /** Claims and discards every currently PENDING maintenance-queue event (both types) so a
     *  claim test below is not at the mercy of leftovers from an earlier test method in this
     *  same class (this class's Postgres container is private to it, but JUnit does not order
     *  test methods for us). */
    private fun drainPendingMaintenanceJobs() {
        QuarkusTransaction.requiringNew().run {
            while (service.claimOldestPendingMaintenanceJob() != null) { /* drain */ }
        }
    }

    private fun insertPendingRecalculation(createdAt: OffsetDateTime): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            val event = DatabaseMaintenanceEvent(
                createdAt = createdAt,
                type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
                status = DatabaseMaintenanceStatus.PENDING,
                details = "queued",
            )
            em.persist(event)
            em.flush()
            event.id!!
        }

    private fun insertPendingQueueIssued(createdAt: OffsetDateTime): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            val event = DatabaseMaintenanceEvent(
                createdAt = createdAt,
                type = DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS,
                status = DatabaseMaintenanceStatus.PENDING,
                details = "queued (scope=ISSUED_WITHOUT_JOB)",
            )
            em.persist(event)
            em.flush()
            event.id!!
        }

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `currentPostgresMaintenance reflects a freshly recorded reindex event and prefers a recorded backup event over the archiver`() {
        // -- this class's own private container never records a REINDEX event anywhere
        // else, so before the recordMaintenanceEvent call below, reindexEvent is genuinely null
        // and currentPostgresMaintenance's `reindexEvent?.let { ... }` null branch is exercised --
        // every OTHER call in this test method runs after the recording, which is why that branch
        // was still open before this assertion existed.
        assertNull(service.currentPostgresMaintenance().lastReindex, "no REINDEX event has been recorded yet in this class's own container")

        // Recorded directly: REINDEX is no longer something the application runs (moved to
        // admin-tools/scripts/db/db-maintenance.sh via ADM-1), but the scheduler still records REINDEX events
        // from pg_stat, so currentPostgresMaintenance's `reindexEvent?.let { ... }` branch is
        // still live and still needs proving.
        service.recordMaintenanceEvent(
            DatabaseMaintenanceEvent(
                type = DatabaseMaintenanceType.REINDEX,
                status = DatabaseMaintenanceStatus.SUCCESS,
                details = "manual-imp6-reindex-marker",
                createdAt = OffsetDateTime.now(),
            ),
        )
        val afterReindex = service.currentPostgresMaintenance()
        assertNotNull(afterReindex.lastReindex, "a just-recorded REINDEX event must show up")
        assertTrue(afterReindex.lastReindex!!.contains("SUCCESS"))

        service.recordMaintenanceEvent(
            DatabaseMaintenanceEvent(
                type = DatabaseMaintenanceType.BACKUP,
                status = DatabaseMaintenanceStatus.SUCCESS,
                details = "manual-imp6-marker",
                createdAt = OffsetDateTime.now(),
            ),
        )
        val afterBackup = service.currentPostgresMaintenance()
        assertNotNull(afterBackup.lastBackup)
        assertTrue(afterBackup.lastBackup!!.contains("SUCCESS"), "a recorded BACKUP event must win over pg_stat_archiver")
    }

    @Test
    fun `claimOldestPendingMaintenanceJob returns null with nothing queued, then claims strictly oldest-first and marks it RUNNING`() {
        drainPendingMaintenanceJobs()
        assertNull(service.claimOldestPendingMaintenanceJob(), "nothing queued after draining")

        val older = insertPendingRecalculation(OffsetDateTime.now().minusMinutes(5))
        val newer = insertPendingRecalculation(OffsetDateTime.now())

        val first = service.claimOldestPendingMaintenanceJob()
        assertNotNull(first)
        assertEquals(older, first!!.eventId, "the older PENDING request must be claimed before the newer one")
        assertEquals(DatabaseMaintenanceType.RECALCULATE_TOTALS, first.type)

        val reloadedOlder = em.find(DatabaseMaintenanceEvent::class.java, older)
        assertEquals(DatabaseMaintenanceStatus.RUNNING, reloadedOlder.status, "claiming must flip the event to RUNNING")

        val second = service.claimOldestPendingMaintenanceJob()
        assertNotNull(second)
        assertEquals(newer, second!!.eventId)
        assertEquals(DatabaseMaintenanceType.RECALCULATE_TOTALS, second.type)

        assertNull(service.claimOldestPendingMaintenanceJob(), "both requests are claimed, nothing left to claim")
    }

    @Test
    fun `claimOldestPendingMaintenanceJob claims across both queue types, oldest created_at first regardless of type`() {
        drainPendingMaintenanceJobs()

        val olderQueueIssued = insertPendingQueueIssued(OffsetDateTime.now().minusMinutes(5))
        val newerRecalculation = insertPendingRecalculation(OffsetDateTime.now())

        val first = service.claimOldestPendingMaintenanceJob()
        assertNotNull(first)
        assertEquals(olderQueueIssued, first!!.eventId, "the older event wins regardless of its type")
        assertEquals(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, first.type)

        val second = service.claimOldestPendingMaintenanceJob()
        assertNotNull(second)
        assertEquals(newerRecalculation, second!!.eventId)
        assertEquals(DatabaseMaintenanceType.RECALCULATE_TOTALS, second.type)

        assertNull(service.claimOldestPendingMaintenanceJob())
    }

    @Test
    fun `claimOldestPendingMaintenanceJob -- two concurrent claims on one pending event, exactly one wins the real race`() {
        // -- the "if (updated == 0) return null" branch (lost the race to another worker
        // instance) can only fire from a genuine second writer changing the row between this
        // method's own SELECT and its conditional UPDATE; a single-threaded test can never
        // interleave with itself. Two real threads against the same PENDING row, relying on
        // Postgres's own row lock (the second UPDATE blocks on the first, then re-evaluates
        // `AND e.status = :pending` against the now-committed RUNNING row and updates 0 rows) --
        // exactly the scenario the method's own comment describes, not a simulation of it.
        drainPendingMaintenanceJobs()
        val id = insertPendingRecalculation(OffsetDateTime.now())

        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val ready = java.util.concurrent.CyclicBarrier(2)
            val results = (1..2).map {
                pool.submit(
                    java.util.concurrent.Callable {
                        ready.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        service.claimOldestPendingMaintenanceJob()
                    },
                )
            }.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }

            val winners = results.filterNotNull()
            assertEquals(1, winners.size, "exactly one of the two concurrent claims must win: $results")
            assertEquals(id, winners.single().eventId)
            assertEquals(1, results.count { it == null }, "the other concurrent claim must lose the race and return null")
        } finally {
            pool.shutdown()
        }

        val reloaded = em.find(DatabaseMaintenanceEvent::class.java, id)
        assertEquals(DatabaseMaintenanceStatus.RUNNING, reloaded.status)
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `performRecalculateTotals names a blank invoice-number sentinel when the failing invoice was never assigned one`() {
        // -- describeInvoiceForFailure's `loadedInvoiceNumber.isBlank()` branch: since N5
        // (InvoiceLifecycleService.create draws a real number immediately), a freshly created
        // invoice is never actually blank any more -- every other failure test in this class
        // therefore exercises the `else` (real, non-blank number) branch instead. A blank
        // invoice_number can still occur for a legacy/imported row (V1's own
        // invoice_number_prefix/-sequence comments: "NULL an Bestandsbelegen und unnummerierten
        // Entwürfen"), simulated here the same way N15R simulates a malformed one: a direct native
        // UPDATE, because InvoiceNumber's validating constructor would reject an empty value from
        // the domain layer itself.
        val parties = buildParties("N15B")
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "N15B Beratung",
                    quantity = BigDecimal("1"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        // Break the position the same way N15D does (missing tax reference -> recalculate()
        // throws), then blank out the invoice number directly at the DB level.
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice_item SET tax_rate_id = NULL, tax_rate_snapshot = NULL WHERE invoice_id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET invoice_number = '', needs_recalculation = true WHERE id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }

        try {
            val message = service.performRecalculateTotals()

            assertTrue(message.startsWith("Recalculation aborted at invoice "), message)
            assertTrue(message.contains("id $invoiceId (no invoice number assigned yet)"), message)

            val event = service.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS)
            assertNotNull(event)
            assertTrue(event!!.details?.contains("no invoice number assigned yet") == true, event.details.toString())
        } finally {
            // Restore a real number FIRST (native, same reasoning as the blank-out itself --
            // InvoiceNumber's validating constructor accepts the empty sentinel but there is no
            // domain-layer call that assigns a fresh one here), THEN let deleteItemByPosition's
            // normal domain-layer path drop the broken item and fully recalculate.
            QuarkusTransaction.requiringNew().run {
                // A valid NEW_PATTERN number (PREFIX-YYYY-MM-NNNNN, prefix <=10 alnum chars) --
                // InvoiceNumber's own format validator rejected the first attempt at this
                // (a literal "N15B-RESTORED-0001" has an 11-char, hyphen-bearing "prefix" segment
                // before any \d{4} year shows up, matching none of its three accepted shapes).
                em.createNativeQuery("UPDATE invoice SET invoice_number = :number WHERE id = :id")
                    .setParameter("number", "N15B-2026-09-00001")
                    .setParameter("id", invoiceId)
                    .executeUpdate()
            }
            QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(invoiceId, "1") }
        }
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `performRecalculateTotals aborts the whole sweep when an invoice row cannot even be read, not just when recalculation fails`() {
        // The regression this originally guarded: reading whole entities makes a single
        // malformed invoice_number blow up inside InvoiceNumberConverter at load time --
        // before InvoiceLifecycleService.recalculate ever runs. Before N15 the sweep's contract
        // was "count and skip"; N15 (PO decision 2026-09-11 evening) changed it to "abort and
        // report" for every per-invoice failure, load failures included, so this must now show up
        // as an aborted sweep naming the invoice by id (there is no number to read), never as a
        // "Recalculated ..." success.
        val parties = buildParties("N15R")
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "UPDATE invoice SET invoice_number = '!!!NOT-A-NUMBER!!!', needs_recalculation = true WHERE id = :id",
            ).setParameter("id", invoiceId).executeUpdate()
        }

        try {
            val message = service.performRecalculateTotals()

            assertTrue(message.startsWith("Recalculation aborted at invoice "), message)
            assertTrue(message.contains("id $invoiceId"), "the message must name our invoice by id: $message")
            assertTrue(message.contains("invoice number unavailable"), message)
            assertTrue(message.contains("left unprocessed"), message)

            val event = service.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS)
            assertNotNull(event, "the sweep must record its own RECALCULATE_TOTALS event")
            assertEquals(DatabaseMaintenanceStatus.FAILED, event!!.status)
            assertTrue(event.details?.contains("id $invoiceId") == true, event.details.toString())
        } finally {
            // Self-heal: InvoiceNumber's own validating constructor would reject the very value
            // we wrote, so the domain layer cannot restore this row -- delete it directly instead,
            // same discipline the `aborts at the first invoice` test below uses.
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("DELETE FROM invoice WHERE id = :id").setParameter("id", invoiceId).executeUpdate()
            }
        }
    }

    // ── N15 (PO decision 2026-09-11 evening) — issued invoices are only ever skipped, never ─────
    // ── recalculated; a data error aborts the sweep instead of being counted and skipped over ───

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `performRecalculateTotals skips an issued invoice instead of ever risking the freeze-guard trigger`() {
        val invoiceId = sentInvoiceWithoutJob("N15S")
        // Force it back into the sweep's candidate set without touching any column the freeze
        // trigger watches (guard_invoice_freeze, V1__baseline_schema.sql, only checks
        // invoice_number/prefix/sequence/invoice_date/type_code/amounts/payment-term
        // snapshot/parties -- needs_recalculation is not one of them, so this update alone can
        // never trip the trigger).
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET needs_recalculation = true WHERE id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }
        val before = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }

        val message = service.performRecalculateTotals()

        assertTrue(message.contains("skipped (read-only)"), message)
        val after = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }
        assertEquals(InvoiceStatus.SENT, after.status, "a skipped issued invoice is left exactly as it was")
        assertEquals(before.totals.netAmount, after.totals.netAmount, "totals must be byte-identical -- skipping never calls recalculate()")
        assertEquals(before.totals.vatAmount, after.totals.vatAmount)
        assertEquals(before.totals.grossAmount, after.totals.grossAmount)
        assertTrue(after.needsRecalculation, "skipping does not clear the flag -- only an actual recalculation would")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `performRecalculateTotals aborts at the first invoice whose recalculation fails, naming it and the counts`() {
        val parties = buildParties("N15D")
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "N15D Beratung",
                    quantity = BigDecimal("1"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        // Break the position at the SQL level -- neither a frozen snapshot nor a live tax-rate
        // reference survives, the same shape InvoiceItem.effectiveTaxRate() throws
        // ITEM_TAX_RATE_REFERENCE_MISSING for.
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice_item SET tax_rate_id = NULL, tax_rate_snapshot = NULL WHERE invoice_id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET needs_recalculation = true WHERE id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }

        try {
            val message = service.performRecalculateTotals()

            assertTrue(message.startsWith("Recalculation aborted at invoice "), message)
            // A fresh DRAFT never had an invoice number assigned (the empty-string sentinel), so
            // describeInvoiceForFailure names it "id N (no invoice number assigned yet)" rather
            // than "<number> (id N)" -- either way "id N" itself is always present.
            assertTrue(message.contains("id $invoiceId"), "the message must name our invoice by id: $message")
            assertTrue(message.contains("left unprocessed"), message)

            val event = service.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS)
            assertNotNull(event)
            assertEquals(DatabaseMaintenanceStatus.FAILED, event!!.status)
            assertTrue(event.details?.contains("id $invoiceId") == true, event.details.toString())
        } finally {
            // Self-heal: delete the broken position so this invoice never poisons a later sweep in
            // this shared, never-cleaned database (same discipline this class's N12 fixtures use).
            QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(invoiceId, "1") }
            val recovered = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.needsRecalculation }
            assertFalse(recovered, "deleting the broken position must leave the invoice fully recalculated again")
        }
    }

    @Test
    fun `markMaintenanceJobFailed records the crash reason on the claimed event`() {
        drainPendingMaintenanceJobs()
        val id = insertPendingRecalculation(OffsetDateTime.now())
        val claimed = service.claimOldestPendingMaintenanceJob()
        assertEquals(id, claimed?.eventId)

        service.markMaintenanceJobFailed(id, "boom-imp6")

        val reloaded = em.find(DatabaseMaintenanceEvent::class.java, id)
        assertEquals(DatabaseMaintenanceStatus.FAILED, reloaded.status)
        assertTrue(reloaded.details?.contains("boom-imp6") == true, reloaded.details.toString())
    }

    // ── execute() is the raw MaintenanceOperations dispatcher the queue processor calls; ──
    // ── nothing above exercises it directly (performRecalculateTotals/performQueueIssuedGenerationJobs ──
    // ── each call their own private executeXxx(null) instead), so the dispatch itself, and its ──
    // ── unknown-type rejection, were both untested against a real database. ──

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `happy path - execute dispatches RECALCULATE_TOTALS through the fence and records success`() {
        val parties = buildParties("N11A")
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET needs_recalculation = true WHERE id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }
        val fenceCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val fence = MaintenanceExecutionFence { fenceCalls.incrementAndGet() }

        val outcome = operations.execute(DatabaseMaintenanceType.RECALCULATE_TOTALS, fence)

        assertTrue(outcome is MaintenanceOperationOutcome.Succeeded, outcome.summary)
        assertTrue(fenceCalls.get() > 0, "the fence must be consulted while recalculating our own tagged invoice")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `boundary - execute dispatches QUEUE_ISSUED_GENERATION_JOBS through the fence and creates our own job`() {
        // Same "assert only our own tagged invoice, never the sweep's global counts" discipline the
        // N12 tests above already use: this class's shared container can carry an intentionally
        // never-healed FAILED fixture from another test's own full-table sweep (e.g. the N12I due-date
        // fixture), so the sweep's own outcome type/counts are not asserted here.
        val invoiceId = sentInvoiceWithoutJob("N11B")
        val fenceCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val fence = MaintenanceExecutionFence { fenceCalls.incrementAndGet() }

        val outcome = operations.execute(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, fence)

        assertTrue(outcome.summary.startsWith("Queued "), outcome.summary)
        assertTrue(fenceCalls.get() > 0, "the fence must be consulted while queueing our own tagged invoice's job")
        assertEquals(1, jobsOf(invoiceId).size)
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `boundary - execute rejects an unknown maintenance type without ever invoking the fence`() {
        val fence = MaintenanceExecutionFence {
            throw AssertionError("the fence must never be consulted before an unhandled type is rejected")
        }

        val outcome = operations.execute(DatabaseMaintenanceType.VACUUM, fence)

        assertTrue(outcome is MaintenanceOperationOutcome.Failed, outcome.summary)
        assertEquals("No maintenance queue handler for type VACUUM", outcome.summary)
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `boundary - execute rejects BACKUP the same way, naming the exact type in its outcome`() {
        val fence = MaintenanceExecutionFence {
            throw AssertionError("the fence must never be consulted before an unhandled type is rejected")
        }

        val outcome = operations.execute(DatabaseMaintenanceType.BACKUP, fence)

        assertTrue(outcome is MaintenanceOperationOutcome.Failed, outcome.summary)
        assertEquals("No maintenance queue handler for type BACKUP", outcome.summary)
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `boundary - queueRecalculation persists SQL metacharacters in details as inert literal text, never executable SQL`() {
        val maliciousDetails = "'; DROP TABLE database_maintenance_event; --"

        val event = service.queueRecalculation(maliciousDetails)

        val reloaded = em.find(DatabaseMaintenanceEvent::class.java, event.id)
        assertEquals(maliciousDetails, reloaded.details, "the text must be stored byte-for-byte, never interpreted as SQL")
        // The table must still exist and be queryable -- an injected DROP would have destroyed it.
        assertNotNull(service.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS))
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `queueIssuedGenerationJobsSweep queues a PENDING QUEUE_ISSUED_GENERATION_JOBS event with the given details`() {
        val event = service.queueIssuedGenerationJobsSweep("scope=ISSUED_WITHOUT_JOB, triggered from System tab")

        assertEquals(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, event.type)
        assertEquals(DatabaseMaintenanceStatus.PENDING, event.status)
        assertEquals("scope=ISSUED_WITHOUT_JOB, triggered from System tab", event.details)
        assertNotNull(em.find(DatabaseMaintenanceEvent::class.java, event.id), "the event must be durably persisted")
    }

    @Test
    fun `queueRecalculation queues a PENDING RECALCULATE_TOTALS event with the given details`() {
        val event = service.queueRecalculation("triggered from System tab")

        assertEquals(DatabaseMaintenanceType.RECALCULATE_TOTALS, event.type)
        assertEquals(DatabaseMaintenanceStatus.PENDING, event.status)
        assertEquals("triggered from System tab", event.details)
        assertNotNull(em.find(DatabaseMaintenanceEvent::class.java, event.id), "the event must be durably persisted")
    }

    // ── N12 — performQueueIssuedGenerationJobs, real fixtures, targeted (not global) assertions ──

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = SenderProfile().apply {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1)
            // A fixture, not a selectable reference rule: `isSelectable` defaults to true, and
            // the row would otherwise show up in TaxRuleMigrationIT, which checks the seed data
            // (same reasoning GenerationJobServiceIT's own fixture documents).
            isSelectable = false; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    /** Creates, fills and sends an invoice — no job of any kind. Distinctive [tag] so this
     *  invoice's own generation_job row (created by the sweep under test) is unambiguous even
     *  against a shared, never-cleaned database. */
    private fun sentInvoiceWithoutJob(tag: String): Long {
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = LocalDate.now() }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        return invoiceId
    }

    private fun jobsOf(invoiceId: Long) = QuarkusTransaction.requiringNew().call { generationJobRepository.findByInvoiceId(invoiceId) }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - performQueueIssuedGenerationJobs creates a PENDING PRINT_JOB (MUSTANG, llm off) for a SENT invoice with no job`() {
        val invoiceId = sentInvoiceWithoutJob("N12A")
        assertTrue(jobsOf(invoiceId).isEmpty(), "sanity: no job exists yet")

        val message = service.performQueueIssuedGenerationJobs()

        assertTrue(message.startsWith("Queued "), message)
        val jobs = jobsOf(invoiceId)
        assertEquals(1, jobs.size, "exactly one job must be created for our tagged invoice")
        val job = jobs.single()
        assertEquals("PRINT_JOB", job.jobType)
        assertEquals(GenerationJobStatus.PENDING, job.status)
        assertEquals(DocumentEngine.MUSTANG, job.engine)
        assertFalse(job.llmOptimize)
        assertTrue(job.issued, "a job created for an already-SENT invoice must be issued=true")
        assertNotNull(
            service.latestMaintenanceEvent(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS),
            "the sweep must record its own QUEUE_ISSUED_GENERATION_JOBS event",
        )
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - performQueueIssuedGenerationJobs is idempotent -- a second run creates nothing more for the same invoice`() {
        val invoiceId = sentInvoiceWithoutJob("N12B")

        service.performQueueIssuedGenerationJobs()
        assertEquals(1, jobsOf(invoiceId).size, "first run creates the one job")

        service.performQueueIssuedGenerationJobs()
        assertEquals(1, jobsOf(invoiceId).size, "second run must not create a second job -- it is already PENDING")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - a FAILED-only invoice is re-queued -- RS-1 never locks on a failed job`() {
        val invoiceId = sentInvoiceWithoutJob("N12C")
        val firstAttempt = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        QuarkusTransaction.requiringNew().run { generationJobRecoveryService.markFailed(firstAttempt, message = "simulated worker crash") }
        assertEquals(1, jobsOf(invoiceId).size, "sanity: only the one FAILED job exists")

        service.performQueueIssuedGenerationJobs()

        val jobs = jobsOf(invoiceId)
        assertEquals(2, jobs.size, "the FAILED attempt plus one new PENDING retry")
        assertTrue(jobs.any { it.status == GenerationJobStatus.PENDING }, "a fresh retry must have been queued")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - an invoice with a non-terminal job is left alone`() {
        val invoiceId = sentInvoiceWithoutJob("N12D")
        QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        assertEquals(1, jobsOf(invoiceId).size, "sanity: one PENDING job exists")

        service.performQueueIssuedGenerationJobs()

        assertEquals(1, jobsOf(invoiceId).size, "an already-PENDING job must not be duplicated")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - an invoice with a COMPLETED issued job is left alone -- RS-1 already locks it`() {
        val invoiceId = sentInvoiceWithoutJob("N12E")
        val job = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        QuarkusTransaction.requiringNew().run { generationJobPublishingService.markDelegated(job) }
        QuarkusTransaction.requiringNew().run { generationJobResultHandler.markCompleted(job) }
        assertEquals(1, jobsOf(invoiceId).size, "sanity: one COMPLETED job exists")

        service.performQueueIssuedGenerationJobs()

        assertEquals(1, jobsOf(invoiceId).size, "an invoice RS-1 already locks must not get a second job")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - a DRAFT invoice is untouched`() {
        val parties = buildParties("N12F")
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }

        service.performQueueIssuedGenerationJobs()

        assertTrue(jobsOf(invoiceId).isEmpty(), "a DRAFT invoice is not issued -- the sweep must not touch it")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - a VOIDED invoice is untouched`() {
        val parties = buildParties("N12G")
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.VOIDED) }

        service.performQueueIssuedGenerationJobs()

        assertTrue(jobsOf(invoiceId).isEmpty(), "a VOIDED invoice can never place a generation job -- the sweep must not touch it")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - P5 restore case -- an invoice whose export PDF already exists in the store with no job row is skipped, not overwritten`() {
        val invoiceId = sentInvoiceWithoutJob("N12H")
        val invoiceNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, invoiceId).invoiceNumber.value }
        val pdfKey = DocumentKeyRules.deriveJobExportKeys(invoiceNumber, invoiceId, "1", draft = false).pdfObjectKey
        val preExistingBytes = "restored-from-backup".toByteArray()
        documentStore.put(pdfKey, preExistingBytes, "application/pdf")

        val message = service.performQueueIssuedGenerationJobs()

        assertTrue(jobsOf(invoiceId).isEmpty(), "P5: no job may be created -- that would let createJob's own render overwrite the restored PDF")
        assertTrue(message.contains("skipped"), message)
        val event = service.latestMaintenanceEvent(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS)
        assertNotNull(event)
        assertTrue(event!!.details?.contains("Invoice #$invoiceId skipped -- restore case") == true, event.details.toString())
        assertTrue(documentStore.exists(pdfKey), "the restored PDF itself must be left untouched")
        assertEquals(preExistingBytes.toList(), documentStore.get(pdfKey).toList(), "the restored PDF's bytes must be unchanged")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `N12 - a per-invoice validation failure inside createJob is counted as failed, not aborting the sweep, and the event is FAILED`() {
        // -- the generic `catch (ex: Exception)` in performQueueIssuedGenerationJobs (as
        // opposed to the two named gate-rejection exceptions, which need a genuine concurrent
        // writer to trigger -- see 's own fence-triggered tests further below) had no
        // test: createJob's own
        // `loaded.validate()` call throws InvoiceValidationException for a due date before the
        // invoice date -- a real, deterministic data-validity failure, set directly at the DB
        // level the same way N15D breaks a tax reference, since the domain layer's own
        // updateHeader would refuse this edit itself.
        val invoiceId = sentInvoiceWithoutJob("N12I")
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET due_date = invoice_date - 1 WHERE id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }

        val message = service.performQueueIssuedGenerationJobs()

        assertTrue(jobsOf(invoiceId).isEmpty(), "the failed invoice must get no job")
        assertTrue(message.contains("1 failed"), message)
        val event = service.latestMaintenanceEvent(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS)
        assertNotNull(event)
        assertEquals(DatabaseMaintenanceStatus.FAILED, event!!.status, "any per-invoice failure must mark the sweep's own event FAILED")
        assertTrue(event.details?.contains("Invoice #$invoiceId failed") == true, event.details.toString())
    }

    // ── both executeXxx methods declare `catch (ex: MaintenanceExecutionFencedException) ──
    // ── { throw ex }` explicitly BEFORE their own broad `catch (ex: Exception)` (AP-EXC-1), so a ──
    // ── lost claim-token race must abort execute() outright rather than being folded into a ──
    // ── generic Failed outcome -- neither rethrow had a test proving it actually escapes. ──

    // -- executeRecalculateTotals's own `invoiceRepo.findById(id) ?: return@call false`
    // (skipped, not aborted) had no test: every existing recalculation failure test above breaks
    // the row so it still LOADS but then fails validation/recalculation, never removes it outright
    // between the sweep's own id SELECT and this invoice's own per-row transaction. The fence hook
    // is reused the same way N24DEL reuses it for performQueueIssuedGenerationJobs's own
    // equivalent branch.
    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `boundary - an invoice deleted concurrently between the sweep's SELECT and its own transaction is skipped, not aborted`() {
        val parties = buildParties("N38DEL")
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET needs_recalculation = true WHERE id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }
        var mutated = false
        val fence = MaintenanceExecutionFence {
            if (!mutated) {
                mutated = true
                QuarkusTransaction.requiringNew().run {
                    em.createNativeQuery("DELETE FROM invoice WHERE id = :id").setParameter("id", invoiceId).executeUpdate()
                }
            }
        }

        val outcome = operations.execute(DatabaseMaintenanceType.RECALCULATE_TOTALS, fence)

        assertTrue(outcome is MaintenanceOperationOutcome.Succeeded, outcome.summary)
        assertTrue(outcome.summary.contains("skipped (read-only)"), "a concurrently-deleted invoice must be counted as skipped, never as a sweep failure: ${outcome.summary}")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `boundary - execute rethrows a fence violation during recalculation instead of swallowing it as a generic failure`() {
        val parties = buildParties("N21A")
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET needs_recalculation = true WHERE id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }
        val fence = MaintenanceExecutionFence { throw MaintenanceExecutionFencedException("boundary-N21A-fence-lost") }

        val ex = assertThrows(MaintenanceExecutionFencedException::class.java) {
            operations.execute(DatabaseMaintenanceType.RECALCULATE_TOTALS, fence)
        }

        assertEquals("boundary-N21A-fence-lost", ex.message)
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `boundary - execute rethrows a fence violation during issued-generation queueing instead of swallowing it as a generic failure`() {
        val invoiceId = sentInvoiceWithoutJob("N21B")
        val fence = MaintenanceExecutionFence { throw MaintenanceExecutionFencedException("boundary-N21B-fence-lost") }

        val ex = assertThrows(MaintenanceExecutionFencedException::class.java) {
            operations.execute(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, fence)
        }

        assertEquals("boundary-N21B-fence-lost", ex.message)
        assertTrue(jobsOf(invoiceId).isEmpty(), "no job may be created once the fence rejects the transaction")
    }

    // ── executeQueueIssuedGenerationJobs's two named gate-rejection catches ──────────────
    // ── (InvoiceImmutableGenerationBlockedException / InvoiceVoidedGenerationBlockedException) ────
    // ── guard against a genuinely concurrent writer between findInvoiceIdsNeedingIssuedGenerationJob's ──
    // ── own SELECT and this invoice's own per-row transaction. The fence hook already exists to be ──
    // ── consulted inside that exact transaction before invoiceRepo.findById runs, so it doubles as ──
    // ── the trigger point for a REAL second, already-committed transaction here -- not a mock, an ──
    // ── actual race recreated with real Postgres commit ordering. ──

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `boundary - a completed issued job created concurrently mid-sweep is caught as an immutable-generation rejection, not a sweep failure`() {
        val invoiceId = sentInvoiceWithoutJob("N24IMM")
        var mutated = false
        val fence = MaintenanceExecutionFence {
            if (!mutated) {
                mutated = true
                // Another worker instance wins the race and completes an issued job for this
                // exact invoice between the sweep's own SELECT (which still saw no completed job)
                // and this invoice's own per-row transaction.
                val job = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
                QuarkusTransaction.requiringNew().run { generationJobPublishingService.markDelegated(job) }
                QuarkusTransaction.requiringNew().run { generationJobResultHandler.markCompleted(job) }
            }
        }

        val outcome = operations.execute(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, fence)

        assertTrue(outcome is MaintenanceOperationOutcome.Succeeded, outcome.summary)
        assertTrue(outcome.details.contains("Invoice #$invoiceId skipped -- gate rejected"), outcome.details)
        assertEquals(1, jobsOf(invoiceId).size, "only the concurrently-completed job exists -- the sweep must not add a second one")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `boundary - a concurrent void between the sweep's SELECT and this invoice's transaction is caught as a voided-generation rejection, not a sweep failure`() {
        val invoiceId = sentInvoiceWithoutJob("N24VOID")
        var mutated = false
        val fence = MaintenanceExecutionFence {
            if (!mutated) {
                mutated = true
                // VOIDED is never itself selected by findInvoiceIdsNeedingIssuedGenerationJob (only
                // SENT/CANCELLED are), so this can only be a concurrent transition after selection --
                // exactly the race processIssuedGenerationJobForInvoice's own catch documents.
                QuarkusTransaction.requiringNew().run {
                    em.createNativeQuery("UPDATE invoice SET status = :voided WHERE id = :id")
                        .setParameter("voided", InvoiceStatus.VOIDED.ordinal)
                        .setParameter("id", invoiceId)
                        .executeUpdate()
                }
            }
        }

        val outcome = operations.execute(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, fence)

        assertTrue(outcome is MaintenanceOperationOutcome.Succeeded, outcome.summary)
        assertTrue(outcome.details.contains("Invoice #$invoiceId skipped -- voided concurrently"), outcome.details)
        assertTrue(jobsOf(invoiceId).isEmpty(), "a concurrently-voided invoice must get no job at all")
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin", "invoicing"])
    fun `boundary - an invoice deleted concurrently between the sweep's SELECT and its own transaction is skipped, not a sweep failure`() {
        val invoiceId = sentInvoiceWithoutJob("N24DEL")
        var mutated = false
        val fence = MaintenanceExecutionFence {
            if (!mutated) {
                mutated = true
                // invoiceRepo.findById(id) ?: return@call QueueOutcome.SKIPPED -- the row itself is
                // gone by the time this invoice's own transaction reads it. Cascades to invoice_item.
                QuarkusTransaction.requiringNew().run {
                    em.createNativeQuery("DELETE FROM invoice WHERE id = :id")
                        .setParameter("id", invoiceId)
                        .executeUpdate()
                }
            }
        }

        val outcome = operations.execute(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, fence)

        assertTrue(outcome is MaintenanceOperationOutcome.Succeeded, outcome.summary)
        assertTrue(jobsOf(invoiceId).isEmpty(), "a concurrently-deleted invoice must get no job")
    }
}
