package de.littletrotter.application

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Starts a real NATS JetStream server for @QuarkusTest classes.
 *
 * [de.littletrotter.infrastructure.messaging.NatsConnectionProducer] (and the eagerly
 * started [de.littletrotter.infrastructure.messaging.GenerationJobNatsAdapter], see its
 * `@Startup` doc comment) require a live NATS connection at CDI startup — since NATS moved from
 * lazy `@Incoming`-based consumption to a plain eager bean, *any* `@QuarkusTest` in this module
 * now needs a reachable NATS server, not just messaging-focused tests. This resource supplies
 * one via Testcontainers instead of relying on the docker-compose stack being up; the app's own
 * `NatsConnectionProducer.ensureStream()` creates the JetStream stream it needs on connect.
 */
class NatsTestResource : QuarkusTestResourceLifecycleManager {
    private val nats = GenericContainer(DockerImageName.parse("nats:2.14.6-alpine")).apply {
        withExposedPorts(4222)
        withCommand("-js")
        waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1))
        withReuse(false)
    }

    override fun start(): MutableMap<String, String> {
        nats.start()
        instance = nats
        return mutableMapOf("nats.url" to "nats://${nats.host}:${nats.getMappedPort(4222)}")
    }

    override fun stop(): Unit = nats.stop()

    companion object {
        @Volatile
        private var instance: GenericContainer<*>? = null

        /**
         * AP-M5 — pauses the currently running NATS test
         * container for an `@QuarkusTest` that needs to prove what happens when NATS is
         * unreachable. `docker pause` freezes the container process via the cgroup freezer;
         * existing TCP sockets stay technically open but nothing on the other end responds, so a
         * client-side publish attempt hangs until its own timeout — it does not get a clean
         * connection-refused. Always pair with [unpause] in a `finally` block: this container
         * instance is shared for the whole test class, so leaving it paused breaks every test that
         * runs after.
         */
        fun pause() {
            val container = instance ?: error("NatsTestResource container not started")
            container.dockerClient.pauseContainerCmd(container.containerId).exec()
        }

        /** Reverses [pause]. */
        fun unpause() {
            val container = instance ?: error("NatsTestResource container not started")
            container.dockerClient.unpauseContainerCmd(container.containerId).exec()
        }
    }
}
