package de.littletrotter.infrastructure.messaging

import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest

import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.ValkeyTestResource
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import de.littletrotter.shared.PayloadExchange
import io.nats.client.Message
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * Bug production 2026-08-26 — the outage: two print jobs (invoices 513 and 541) stuck DELEGATED
 * forever, republished every 5 minutes by [de.littletrotter.application.invoice
 * .GenerationJobProcessor]'s stale-DELEGATED sweep, each cycle failing identically with
 * `org.hibernate.StaleObjectStateException` on the invoice's `@Version`-checked UPDATE.
 *
 * This test proves the SESSION-HYGIENE HAZARD the fix (per-attempt `EntityManager.clear()` in
 * [GenerationJobResultConsumer], hermetic per-message request context in
 * [GenerationJobNatsAdapter]) defends against, with a real Postgres and a real, uncooked
 * `OptimisticLockException`/`StaleObjectStateException` — not a simulated one:
 *
 * 1. An invoice is loaded into an activated CDI request context (the same kind
 *    [GenerationJobNatsAdapter.receive] activates around [GenerationJobResultConsumer.consume]).
 * 2. A concurrent writer bumps the row's `version` in the DB directly.
 * 3. A write against the now-stale, still-managed in-memory copy is attempted and genuinely fails
 *    (`assertGenuineOptimisticLockFailure` below) — confirming Hibernate really did detect the
 *    conflict, not that the write was silently skipped.
 * 4. Critically, the entity is STILL `em.contains() == true` and STILL carries its stale
 *    `version` after that failed, rolled-back write — proven directly (`assertStillPoisoned`
 *    below) — exactly the "session survives a failed flush" mechanism the incident's structural
 *    findings describe (see this repo's own AP-M10-era commits for `GenerationJobResultConsumer`
 *    /`GenerationJobNatsAdapter` KDoc). Without clearing, ANY later operation that reuses this
 *    same `EntityManager` without an intervening reset risks reusing this exact poisoned entity.
 *
 * NOTE for a future maintainer investigating a similar production incident: in THIS repo's current
 * Hibernate ORM/Quarkus version, [de.littletrotter.application.invoice.GenerationJobService
 * .markCompleted]'s own call shape (a fresh `@Transactional` entry re-associating
 * `GenerationJob.invoice` via `EntityManager.merge()`, which has no `cascade = MERGE`) happens to
 * re-load the invoice fresh on every call, which independently neutralizes the exact poisoned
 * reference this test constructs before `consume()` ever reaches it — confirmed empirically by
 * driving many additional variations of this same setup directly against `consume()`. That
 * resilience is a property of the current dependency versions' internals, not a contract this
 * codebase documents or tests elsewhere, so it must not be relied on — [assertStillPoisoned]
 * exists precisely to keep proving that the underlying hazard this fix defends against is real,
 * independent of whether today's specific ORM version happens to also paper over it on this one
 * call path.
 *
 * [NatsTestResource] — MANDATORY for any `@QuarkusTest` in this module, not just messaging-focused
 * ones (see that class's own KDoc): [GenerationJobNatsAdapter] is `@Startup`-eager, so every
 * `@QuarkusTest` boots it, and without an isolated NATS Testcontainer it falls back to whatever
 * `nats.url` resolves to by default — a real, shared NATS server if one happens to be reachable
 * (e.g. a locally running docker-compose stack), which this class's own durable consumer
 * (`little-trotter-invoice-result-consumer`, the SAME name production uses) would then compete
 * for real messages with. Never omit this resource here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ValkeyTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class GenerationJobResultConsumerStaleSessionIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobResultConsumer: GenerationJobResultConsumer

    @Inject
    lateinit var generationJobNatsAdapter: GenerationJobNatsAdapter

    @Inject
    lateinit var payloadExchange: PayloadExchange

    private val mapper = ObjectMapper()

    /** Persists an invoice with a DELEGATED generation job, matching the production symptom
     *  (invoices 513/541 stuck DELEGATED). One top-level, immediately-committed transaction — see
     *  [de.littletrotter.application.invoice.GenerationJobServiceIT]'s own KDoc for why this
     *  class's helpers all follow that shape rather than one enclosing `@Transactional` test.
     *  Deliberately builds the fixtures via direct `em.persist` rather than
     *  [de.littletrotter.application.invoice.GenerationJobService.createJob]: `createJob` fires
     *  `GenerationJobCreated` synchronously on commit, which in this real, fully CDI-wired
     *  `@QuarkusTest` triggers `GenerationJobProcessor`'s real fast-path publish on a background
     *  executor thread — a genuine second writer racing this test's own invoice row (it sets
     *  `Invoice.validationStatus = PENDING`, confirmed empirically) that would otherwise confound
     *  the single, deliberate concurrent write this test needs to control precisely. */
    private fun persistInvoiceWithJob(number: String): Pair<Long, Long> = QuarkusTransaction.requiringNew().call<Pair<Long, Long>> {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "SS${number.takeLast(4)}-"
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
            code = "SS_TEST_19_$number"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            // N5 — DocumentGeneratedReactionHandler.onDocumentGeneratedUpdateValidationStatus only
            // runs off the DocumentGenerated event, which GenerationJobService now gates on
            // status.isIssued (not invoiceNumber.isAssigned() — every invoice is numbered from
            // birth since N5); without this, the event never fires and validationStatus never
            // leaves PENDING below.
            forceStatusForTest(InvoiceStatus.SENT)
            invoiceDate = LocalDate.now()
            senderProfile = sender
            this.recipient = recipient
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
            // a DELEGATED job only ever exists for an invoice DocumentJobPublisher.publish
            // already moved to PENDING; this fixture builds the job directly (bypassing publish,
            // see this method's own KDoc), so it has to set the same precondition by hand for
            // Invoice.transitionValidationStatusTo's PENDING -> VALID edge below to be legal.
            validationStatus = ValidationStatus.PENDING
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
        val job = GenerationJob().apply {
            this.invoice = invoice
            this.jobType = "PDF_EXPORT"
            this.status = GenerationJobStatus.DELEGATED
            this.createdAt = OffsetDateTime.now()
        }
        em.persist(job)
        em.flush()
        invoice.id!! to job.id!!
    }

    /**
     * Both read helpers are deliberately WRITE-FREE observers, two layers deep:
     *
     * 1. They clear the request-scoped EM first: this test DELIBERATELY poisons that EM with a
     *    failed flush (that is its subject), and under a full-suite run the poisoned session can
     *    leak into these fresh transactions and fail their commit (ARJUNA016053) — the exact
     *    session-hygiene hazard the class KDoc describes, applied to the test itself.
     * 2. They read scalar projections instead of hydrating entities (L19, the full-run flake
     *    "expected VALID but was PENDING"): merely LOADING an [Invoice] whose eight
     *    `invoice_references` columns are all null (as this fixture's are) leaves the entity
     *    phantom-dirty — [Invoice]'s `@PostLoad` (`initializeEmbeddablesAfterLoad`) swaps
     *    Hibernate's null composite for a fresh [de.littletrotter.domain.invoice
     *    .InvoiceReferences] instance, which the dirty check then reports as a change — so each
     *    former `em.find`-based poll's commit flushed a full-column UPDATE that bumped the row's
     *    `@Version`. Whenever such a poll commit landed inside [de.littletrotter.application
     *    .invoice.DocumentGeneratedReactionHandler.onDocumentGeneratedUpdateValidationStatus]'s
     *    single-shot load-to-commit window (an async observer, scheduled noticeably later under
     *    full-suite load, which is why the flake never showed isolated), the observer's
     *    version-checked VALID write failed and was swallowed by design — never retried —
     *    leaving the invoice PENDING past every remaining poll. A projection never instantiates
     *    the entity, so no `@PostLoad`, nothing dirty, nothing written: the assertion phase can
     *    no longer shoot down the very observer commit it is waiting for.
     */
    private fun statusOf(jobId: Long): GenerationJobStatus =
        QuarkusTransaction.requiringNew().call<GenerationJobStatus> {
            em.clear()
            em.createQuery("select j.status from GenerationJob j where j.id = :id", GenerationJobStatus::class.java)
                .setParameter("id", jobId)
                .singleResult
        }

    private fun validationStatusOf(invoiceId: Long): ValidationStatus =
        QuarkusTransaction.requiringNew().call<ValidationStatus> {
            em.clear()
            em.createQuery("select i.validationStatus from Invoice i where i.id = :id", ValidationStatus::class.java)
                .setParameter("id", invoiceId)
                .singleResult
        }

    /**
     * Fix 2026-08-30 (multi-job PENDING resolution) — the invoice's validationStatus is now
     * resolved by [de.littletrotter.application.invoice.DocumentGeneratedReactionHandler]'s
     * ASYNC observer, i.e. eventually consistent (contract: within 2 seconds, same bound
     * `InvoiceValidationStatusResolutionE2EIT` pins), so this awaits instead of reading once.
     * The assertion stays exact: the expected status still has to appear within the contract
     * window. L19 later proved awaiting alone was only half the fix — the polls themselves used
     * to WRITE (phantom-dirty entity load, see the read helpers' shared KDoc) and could kill the
     * observer's one and only commit; the helpers are scalar projections now, so every poll is
     * genuinely read-only. The catch below is retained as pure robustness against transient
     * infrastructure hiccups — since the projection change a poll no longer has anything of its
     * own to flush, so a failure here is no longer expected on any known path.
     */
    private fun awaitValidationStatus(invoiceId: Long, expected: ValidationStatus, timeoutMs: Long = 2_000): ValidationStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: ValidationStatus? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                last = validationStatusOf(invoiceId)
                if (last == expected) return last
            } catch (_: Exception) {
                // transient infrastructure hiccup — treat as "not yet" and retry with a fresh session
            }
            Thread.sleep(100)
        }
        return last ?: validationStatusOf(invoiceId)
    }

    @Test
    fun `a stale invoice cached in the request context survives a failed flush and does not block the consumer from completing the job`() {
        val (invoiceId, jobId) = persistInvoiceWithJob("ITSS-2026-0001")

        // Producer side of AP-M6: the KoSIT report travels via Valkey, referenced by key on the
        // wire — see resolveKositReport's own KDoc.
        val kositReportKey = "lt:kosit:$jobId"
        val kositReportJson = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.VALID, summary = null, details = emptyList())
        )
        payloadExchange.put(kositReportKey, kositReportJson, 60)

        val payload = mapper.writeValueAsString(
            mapOf(
                "id" to jobId.toString(),
                "status" to "COMPLETED",
                "resultRefs" to mapOf(
                    "objectKey" to "invoices/$invoiceId.pdf",
                    "downloadUrl" to "http://rustfs:9000/little-trotter-documents/invoices/$invoiceId.pdf",
                    "zugferdObjectKey" to "invoices/$invoiceId.xml",
                    "llmStatus" to "NOT_REQUESTED",
                    "formatGuideline" to "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0",
                ),
                "kositReportRef" to mapOf("key" to kositReportKey),
                "correlationId" to "corr-$jobId",
            )
        )

        val requestContext = Arc.container().requestContext()
        requestContext.activate()
        try {
            // Step 1 — load the invoice into THIS request's persistence context, simulating the
            // ambient session GenerationJobNatsAdapter.receive's request-context activation
            // leaves behind (structural finding #2 in the incident writeup: the JetStream
            // dispatch thread can carry one long-lived session across messages/transactions).
            val cachedInvoice = em.find(Invoice::class.java, invoiceId)
            val versionAtLoad = cachedInvoice.version

            // Step 2 — a concurrent writer (e.g. another job-creation transaction touching the
            // same invoice) bumps the row's version in the DB directly, bypassing this session's
            // first-level cache entirely — the cached copy above never learns about it.
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("UPDATE invoice SET version = version + 1 WHERE id = :id")
                    .setParameter("id", invoiceId)
                    .executeUpdate()
            }

            // Step 3 — a genuine, uncooked write against the now-stale managed copy: proves
            // Hibernate really does detect the conflict here (not a simulated exception).
            assertGenuineOptimisticLockFailure(cachedInvoice)

            // Step 4 — the failed, rolled-back write does NOT detach or refresh the entity: the
            // session hygiene hazard this fix defends against, proven directly rather than
            // inferred from consume()'s eventual outcome (see this class's own KDoc for why).
            assertStillPoisoned(cachedInvoice, versionAtLoad)

            // Step 5 — process the COMPLETED result from WITHIN this same, still-poisoned request
            // context, exactly like GenerationJobNatsAdapter.receive does today.
            generationJobResultConsumer.consume(payload)
        } finally {
            requestContext.terminate()
        }

        assertEquals(
            GenerationJobStatus.COMPLETED,
            statusOf(jobId),
            "the job must reach COMPLETED even though its request context's session held a stale, poisoned invoice copy",
        )
        assertEquals(
            ValidationStatus.VALID,
            awaitValidationStatus(invoiceId, ValidationStatus.VALID),
            "the invoice's validationStatus must be updated from the resolved KoSIT report, not left at its stale pre-completion value",
        )
    }

    /** Forces a real flush against [invoice] with its currently-stale in-memory `@Version` and
     *  asserts it genuinely fails — the DB row was already moved on by the Step-2 writer above.
     *
     * dirties [Invoice.freeText] rather than [Invoice.validationStatus] to trigger the
     *  flush: any field would do (this method only needs *something* dirty so Hibernate reissues
     *  the version-checked `UPDATE`), but [Invoice.validationStatus] now goes through
     * [Invoice.transitionValidationStatusTo]'s -style guard. Writing `INVALID` directly
     *  here left the poisoned in-memory copy carrying an illegal precondition
     *  (`INVALID -> VALID` is not a legal edge — see [de.littletrotter.domain.shared
     *  .ValidationStatus.canTransitionTo]'s own KDoc) for the real completion path this test
     *  exercises below, which never reflects a reachable production state: a field with no
     *  domain invariant is the correct choice for a "make this entity dirty" helper. */
    private fun assertGenuineOptimisticLockFailure(invoice: Invoice) {
        try {
            QuarkusTransaction.requiringNew().run {
                invoice.freeText = "stale-session-it-poison"
                em.merge(invoice)
                em.flush()
            }
            fail<Unit>("expected a genuine optimistic-lock conflict — the row was concurrently bumped in Step 2")
        } catch (e: Exception) {
            // Expected: org.hibernate.StaleObjectStateException / jakarta.persistence
            // .OptimisticLockException, exactly the exception class production hit.
        }
    }

    /** The hazard itself: after the failed flush above rolls back, [invoice] must still be the
     *  SAME managed, stale instance in this request's persistence context — not detached, not
     *  refreshed to the current DB row. */
    private fun assertStillPoisoned(invoice: Invoice, versionAtLoad: Long?) {
        assertTrue(em.contains(invoice), "the entity must still be managed after the rolled-back flush — that's the hazard")
        assertEquals(versionAtLoad, invoice.version, "the managed copy's version must still reflect its stale, pre-conflict read")
    }

    /**
     * The real, CDI-wired [GenerationJobNatsAdapter] — not a hand-instantiated one — actually
     * activates and tears down a genuine ArC request context per message: exercises
     * [GenerationJobNatsAdapter]'s default `requestContextFactory` (the real
     * `Arc.container().requestContext()` path `GenerationJobNatsAdapterTest`'s plain-unit tests
     * can never reach, since they construct the adapter outside any running ArC container — see
     * that field's own KDoc). A minimal payload with no numeric id is enough: `consume` returns
     * immediately after logging a warning, so this proves the request-context activate/`.use`
     * teardown machinery itself without touching Postgres/Valkey/DB state at all.
     */
    @Test
    fun `the real ArC-wired adapter processes a message through its own default request-context factory`() {
        val msg: Message = mock()
        whenever(msg.data).thenReturn("{}".toByteArray(Charsets.UTF_8))

        generationJobNatsAdapter.receive(msg)

        verify(msg).ack()
    }
}
