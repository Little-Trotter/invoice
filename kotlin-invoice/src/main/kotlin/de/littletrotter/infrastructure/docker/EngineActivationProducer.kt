package de.littletrotter.infrastructure.docker

import de.littletrotter.application.invoice.EngineActivation
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * facturx-arc-improve.md §3.2 Stufe 2 / §7 — chooses the [EngineActivation] adapter at
 * runtime, same shape as [de.littletrotter.infrastructure.storage.DocumentStoreProducer]'s
 * backend switch. An empty/unset `invoice.facturx.docker-proxy.url` is the intended "Stufe 2 not
 * deployed here" default, not a misconfiguration — see [NoopEngineActivation]'s own KDoc.
 */
@ApplicationScoped
class EngineActivationProducer @Inject constructor(
    @param:ConfigProperty(name = "invoice.facturx.docker-proxy.url")
    private val dockerProxyUrl: Optional<String>,
    @param:ConfigProperty(name = "invoice.facturx.docker-proxy.container-name", defaultValue = "facturx-engine-api")
    private val containerName: String,
    // Reuses the same URL Stufe 1's DockerStatusService already resolves the engine's own
    // /healthz on — one truth for "where is the engine", not a second, independently drifting one.
    @param:ConfigProperty(name = "invoice.facturx.engine.url", defaultValue = DockerStatusService.DEFAULT_FACTURX_ENGINE_URL)
    private val engineHealthUrl: String,
    @param:ConfigProperty(name = "invoice.facturx.docker-proxy.start-timeout-ms", defaultValue = "30000")
    private val startTimeoutMs: Long,
    @param:ConfigProperty(name = "invoice.facturx.docker-proxy.poll-interval-ms", defaultValue = "1000")
    private val pollIntervalMs: Long,
    private val time: TimeSource,
) {

    @Produces
    fun produce(): EngineActivation {
        val url = dockerProxyUrl.orElse(null)?.trim()
        if (url.isNullOrBlank()) {
            return NoopEngineActivation()
        }
        return SocketProxyEngineActivation(
            proxyBaseUrl = url,
            containerName = containerName,
            engineHealthUrl = engineHealthUrl,
            startTimeoutMs = startTimeoutMs,
            pollIntervalMs = pollIntervalMs,
            time = time,
        )
    }
}
