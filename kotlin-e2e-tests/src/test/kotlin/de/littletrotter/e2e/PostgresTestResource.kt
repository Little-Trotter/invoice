package de.littletrotter.e2e

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/**
 * Provisions the same lt_migrator/lt_app role separation the real stack uses
 * (docker/postgres/init-roles.sh + V1__baseline_schema.sql section 13), so e2e runs exercise
 * the actual runtime grants instead of the Testcontainers bootstrap superuser. The container's
 * own superuser ("test") is used only to create the two roles, mirroring how the bootstrap
 * role is provisioning-only in production.
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
        // Not Quarkus config — published for tests that drive the ADMIN-side tooling
        // (admin-tools/scripts/backup: pg_dump/pg_restore run as the bootstrap role in
        // production, never as lt_app, which by design owns no table and may not DDL). Named
        // apart from the `quarkus.*` keys so nothing can mistake them for datasource settings.
        props["test.postgres.admin-username"] = postgres.username
        props["test.postgres.admin-password"] = postgres.password
        props["test.postgres.lt-app-password"] = LT_APP_PASSWORD
        // This module also carries kotlin-invoice's own application.properties on the
        // classpath (quarkus.index-dependency makes the app classes/resources of the indexed
        // dependency visible here) — it defines quarkus.datasource.jdbc.url/username/password
        // (and now quarkus.flyway.username/password) with its own literal fallback defaults.
        // Empirically, in this cross-module "index-dependency" boot path the returned map above
        // does NOT reliably outrank that second application.properties for every key (observed:
        // username/password got overridden as expected, the URL silently did not, sending Flyway
        // at a real, unrelated Postgres instance on localhost:5432 instead of this ephemeral
        // container — this was diagnosed once and is now guarded against). System properties carry a
        // strictly higher SmallRye Config ordinal than any properties file, so setting them
        // directly here — before Quarkus's config is assembled — removes the ambiguity outright,
        // the same fix already applied for username/password via kotlin-e2e-tests/pom.xml's
        // failsafe systemPropertyVariables (those are static; this one has to be dynamic, since
        // the container's mapped port is only known once it has started).
        props.forEach { (key, value) -> System.setProperty(key, value) }
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
