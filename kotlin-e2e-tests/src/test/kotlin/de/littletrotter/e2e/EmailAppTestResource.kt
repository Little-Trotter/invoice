package de.littletrotter.e2e

import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.util.Base64
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real mail module for E2E runs: the `email-app` image plus the infrastructure it needs
 * (own Postgres, RustFS, Valkey) and Mailpit as the SMTP sink, wired on one private
 * Docker network. NATS is not private: the module joins the run's [CentralNats] — the same server
 * the invoicing application uses — and creates its own streams on it at startup, as the
 * deployed module does. That makes the `mail.send.result` projection into the invoice real. The invoicing application under test talks to it over HTTP only, exactly as in
 * a deployment; nothing of `kotlin-email` is on this module's classpath.
 *
 * The image is not built here: the Dockerfile's build context is the whole repository, and a
 * Testcontainers image build would ship all of it to the daemon on every run. The module's own
 * Dockerfile is the single build path, see [IMAGE_BUILD_HINT]. A missing image fails the run with
 * that command rather than skipping the test.
 *
 * The mail module fetches attachments from a URL the invoicing application hands over. In a
 * deployment that is a presigned object-store URL; the store's host name is part of the signature
 * and cannot be both reachable from the host JVM and from the module's container, so the tests
 * hand over a URL below [ATTACHMENT_BASE_URL_PROPERTY] instead, serving files written to [ATTACHMENT_DIR_PROPERTY] — a plain HTTP server on the host, reachable from the
 * container as `host.docker.internal`. The fetch itself, including checksum and size verification,
 * is the module's real code path.
 *
 * Publishes `mail.enabled`, `mail.base-url` and `mail.integration-token` for the invoicing
 * application, and [MAILPIT_API_URL_PROPERTY] for assertions on what actually left the module.
 */
class EmailAppTestResource : QuarkusTestResourceLifecycleManager {

    private val network: Network = Network.newNetwork()

    private val postgres = GenericContainer("postgres:18")
        .withNetwork(network)
        .withNetworkAliases(POSTGRES_ALIAS)
        .withEnv("POSTGRES_DB", DATABASE)
        .withEnv("POSTGRES_USER", DB_USER)
        .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))

    private val rustfs = GenericContainer("rustfs/rustfs:1.0.1")
        .withNetwork(network)
        .withNetworkAliases(RUSTFS_ALIAS)
        .withExposedPorts(RUSTFS_PORT)
        .withEnv("RUSTFS_ACCESS_KEY", S3_KEY)
        .withEnv("RUSTFS_SECRET_KEY", S3_KEY)
        .withCommand("/data")
        .waitingFor(Wait.forHttp("/health").forPort(RUSTFS_PORT).forStatusCode(HTTP_OK))

    private val valkey = GenericContainer("valkey/valkey:9.1.1-alpine")
        .withNetwork(network)
        .withNetworkAliases(VALKEY_ALIAS)
        .withExposedPorts(VALKEY_PORT)
        .withCommand("valkey-server", "--save", "", "--appendonly", "no")
        .waitingFor(Wait.forListeningPort())

    private val mailpit = GenericContainer("axllent/mailpit:v1.27.7")
        .withNetwork(network)
        .withNetworkAliases(MAILPIT_ALIAS)
        .withExposedPorts(SMTP_PORT, MAILPIT_HTTP_PORT)
        .waitingFor(Wait.forListeningPorts(SMTP_PORT, MAILPIT_HTTP_PORT))

    private val emailApp = GenericContainer(emailAppImage())
        .withNetwork(network)
        .withExtraHost(HOST_ALIAS, "host-gateway")
        .withExposedPorts(APP_PORT)
        .withEnv(
            mapOf(
                "QUARKUS_PROFILE" to "stack",
                "MAIL_DB_URL" to "jdbc:postgresql://$POSTGRES_ALIAS:5432/$DATABASE",
                "MAIL_DB_USER" to DB_USER,
                "MAIL_DB_PASSWORD" to DB_PASSWORD,
                "MAIL_DB_MIGRATOR_USER" to DB_USER,
                "MAIL_DB_MIGRATOR_PASSWORD" to DB_PASSWORD,
                "MAIL_S3_ENDPOINT" to "http://$RUSTFS_ALIAS:$RUSTFS_PORT",
                "MAIL_S3_ACCESS_KEY" to S3_KEY,
                "MAIL_S3_SECRET_KEY" to S3_KEY,
                "MAIL_S3_BUCKET" to "mail-journal",
                "MAIL_SMTP_HOST" to MAILPIT_ALIAS,
                "MAIL_SMTP_PORT" to SMTP_PORT.toString(),
                "MAIL_SMTP_AUTH" to "false",
                "MAIL_SMTP_STARTTLS" to "false",
                "MAIL_SMTP_SSL" to "false",
                "MAIL_FROM_ADDRESS" to "rechnung@example.invalid",
                "MAIL_VALKEY_HOST" to VALKEY_ALIAS,
                "MAIL_VALKEY_PORT" to VALKEY_PORT.toString(),
                "MAIL_INTEGRATION_TOKEN" to INTEGRATION_TOKEN,
                "AUTH_ENABLED" to "false",
            ) + archiveSigningKeys(),
        )
        .waitingFor(Wait.forHttp("/q/health/ready").forPort(APP_PORT).forStatusCode(HTTP_OK))

    /** Before the offer module, which needs this module's address to hand drafts over. */
    override fun order(): Int = ORDER_BEFORE_OFFER_MODULE

    override fun start(): MutableMap<String, String> {
        val attachmentPort = startAttachmentHost()
        // The module only fetches from its own object store and from explicitly allowed hosts. The
        // host alone is allowed: presigned URLs of the run's object store carry its mapped port,
        // which differs from the attachment server's.
        emailApp.withEnv("MAIL_COMPOSE_FETCH_ALLOWED_HOSTS", HOST_ALIAS)
        // The central NATS comes up first and is reached through the host gateway; the module
        // creates its own streams on it while starting, before the invoicing application binds.
        val nats = CentralNats.acquire()
        emailApp.withEnv("MAIL_NATS_URL", "nats://$HOST_ALIAS:${nats.port}")
        listOf(postgres, rustfs, valkey, mailpit).forEach { it.start() }
        try {
            emailApp.start()
        } catch (e: org.testcontainers.containers.ContainerLaunchException) {
            throw IllegalStateException("email-app did not become ready; its log tail:\n${emailApp.logs.takeLast(LOG_TAIL_CHARS)}", e)
        }
        val props = mutableMapOf(
            MAILPIT_API_URL_PROPERTY to "http://${mailpit.host}:${mailpit.getMappedPort(MAILPIT_HTTP_PORT)}",
            ATTACHMENT_DIR_PROPERTY to attachmentDir.toString(),
            ATTACHMENT_BASE_URL_PROPERTY to "http://$HOST_ALIAS:$attachmentPort",
            "mail.enabled" to "true",
            "mail.base-url" to "http://${emailApp.host}:${emailApp.getMappedPort(APP_PORT)}",
            "mail.integration-token" to INTEGRATION_TOKEN,
        )
        // Same reason as the other resources of this module: a system property outranks the
        // kotlin-invoice application.properties that shares this classpath.
        props.forEach { (key, value) -> System.setProperty(key, value) }
        return props
    }

    private var attachmentDir: Path? = null

    private fun startAttachmentHost(): Int {
        val dir = Files.createTempDirectory("e2e-attachments")
        attachmentDir = dir
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val file = dir.resolve(exchange.requestURI.path.trimStart('/')).normalize()
            val bytes = if (file.startsWith(dir) && Files.isRegularFile(file)) Files.readAllBytes(file) else null
            if (bytes == null) {
                exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
            } else {
                exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        attachmentServer = server
        return server.address.port
    }

    @Volatile
    private var attachmentServer: HttpServer? = null

    override fun stop() {
        // A later test class in this JVM must not see the address of a module that is gone.
        listOf("mail.enabled", "mail.base-url", "mail.integration-token").forEach { System.clearProperty(it) }
        attachmentServer?.stop(0)
        attachmentServer = null
        attachmentDir?.toFile()?.deleteRecursively()
        attachmentDir = null
        emailApp.stop()
        listOf(mailpit, valkey, rustfs, postgres).forEach { it.stop() }
        network.close()
        CentralNats.release()
    }

    companion object {
        /** Tag the image must carry; `E2E_EMAIL_APP_IMAGE` selects another one. */
        const val DEFAULT_IMAGE = "little-trotter-email-app:e2e"

        /** The one build path of the image, run from the repository root. */
        const val IMAGE_BUILD_HINT = "make e2e-mail-image"

        /**
         * Config keys through which the running resource hands its endpoints to the tests. Quarkus
         * starts the resource in a different classloader than the one a test class sees once more
         * than one `@QuarkusTest` class runs, so static state cannot carry them; strings can.
         */
        const val MAILPIT_API_URL_PROPERTY = "mail.e2e.mailpit-api-url"
        const val ATTACHMENT_DIR_PROPERTY = "mail.e2e.attachment-dir"
        const val ATTACHMENT_BASE_URL_PROPERTY = "mail.e2e.attachment-base-url"

        private const val HOST_ALIAS = "host.docker.internal"
        private const val HTTP_NOT_FOUND = 404
        private const val ORDER_BEFORE_OFFER_MODULE = 5

        private const val LOG_TAIL_CHARS = 4000

        private const val POSTGRES_ALIAS = "mail-postgres"
        private const val RUSTFS_ALIAS = "mail-rustfs"
        private const val VALKEY_ALIAS = "mail-valkey"
        private const val MAILPIT_ALIAS = "mail-mailpit"
        private const val DATABASE = "littletrotter"
        private const val DB_USER = "postgres"
        private const val DB_PASSWORD = "postgres"
        private const val S3_KEY = "mailadmin"
        private const val INTEGRATION_TOKEN = "e2e-integration-token"
        private const val RUSTFS_PORT = 9000
        private const val VALKEY_PORT = 6379
        private const val SMTP_PORT = 1025
        private const val MAILPIT_HTTP_PORT = 8025
        private const val APP_PORT = 8080
        private const val HTTP_OK = 200

        /** A throwaway Ed25519 keypair (PKCS#8 / X.509, Base64) — the module refuses to start without archive signing keys. */
        private fun archiveSigningKeys(): Map<String, String> {
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val base64 = Base64.getEncoder()
            return mapOf(
                "MAIL_ARCHIVE_ED25519_PRIVATE_KEY" to base64.encodeToString(keyPair.private.encoded),
                "MAIL_ARCHIVE_ED25519_PUBLIC_KEY" to base64.encodeToString(keyPair.public.encoded),
            )
        }

        private fun emailAppImage(): String {
            val image = System.getenv("E2E_EMAIL_APP_IMAGE")?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE
            val present = DockerClientFactory.lazyClient().listImagesCmd().withImageNameFilter(image).exec().isNotEmpty()
            check(present) { "email-app image '$image' not found locally; build it with: $IMAGE_BUILD_HINT" }
            return image
        }
    }
}
