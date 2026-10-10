package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.asMutableForTest

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * L2 (`fix-l2-job-result-writeback-stale-version.md`) — reproduces, against a real Postgres
 * (Testcontainers via [PostgresTestResource]), the exact optimistic-lock race the live diagnosis
 * traced: [GenerationJob.invoice] crosses a transaction boundary detached, carrying whatever
 * `@Version` it held at read time, while a concurrent writer bumps the same invoice's version in
 * between. Before the fix, [GenerationJobRecoveryService.markFailed] wrote through that detached, stale
 * reference and Postgres rejected the `UPDATE` with `StaleObjectStateException` ("Unexpected row
 * count (expected row count 1 but was 0)") — three attempts, then the DLQ, on an invoice that was
 * a **draft**, exactly as the live stack observed it.
 *
 * A first version of this test (built before this KDoc) built the "concurrent" write via
 * `em.find(Invoice::class.java, ...)` + a field assignment, in the same `QuarkusTransaction
 * .requiringNew()` shape [GenerationJobServiceIT]'s own helpers use. It passed identically against
 * the pre-fix source — 0 errors, both before and after the fix — because it never actually
 * detached anything: this test's `em` is ONE request/test-scoped `EntityManager` shared across
 * every `requiringNew()` call in the method (a new JTA transaction is not a new persistence
 * context), so `detachedJob.invoice` and the "concurrent" writer's own `em.find` were resolving to
 * the exact same managed instance in the same identity map the whole time — nothing was ever
 * stale, so there was nothing for the fix to fix. That is a test that happens to be green, not a
 * reproduction, and it must never be mistaken for a regression guard again.
 *
 * This version applies the correction, using the exact technique already established in this
 * codebase for exercising a genuine session-detachment hazard against Quarkus's shared
 * request-scoped `EntityManager` — see [de.littletrotter.infrastructure.messaging
 * .GenerationJobResultConsumerStaleSessionIT] (`assertGenuineOptimisticLockFailure`, its Step 2 raw
 * `UPDATE`) and [de.littletrotter.e2e.nats.InvoiceValidationStatusResolutionE2EIT]
 * (`currentValidationStatusInDb`), not a new one:
 *
 * 1. `em.clear()` after reading [GenerationJob.invoice] fresh, forcing genuine detachment of
 *    `detachedJob`/its invoice from this test's own shared session — mirroring
 *    [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
 *    .clearSessionBeforeAttempt]'s real effect instead of merely assuming a `requiringNew()`
 *    transaction boundary already implies it.
 * 2. The concurrent writer bumps `invoice.version` via a raw native `UPDATE`, bypassing the ORM
 *    entirely — exactly [GenerationJobResultConsumerStaleSessionIT]'s own Step 2 — so the write
 *    that manufactures the race can never itself be confused with, or neutralised by, whatever
 *    Hibernate's own `merge()`/attach semantics might otherwise do to an entity-level write.
 * 3. Every read-back also clears first, for the same reason [GenerationJobResultConsumerStaleSessionIT
 *    .statusOf]/`validationStatusOf` do: a poisoned session must never leak a stale, cached copy
 *    into the assertion phase.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class GenerationJobServiceConcurrencyIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobRecoveryService: GenerationJobRecoveryService

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    /** Runs in its own, immediately-committed transaction so the row is visible to the separately
     *  transacted service calls that follow it — same pattern as [GenerationJobServiceIT
     *  .persistInvoice]. `validationStatus = PENDING` is set directly (bypassing the normal
     *  publish-time transition) because this test's whole point is exercising
     *  [GenerationJobRecoveryService.markFailed]'s own `clearPendingValidationStatus` write — the write L2
     *  traced the stale-version conflict to — in isolation, without going through the full
     *  publish/NATS pipeline. */
    private fun persistDraftInvoice(number: String): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            // uq_sender_profile_invoice_prefix — each fixture invocation needs its own, distinct
            // prefix (a shared literal would collide on a second run in the same suite).
            invoicePrefix = "L2${number.takeLast(2)}-"
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-$number")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val taxRate = TaxRate().apply {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "IT_L2_19_$number"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            invoiceDate = LocalDate.now()
            senderProfile = sender
            this.recipient = recipient
            // L2 — the observed conflict needs no issued document: a draft's own writeback race is
            // the reported symptom, so this is deliberately left DRAFT (the entity's own default).
            validationStatus = ValidationStatus.PENDING
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
        }
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal("1.00")
            unitPriceNet = Money(BigDecimal("100.00"))
            netAmount = Money(BigDecimal("100.00"))
            vatAmount = Money(BigDecimal("19.00"))
            grossAmount = Money(BigDecimal("119.00"))
            taxRateRef = taxRate
        }
        invoice.itemList.asMutableForTest().add(item.also { it.invoice = invoice })
        em.persist(invoice)
        em.flush()
        invoice
    }

    /** [GenerationJobResultConsumerStaleSessionIT.validationStatusOf]'s own shape: `em.clear()`
     *  first so a poisoned/stale session can never leak a cached copy into the assertion phase. */
    private fun validationStatusInDb(invoiceId: Long): ValidationStatus =
        QuarkusTransaction.requiringNew().call<ValidationStatus> {
            em.clear()
            em.find(Invoice::class.java, invoiceId).validationStatus
        }

    @Test
    fun `L2 - markFailed survives a concurrent invoice version bump between the job's own detached read and the write-back`() {
        val invoice = persistDraftInvoice("ITL2-2026-0001")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)

        // Step 1 — GenerationJobResultConsumer's own findById(): read the job (and initialise its
        // lazy invoice association) fresh, inside its own transaction.
        val detachedJob = QuarkusTransaction.requiringNew().call<GenerationJob> {
            val loaded = em.find(GenerationJob::class.java, job.id)
            checkNotNull(loaded.invoice?.validationStatus) { "fixture invariant: the invoice must be PENDING" }
            loaded
        }
        // `em.clear()` — the actual detaching step. This test's `em` is one request/test-scoped
        // EntityManager shared across every `requiringNew()` call in this method; without this,
        // `detachedJob`/`detachedJob.invoice` would still be sitting in that one shared session's
        // identity map, and step 2 below would resolve to the exact same managed instance instead
        // of writing to a genuinely independent copy — the mistake the first version of this test
        // made (see the class KDoc). This is what actually reproduces
        // `GenerationJobResultConsumer.clearSessionBeforeAttempt()`'s real effect.
        em.clear()

        // Step 2 — a concurrent writer (the UI, the poll tick, a reaction handler) bumps the SAME
        // invoice's version directly at the SQL level, bypassing the ORM entirely — the same
        // technique GenerationJobResultConsumerStaleSessionIT's own Step 2 uses, so this write can
        // never be confused with, or incidentally neutralised by, Hibernate's own entity-level
        // merge/attach semantics.
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET version = version + 1 WHERE id = :id")
                .setParameter("id", invoice.id)
                .executeUpdate()
        }

        // Step 3 — the result callback finally processes, using the now-genuinely-stale job/invoice
        // read from step 1 — exactly what GenerationJobResultConsumer hands markFailed after its
        // own clearSessionBeforeAttempt() detached everything. Before the fix, markFailed wrote
        // through detachedJob.invoice directly and Postgres rejected the UPDATE with
        // StaleObjectStateException (the version detachedJob's invoice still carries is the
        // pre-step-2 one).
        val failed = generationJobRecoveryService.markFailed(detachedJob, message = "worker crashed")

        assertEquals(GenerationJobStatus.FAILED, failed.status)
        assertEquals(
            ValidationStatus.UNAVAILABLE, validationStatusInDb(invoice.id!!),
            "L2 - the write must land on the current row, surviving the concurrent version bump",
        )
    }
}
