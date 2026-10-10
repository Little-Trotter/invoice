package de.littletrotter.application

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/**
 * Provisions the same lt_migrator/lt_app role separation the real stack uses
 * (docker/postgres/init-roles.sh + V1__baseline_schema.sql section 13), so this module's
 * @QuarkusTest ITs run against lt_app like production instead of the Testcontainers bootstrap
 * superuser. The container's own superuser ("test") is used only to create the two roles.
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {
    private val postgres = PostgreSQLContainer("postgres:18").apply {
        withDatabaseName("testdb")
        withUsername("test")
        withPassword("test")
        withReuse(false)
    }

    override fun start(): MutableMap<String, String> {
        postgres.start()
        provisionRoles()

        val props = mutableMapOf<String, String>()
        props["quarkus.datasource.jdbc.url"] = postgres.jdbcUrl
        props["quarkus.datasource.username"] = LT_APP_USER
        props["quarkus.datasource.password"] = LT_APP_PASSWORD
        props["quarkus.datasource.db-kind"] = "postgresql"
        props["quarkus.flyway.username"] = LT_MIGRATOR_USER
        props["quarkus.flyway.password"] = LT_MIGRATOR_PASSWORD
        return props
    }

    /**
     * Creates lt_migrator/lt_app against the running container using its bootstrap superuser —
     * the same shape as docker/postgres/init-roles.sh, just issued over a plain JDBC connection
     * instead of a container-init script, since Quarkus (and therefore Flyway) has not started
     * yet at this point.
     */
    private fun provisionRoles() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE ROLE $LT_MIGRATOR_USER WITH LOGIN NOSUPERUSER PASSWORD '$LT_MIGRATOR_PASSWORD'")
                st.execute(
                    "CREATE ROLE $LT_APP_USER WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE " +
                        "PASSWORD '$LT_APP_PASSWORD'",
                )
                st.execute("GRANT CONNECT ON DATABASE \"${postgres.databaseName}\" TO $LT_MIGRATOR_USER")
                st.execute("GRANT CONNECT ON DATABASE \"${postgres.databaseName}\" TO $LT_APP_USER")
                st.execute("GRANT USAGE, CREATE ON SCHEMA public TO $LT_MIGRATOR_USER")
                st.execute("GRANT USAGE ON SCHEMA public TO $LT_APP_USER")
                // Database-level CREATE, not just schema-level — see init-roles.sh's own
                // comment on this grant (PostgreSQL trusted-extension mechanism).
                st.execute("GRANT CREATE ON DATABASE \"${postgres.databaseName}\" TO $LT_MIGRATOR_USER")
            }
        }
    }

    override fun stop(): Unit = postgres.stop()

    companion object {
        private const val LT_MIGRATOR_USER = "lt_migrator"
        private const val LT_MIGRATOR_PASSWORD = "lt_migrator_test"
        private const val LT_APP_USER = "lt_app"
        private const val LT_APP_PASSWORD = "lt_app_test"
    }
}
