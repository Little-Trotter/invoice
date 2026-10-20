package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.TempDocumentStoreResource
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.quote.QuoteAttachmentQueryService
import de.littletrotter.application.quote.QuoteDetailQueryService
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent
import de.littletrotter.application.quote.QuoteDocumentFileQueryService
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.QuoteDocumentQueryService
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.quote.InvoiceCreatedFromQuoteEvent
import de.littletrotter.shared.quote.InvoiceCreationFromQuoteRejectedEvent
import de.littletrotter.shared.quote.QuoteInvoiceOrderRequested
import de.littletrotter.shared.quote.QuoteStreams
import de.littletrotter.shared.quote.QuoteSubjects
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.ui.view.quote.quoteDetail
import io.nats.client.JetStream
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Turns the offer module on, so the acceptance stream, the reply stream and the consumer exist. */
class QuoteEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("quote.enabled" to "true")
}

/**
 * The acceptance subscription end to end against real JetStream and Postgres: an event on
 * `quote.invoice-order.requested` becomes one invoice draft and a reply on the reply stream, a duplicate delivery
 * is answered again without a second invoice, and a refusal reaches the offer service and the
 * message center.
 */
@QuarkusTest
@TestProfile(QuoteEnabledProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class QuoteInvoiceOrderConsumerIT {

    companion object {
        private val counter = AtomicInteger(0)
        private val WAIT = Duration.ofSeconds(60)
    }

    @Inject
    lateinit var jetStream: JetStream

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var featureGate: FeatureSettingsGate

    @Inject
    lateinit var customers: CustomerManagementService

    @Inject
    lateinit var sellerProfiles: SellerProfileService

    @Inject
    lateinit var senderProfiles: SenderProfileRepository

    private val details: QuoteDetailQueryService = mock()
    private val documents: QuoteDocumentQueryService = mock()
    private val documentFiles: QuoteDocumentFileQueryService = mock()
    private val attachments: QuoteAttachmentQueryService = mock()

    @BeforeEach
    fun installQuoteMocks() {
        QuarkusMock.installMockForType(details, QuoteDetailQueryService::class.java)
        QuarkusMock.installMockForType(documents, QuoteDocumentQueryService::class.java)
        QuarkusMock.installMockForType(documentFiles, QuoteDocumentFileQueryService::class.java)
        QuarkusMock.installMockForType(attachments, QuoteAttachmentQueryService::class.java)
        QuarkusTransaction.requiringNew().run {
            if (senderProfiles.findDefault() == null) {
                aSenderProfile {
                    name = "Standardabsender"
                    invoicePrefix = "QCON-"
                    email = Email("default${System.nanoTime()}@example.invalid")
                    taxNumber = TaxNumber("12/345/67890")
                    vatId = VatId("DE123456789")
                    iban = Iban("DE89370400440532013000")
                    registerCourt = "Amtsgericht Test"
                    phone = "+49 30 1234567"
                }.also { sellerProfiles.save(it, defaults = SellerDefaultSelections()) }
            }
        }
    }

    private fun acceptedEvent(quoteNumber: String) = QuoteInvoiceOrderRequested(
        orderId = UUID.randomUUID(),
        quoteId = UUID.randomUUID(),
        quoteNumber = quoteNumber,
        acceptedOn = LocalDate.of(2026, 10, 5),
        customerOrderReference = null,
        deliveryDate = null,
        servicePeriodFrom = null,
        servicePeriodTo = null,
        actor = "angebots-sachbearbeiter",
        occurredAt = Instant.parse("2026-10-05T08:00:00Z"),
    )

    private fun givenQuote(event: QuoteInvoiceOrderRequested, withDocument: Boolean) {
        val n = counter.incrementAndGet()
        val customerId = QuarkusTransaction.requiringNew().call<Long> {
            aRecipient {
                name = "Kunde $n"
                email = Email("consumer$n${System.nanoTime()}@example.invalid")
                customerType = CustomerType.CONSUMER
                vatId = VatId("DE987654321")
            }.also { customers.save(it) }.id!!
        }
        val id = event.quoteId.toString()
        whenever(details.find(id)).thenReturn(quoteDetail(id = id) { it.copy(party = it.party.copy(customerId = customerId.toString()), b2bValues = emptyList()) })
        whenever(documents.documents(id)).thenReturn(
            if (withDocument) listOf(QuoteDocument("d1", QuoteDocumentMark.DISPATCH, QuoteDocumentStatus.COMPLETED, Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T10:00:05Z"))) else emptyList(),
        )
        whenever(documentFiles.pdf(id, "d1")).thenReturn(QuoteDocumentContent("angebot.pdf", "%PDF-1.4\n% ${UUID.randomUUID()}\n".toByteArray()))
        whenever(attachments.attachments(id)).thenReturn(emptyList())
    }

    private fun publish(event: QuoteInvoiceOrderRequested) {
        jetStream.publish(QuoteSubjects.INVOICE_ORDER_REQUESTED, QuoteInvoiceOrderRequested.encode(event))
    }

    private fun <T> await(what: String, probe: () -> T?): T {
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(250)
        }
        error("Timed out waiting for $what")
    }

    private fun <T> awaitOrReport(what: String, state: () -> String, probe: () -> T?): T =
        try {
            await(what, probe)
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message} - ${state()}", e)
        }

    private fun invoiceCount(event: QuoteInvoiceOrderRequested): Long =
        (em.createNativeQuery("SELECT count(*) FROM invoice WHERE source_quote_id = :q").setParameter("q", event.quoteId).singleResult as Number).toLong()

    private fun outboxRows(event: QuoteInvoiceOrderRequested): List<String> =
        em.createNativeQuery("SELECT subject || ':' || status || ':' || attempts || ':' || coalesce(last_error, '') FROM outbox WHERE aggregate_id = :a ORDER BY id")
            .setParameter("a", event.quoteId.toString()).resultList.map { it.toString() }

    /** Payloads on [subject] of the reply stream, read by a throw-away consumer. */
    private fun replies(subject: String): List<ByteArray> {
        val subscription = jetStream.subscribe(
            subject,
            PullSubscribeOptions.builder().stream(QuoteStreams.INVOICE_REPLY)
                .configuration(ConsumerConfiguration.builder().ackPolicy(AckPolicy.None).deliverPolicy(DeliverPolicy.All).build())
                .build(),
        )
        try {
            return subscription.fetch(500, Duration.ofSeconds(2)).map { it.data }
        } finally {
            subscription.unsubscribe()
        }
    }

    private fun createdReplies(event: QuoteInvoiceOrderRequested) =
        replies(QuoteSubjects.INVOICE_CREATED).map { InvoiceCreatedFromQuoteEvent.decode(it) }.filter { it.quoteId == event.quoteId }

    private fun rejectedReplies(event: QuoteInvoiceOrderRequested) =
        replies(QuoteSubjects.INVOICE_REJECTED).map { InvoiceCreationFromQuoteRejectedEvent.decode(it) }.filter { it.quoteId == event.quoteId }

    @Test
    fun `a duplicate delivery creates one invoice and is answered twice`() {
        val event = acceptedEvent("AN-IT-DUP-${counter.incrementAndGet()}")
        givenQuote(event, withDocument = true)

        publish(event)
        publish(event)

        val answers = awaitOrReport("two created replies", { "invoices=${invoiceCount(event)}, outbox=${outboxRows(event)}" }) { createdReplies(event).takeIf { it.size >= 2 } }
        assertEquals(2, answers.size)
        assertEquals(1, answers.map { it.invoiceId }.toSet().size)
        assertEquals(event.orderId, answers.first().eventId)
        assertEquals(1, invoiceCount(event))
    }

    @Test
    fun `an acceptance published while the module is switched off still becomes an invoice draft`() {
        val event = acceptedEvent("AN-IT-OFF-${counter.incrementAndGet()}")
        givenQuote(event, withDocument = true)
        setQuoteFlag(false)
        try {
            publish(event)

            val answers = await("a created reply") { createdReplies(event).takeIf { it.isNotEmpty() } }
            assertEquals(event.orderId, answers.first().eventId)
            assertEquals(1, invoiceCount(event))
        } finally {
            setQuoteFlag(true)
        }
    }

    private fun setQuoteFlag(enabled: Boolean) {
        QuarkusTransaction.requiringNew().run { featureGate.setEnabled(FeatureFlag.QUOTE, enabled, "it") }
    }

    @Test
    fun `a refused acceptance reaches the offer service and the message center`() {
        val event = acceptedEvent("AN-IT-REJ-${counter.incrementAndGet()}")
        givenQuote(event, withDocument = false)

        publish(event)

        val answers = await("a rejected reply") { rejectedReplies(event).takeIf { it.isNotEmpty() } }
        assertEquals("Das Angebotsdokument (PDF) liegt nicht vor.", answers.single().reason)
        val shown = await("the message center row") {
            (em.createNativeQuery("SELECT count(*) FROM system_message WHERE resolved_text LIKE :t").setParameter("t", "%${event.quoteNumber}%").singleResult as Number).toLong()
                .takeIf { it > 0 }
        }
        assertTrue(shown > 0)
        assertEquals(0, invoiceCount(event))
    }
}
