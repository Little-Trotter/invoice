package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.repository.AddressRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.shared.masterdata.CustomerUpserted
import de.littletrotter.shared.masterdata.MasterdataEntityTypes
import de.littletrotter.shared.masterdata.MasterdataStreams
import de.littletrotter.shared.masterdata.MasterdataSubjects
import de.littletrotter.shared.masterdata.MasterdataWireCodec
import de.littletrotter.shared.masterdata.NumberRangeUpserted
import de.littletrotter.shared.masterdata.SnapshotChunk
import de.littletrotter.shared.masterdata.SnapshotRequested
import io.nats.client.Connection
import io.nats.client.JetStreamSubscription
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * A snapshot request on `masterdata.snapshot.requested` is answered, through the real consumer
 * and JetStream, with the whole current inventory in gapless chunks that stay below the envelope
 * limit, the last one marked.
 */
@QuarkusTest
@TestProfile(QuoteEnabledProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class MasterdataSnapshotStreamingIT {

    @Inject lateinit var connection: Connection
    @Inject lateinit var recipients: RecipientRepository
    @Inject lateinit var addresses: AddressRepository
    @Inject lateinit var numberRanges: NumberRangeRepository

    private fun subscription(): JetStreamSubscription =
        connection.jetStream().subscribe(
            MasterdataSubjects.SNAPSHOT_CHUNK,
            PullSubscribeOptions.builder().stream(MasterdataStreams.MASTERDATA)
                .configuration(
                    ConsumerConfiguration.builder().ackPolicy(AckPolicy.None).deliverPolicy(DeliverPolicy.All).build(),
                ).build(),
        )

    private fun chunksOf(requestId: String, sub: JetStreamSubscription): List<SnapshotChunk> {
        val found = mutableListOf<SnapshotChunk>()
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline && found.none { it.last }) {
            sub.fetch(50, Duration.ofSeconds(1)).forEach { message ->
                assertTrue(message.data.size < MasterdataWireCodec.MAX_ENVELOPE_BYTES, "chunk of ${message.data.size} bytes")
                val chunk = MasterdataWireCodec.decode<SnapshotChunk>(String(message.data, Charsets.UTF_8))
                if (chunk.requestId == requestId) found.add(chunk)
            }
        }
        return found.sortedBy { it.sequence }
    }

    @Test
    fun `a request is answered with every customer in chunks below the limit`() {
        val created = mutableListOf<Long>()
        repeat(40) { index ->
            val id = QuarkusTransaction.requiringNew().call {
                val recipient = recipients.save(
                    Recipient().apply {
                        customerNumber = CustomerNumber("KD-${UUID.randomUUID().toString().take(8)}")
                        name = "Snapshot customer $index " + "x".repeat(100)
                    },
                )
                repeat(20) { n ->
                    addresses.save(Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Strasse $n " + "y".repeat(200), "10115", "Berlin"))
                }
                recipient.id!!
            }
            created.add(id)
        }
        val sub = subscription()
        val requestId = "req-${UUID.randomUUID()}"

        connection.jetStream().publish(
            MasterdataSubjects.SNAPSHOT_REQUESTED,
            MasterdataWireCodec.encode(SnapshotRequested(requestId, listOf(MasterdataEntityTypes.CUSTOMER))).toByteArray(),
        )

        val chunks = chunksOf(requestId, sub)
        assertEquals((0 until chunks.size).toList(), chunks.map { it.sequence })
        assertTrue(chunks.size > 1, "inventory should need several chunks")
        assertEquals(listOf(true), chunks.map { it.last }.filter { it })
        assertTrue(chunks.last().last)
        val delivered = chunks.flatMap { it.entries }.map { MasterdataWireCodec.decode<CustomerUpserted>(it).id }
        assertTrue(delivered.containsAll(created), "snapshot lacks customers")
        assertEquals(delivered.size, delivered.toSet().size)
    }

    @Test
    fun `an unknown entity type yields one empty final chunk`() {
        val sub = subscription()
        val requestId = "req-${UUID.randomUUID()}"

        connection.jetStream().publish(
            MasterdataSubjects.SNAPSHOT_REQUESTED,
            MasterdataWireCodec.encode(SnapshotRequested(requestId, listOf("nothing"))).toByteArray(),
        )

        val chunk = chunksOf(requestId, sub).single()
        assertTrue(chunk.last)
        assertEquals(emptyList<String>(), chunk.entries)
    }

    @Test
    fun `the number range snapshot carries quote ranges only`() {
        val ids = QuarkusTransaction.requiringNew().call {
            listOf(NumberRangeSubject.QUOTE, NumberRangeSubject.CUSTOMER).map { subject ->
                numberRanges.save(
                    NumberRange().apply {
                        code = "S${UUID.randomUUID().toString().take(8)}"
                        displayName = "Snapshot"
                        this.subject = subject
                        isActive = false
                        isDefault = false
                    },
                ).id!!
            }
        }
        val sub = subscription()
        val requestId = "req-${UUID.randomUUID()}"

        connection.jetStream().publish(
            MasterdataSubjects.SNAPSHOT_REQUESTED,
            MasterdataWireCodec.encode(SnapshotRequested(requestId, listOf(MasterdataEntityTypes.NUMBER_RANGE))).toByteArray(),
        )

        val delivered = chunksOf(requestId, sub).flatMap { it.entries }.map { MasterdataWireCodec.decode<NumberRangeUpserted>(it) }
        assertTrue(delivered.any { it.id == ids[0] }, "quote range missing")
        assertTrue(delivered.none { it.id == ids[1] }, "customer range leaked")
        assertTrue(delivered.all { it.subject == "QUOTE" })
    }
}
