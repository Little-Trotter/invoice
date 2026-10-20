package de.littletrotter.application.invoice

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.NatsStreamSetup
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

/**
 * [GenerationJobFastPublishTransaction.publishOne] records [GenerationJob.delegatedStreamSeq]
 * synchronously against a real Postgres and a real NATS broker: the exact
 * [io.nats.client.api.PublishAck.seqno] this publish's own message landed at is already committed
 * by the time [GenerationJobFastPublishTransaction.publishOne] returns — no polling/await needed, unlike
 * the coordinator-reviewed fire-and-forget design this replaced (see the progress log).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class DocumentJobPublishTransactionDelegatedSeqIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobFastPublishTransaction: GenerationJobFastPublishTransaction

    @ConfigProperty(name = "nats.url")
    lateinit var natsUrl: String

    private fun persistInvoice(number: String): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "DS${number.takeLast(4)}-"
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
            code = "DS_TEST_19_$number"
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

    /** A plain `PENDING` job — not [GenerationJobService.createJob] — so this test drives
     *  [GenerationJobFastPublishTransaction.publishOne] directly, once, deterministically, rather than
     *  racing the real CDI event's own asynchronous dispatch. */
    private fun persistPendingJob(invoiceId: Long): GenerationJob = QuarkusTransaction.requiringNew().call<GenerationJob> {
        val job = aGenerationJob {
            invoice = em.getReference(Invoice::class.java, invoiceId)
            jobType = "PDF_EXPORT"
        }
        em.persist(job)
        em.flush()
        job
    }

    private fun reload(jobId: Long): GenerationJob = QuarkusTransaction.requiringNew().call<GenerationJob> {
        em.find(GenerationJob::class.java, jobId)
    }

    @Test
    fun `publishOne records the real PublishAck seqno as delegatedStreamSeq synchronously`() {
        val invoice = persistInvoice("ITDS-2026-0001")
        val job = persistPendingJob(invoice.id!!)

        generationJobFastPublishTransaction.publishOne(job.id!!)

        // No waitUntil/polling: publishOne's own transaction has already committed both the
        // DELEGATED claim and the delegatedStreamSeq record by the time it returns.
        val reloaded = reload(job.id!!)
        assertEquals(GenerationJobStatus.DELEGATED, reloaded.status, "a successful publish must leave the job DELEGATED")
        val seq = reloaded.delegatedStreamSeq
        assertTrue(seq != null && seq > 0, "delegatedStreamSeq must already be recorded once publishOne returns")

        Nats.connect(natsUrl).use { conn ->
            assertTrue(
                runCatching { conn.jetStreamManagement().getMessage(NatsStreamSetup.STREAM_NAME, seq!!) }.isSuccess,
                "the recorded sequence must actually resolve to a real message in the stream",
            )
        }
    }
}
