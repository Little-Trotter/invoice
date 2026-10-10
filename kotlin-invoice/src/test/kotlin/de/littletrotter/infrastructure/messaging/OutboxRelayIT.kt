package de.littletrotter.infrastructure.messaging

import de.littletrotter.infrastructure.persistence.TestSchema
import de.littletrotter.shared.time.ClockTimeSource
import io.nats.client.Connection
import io.nats.client.JetStreamSubscription
import io.nats.client.Nats
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.StreamConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Real Postgres (baseline DDL) and real JetStream: an outbox row is published exactly once, is
 * marked published afterwards, and a repeated run neither republishes it nor loses the failing
 * ones behind it.
 */
@Testcontainers
class OutboxRelayIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")

        @Container
        @JvmStatic
        val nats: GenericContainer<*> = GenericContainer(DockerImageName.parse("nats:2.14.6-alpine"))
            .withExposedPorts(4222)
            .withCommand("-js")
            .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1))

        private const val STREAM = "outbox-it"
        private val now = Instant.parse("2026-10-04T10:15:30Z")
        private lateinit var dataSource: PGSimpleDataSource
        private lateinit var connection: Connection
        private lateinit var repository: OutboxRepository

        @JvmStatic
        @BeforeAll
        fun setup() {
            dataSource = PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
            dataSource.connection.use { TestSchema.apply(it) }
            repository = OutboxRepository(dataSource, ClockTimeSource.fixedAt(now))
            connection = Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}")
            connection.jetStreamManagement().addStream(
                StreamConfiguration.builder().name(STREAM).subjects("outbox.it.>").build(),
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            connection.close()
        }
    }

    private fun relay() = OutboxRelay(repository, connection, connection.jetStream(), NatsReconnectListener(), batchSize = 10, maxAttempts = 2)

    private fun row(id: Long): Map<String, Any?> = dataSource.connection.use { c ->
        c.prepareStatement("SELECT status, attempts, last_error, created_at, published_at FROM outbox WHERE id = ?").use { s ->
            s.setLong(1, id)
            s.executeQuery().use { rs ->
                check(rs.next())
                mapOf(
                    "status" to rs.getInt("status"),
                    "attempts" to rs.getInt("attempts"),
                    "last_error" to rs.getString("last_error"),
                    "created_at" to rs.getTimestamp("created_at").toInstant(),
                    "published_at" to rs.getTimestamp("published_at")?.toInstant(),
                )
            }
        }
    }

    private fun pull(subject: String): JetStreamSubscription =
        connection.jetStream().subscribe(subject, PullSubscribeOptions.builder().stream(STREAM).build())

    @Test
    fun `a pending row is published exactly once and marked published`() {
        val id = repository.append("Customer", "42", "outbox.it.once", """{"id":42}""")
        assertEquals(OutboxStatus.PENDING.ordinal, row(id)["status"])
        assertEquals(now, row(id)["created_at"])
        val sub = pull("outbox.it.once")

        assertEquals(1, relay().relayPending())
        assertEquals(0, relay().relayPending())

        val messages = sub.fetch(5, Duration.ofSeconds(2))
        assertEquals(1, messages.size)
        assertEquals("""{"id":42}""", String(messages.single().data, Charsets.UTF_8))
        assertEquals(OutboxStatus.PUBLISHED.ordinal, row(id)["status"])
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), row(id)["published_at"])
    }

    @Test
    fun `a row whose publish was repeated after a lost mark is dropped by the stream`() {
        val id = repository.append("Customer", "43", "outbox.it.dup", "{}")
        val sub = pull("outbox.it.dup")
        relay().relayPending()
        dataSource.connection.use {
            it.createStatement().execute("UPDATE outbox SET status = 0, published_at = NULL WHERE id = $id")
        }

        assertEquals(1, relay().relayPending())

        assertEquals(1, sub.fetch(5, Duration.ofSeconds(2)).size)
        assertEquals(OutboxStatus.PUBLISHED.ordinal, row(id)["status"])
    }

    @Test
    fun `a subject without a stream is retried and parked as failed after the attempt budget`() {
        val id = repository.append("Customer", "44", "outbox.nostream.x", "{}")

        assertEquals(0, relay().relayPending())
        assertEquals(OutboxStatus.PENDING.ordinal, row(id)["status"])
        assertEquals(0, relay().relayPending())

        assertEquals(OutboxStatus.FAILED.ordinal, row(id)["status"])
        assertEquals(2, row(id)["attempts"])
        assertNotNull(row(id)["last_error"])
        assertNull(row(id)["published_at"])
        assertEquals(0, relay().relayPending())
    }
}
