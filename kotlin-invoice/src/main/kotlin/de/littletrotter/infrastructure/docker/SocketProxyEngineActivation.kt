package de.littletrotter.infrastructure.docker

import de.littletrotter.application.invoice.EngineActivation
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.shared.time.TimeSource
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Talks to a `tecnativa/docker-socket-proxy` sidecar instead of mounting the raw `docker.sock`
 * into an application container (root-equivalent access — the reason this stage does not just add
 * a Docker client). The proxy exposes the real Docker Engine API, restricted at *its own*
 * configuration to `POST start`/`stop` against exactly one, explicitly named container — this
 * adapter has no broader reach than that even if it wanted one; it only ever addresses
 * [containerName].
 *
 * [ensureRunning] deliberately blocks until `/healthz` answers or [startTimeoutMs] elapses (the UI
 * waits on `/healthz`) — a checkbox click or a job about to be enqueued should see the container's
 * real state, not an optimistic "started".
 *
 * Every failure — proxy unreachable, container missing, `/healthz` never turning green — is
 * logged and swallowed, never thrown, per [EngineActivation]'s own contract: a stuck engine must
 * not break invoice creation or the reference-data screen.
 */
class SocketProxyEngineActivation(
    private val proxyBaseUrl: String,
    private val containerName: String,
    private val engineHealthUrl: String,
    private val startTimeoutMs: Long,
    private val pollIntervalMs: Long,
    private val time: TimeSource,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .build(),
) : EngineActivation {

    private val log = Logger.getLogger(javaClass)

    override fun ensureRunning(engine: DocumentEngine) {
        if (engine != DocumentEngine.FACTURX) return
        if (!callProxy("start")) return
        awaitHealthy()
    }

    override fun releaseIdle(engine: DocumentEngine) {
        if (engine != DocumentEngine.FACTURX) return
        callProxy("stop")
    }

    /** Mirrors the real Docker Engine API's own contract for this call, which the proxy just
     *  forwards: 204 = done, 304 = already in the requested state — both mean "nothing left to
     *  do" here, not a failure. */
    private fun callProxy(action: String): Boolean = try {
        val request = HttpRequest.newBuilder(URI.create("${proxyBaseUrl.trimEnd('/')}/containers/$containerName/$action"))
            .timeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        val ok = response.statusCode() == HTTP_NO_CONTENT || response.statusCode() == HTTP_NOT_MODIFIED
        if (!ok) {
            log.warnf(
                "docker-socket-proxy %s on container %s returned unexpected status %d",
                action, containerName, response.statusCode(),
            )
        }
        ok
    } catch (e: Exception) {
        // AP-EXC-1: kept broad on purpose — this class's own KDoc and the EngineActivation port
        // contract both call it out explicitly: every failure here is best-effort and must be
        // swallowed, never thrown, because a stuck/unreachable engine container must not break
        // invoice creation or the reference-data screen that call ensureRunning()/releaseIdle().
        // Already logged at warn with the action and container name.
        log.warnf("docker-socket-proxy %s on container %s failed: %s", action, containerName, e.message)
        false
    }

    private fun awaitHealthy() {
        val deadline = time.instant().plusMillis(startTimeoutMs)
        while (true) {
            if (isHealthy()) return
            if (!time.instant().isBefore(deadline)) {
                log.warnf("Factur-X engine did not report healthy within %dms of starting it", startTimeoutMs)
                return
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun isHealthy(): Boolean = try {
        val request = HttpRequest.newBuilder(URI.create("${engineHealthUrl.trimEnd('/')}/healthz"))
            .timeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .GET()
            .build()
        http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3000L
        const val HTTP_OK = 200
        const val HTTP_NO_CONTENT = 204
        const val HTTP_NOT_MODIFIED = 304
    }
}
