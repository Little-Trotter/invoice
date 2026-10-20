package de.littletrotter.application.invoice

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.test.NatsTestContainersExtension
import de.littletrotter.test.asJsonMap
import de.littletrotter.test.asJsonMapList
import io.nats.client.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Integration tests verifying that [DocumentJobPublisher] writes correctly structured
 * JSON job messages to a real NATS broker (Testcontainers).
 *
 * Verifies:
 *   - The message lands on the configured subject
 *   - JSON structure matches what kotlin-xpdf JobConsumer expects
 *   - Invoice fields (number, sender, recipient, dates, items) are serialized correctly
 *   - The correlationId NATS header is present on every message
 *   - The internal sent-counter increments with each successful publish
 *   - Multiple invoices produce independent messages
 */
@ExtendWith(NatsTestContainersExtension::class)
class DocumentJobPublisherIT {

    private val mapper = jacksonObjectMapper()

    @Test
    fun publishedMessageAppearsOnNatsWithCorrectInvoiceStructure() {
        val subject = "publisher-it-basic-${shortId()}"
        val publisher = publisherFor(subject)

        val invoice = testInvoice(id = 1L, invoiceNumber = "INV-2024-0001")
        publisher.publish(invoice, 1L).get(10, TimeUnit.SECONDS)

        val record = pollMessage(subject)
        assertNotNull(record, "Message must arrive on subject '$subject'")

        val payload = mapper.readValue<Map<String, Any?>>(record!!.data)

        assertEquals("1", payload["id"], "Job id must match")
        assertEquals("PDF_EXPORT", payload["type"], "Job type must be PDF_EXPORT")
        assertNotNull(payload["correlationId"], "correlationId must be present in payload")

        val invoiceNode = payload["payload"].asJsonMap()?.get("invoice").asJsonMap()
        assertNotNull(invoiceNode, "payload.invoice node must exist")

        val invoiceNumber = invoiceNode!!["invoiceNumber"].asJsonMap()?.get("value")
        assertEquals("INV-2024-0001", invoiceNumber, "Invoice number must be serialized correctly")

        val sender = invoiceNode["senderProfile"].asJsonMap()
        assertNotNull(sender, "senderProfile must be present")
        assertEquals("Acme GmbH", sender!!["name"])
        assertEquals("DE123456789", sender["vatId"])

        val recipient = invoiceNode["recipient"].asJsonMap()
        assertNotNull(recipient, "recipient must be present")
        assertEquals("Kunde AG", recipient!!["name"])
    }

    @Test
    fun correlationIdHeaderIsAttachedToEveryMessage() {
        val subject = "publisher-it-header-${shortId()}"
        val publisher = publisherFor(subject)

        val invoice = testInvoice(id = 2L, invoiceNumber = "INV-2024-0002")
        publisher.publish(invoice, 2L).get(10, TimeUnit.SECONDS)

        val record = pollMessage(subject)
        assertNotNull(record, "Message must arrive on subject '$subject'")

        val headerValue = record!!.headers?.getFirst("correlationId")
        assertNotNull(headerValue, "correlationId header must be present on the NATS message")
        assertTrue(headerValue!!.isNotBlank(), "correlationId header must not be blank")
        assertTrue(headerValue.length >= 16, "correlationId header looks like a UUID: $headerValue")
    }

    @Test
    fun sentCounterIncrementsWithEachSuccessfulPublish() {
        val subject = "publisher-it-counter-${shortId()}"
        val publisher = publisherFor(subject)

        assertEquals(0L, publisher.currentSentCount(), "Initial sent count must be 0")

        publisher.publish(testInvoice(id = 10L, "RE-2024-0010"), 10L).get(10, TimeUnit.SECONDS)
        publisher.publish(testInvoice(id = 11L, "RE-2024-0011"), 11L).get(10, TimeUnit.SECONDS)
        publisher.publish(testInvoice(id = 12L, "RE-2024-0012"), 12L).get(10, TimeUnit.SECONDS)

        assertEquals(3L, publisher.currentSentCount(), "Sent count must reflect all published messages")
    }

    // ── AP-M2 — "Publishing
    // the same jobId twice within 2 minutes results in one message in the stream ..., and the
    // second PublishAck reports duplicate == true." `publish()` now sets
    // `PublishOptions.messageId("job-$jobId")` (see its own KDoc). Proven here against JetStream's
    // own client-default duplicate window (`NatsTestContainersExtension`'s TEST_STREAM does not
    // set `duplicateWindow` explicitly) — the same technique `ResultPublisherDedupIT` (kotlin-xpdf)
    // uses for `ResultPublisher`. AP-M3 is expected to set `duplicateWindow = 2 min` explicitly on
    // the *production* stream (`NatsStreamSetup`, not touched here); this test does not depend on
    // that value, only on a dedup window existing at all.

    @Test
    fun `publishing the same jobId twice is deduped to a single stream message`() {
        val subject = "publisher-it-dedup-${shortId()}"
        val publisher = publisherFor(subject)
        val jobId = 9001L

        val firstAck = publisher.publish(testInvoice(jobId, "INV-2024-9001"), jobId).get(10, TimeUnit.SECONDS)
        val secondAck = publisher.publish(testInvoice(jobId, "INV-2024-9002"), jobId).get(10, TimeUnit.SECONDS)

        assertFalse(firstAck.isDuplicate, "the first publish of a fresh Nats-Msg-Id must not be reported as a duplicate")
        assertTrue(secondAck.isDuplicate, "a second publish of the same jobId (\"job-$jobId\") must be reported as a duplicate")
        assertEquals(
            firstAck.seqno, secondAck.seqno,
            "both PublishAcks must reference the same stored stream sequence - only one message was ever appended",
        )
    }

    @Test
    fun `a different jobId is not deduped - it is a distinct Nats-Msg-Id`() {
        val subject = "publisher-it-dedup-distinct-${shortId()}"
        val publisher = publisherFor(subject)

        val firstAck = publisher.publish(testInvoice(9101L, "INV-2024-9101"), 9101L).get(10, TimeUnit.SECONDS)
        val secondAck = publisher.publish(testInvoice(9102L, "INV-2024-9102"), 9102L).get(10, TimeUnit.SECONDS)

        assertFalse(firstAck.isDuplicate)
        assertFalse(secondAck.isDuplicate, "a different jobId is a different Nats-Msg-Id - both messages must land in the stream")
        assertTrue(secondAck.seqno > firstAck.seqno, "the second, non-duplicate publish must have advanced the stream sequence")
    }

    @Test
    fun invoiceWithLineItemsSerializesItemAmountsAndTaxRate() {
        val subject = "publisher-it-items-${shortId()}"
        val publisher = publisherFor(subject)

        val invoice = testInvoiceWithItems(id = 20L, invoiceNumber = "INV-2024-0020")
        publisher.publish(invoice, 20L).get(10, TimeUnit.SECONDS)

        val record = pollMessage(subject)
        assertNotNull(record)

        val invoiceNode = mapper.readValue<Map<String, Any?>>(record!!.data)["payload"].asJsonMap()?.get("invoice").asJsonMap()
        assertNotNull(invoiceNode)

        val items = invoiceNode!!["items"].asJsonMapList()
        assertNotNull(items)
        assertTrue(items!!.isNotEmpty(), "Items list must not be empty")

        val firstItem = items.first()
        assertEquals("1", firstItem["positionId"])
        assertEquals("Software-Entwicklung", firstItem["description"])

        val unitPriceNet = firstItem["unitPriceNet"].asJsonMap()?.get("amount")
        assertNotNull(unitPriceNet, "unitPriceNet.amount must be present")
        assertEquals(19, (firstItem["taxRatePercent"] as Number).toInt(), "a draft item carries its referenced tax rate")
    }

    @Test
    fun multipleInvoicesProduceIndependentMessages() {
        val subject = "publisher-it-multi-${shortId()}"
        val publisher = publisherFor(subject)

        val pairs = listOf(
            100L to "RE-2024-0100",
            101L to "RE-2024-0101",
            102L to "RE-2024-0102"
        )

        pairs.forEach { (id, number) ->
            publisher.publish(testInvoice(id, number), id).get(10, TimeUnit.SECONDS)
        }

        pairs.forEach { (id, number) ->
            val record = pollMessage(subject, matching = { msg ->
                (mapper.readValue<Map<String, Any?>>(msg.data)["id"] as? String) == id.toString()
            })
            assertNotNull(record, "Message for job $id must appear on '$subject'")

            val invoiceNode = mapper.readValue<Map<String, Any?>>(record!!.data)["payload"].asJsonMap()?.get("invoice").asJsonMap()
            val invoiceNum = invoiceNode?.get("invoiceNumber").asJsonMap()?.get("value")
            assertEquals(number, invoiceNum, "Invoice number for job $id must be '$number'")
        }

        assertEquals(pairs.size.toLong(), publisher.currentSentCount())
    }

    @Test
    fun invoiceDatesAndPaymentTermDaysAreSerializedIntoMessage() {
        val subject = "publisher-it-dates-${shortId()}"
        val publisher = publisherFor(subject)

        val invoice = testInvoice(id = 30L, invoiceNumber = "RE-2024-0030").edit {
            invoiceDate = LocalDate.of(2024, 6, 15)
            servicePeriodFrom = LocalDate.of(2024, 6, 1)
            servicePeriodTo = LocalDate.of(2024, 6, 30)
            dueDate = LocalDate.of(2024, 7, 15)
            paymentTermsDays = 30
        }

        publisher.publish(invoice, 30L).get(10, TimeUnit.SECONDS)

        val record = pollMessage(subject)
        assertNotNull(record)

        val invoiceNode = mapper.readValue<Map<String, Any?>>(record!!.data)["payload"].asJsonMap()?.get("invoice").asJsonMap()
        assertNotNull(invoiceNode)

        // W3 regrouped the wire contract: date fields live in the nested `dates` object now.
        val datesNode = invoiceNode!!["dates"].asJsonMap()
        assertEquals("2024-06-15", datesNode!!["invoiceDate"])
        assertEquals("2024-06-01", datesNode["servicePeriodFrom"])
        assertEquals("2024-06-30", datesNode["servicePeriodTo"])
        assertEquals("2024-07-15", datesNode["dueDate"])
        assertEquals(30, datesNode["paymentTermsDays"])
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun publisherFor(subject: String): DocumentJobPublisher =
        DocumentJobPublisher(
            jetStreamProvider = { NatsTestContainersExtension.jetStream() },
            jobsSubjectOverride = subject,
        )

    private fun testInvoice(id: Long, invoiceNumber: String): Invoice =
        anInvoice(
            id = id,
            invoiceNumber = InvoiceNumber(invoiceNumber),
            senderProfile = aSenderProfile {
                name = "Acme GmbH"
                vatId = VatId("DE123456789")
            },
            recipient = aRecipient {
                name = "Kunde AG"
                vatId = VatId("DE987654321")
            },
        )

    private fun testInvoiceWithItems(id: Long, invoiceNumber: String): Invoice {
        val invoice = testInvoice(id, invoiceNumber)
        val item = anInvoiceItem(
            positionId = "1",
            description = "Software-Entwicklung",
            quantity = BigDecimal("10"),
            unitPriceNet = Money(BigDecimal("150.00")),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()
        return invoice
    }

    /**
     * Reads back from the JetStream stream rather than a live core subscription: the
     * message is already persisted by the time publish() completes, so a fresh ephemeral
     * JetStream consumer (default deliver-all policy) reliably replays it — no race with
     * "subscribe before publish" like a core NATS subscription would have.
     */
    private fun pollMessage(
        subject: String,
        timeoutSeconds: Long = 15,
        matching: (Message) -> Boolean = { true },
    ): Message? {
        val sub = NatsTestContainersExtension.jetStream().subscribe(subject)
        try {
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val msg = sub.nextMessage(Duration.ofMillis(300)) ?: continue
                msg.ack()
                if (matching(msg)) return msg
            }
        } finally {
            sub.unsubscribe()
        }
        return null
    }

    private fun shortId() = UUID.randomUUID().toString().take(8)
}
