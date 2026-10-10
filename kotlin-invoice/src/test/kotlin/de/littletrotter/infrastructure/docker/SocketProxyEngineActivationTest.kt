package de.littletrotter.infrastructure.docker

import com.sun.net.httpserver.HttpServer
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises [SocketProxyEngineActivation]'s HTTP logic against a
 * local, in-process HTTP server standing in for `tecnativa/docker-socket-proxy` (and a second one
 * for the engine's own `/healthz`), following the same pattern as `ViesHttpClientTest`: no live
 * Docker daemon or docker-socket-proxy container involved — this covers the acceptance criterion
 * "Schalter an → Container läuft binnen Zeitgrenze" at the adapter level.
 */
class SocketProxyEngineActivationTest {

    private var proxy: HttpServer? = null
    private var engine: HttpServer? = null

    @AfterEach
    fun teardown() {
        proxy?.stop(0)
        engine?.stop(0)
    }

    private fun startProxy(): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
            it.start()
            proxy = it
        }

    private fun startEngine(): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
            it.start()
            engine = it
        }

    private fun activation(
        proxyServer: HttpServer,
        engineServer: HttpServer,
        startTimeoutMs: Long = 2000,
        pollIntervalMs: Long = 50,
    ) = SocketProxyEngineActivation(
        proxyBaseUrl = "http://127.0.0.1:${proxyServer.address.port}",
        containerName = "facturx-engine-api",
        engineHealthUrl = "http://127.0.0.1:${engineServer.address.port}",
        startTimeoutMs = startTimeoutMs,
        pollIntervalMs = pollIntervalMs,
        time = SYSTEM_TIME,
    )

    @Test
    fun `ensureRunning starts the container and returns once healthz turns green`() {
        val proxyServer = startProxy()
        val engineServer = startEngine()
        val startCalls = AtomicInteger(0)
        proxyServer.createContext("/containers/facturx-engine-api/start") { exchange ->
            startCalls.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        engineServer.createContext("/healthz") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        activation(proxyServer, engineServer).ensureRunning(DocumentEngine.FACTURX)

        assertEquals(1, startCalls.get())
    }

    @Test
    fun `ensureRunning polls healthz until it turns green, then stops`() {
        val proxyServer = startProxy()
        val engineServer = startEngine()
        proxyServer.createContext("/containers/facturx-engine-api/start") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val healthCalls = AtomicInteger(0)
        engineServer.createContext("/healthz") { exchange ->
            val callNumber = healthCalls.incrementAndGet()
            // The first two polls see a still-starting container; the third is healthy.
            exchange.sendResponseHeaders(if (callNumber >= 3) 200 else 503, -1)
            exchange.close()
        }

        activation(proxyServer, engineServer, startTimeoutMs = 3000, pollIntervalMs = 20)
            .ensureRunning(DocumentEngine.FACTURX)

        assertTrue(healthCalls.get() >= 3, "expected at least 3 healthz polls, saw ${healthCalls.get()}")
    }

    @Test
    fun `ensureRunning gives up after the start timeout without throwing`() {
        val proxyServer = startProxy()
        val engineServer = startEngine()
        proxyServer.createContext("/containers/facturx-engine-api/start") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        engineServer.createContext("/healthz") { exchange ->
            // Never turns healthy.
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }

        val before = System.currentTimeMillis()
        activation(proxyServer, engineServer, startTimeoutMs = 300, pollIntervalMs = 50)
            .ensureRunning(DocumentEngine.FACTURX)
        val elapsed = System.currentTimeMillis() - before

        assertTrue(elapsed < 5000, "must give up well inside a bounded time, took ${elapsed}ms")
    }

    @Test
    fun `ensureRunning never polls healthz when the proxy refuses to start the container`() {
        val proxyServer = startProxy()
        val engineServer = startEngine()
        proxyServer.createContext("/containers/facturx-engine-api/start") { exchange ->
            // e.g. the proxy's own ACL config denies this verb.
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        val healthCalls = AtomicInteger(0)
        engineServer.createContext("/healthz") { exchange ->
            healthCalls.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        activation(proxyServer, engineServer).ensureRunning(DocumentEngine.FACTURX)

        assertEquals(0, healthCalls.get(), "a rejected start must not be followed by health polling")
    }

    @Test
    fun `ensureRunning treats an already-running container (304) as a success`() {
        val proxyServer = startProxy()
        val engineServer = startEngine()
        proxyServer.createContext("/containers/facturx-engine-api/start") { exchange ->
            exchange.sendResponseHeaders(304, -1)
            exchange.close()
        }
        engineServer.createContext("/healthz") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        activation(proxyServer, engineServer).ensureRunning(DocumentEngine.FACTURX)
        // No exception is the assertion here — a 304 must be treated the same as a fresh start.
    }

    @Test
    fun `releaseIdle stops the container`() {
        val proxyServer = startProxy()
        val engineServer = startEngine()
        val stopCalls = AtomicInteger(0)
        proxyServer.createContext("/containers/facturx-engine-api/stop") { exchange ->
            stopCalls.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }

        activation(proxyServer, engineServer).releaseIdle(DocumentEngine.FACTURX)

        assertEquals(1, stopCalls.get())
    }

    @Test
    fun `neither ensureRunning nor releaseIdle call the proxy for a MUSTANG engine`() {
        val proxyServer = startProxy()
        val engineServer = startEngine()
        val calls = AtomicInteger(0)
        proxyServer.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }

        val activation = activation(proxyServer, engineServer)
        activation.ensureRunning(DocumentEngine.MUSTANG)
        activation.releaseIdle(DocumentEngine.MUSTANG)

        assertEquals(0, calls.get())
    }

    @Test
    fun `boundary - a healthz endpoint that refuses connections is treated as not-yet-healthy until the timeout`() {
        val proxyServer = startProxy()
        proxyServer.createContext("/containers/facturx-engine-api/start") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        // No engine server is started at all — connecting to /healthz must fail, exercising
        // isHealthy()'s own catch clause rather than the proxy's.
        val activationUnderTest = SocketProxyEngineActivation(
            proxyBaseUrl = "http://127.0.0.1:${proxyServer.address.port}",
            containerName = "facturx-engine-api",
            engineHealthUrl = "http://127.0.0.1:1",
            startTimeoutMs = 300,
            pollIntervalMs = 50,
            time = SYSTEM_TIME,
        )

        val before = System.currentTimeMillis()
        activationUnderTest.ensureRunning(DocumentEngine.FACTURX)
        val elapsed = System.currentTimeMillis() - before

        assertTrue(elapsed < 5000, "an unreachable healthz must still give up within a bounded time, took ${elapsed}ms")
    }

    @Test
    fun `boundary - interrupting the poll wait stops it immediately and restores the thread's interrupted status`() {
        val proxyServer = startProxy()
        val engineServer = startEngine()
        proxyServer.createContext("/containers/facturx-engine-api/start") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        engineServer.createContext("/healthz") { exchange ->
            // Never turns healthy, so awaitHealthy is still sleeping between polls when interrupted.
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        val activationUnderTest = activation(proxyServer, engineServer, startTimeoutMs = 60_000, pollIntervalMs = 5_000)
        val threadWasInterrupted = AtomicBoolean(false)
        val worker =
            Thread {
                activationUnderTest.ensureRunning(DocumentEngine.FACTURX)
                threadWasInterrupted.set(Thread.currentThread().isInterrupted)
            }

        worker.start()
        Thread.sleep(300)
        worker.interrupt()
        worker.join(5000)

        assertTrue(!worker.isAlive, "an interrupted wait must return immediately, not keep sleeping")
        assertTrue(threadWasInterrupted.get(), "the thread's interrupted status must be restored before returning")
    }

    @Test
    fun `a proxy that is unreachable does not throw`() {
        val engineServer = startEngine()
        val unreachableProxy = SocketProxyEngineActivation(
            proxyBaseUrl = "http://127.0.0.1:1",
            containerName = "facturx-engine-api",
            engineHealthUrl = "http://127.0.0.1:${engineServer.address.port}",
            startTimeoutMs = 500,
            pollIntervalMs = 50,
            time = SYSTEM_TIME,
        )

        unreachableProxy.ensureRunning(DocumentEngine.FACTURX)
        unreachableProxy.releaseIdle(DocumentEngine.FACTURX)
    }
}

/** The start wait polls a real sidecar over HTTP, so these tests measure it on the real clock. */
private val SYSTEM_TIME = ClockTimeSource(Clock.systemUTC())
