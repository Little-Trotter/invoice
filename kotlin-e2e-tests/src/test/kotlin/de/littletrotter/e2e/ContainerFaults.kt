package de.littletrotter.e2e

import org.testcontainers.DockerClientFactory
import java.net.ServerSocket

/**
 * Takes a running E2E container away and brings it back through the Docker daemon, the way an
 * operator restarts a server, so a test can change the world between the two calls.
 *
 * The Testcontainers object itself is never stopped and started again: that would hand out new host
 * ports, and the applications under test were given the old ones. The containers whose address the
 * applications hold ([CentralNats], the offer module) are therefore created with a fixed host port,
 * which the daemon keeps across a stop and start of the same container.
 */
object ContainerFaults {

    /** System property under which [NatsTestResource] publishes the id of the NATS container. */
    const val NATS_CONTAINER_ID_PROPERTY = "nats.e2e.container-id"

    /** System property under which [QuoteAppTestResource] publishes the id of the offer module container. */
    const val QUOTE_APP_CONTAINER_ID_PROPERTY = "quote.e2e.container-id"

    private const val STOP_TIMEOUT_SECONDS = 5

    /** A host port that is free now; the container binds it right after, so the window for a clash is tiny. */
    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Stops the container; its filesystem, and with it a JetStream store, survives. */
    fun stop(containerId: String) {
        DockerClientFactory.lazyClient().stopContainerCmd(containerId).withTimeout(STOP_TIMEOUT_SECONDS).exec()
    }

    /** Starts a stopped container again on its previous host port. */
    fun start(containerId: String) {
        DockerClientFactory.lazyClient().startContainerCmd(containerId).exec()
    }

    /** The id the daemon knows the container by, published by the resource that created it. */
    fun containerId(property: String): String =
        checkNotNull(System.getProperty(property)) { "system property '$property' is not set; the resource that publishes it must be part of this run" }
}
