package de.littletrotter.e2e

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait

/**
 * The real offer module for E2E runs: the `quote-app` image plus its own Postgres on one private
 * Docker network. NATS is the run's [CentralNats], the same server the invoicing
 * application publishes its master data to, reached through the host gateway; the module creates
 * the master data stream on it if the invoicing application has not yet.
 *
 * The object store is not private either: the module uses the run's [S3TestResource] through the
 * host gateway. Two containers (offer module and mail module) and the test JVM must see one store:
 * the dispatch copy the test puts there has to be readable by the module, and the presigned URLs
 * the module hands to the mail module carry the store's host name inside their signature, so that
 * name must be one both containers resolve (`host.docker.internal`). [S3TestResource] therefore
 * starts first, and the mail module, when part of the run, before this resource: its address is
 * only known once it is up, and the offer module needs it to hand over drafts. The invoicing application
 * under test talks to the module over HTTP only, as in a deployment; nothing of `kotlin-quote` is
 * on this module's classpath.
 *
 * The image is not built here (its Dockerfile's build context is the whole repository); the
 * Makefile target in [IMAGE_BUILD_HINT] is the single build path, and a missing image fails the run
 * with that command rather than skipping the test.
 *
 * Publishes `quote.base-url` and `quote.integration-token` for the invoicing application, and the
 * same values under [BASE_URL_PROPERTY] and [TOKEN_PROPERTY] for assertions against the module.
 */
class QuoteAppTestResource : QuarkusTestResourceLifecycleManager {

    private val network: Network = Network.newNetwork()

    private val postgres = GenericContainer("postgres:18")
        .withNetwork(network)
        .withNetworkAliases(POSTGRES_ALIAS)
        .withEnv("POSTGRES_DB", DATABASE)
        .withEnv("POSTGRES_USER", DB_USER)
        .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))

    private val quoteApp = GenericContainer(quoteAppImage())
        .withNetwork(network)
        .withExtraHost(HOST_ALIAS, "host-gateway")
        .withExposedPorts(APP_PORT)
        .withEnv(
            mapOf(
                "QUARKUS_PROFILE" to "stack",
                "QUOTE_DB_URL" to "jdbc:postgresql://$POSTGRES_ALIAS:5432/$DATABASE",
                "QUOTE_DB_USER" to DB_USER,
                "QUOTE_DB_PASSWORD" to DB_PASSWORD,
                "QUOTE_DB_MIGRATOR_USER" to DB_USER,
                "QUOTE_DB_MIGRATOR_PASSWORD" to DB_PASSWORD,
                "QUOTE_S3_ACCESS_KEY" to S3_KEY,
                "QUOTE_S3_SECRET_KEY" to S3_KEY,
                "QUOTE_S3_BUCKET" to "quote-documents",
                "QUOTE_INTEGRATION_TOKEN" to INTEGRATION_TOKEN,
            ),
        )
        .waitingFor(Wait.forHttp("/q/health/ready").forPort(APP_PORT).forStatusCode(HTTP_OK))

    init {
        // Fixed host port: a fault test stops and starts this container and the invoicing application keeps the address.
        quoteApp.setPortBindings(listOf("${ContainerFaults.freePort()}:$APP_PORT"))
    }

    override fun order(): Int = ORDER_AFTER_INFRASTRUCTURE

    override fun start(): MutableMap<String, String> {
        val nats = CentralNats.acquire()
        quoteApp.withEnv("QUOTE_NATS_URL", "nats://$HOST_ALIAS:${nats.port}")
        quoteApp.withEnv("QUOTE_S3_ENDPOINT", viaHostGateway(requiredProperty("s3.endpoint")))
        mailEnv().forEach { (key, value) -> quoteApp.withEnv(key, value) }
        postgres.start()
        try {
            quoteApp.start()
        } catch (e: ContainerLaunchException) {
            throw IllegalStateException("quote-app did not become ready; its log tail:\n${quoteApp.logs.takeLast(LOG_TAIL_CHARS)}", e)
        }
        val baseUrl = "http://${quoteApp.host}:${quoteApp.getMappedPort(APP_PORT)}"
        val props = mutableMapOf(
            "quote.base-url" to baseUrl,
            "quote.integration-token" to INTEGRATION_TOKEN,
            BASE_URL_PROPERTY to baseUrl,
            TOKEN_PROPERTY to INTEGRATION_TOKEN,
            ContainerFaults.QUOTE_APP_CONTAINER_ID_PROPERTY to quoteApp.containerId,
        )
        // A system property outranks the kotlin-invoice application.properties that shares this classpath.
        props.forEach { (key, value) -> System.setProperty(key, value) }
        return props
    }

    override fun stop() {
        quoteApp.stop()
        postgres.stop()
        network.close()
        CentralNats.release()
    }

    /** Mail hand-over settings, only when the mail module is part of this run (it publishes them on start). */
    private fun mailEnv(): Map<String, String> {
        val baseUrl = System.getProperty("mail.base-url") ?: return emptyMap()
        return mapOf(
            "MAIL_ENABLED" to "true",
            "MAIL_BASE_URL" to viaHostGateway(baseUrl),
            "MAIL_INTEGRATION_TOKEN" to requiredProperty("mail.integration-token"),
        )
    }

    companion object {
        /** Tag the image must carry; `E2E_QUOTE_APP_IMAGE` selects another one. */
        const val DEFAULT_IMAGE = "little-trotter-quote-app:e2e"

        /** The one build path of the image, run from the repository root. */
        const val IMAGE_BUILD_HINT = "make e2e-quote-image"

        /** Config keys through which the running resource hands its endpoint to the tests. */
        const val BASE_URL_PROPERTY = "quote.e2e.base-url"
        const val TOKEN_PROPERTY = "quote.e2e.integration-token"

        private const val HOST_ALIAS = "host.docker.internal"
        private const val LOG_TAIL_CHARS = 4000
        private const val POSTGRES_ALIAS = "quote-postgres"
        private const val DATABASE = "littletrotter"
        private const val DB_USER = "postgres"
        private const val DB_PASSWORD = "postgres"
        private const val S3_KEY = "rustfsadmin"
        private const val ORDER_AFTER_INFRASTRUCTURE = 10
        private const val INTEGRATION_TOKEN = "e2e-quote-integration-token"
        private const val APP_PORT = 8080
        private const val HTTP_OK = 200

        private fun requiredProperty(name: String): String =
            checkNotNull(System.getProperty(name)) { "system property '$name' is not set; the resource that publishes it must start first" }

        /** The same endpoint as seen from a container: the host part becomes the host gateway alias. */
        private fun viaHostGateway(url: String): String {
            val uri = java.net.URI.create(url)
            return java.net.URI(uri.scheme, null, HOST_ALIAS, uri.port, uri.path, null, null).toString()
        }

        private fun quoteAppImage(): String {
            val image = System.getenv("E2E_QUOTE_APP_IMAGE")?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE
            val present = DockerClientFactory.lazyClient().listImagesCmd().withImageNameFilter(image).exec().isNotEmpty()
            check(present) { "quote-app image '$image' not found locally; build it with: $IMAGE_BUILD_HINT" }
            return image
        }
    }
}
