package de.littletrotter.e2e

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Starts a real NATS JetStream server for `@QuarkusTest` classes in this module.
 *
 * `de.littletrotter.infrastructure.messaging.NatsConnectionProducer` (and the eagerly started
 * `GenerationJobNatsAdapter`) require a live NATS connection at CDI startup — *any* `@QuarkusTest`
 * that boots the real app needs a reachable NATS server, not just messaging-focused tests (same
 * reasoning kotlin-invoice's own `NatsTestResource` documents for its module). A module-local
 * copy rather than reusing kotlin-invoice's: test-scoped classes aren't shared across Maven
 * modules here (`PostgresTestResource`/`S3TestResource` are module-local for the same reason).
 * `System.setProperty` alongside the returned map — same cross-module index-dependency
 * reasoning as `PostgresTestResource`: the returned config map does not reliably outrank
 * kotlin-invoice's own `application.properties` (also on this module's classpath) in that boot
 * path, but a system property, with its higher SmallRye Config ordinal, does.
 */
class NatsTestResource : QuarkusTestResourceLifecycleManager {

    override fun start(): MutableMap<String, String> {
        val nats = CentralNats.acquire()
        val props = mutableMapOf("nats.url" to nats.url, ContainerFaults.NATS_CONTAINER_ID_PROPERTY to nats.containerId)
        props.forEach { (key, value) -> System.setProperty(key, value) }
        return props
    }

    override fun stop(): Unit = CentralNats.release()
}

/**
 * The one NATS server of an E2E run, as in a deployment where the invoicing application and the
 * mail module share a single server and each owns its own streams on it. Both [NatsTestResource]
 * and [EmailAppTestResource] acquire it, so whichever Quarkus starts first brings it up and the
 * mail module can never start against a server the application does not see. Reference-counted so
 * it lives exactly as long as the last resource that needs it.
 */
object CentralNats {
    /** Server address as seen from the test JVM and, via the host gateway, from other containers. */
    class Endpoint(val host: String, val port: Int, val containerId: String) {
        val url: String get() = "nats://$host:$port"
    }

    private var container: GenericContainer<*>? = null
    private var holders = 0

    @Synchronized
    fun acquire(): Endpoint {
        val running = container ?: GenericContainer(DockerImageName.parse("nats:2.15.1-alpine")).apply {
            // Fixed host port: a fault test stops and starts this container and the applications keep the address.
            val hostPort = ContainerFaults.freePort()
            withExposedPorts(NATS_PORT)
            setPortBindings(listOf("$hostPort:$NATS_PORT"))
            withCommand("-js")
            waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1))
            withReuse(false)
            start()
        }.also { container = it }
        holders++
        return Endpoint(running.host, running.getMappedPort(NATS_PORT), running.containerId)
    }

    @Synchronized
    fun release() {
        holders--
        if (holders <= 0) {
            container?.stop()
            container = null
            holders = 0
        }
    }

    private const val NATS_PORT = 4222
}
