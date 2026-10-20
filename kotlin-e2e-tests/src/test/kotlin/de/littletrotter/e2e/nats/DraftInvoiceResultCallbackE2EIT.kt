package de.littletrotter.e2e.nats

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.resolveValidationStatusFromKositReport
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.ValkeyTestResource
import de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * L2 (`fix-l2-job-result-writeback-stale-version.md`) / L25
 * (`fix-l25-business-stage-never-evaluated.md`) — the live diagnosis's own precondition, driven
 * end to end for the first time: "the affected invoice was a **draft** — the conflict therefore
 * does not need an issued document to appear." Every prior automated test either used a mock/fake
 * [de.littletrotter.domain.repository.GenerationJobRepository] (no real Hibernate session, so no
 * detached-entity hazard at all — see [NatsRoundTripE2EIT]'s own KDoc for why this module cannot
 * boot the full app inside its NATS/Testcontainers harness) or a finalized (ISSUED) invoice.
 *
 * This drives the REAL, CDI-injected [GenerationJobResultConsumer] — real
 * [GenerationJobService], real Postgres, real `EntityManager.clear()` between attempts
 * ([GenerationJobResultConsumer.clearSessionBeforeAttempt]) — directly with `consume(...)`, the
 * same convention [InvoiceValidationStatusResolutionE2EIT] and [PrintJobListViewLiveUpdateE2EIT]
 * already use for this package (NATS transport itself is not under test here, only the
 * consumer/service/persistence chain a real message hands off to).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ValkeyTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class DraftInvoiceResultCallbackE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultConsumer: GenerationJobResultConsumer

    @Inject
    lateinit var payloadExchange: PayloadExchange

    private val mapper = jacksonObjectMapper()

    /** A genuinely DRAFT invoice — never transitioned to IN_REVIEW/ISSUED, matching the live
     *  diagnosis's own precondition. [Invoice.validationStatus] is set directly to
     *  [ValidationStatus.PENDING], the state a real worker delegation leaves it in, since this
     *  fixture never goes through the real publish path that would otherwise set it. */
    private fun buildDraftInvoice(tag: String): Long {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            val taxRate = TaxRate().edit {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate)
            em.flush()
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run {
            em.find(Invoice::class.java, invoiceId)!!.transitionValidationStatusTo(ValidationStatus.PENDING)
        }
        return invoiceId
    }

    private fun createAndDelegateJob(invoiceId: Long): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
            generationJobPublishingService.markDelegated(job)
            job.id!!
        }

    private fun jobInDb(jobId: Long): GenerationJob =
        QuarkusTransaction.requiringNew().call<GenerationJob> {
            em.clear()
            em.find(GenerationJob::class.java, jobId)
        }

    private fun validationStatusInDb(invoiceId: Long): ValidationStatus =
        QuarkusTransaction.requiringNew().call<ValidationStatus> {
            em.clear()
            em.find(Invoice::class.java, invoiceId).validationStatus
        }

    @Test
    fun `L2-L25 - a draft invoice's COMPLETED result completes end to end with a real, evaluated BUSINESS stage, no DLQ`() {
        val invoiceId = buildDraftInvoice("E2EDR1")
        val jobId = createAndDelegateJob(invoiceId)

        // Real worker shape: SYNTAX/KOSIT already evaluated, BUSINESS still the always-`skipped`
        // placeholder this fix replaces with a real evaluation.
        val stages = listOf(
            ValidationStageResult("SYNTAX", KositOutcome.VALID),
            ValidationStageResult("KOSIT", KositOutcome.VALID),
            ValidationStageResult("BUSINESS", KositOutcome.VALID, skipped = true, skipReason = "worker cannot evaluate compliance"),
        )
        val run = ValidationRun(stages = stages, overall = ValidationRun.overallOutcomeOf(stages), startedAt = "2026-09-07T00:00:00Z")
        val kositReportKey = "lt:kosit:$jobId"
        payloadExchange.put(kositReportKey, KositReportPayload.encode(KositValidationResult(status = run.overall, validationRun = run)), 60)

        val payload = mapper.writeValueAsString(
            mapOf(
                "id" to jobId.toString(),
                "status" to "COMPLETED",
                "resultRefs" to mapOf("pdf" to "s3://bucket/$jobId.pdf"),
                "kositReportRef" to mapOf("key" to kositReportKey),
            ),
        )

        // The real chain: GenerationJobResultConsumer clears its session before this (and every)
        // attempt, so `job` is genuinely detached by the time markCompleted's own transaction
        // reloads it — exactly the live diagnosis's own mechanism, for a draft invoice.
        generationJobResultConsumer.consume(payload)

        val job = jobInDb(jobId)
        assertEquals(
            GenerationJobStatus.COMPLETED, job.status,
            "L2 - a draft's own result callback must reach COMPLETED, never fall into the DLQ retry loop",
        )
        assertNull(job.lastError, "L2 - a clean callback must never leave a give-up reason behind")

        val mergedRun = KositReportPayload.decode(job.kositReport)!!.validationRun!!
        val businessStage = mergedRun.stages.single { it.stage == "BUSINESS" }
        assertEquals(
            false, businessStage.skipped,
            "L25 - the BUSINESS stage must be evaluated for real for a draft invoice too, not left the worker's placeholder",
        )
    }

    @Test
    fun `L2 - a draft invoice's FAILED result clears its PENDING validation status through the real detached job path, no DLQ`() {
        val invoiceId = buildDraftInvoice("E2EDR2")
        val jobId = createAndDelegateJob(invoiceId)

        assertEquals(
            ValidationStatus.PENDING, validationStatusInDb(invoiceId),
            "fixture precondition: the invoice must start out PENDING (\"Validierung läuft\")",
        )

        val payload = mapper.writeValueAsString(
            mapOf(
                "id" to jobId.toString(),
                "status" to "FAILED",
                "resultRefs" to mapOf("error" to "KoSIT service unreachable"),
            ),
        )

        // markFailed's clearPendingValidationStatus write is unconditional (unlike the
        // DocumentGenerated event, it does not require invoiceNumber.isAssigned()) — exactly the
        // write L2's live diagnosis traced the draft-invoice StaleObjectStateException to.
        generationJobResultConsumer.consume(payload)

        val job = jobInDb(jobId)
        assertEquals(GenerationJobStatus.FAILED, job.status)
        assertNull(job.lastError, "a FAILED worker result is a normal terminal outcome, not a processing failure — recordLastError/DLQ must never fire")
        assertEquals(
            ValidationStatus.UNAVAILABLE, validationStatusInDb(invoiceId),
            "L2 - the draft invoice's own PENDING validation status must be cleared, surviving the real detached-job write-back path",
        )
    }

    @Test
    fun `a draft invoice's COMPLETED result resolves its PENDING header status to the job's own outcome`() {
        val invoiceId = buildDraftInvoice("E2EDR3")
        val jobId = createAndDelegateJob(invoiceId)
        assertEquals(ValidationStatus.PENDING, validationStatusInDb(invoiceId), "fixture precondition: the draft starts out PENDING")

        val stages = listOf(
            ValidationStageResult("SYNTAX", KositOutcome.VALID),
            ValidationStageResult("KOSIT", KositOutcome.VALID),
        )
        val run = ValidationRun(stages = stages, overall = ValidationRun.overallOutcomeOf(stages), startedAt = "2026-10-15T00:00:00Z")
        val kositReportKey = "lt:kosit:$jobId"
        payloadExchange.put(kositReportKey, KositReportPayload.encode(KositValidationResult(status = run.overall, validationRun = run)), 60)
        val payload = mapper.writeValueAsString(
            mapOf(
                "id" to jobId.toString(),
                "status" to "COMPLETED",
                "resultRefs" to mapOf("pdf" to "s3://bucket/$jobId.pdf"),
                "kositReportRef" to mapOf("key" to kositReportKey),
            ),
        )

        generationJobResultConsumer.consume(payload)

        // The status write is an async reaction to the completion event, so poll briefly.
        val deadlineMs = System.currentTimeMillis() + 5_000
        var resolved = validationStatusInDb(invoiceId)
        while (resolved == ValidationStatus.PENDING && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(20)
            resolved = validationStatusInDb(invoiceId)
        }

        assertNotEquals(
            ValidationStatus.PENDING, resolved,
            "a draft's header must not keep showing \"Validierung läuft\" once its job completed",
        )
        // The e-invoice tab reads the job's stored report; the header must agree with it.
        assertEquals(resolveValidationStatusFromKositReport(jobInDb(jobId).kositReport), resolved)
    }
}
