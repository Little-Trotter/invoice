package de.littletrotter.test

import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * JUnit 5 extension providing a reusable NATS JetStream Testcontainer for integration tests.
 * Usage: annotate test classes with @ExtendWith(NatsTestContainersExtension::class)
 *
 * A single wildcard-subject stream (subjects=["*"]) backs every test — since none of the
 * subject names used across tests contain a literal dot, a single-token wildcard `*` matches
 * all of them without having to pre-register each dynamically generated subject.
 */
class NatsTestContainersExtension : BeforeAllCallback {
    companion object {
        private val natsImage = DockerImageName.parse("nats:2.14.6-alpine")

        private val nats: GenericContainer<*> by lazy {
            GenericContainer(natsImage)
                .withExposedPorts(4222)
                .withCommand("-js")
                .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1))
                .withReuse(true)
                .apply { start() }
        }

        fun natsUrl(): String = "nats://${nats.host}:${nats.getMappedPort(4222)}"

        val connection: Connection by lazy {
            val conn = Nats.connect(Options.Builder().server(natsUrl()).build())
            ensureTestStream(conn)
            conn
        }

        fun jetStream(): JetStream = connection.jetStream()

        private fun ensureTestStream(conn: Connection) {
            val jsm = conn.jetStreamManagement()
            try {
                jsm.getStreamInfo(TEST_STREAM)
            } catch (e: Exception) {
                jsm.addStream(
                    StreamConfiguration.builder()
                        .name(TEST_STREAM)
                        .subjects("*")
                        .storageType(StorageType.Memory)
                        .retentionPolicy(RetentionPolicy.Limits)
                        .build()
                )
            }
        }

        private const val TEST_STREAM = "TEST_STREAM"
    }

    override fun beforeAll(context: ExtensionContext) {
        System.setProperty("nats.url", natsUrl())
        connection // trigger connect + stream setup
    }
}
