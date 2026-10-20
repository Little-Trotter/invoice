package de.littletrotter.infrastructure.docker

import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.application.system.port.outbound.SystemStatusService
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
import de.littletrotter.application.dto.ServiceStatus

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
 * on (oMLX) — split out for the same `LongParameterList` reason as
 * [DockerStatusEndpoints].
 */
@ApplicationScoped
class DockerStatusOptionalFeatures(
    @param:ConfigProperty(name = "invoice.omlx.enabled", defaultValue = "false")
    val omlxEnabled: Boolean,
    @param:ConfigProperty(name = "invoice.omlx.url")
    val omlxUrl: Optional<String>,
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
) : SystemStatusService {

    override fun listDependencies(): List<ServiceStatus> {
        val postgres = tcpEndpointFromJdbcUrl(endpoints.datasourceJdbcUrl.orElse(null))
        val rustfs = tcpEndpointFromUrl(endpoints.rustfsEndpoint.orElse(null), LOCALHOST, RUSTFS_PORT)
        val nats = tcpEndpointFromUrl(endpoints.natsUrl.orElse(DEFAULT_NATS_URL), LOCALHOST, NATS_PORT)
        val valkey = tcpEndpointFromUrl(endpoints.valkeyEndpoint.orElse(null), LOCALHOST, VALKEY_PORT)
        val xpdf = tcpEndpointFromUrl(endpoints.xpdfUrl.orElse(DEFAULT_XPDF_URL), LOCALHOST, XPDF_PORT)
        val xpdfUp = checkTcpPort(xpdf.host, xpdf.port)
        val invoice = TcpEndpoint(LOCALHOST, endpoints.quarkusHttpPort)

        val dependencies = mutableListOf(
            ServiceStatus(
                key = "postgresql",
                instanceName = displayNameFromHost(postgres.host, "postgres"),
                target = postgres.display(),
                up = checkTcpPort(postgres.host, postgres.port),
            ),
            ServiceStatus(
                key = "rustfs",
                instanceName = displayNameFromHost(rustfs.host, "rustfs"),
                target = rustfs.display(),
                up = checkTcpPort(rustfs.host, rustfs.port),
            ),
            ServiceStatus(
                key = "nats",
                instanceName = displayNameFromHost(nats.host, "nats"),
                target = nats.display(),
                up = checkTcpPort(nats.host, nats.port),
            ),
            ServiceStatus(
                key = "valkey",
                instanceName = displayNameFromHost(valkey.host, "Valkey"),
                target = valkey.display(),
                up = checkTcpPort(valkey.host, valkey.port),
            ),
            ServiceStatus(
                key = "xpdf",
                instanceName = displayNameFromHost(xpdf.host, "xpdf"),
                target = xpdf.display(),
                up = xpdfUp,
            ),
            ServiceStatus(
                key = "invoice",
                instanceName = "Invoice App",
                target = invoice.display(),
                up = checkTcpPort(invoice.host, invoice.port),
            ),
            ServiceStatus(
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
     *  change, same conditions and same [ServiceStatus] entries as before. */
    private fun appendOptionalDependencies(
        dependencies: MutableList<ServiceStatus>,
        xpdf: TcpEndpoint,
        xpdfUp: Boolean,
    ) {
        if (optionalFeatures.omlxEnabled) {
            val omlx = tcpEndpointFromUrl(optionalFeatures.omlxUrl.orElse(null), LOCALHOST, OMLX_PORT)
            dependencies += ServiceStatus(
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
            dependencies += ServiceStatus(
                key = "verapdf",
                instanceName = "veraPDF",
                target = "${xpdf.display()}/status/verapdf",
                up = veraPdfStatus.available,
                version = veraPdfStatus.version,
            )
        }
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
        private val HOST_PORT = Regex("^[^:/]+:\\d+$")
        private val AVAILABLE_TRUE = Regex("\"available\"\\s*:\\s*true")
        private val VERSION_VALUE = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")
        private const val TIMEOUT_MS = 2000
        private const val LOCALHOST = "localhost"
        private const val POSTGRES_PORT = 5432
        private const val RUSTFS_PORT = 9000
        private const val OMLX_PORT = 8020
        private const val NATS_PORT = 4222
        private const val XPDF_PORT = 8081
        private const val VALKEY_PORT = 6379
        private const val HTTP_OK = 200

        private const val DEFAULT_NATS_URL = "nats://localhost:4222"
        private const val DEFAULT_XPDF_URL = "http://localhost:8081"

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
            if (trimmed.matches(HOST_PORT) && !trimmed.startsWith("http", true)) {
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
            AVAILABLE_TRUE.containsMatchIn(body)

        /** Mirrors [parseVeraPdfAvailable]'s tolerant-regex approach; a missing/malformed
         *  `version` field yields "" rather than a parse exception. */
        fun parseVeraPdfVersion(body: String): String =
            VERSION_VALUE.find(body)?.groupValues?.get(1) ?: ""
    }
}
