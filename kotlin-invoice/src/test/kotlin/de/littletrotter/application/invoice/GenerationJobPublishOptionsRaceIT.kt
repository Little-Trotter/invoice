package de.littletrotter.application.invoice

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.ValkeyTestResource
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import io.nats.client.Connection
import io.nats.client.Nats
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Per the outage analysis of 26.08. §4, before this fix, the UI print button published directly
 * with the dialog's `llmOptimize` choice while the fast path
 * ([GenerationJobProcessor.publishOne]) and the poller republish
 * ([GenerationJobProcessor.pollPendingJobs]) published the same job WITHOUT those values;
 * JetStream's dedup ("job-$id") kept whichever message won the race, silently dropping the AI
 * checkbox choice whenever the fast path won.
 *
 * The fix persists `llmOptimize` on [GenerationJob] itself at
 * [GenerationJobCreationService.createJob] time and collapses every publish call site onto the single
 * [DocumentJobPublisher.publish] overload that reads them off the job — this test proves that by
 * racing [GenerationJobProcessor.publishOne]'s fast path (scheduled automatically by createJob's
 * transaction commit) against an explicit, synchronous [GenerationJobProcessor.pollPendingJobs]
 * call for the very same `PENDING`->`DELEGATED` claim (same technique as
 * [GenerationJobFastPublishIT]'s `concurrent poller and fast path publish exactly once`) and
 * asserting the payload that actually reaches NATS — whichever side wins — carries the choice.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(ValkeyTestResource::class)
class GenerationJobPublishOptionsRaceIT {

    private val mapper = jacksonObjectMapper()

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobProcessor: GenerationJobProcessor

    @Inject
    lateinit var validationSettingsGate: ValidationSettingsGate

    @ConfigProperty(name = "nats.url")
    lateinit var natsUrl: String

    private fun natsConnection(): Connection = Nats.connect(natsUrl)

    /** Same shape as [GenerationJobFastPublishIT]'s own helper, . */
    private fun persistInvoice(number: String): Invoice =
        QuarkusTransaction.requiringNew().call<Invoice> {
            val sender = aSenderProfile {
                name = "Muster GmbH"
                email = Email("billing@muster.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                registerCourt = "AG Musterstadt"
                invoicePrefix = "${number.take(4)}-${number.takeLast(4)}-"
                // Exactly the footer fields the UI's now-deleted-from-the-publish-path
                // `dtoToInvoice` reconstruction once lost on the way to the printed PDF (outage
                // analysis, "four footer fields already went missing on a PDF once").
                managingDirector = "Erika Musterfrau"
                bankName = "Musterbank"
                bic = Bic("MUSTDEXXFRA")
            }
            val recipient = aRecipient {
                customerNumber = CustomerNumber("KD-$number")
                name = "Kunde AG"
                email = Email("kunde@kunde-ag.example")
                vatId = VatId("DE987654321")
                iban = Iban("DE02120300000000202051")
            }
            val taxRate = TaxRate().edit {
                name = "19%"
                rate = TaxRatePercent(BigDecimal("19.00"))
                code = "H27_3_TEST_19_$number"
                category = "S"
                isSelectable = false
            }
            em.persist(sender)
            em.persist(recipient)
            em.persist(taxRate)
            val item = anInvoiceItem(
                positionId = "1",
                description = "Beratung",
                quantity = BigDecimal("1.00"),
                unitPriceNet = Money(BigDecimal("100.00")),
                taxRateRef = taxRate,
            )
            val invoice = anInvoice(
                invoiceNumber = InvoiceNumber(number),
                invoiceDate = LocalDate.now(),
                senderProfile = sender,
                recipient = recipient,
                items = listOf(item),
                recalculate = true,
            )
            em.persist(invoice)
            em.flush()
            invoice
        }

    @Test
    fun `H27-3 - a job created with the LLM flag renders with it regardless of which publish path wins the race`() {
        // OMLX_LLM_CHECK ships opt-in (seeded FALSE) — flip it on so a truthy
        // GenerationJob.llmOptimize actually reaches the wire's `options.llmOptimize` (the AND
        // documented on DocumentJobPublishTransaction.publishTransactional), matching the other gate tests.
        validationSettingsGate.setEnabled(de.littletrotter.domain.reference.ValidationCapabilityId.OMLX_LLM_CHECK, true)
        val invoice = persistInvoice("H273-2026-0001")
        val conn = natsConnection()
        try {
            val delivered = CountDownLatch(1)
            val body = AtomicReference<ByteArray>()
            conn.createDispatcher { msg ->
                body.set(msg.data)
                delivered.countDown()
            }.subscribe("document-jobs")

            // createJob()'s transaction commit already scheduled the fast path
            // (onGenerationJobCreated -> managedExecutor -> publishOne) onto a worker thread by
            // the time this call returns. Calling pollPendingJobs() synchronously right here races
            // it against that already-in-flight fast path for the same PENDING->DELEGATED claim —
            // exactly the scenario tryMarkDelegated exists to guard, and exactly the race the
            // 26.08.2026 outage exposed for llmOptimize specifically.
            generationJobCreationService.createJob(
                invoice, "PRINT_JOB",
                DocumentJobLane.BULK,
                llmOptimize = true,
            )
            generationJobProcessor.pollPendingJobs()

            assertTrue(delivered.await(10, TimeUnit.SECONDS), "neither the fast path nor the poller delivered a message in time")

            val root = mapper.readTree(body.get())
            assertTrue(
                root.path("options").path("llmOptimize").asBoolean(),
                "the AI-check choice made at createJob time must reach NATS regardless of which publish path won the race",
            )
        } finally {
            conn.close()
        }
    }

    /**
     * The print job used to be published from `InvoiceExportPanel`'s own `Invoice`
     * (`ui/view/invoice/InvoiceDetailView.dtoToInvoice`, a hand-mapped, partial rebuild of
     * `InvoiceDetailDto`), not the repository's own state; a mapping that lagged behind the DTO
     * lost fields on the way to the printed PDF (four footer fields, once). [DocumentJobPublisher
     * .publish] now maps exactly [de.littletrotter.domain.invoice.GenerationJob.invoice] — the
     * entity [GenerationJobCreationService.createJob] itself just loaded through the application layer
     * — so this proves the published payload's sender footer data equals what is actually
     * persisted, regardless of what any UI-side copy might have contained.
     */
    @Test
    fun `H27-2 - the published payload's sender footer data equals the repository state`() {
        val invoice = persistInvoice("H272-2026-0001")
        val conn = natsConnection()
        try {
            val delivered = CountDownLatch(1)
            val body = AtomicReference<ByteArray>()
            conn.createDispatcher { msg ->
                body.set(msg.data)
                delivered.countDown()
            }.subscribe("document-jobs")

            generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

            assertTrue(delivered.await(10, TimeUnit.SECONDS), "the job was not delivered to NATS in time")

            val senderNode = mapper.readTree(body.get())
                .path("payload").path("invoice").path("senderProfile")
            assertEquals("Erika Musterfrau", senderNode.path("managingDirector").asText())
            assertEquals("Musterbank", senderNode.path("bankName").asText())
            assertEquals("MUSTDEXXFRA", senderNode.path("bic").asText())
        } finally {
            conn.close()
        }
    }
}
