package de.littletrotter.infrastructure.docker

import de.littletrotter.application.invoice.FacturXDeployment
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.application.system.SystemStatusService
import de.littletrotter.domain.reference.ValidationCapabilityId
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

/**
 * Plain TCP/HTTP endpoint config [DockerStatusService] probes — grouped into its own bean so the
 * service's own constructor stays under detekt's `LongParameterList` threshold. No behavior of
 * its own: every property here is read exactly as it was before this split, same names/defaults.
 */
@ApplicationScoped
class DockerStatusEndpoints(
    @param:ConfigProperty(name = "quarkus.datasource.jdbc.url")
    val datasourceJdbcUrl: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.endpoint")
    val rustfsEndpoint: Optional<String>,
    @param:ConfigProperty(name = "nats.url", defaultValue = "nats://localhost:4222")
    val natsUrl: Optional<String>,
    @param:ConfigProperty(name = "cache.valkey.endpoint", defaultValue = "valkey:6379")
    val valkeyEndpoint: Optional<String>,
    @param:ConfigProperty(name = "xpdf.url", defaultValue = "http://localhost:8081")
    val xpdfUrl: Optional<String>,
    @param:ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    val quarkusHttpPort: Int,
)

/**
 * Optional-feature endpoint config [DockerStatusService] probes only while the matching switch is
 * on (oMLX / Factur-X engine) — split out for the same `LongParameterList` reason as
 * [DockerStatusEndpoints].
 */
@ApplicationScoped
class DockerStatusOptionalFeatures(
    @param:ConfigProperty(name = "invoice.omlx.enabled", defaultValue = "false")
    val omlxEnabled: Boolean,
    @param:ConfigProperty(name = "invoice.omlx.url")
    val omlxUrl: Optional<String>,
    // facturx-arc-improve.md §3.2 Stufe 1 — unlike veraPDF (a CLI *inside* the xpdf
    // worker, reached via its /status/verapdf passthrough), the Factur-X engine is its own
    // networked container (docker/compose/facturx.yml); this service can reach it directly, the
    // same way it already reaches rustfs/nats/valkey. The port is the engine's own, confirmed
    // against the running 2.1.0 image ( §3).
    @param:ConfigProperty(name = "invoice.facturx.engine.url", defaultValue = DockerStatusService.DEFAULT_FACTURX_ENGINE_URL)
    val facturxEngineUrl: String,
)

/**
 * Checks local runtime dependencies via TCP port probing.
 * Implements the [SystemStatusService] application-layer port.
 */
@ApplicationScoped
class DockerStatusService(
    private val endpoints: DockerStatusEndpoints,
    private val optionalFeatures: DockerStatusOptionalFeatures,
    // The one place that already knows the switch's value; this
    // service reuses it rather than re-reading validation_setting itself.
    private val validationSettingsGate: ValidationSettingsGate,
    // A non-deployed engine has no container to be red about; gates the tile itself, on
    // top of the ValidationCapabilityId.FACTURX_ENGINE switch check below.
    private val facturXDeployment: FacturXDeployment,
) : SystemStatusService {

    override fun listDependencies(): List<SystemStatusService.ServiceStatus> {
        val postgres = tcpEndpointFromJdbcUrl(endpoints.datasourceJdbcUrl.orElse(null))
        val rustfs = tcpEndpointFromUrl(endpoints.rustfsEndpoint.orElse(null), LOCALHOST, RUSTFS_PORT)
        val nats = tcpEndpointFromUrl(endpoints.natsUrl.orElse(DEFAULT_NATS_URL), LOCALHOST, NATS_PORT)
        val valkey = tcpEndpointFromUrl(endpoints.valkeyEndpoint.orElse(null), LOCALHOST, VALKEY_PORT)
        val xpdf = tcpEndpointFromUrl(endpoints.xpdfUrl.orElse(DEFAULT_XPDF_URL), LOCALHOST, XPDF_PORT)
        val xpdfUp = checkTcpPort(xpdf.host, xpdf.port)
        val invoice = TcpEndpoint(LOCALHOST, endpoints.quarkusHttpPort)

        val dependencies = mutableListOf(
            SystemStatusService.ServiceStatus(
                key = "postgresql",
                instanceName = displayNameFromHost(postgres.host, "postgres"),
                target = postgres.display(),
                up = checkTcpPort(postgres.host, postgres.port),
            ),
            SystemStatusService.ServiceStatus(
                key = "rustfs",
                instanceName = displayNameFromHost(rustfs.host, "rustfs"),
                target = rustfs.display(),
                up = checkTcpPort(rustfs.host, rustfs.port),
            ),
            SystemStatusService.ServiceStatus(
                key = "nats",
                instanceName = displayNameFromHost(nats.host, "nats"),
                target = nats.display(),
                up = checkTcpPort(nats.host, nats.port),
            ),
            SystemStatusService.ServiceStatus(
                key = "valkey",
                instanceName = displayNameFromHost(valkey.host, "Valkey"),
                target = valkey.display(),
                up = checkTcpPort(valkey.host, valkey.port),
            ),
            SystemStatusService.ServiceStatus(
                key = "xpdf",
                instanceName = displayNameFromHost(xpdf.host, "xpdf"),
                target = xpdf.display(),
                up = xpdfUp,
            ),
            SystemStatusService.ServiceStatus(
                key = "invoice",
                instanceName = "Invoice App",
                target = invoice.display(),
                up = checkTcpPort(invoice.host, invoice.port),
            ),
            SystemStatusService.ServiceStatus(
                key = "worker",
                instanceName = "Worker",
                target = invoice.display(),
                up = checkTcpPort(invoice.host, invoice.port),
            ),
        )

        appendOptionalDependencies(dependencies, xpdf, xpdfUp)

        return dependencies.toList()
    }

    /** The three feature-gated tiles (omlx / veraPDF / Factur-X engine), split out of
     *  [listDependencies] purely to stay under detekt's `LongMethod` threshold — no behavior
     *  change, same conditions and same [SystemStatusService.ServiceStatus] entries as before. */
    private fun appendOptionalDependencies(
        dependencies: MutableList<SystemStatusService.ServiceStatus>,
        xpdf: TcpEndpoint,
        xpdfUp: Boolean,
    ) {
        if (optionalFeatures.omlxEnabled) {
            val omlx = tcpEndpointFromUrl(optionalFeatures.omlxUrl.orElse(null), LOCALHOST, OMLX_PORT)
            dependencies += SystemStatusService.ServiceStatus(
                key = "omlx",
                instanceName = displayNameFromHost(omlx.host, "omlx"),
                target = omlx.display(),
                up = checkTcpPort(omlx.host, omlx.port),
            )
        }

        // A consistency hint, not a hard error (unlike the edition
        // check): only shown while the switch is on, mirroring the `omlx` block above ("only
        // relevant when ..."). Off means nothing to reconcile — a permanently red line for a
        // deliberately unused check would be noise, not a hint. `up` reflects whether the CLI is
        // actually reachable *inside the worker* (`VeraPdfStatusResource`, kotlin-xpdf) — this
        // service cannot see that via TCP probing alone, since veraPDF runs as the worker's own
        // subprocess, not a networked dependency of its own.
        if (validationSettingsGate.isEnabled(ValidationCapabilityId.VERAPDF_CHECK)) {
            val veraPdfStatus = if (xpdfUp) fetchVeraPdfStatus(xpdf) else VeraPdfProbe(false, "")
            dependencies += SystemStatusService.ServiceStatus(
                key = "verapdf",
                instanceName = "veraPDF",
                target = "${xpdf.display()}/status/verapdf",
                up = veraPdfStatus.available,
                version = veraPdfStatus.version,
            )
        }

        // facturx-arc-improve.md §3.2 Stufe 1 — same "consistency hint, not a hard
        // error" reasoning as the veraPDF block above: the container only runs when an operator
        // has actually applied docker/compose/facturx.yml, so a permanently red line while the
        // switch is off would be noise, not signal. `up` needs both a live TCP port *and* a
        // healthy /healthz — a listening-but-still-starting engine must not read as available.
        // Additionally gated on deployment: a non-deployed engine has no container that
        // will ever come up, so the tile would otherwise be permanently red rather than absent.
        if (facturXDeployment.deployed && validationSettingsGate.isEnabled(ValidationCapabilityId.FACTURX_ENGINE)) {
            val facturx = tcpEndpointFromUrl(optionalFeatures.facturxEngineUrl, LOCALHOST, FACTURX_ENGINE_PORT)
            dependencies += SystemStatusService.ServiceStatus(
                key = "facturx",
                instanceName = "Factur-X Engine",
                target = "${facturx.display()}/healthz",
                up = checkTcpPort(facturx.host, facturx.port) && checkFacturxHealthy(facturx),
            )
        }
    }

    /** Only called once the TCP port itself is confirmed open;
     *  mirrors `checkVeraPdfReachable`'s own reasoning against a second, redundant connection
     *  attempt when the port is already known to be closed. */
    private fun checkFacturxHealthy(engine: TcpEndpoint): Boolean = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://${engine.host}:${engine.port}/healthz"))
            .timeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        parseFacturxHealthy(response.statusCode())
    } catch (_: Exception) {
        false
    }

    /** Only called once `xpdf` itself is confirmed reachable; a down worker
     *  already explains an unreachable veraPDF without a second, redundant network attempt.
     *  Also carries the installed CLI version `VeraPdfStatusResource` already returns, so the
     *  system-status tile can surface it instead of discarding it. */
    private fun fetchVeraPdfStatus(xpdf: TcpEndpoint): VeraPdfProbe = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://${xpdf.host}:${xpdf.port}/status/verapdf"))
            .timeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == HTTP_OK) {
            VeraPdfProbe(parseVeraPdfAvailable(response.body()), parseVeraPdfVersion(response.body()))
        } else {
            VeraPdfProbe(false, "")
        }
    } catch (_: Exception) {
        VeraPdfProbe(false, "")
    }

    private fun checkTcpPort(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    data class TcpEndpoint(val host: String, val port: Int) {
        fun display(): String = "$host:$port"
    }

    data class VeraPdfProbe(val available: Boolean, val version: String)

    companion object {
        private const val TIMEOUT_MS = 2000
        private const val LOCALHOST = "localhost"
        private const val POSTGRES_PORT = 5432
        private const val RUSTFS_PORT = 9000
        private const val OMLX_PORT = 8020
        private const val NATS_PORT = 4222
        private const val XPDF_PORT = 8081
        private const val VALKEY_PORT = 6379
        private const val HTTP_OK = 200
        // facturx-arc-improve.md §6.3 — the engine's own `EXPOSE 8000`/Gunicorn bind, confirmed
        // against the running 2.1.0 image in the gate protocol
        // (§3), not a guess.
        private const val FACTURX_ENGINE_PORT = 8000

        private const val DEFAULT_NATS_URL = "nats://localhost:4222"
        private const val DEFAULT_XPDF_URL = "http://localhost:8081"
        const val DEFAULT_FACTURX_ENGINE_URL = "http://facturx-engine-api:8000"

        fun tcpEndpointFromJdbcUrl(jdbcUrl: String?): TcpEndpoint {
            if (jdbcUrl.isNullOrBlank()) {
                return TcpEndpoint(LOCALHOST, POSTGRES_PORT)
            }
            val normalized = if (jdbcUrl.startsWith("jdbc:")) jdbcUrl.removePrefix("jdbc:") else jdbcUrl
            return tcpEndpointFromUrl(normalized, LOCALHOST, POSTGRES_PORT)
        }

        fun tcpEndpointFromUrl(url: String?, defaultHost: String, defaultPort: Int): TcpEndpoint {
            if (url.isNullOrBlank()) {
                return TcpEndpoint(defaultHost, defaultPort)
            }
            val trimmed = url.trim()
            // Handle "host:port" format without http:// (common in config values).
            if (trimmed.matches(Regex("^[^:/]+:\\d+$")) && !trimmed.startsWith("http", true)) {
                val parts = trimmed.split(":")
                return TcpEndpoint(parts[0], parts[1].toIntOrNull() ?: defaultPort)
            }

            return try {
                val normalizedUrl = when {
                    trimmed.contains("://") -> trimmed
                    else -> "http://$trimmed"
                }
                val uri = URI.create(normalizedUrl)
                val host = uri.host?.trim().takeUnless { it.isNullOrBlank() } ?: defaultHost
                val port = if (uri.port > 0) uri.port else defaultPort
                TcpEndpoint(host, port)
            } catch (_: IllegalArgumentException) {
                TcpEndpoint(defaultHost, defaultPort)
            }
        }

        fun displayNameFromHost(host: String?, fallback: String): String {
            if (host.isNullOrBlank()) {
                return fallback
            }
            val trimmed = host.trim()
            val firstDot = trimmed.indexOf('.')
            return if (firstDot > 0) trimmed.substring(0, firstDot) else trimmed
        }

        /** Pure parsing kept separate from `checkVeraPdfReachable`'s actual HTTP
         *  call so the JSON-shape logic is unit-testable without a running worker. Deliberately
         *  tolerant (a regex, not full JSON parsing): a malformed/unexpected body is "not
         *  available", not a parse exception the caller would need to catch a second time. */
        fun parseVeraPdfAvailable(body: String): Boolean =
            Regex("\"available\"\\s*:\\s*true").containsMatchIn(body)

        /** Mirrors [parseVeraPdfAvailable]'s tolerant-regex approach; a missing/malformed
         *  `version` field yields "" rather than a parse exception. */
        fun parseVeraPdfVersion(body: String): String =
            Regex("\"version\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: ""

        /** Kept as its own function (like
         *  [parseVeraPdfAvailable]) so the "what counts as healthy" decision is unit-testable
         *  without a running engine. `/healthz` is a plain liveness probe, not a JSON body to
         *  parse — 200 is the entire contract. */
        fun parseFacturxHealthy(statusCode: Int): Boolean = statusCode == HTTP_OK
    }
}
