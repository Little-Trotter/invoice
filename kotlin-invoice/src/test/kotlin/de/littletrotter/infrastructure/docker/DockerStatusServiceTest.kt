package de.littletrotter.infrastructure.docker

import com.sun.net.httpserver.HttpServer
import de.littletrotter.application.invoice.FacturXDeployment
import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.reference.ValidationCapabilityId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Optional

class DockerStatusServiceTest {

    private val serversToStop = mutableListOf<HttpServer>()
    private val socketsToClose = mutableListOf<ServerSocket>()

    @AfterEach
    fun cleanup() {
        serversToStop.forEach { it.stop(0) }
        serversToStop.clear()
        socketsToClose.forEach { it.close() }
        socketsToClose.clear()
    }

    private fun startHttpServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        serversToStop += server
        return server
    }

    private fun respond(server: HttpServer, path: String, status: Int, body: String) {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    /** All other dependencies are irrelevant to the facturx-entry tests below — Optional.empty()
     *  everywhere makes each one fall back to its own localhost default, same as an unconfigured
     *  dev box. `deployed` defaults to `true` so tests unconcerned with that axis keep
     *  exercising that gate (the switch) alone. */
    private fun dockerStatusService(
        gate: ValidationSettingsGate,
        facturxEngineUrl: String = "http://127.0.0.1:1",
        deployed: Boolean = true,
        xpdfUrl: Optional<String> = Optional.empty(),
        quarkusHttpPort: Int = 8080,
        omlxEnabled: Boolean = false,
        omlxUrl: Optional<String> = Optional.empty(),
    ): DockerStatusService =
        DockerStatusService(
            endpoints = DockerStatusEndpoints(
                datasourceJdbcUrl = Optional.empty(),
                rustfsEndpoint = Optional.empty(),
                natsUrl = Optional.empty(),
                valkeyEndpoint = Optional.empty(),
                xpdfUrl = xpdfUrl,
                quarkusHttpPort = quarkusHttpPort,
            ),
            optionalFeatures = DockerStatusOptionalFeatures(
                omlxEnabled = omlxEnabled,
                omlxUrl = omlxUrl,
                facturxEngineUrl = facturxEngineUrl,
            ),
            validationSettingsGate = gate,
            facturXDeployment = FacturXDeployment(deployed),
        )

    @Test
    fun `tcpEndpointFromJdbcUrl uses configured compose host`() {
        val endpoint = DockerStatusService.tcpEndpointFromJdbcUrl("jdbc:postgresql://postgres:5432/rechnung")

        assertEquals("postgres", endpoint.host)
        assertEquals(5432, endpoint.port)
        assertEquals("postgres:5432", endpoint.display())
    }

    @Test
    fun `tcpEndpointFromUrl uses configured compose host`() {
        val endpoint = DockerStatusService.tcpEndpointFromUrl(
            "http://rustfs:9000",
            "localhost",
            9000,
        )

        assertEquals("rustfs", endpoint.host)
        assertEquals(9000, endpoint.port)
    }

    @Test
    fun `tcpEndpointFromUrl falls back for invalid input`() {
        val endpoint = DockerStatusService.tcpEndpointFromUrl(
            "not a valid url",
            "localhost",
            11434,
        )

        assertEquals("localhost", endpoint.host)
        assertEquals(11434, endpoint.port)
    }

    @Test
    fun `boundary - tcpEndpointFromJdbcUrl falls back to the postgres default for a null or blank jdbcUrl`() {
        assertEquals(DockerStatusService.TcpEndpoint("localhost", 5432), DockerStatusService.tcpEndpointFromJdbcUrl(null))
        assertEquals(DockerStatusService.TcpEndpoint("localhost", 5432), DockerStatusService.tcpEndpointFromJdbcUrl("   "))
    }

    @Test
    fun `boundary - tcpEndpointFromJdbcUrl accepts a url without the jdbc- prefix unchanged`() {
        val endpoint = DockerStatusService.tcpEndpointFromJdbcUrl("postgresql://postgres:5432/rechnung")

        assertEquals("postgres", endpoint.host)
        assertEquals(5432, endpoint.port)
    }

    @Test
    fun `boundary - tcpEndpointFromUrl falls back to the given default for a null or blank url`() {
        assertEquals(DockerStatusService.TcpEndpoint("localhost", 4222), DockerStatusService.tcpEndpointFromUrl(null, "localhost", 4222))
        assertEquals(DockerStatusService.TcpEndpoint("localhost", 4222), DockerStatusService.tcpEndpointFromUrl("   ", "localhost", 4222))
    }

    @Test
    fun `boundary - tcpEndpointFromUrl reads a bare host colon port value without a scheme`() {
        val endpoint = DockerStatusService.tcpEndpointFromUrl("nats:4222", "localhost", 4222)

        assertEquals("nats", endpoint.host)
        assertEquals(4222, endpoint.port)
    }

    @Test
    fun `boundary - tcpEndpointFromUrl falls back to the default host when a parsed url has none`() {
        val endpoint = DockerStatusService.tcpEndpointFromUrl("http:///no-host-path", "localhost", 9000)

        assertEquals("localhost", endpoint.host)
        assertEquals(9000, endpoint.port)
    }

    @Test
    fun `boundary - tcpEndpointFromUrl falls back to the default port for a host colon port value that overflows an Int`() {
        val endpoint = DockerStatusService.tcpEndpointFromUrl("nats:99999999999", "localhost", 4222)

        assertEquals("nats", endpoint.host)
        assertEquals(4222, endpoint.port)
    }

    @Test
    fun `boundary - a host-colon-port value that itself starts with http skips the bare shortcut and still parses`() {
        val endpoint = DockerStatusService.tcpEndpointFromUrl("http:4222", "localhost", 9000)

        assertEquals("http", endpoint.host)
        assertEquals(4222, endpoint.port)
    }

    @Test
    fun `boundary - tcpEndpointFromUrl falls back to the default port when a parsed url has none`() {
        val endpoint = DockerStatusService.tcpEndpointFromUrl("http://rustfs", "localhost", 9000)

        assertEquals("rustfs", endpoint.host)
        assertEquals(9000, endpoint.port)
    }

    @Test
    fun `displayNameFromHost shortens dns names`() {
        assertEquals("postgres", DockerStatusService.displayNameFromHost("postgres.internal", "fallback"))
        assertEquals("localhost", DockerStatusService.displayNameFromHost("localhost", "fallback"))
        assertEquals("fallback", DockerStatusService.displayNameFromHost("  ", "fallback"))
        assertEquals("fallback", DockerStatusService.displayNameFromHost(null, "fallback"))
    }

    // ── The veraPDF consistency hint ──────────────────────────
    // Only the JSON-shape parsing is unit-testable here without a running kotlin-xpdf worker;
    // the actual HTTP call (`fetchVeraPdfStatus`) needs a real or mocked HTTP server and remains
    // an open verification item.

    @Test
    fun `parseVeraPdfAvailable reads a true flag`() {
        assertTrue(DockerStatusService.parseVeraPdfAvailable("""{"available":true,"version":"1.28.2"}"""))
    }

    @Test
    fun `parseVeraPdfAvailable reads a false flag`() {
        assertEquals(false, DockerStatusService.parseVeraPdfAvailable("""{"available":false,"version":""}"""))
    }

    @Test
    fun `parseVeraPdfAvailable treats malformed bodies as not available`() {
        assertEquals(false, DockerStatusService.parseVeraPdfAvailable(""))
        assertEquals(false, DockerStatusService.parseVeraPdfAvailable("not json at all"))
    }

    @Test
    fun `parseVeraPdfVersion reads the version field`() {
        assertEquals("1.28.2", DockerStatusService.parseVeraPdfVersion("""{"available":true,"version":"1.28.2"}"""))
    }

    @Test
    fun `parseVeraPdfVersion treats a missing or malformed body as blank`() {
        assertEquals("", DockerStatusService.parseVeraPdfVersion("""{"available":false,"version":""}"""))
        assertEquals("", DockerStatusService.parseVeraPdfVersion(""))
        assertEquals("", DockerStatusService.parseVeraPdfVersion("not json at all"))
    }

    // ── facturx-arc-improve.md §3.2 Stufe 1 / §7 — the facturx consistency hint ──────────
    // Same shape as the veraPDF block above: the pure /healthz-status parsing is unit-testable
    // without a running engine, and the conditional entry itself only needs the switch — the
    // actual reachability check needs a real or mocked HTTP server (open item, same as veraPDF's
    // own note above).

    @Test
    fun `parseFacturxHealthy treats 200 as healthy`() {
        assertTrue(DockerStatusService.parseFacturxHealthy(200))
    }

    @Test
    fun `parseFacturxHealthy treats anything else as not healthy`() {
        assertFalse(DockerStatusService.parseFacturxHealthy(404))
        assertFalse(DockerStatusService.parseFacturxHealthy(500))
        assertFalse(DockerStatusService.parseFacturxHealthy(0))
    }

    @Test
    fun `facturx entry is present when the FACTURX_ENGINE switch is on`() {
        val service = dockerStatusService(validationSettingsGate())

        val dependencies = service.listDependencies()

        assertTrue(dependencies.any { it.key == "facturx" })
    }

    @Test
    fun `facturx entry is absent when the FACTURX_ENGINE switch is off`() {
        val service = dockerStatusService(validationSettingsGate(ValidationCapabilityId.FACTURX_ENGINE))

        val dependencies = service.listDependencies()

        assertFalse(dependencies.any { it.key == "facturx" })
    }

    // A non-deployed engine has no container to be red about: the tile must be omitted,
    // not shown perpetually down, even with the switch left on from an earlier deployment.
    @Test
    fun `facturx entry is absent when not deployed, even with the switch on`() {
        val service = dockerStatusService(validationSettingsGate(), deployed = false)

        val dependencies = service.listDependencies()

        assertFalse(dependencies.any { it.key == "facturx" })
    }

    // ── Additional coverage ─────────────────────────────────────────────────────

    @Test
    fun `happy path - listDependencies reports exactly the seven base entries when no optional feature is on`() {
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.VERAPDF_CHECK, ValidationCapabilityId.FACTURX_ENGINE),
        )

        val dependencies = service.listDependencies()

        assertEquals(
            listOf("postgresql", "rustfs", "nats", "valkey", "xpdf", "invoice", "worker"),
            dependencies.map { it.key },
        )
    }

    @Test
    fun `boundary - a real listening TCP port reports the dependency up`() {
        // Bound to the wildcard address (no explicit bind host) so it accepts on whatever
        // "localhost" resolves to here (IPv4 127.0.0.1 or IPv6 ::1) — the production code always
        // probes "localhost" itself, and a mismatch there is exactly what broke this the first time.
        val socket = ServerSocket(0)
        socketsToClose += socket
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.VERAPDF_CHECK, ValidationCapabilityId.FACTURX_ENGINE),
            quarkusHttpPort = socket.localPort,
        )

        val dependencies = service.listDependencies()

        assertTrue(dependencies.single { it.key == "invoice" }.up)
        assertTrue(dependencies.single { it.key == "worker" }.up)
    }

    @Test
    fun `boundary - omlx entry is present but reported down when enabled and unreachable`() {
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.VERAPDF_CHECK, ValidationCapabilityId.FACTURX_ENGINE),
            omlxEnabled = true,
            omlxUrl = Optional.of("http://127.0.0.1:1"),
        )

        val dependencies = service.listDependencies()

        val omlx = dependencies.single { it.key == "omlx" }
        assertFalse(omlx.up, "port 1 never accepts a connection")
    }

    @Test
    fun `boundary - veraPDF entry is up with its version once xpdf answers status verapdf`() {
        val server = startHttpServer()
        respond(server, "/status/verapdf", 200, """{"available":true,"version":"1.28.2"}""")
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.FACTURX_ENGINE),
            xpdfUrl = Optional.of("http://127.0.0.1:${server.address.port}"),
        )

        val dependencies = service.listDependencies()

        val verapdf = dependencies.single { it.key == "verapdf" }
        assertTrue(verapdf.up)
        assertEquals("1.28.2", verapdf.version)
    }

    @Test
    fun `boundary - veraPDF entry is down when xpdf answers status verapdf with a non-200 status`() {
        val server = startHttpServer()
        respond(server, "/status/verapdf", 500, "")
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.FACTURX_ENGINE),
            xpdfUrl = Optional.of("http://127.0.0.1:${server.address.port}"),
        )

        val dependencies = service.listDependencies()

        assertFalse(dependencies.single { it.key == "verapdf" }.up)
    }

    @Test
    fun `boundary - facturx entry is up once the port is open and healthz answers 200`() {
        val server = startHttpServer()
        respond(server, "/healthz", 200, "")
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.VERAPDF_CHECK),
            facturxEngineUrl = "http://127.0.0.1:${server.address.port}",
        )

        val dependencies = service.listDependencies()

        assertTrue(dependencies.single { it.key == "facturx" }.up)
    }

    @Test
    fun `boundary - facturx entry stays down when the port is open but healthz answers unhealthy`() {
        val server = startHttpServer()
        respond(server, "/healthz", 503, "")
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.VERAPDF_CHECK),
            facturxEngineUrl = "http://127.0.0.1:${server.address.port}",
        )

        val dependencies = service.listDependencies()

        assertFalse(
            dependencies.single { it.key == "facturx" }.up,
            "a listening-but-unhealthy engine must not read as available",
        )
    }

    /** A raw TCP listener (no HTTP behind it) that accepts and immediately closes every
     *  connection — the port itself reports open ([checkTcpPort] succeeds), but the following
     *  HTTP request throws instead of returning a status code, exercising `checkFacturxHealthy`'s
     *  and `fetchVeraPdfStatus`'s own `catch` branches. */
    private fun startPortThatAcceptsAndCloses(): ServerSocket {
        val socket = ServerSocket(0)
        socketsToClose += socket
        Thread {
            try {
                while (!socket.isClosed) {
                    socket.accept().close()
                }
            } catch (_: Exception) {
                // Socket closed by test teardown — expected.
            }
        }.apply { isDaemon = true }.start()
        return socket
    }

    @Test
    fun `boundary - veraPDF entry is down when the port is open but the HTTP call itself throws`() {
        val socket = startPortThatAcceptsAndCloses()
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.FACTURX_ENGINE),
            xpdfUrl = Optional.of("http://127.0.0.1:${socket.localPort}"),
        )

        val dependencies = service.listDependencies()

        assertFalse(dependencies.single { it.key == "verapdf" }.up)
    }

    @Test
    fun `boundary - facturx entry is down when the port is open but the HTTP call itself throws`() {
        val socket = startPortThatAcceptsAndCloses()
        val service = dockerStatusService(
            validationSettingsGate(ValidationCapabilityId.VERAPDF_CHECK),
            facturxEngineUrl = "http://127.0.0.1:${socket.localPort}",
        )

        val dependencies = service.listDependencies()

        assertFalse(dependencies.single { it.key == "facturx" }.up)
    }
}
