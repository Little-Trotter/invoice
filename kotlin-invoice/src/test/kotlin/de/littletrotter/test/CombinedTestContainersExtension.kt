package de.littletrotter.test

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Combined extension starting NATS + RustFS + PostgreSQL for system/integration tests.
 * KoSIT validator removed — not required by current test suite.
 * Usage: annotate test classes with @ExtendWith(CombinedTestContainersExtension::class)
 */
class CombinedTestContainersExtension : BeforeAllCallback {
    companion object {
        private val rustfsImage = DockerImageName.parse("rustfs/rustfs:1.0.1")
        private val postgresImage = DockerImageName.parse("postgres:18")

        fun natsUrl(): String = NatsTestContainersExtension.natsUrl()
        fun natsJetStream() = NatsTestContainersExtension.jetStream()

        private val rustfs: GenericContainer<*> by lazy {
            GenericContainer(rustfsImage)
                .withExposedPorts(9000)
                .withEnv("RUSTFS_ACCESS_KEY", "rustfs")
                .withEnv("RUSTFS_SECRET_KEY", "rustfs123")
                .withCommand("/data")
                .waitingFor(Wait.forHttp("/health").forStatusCode(200))
                .withReuse(true)
                .apply { start() }
        }

        private val postgres: PostgreSQLContainer by lazy {
            PostgreSQLContainer(postgresImage)
                .withDatabaseName("invoice_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true)
                .apply { start() }
        }

        fun rustfsEndpoint(): String = "http://${rustfs.host}:${rustfs.getMappedPort(9000)}"
        fun postgresJdbcUrl(): String = postgres.jdbcUrl
    }

    override fun beforeAll(context: ExtensionContext) {
        System.setProperty("nats.url", natsUrl())
        NatsTestContainersExtension.connection // trigger connect + stream setup
        System.setProperty("s3.endpoint", rustfsEndpoint())
        System.setProperty("s3.accessKey", "rustfs")
        System.setProperty("s3.secretKey", "rustfs123")
        System.setProperty("quarkus.datasource.jdbc.url", postgresJdbcUrl())
        System.setProperty("quarkus.datasource.username", postgres.username)
        System.setProperty("quarkus.datasource.password", postgres.password)
    }
}
