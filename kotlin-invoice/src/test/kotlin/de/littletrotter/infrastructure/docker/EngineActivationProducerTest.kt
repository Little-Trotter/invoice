package de.littletrotter.infrastructure.docker

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE
import java.util.Optional

/**
 * The config-driven adapter switch, same shape as
 * [de.littletrotter.infrastructure.storage.DocumentStoreProducerTest].
 */
class EngineActivationProducerTest {

    private fun producer(dockerProxyUrl: Optional<String>) = EngineActivationProducer(
        dockerProxyUrl = dockerProxyUrl,
        containerName = "facturx-engine-api",
        engineHealthUrl = "http://facturx-engine-api:8000",
        startTimeoutMs = 1000,
        pollIntervalMs = 100,
        time = FIXED_TIME_SOURCE,
    )

    @Test
    fun `producer returns a no-op adapter when the proxy url is unset`() {
        val activation = producer(Optional.empty()).produce()

        assertTrue(activation is NoopEngineActivation)
    }

    @Test
    fun `producer returns a no-op adapter when the proxy url is blank`() {
        val activation = producer(Optional.of("   ")).produce()

        assertTrue(activation is NoopEngineActivation)
    }

    @Test
    fun `producer returns the socket-proxy adapter when a proxy url is configured`() {
        val activation = producer(Optional.of("http://docker-socket-proxy:2375")).produce()

        assertTrue(activation is SocketProxyEngineActivation)
    }
}
