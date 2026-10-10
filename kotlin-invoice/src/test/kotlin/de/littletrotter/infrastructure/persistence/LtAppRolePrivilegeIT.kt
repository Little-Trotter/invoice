package de.littletrotter.infrastructure.persistence

import de.littletrotter.test.withAppNow
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * closes the coverage gap the work package's own report explicitly deferred:
 * `InvoiceFreezeTriggerIT`/`InvoiceAuditJournalCaptureTriggerIT`/`NumberJournalIT` all connect as
 * the Testcontainers bootstrap superuser and prove the *trigger* rejects append-only mutations —
 * a real defense, but independent of which role issues the statement. This class proves the
 * *grant* boundary itself: that `lt_app` (the role every application/job-worker/datagen process
 * connects as, per `V1__baseline_schema.sql` section 14/) genuinely lacks the privilege to
 * even attempt the statements that would matter, not merely that a trigger happens to block them
 * once attempted. Runs the *actual* `db/migration/ddl,db/migration/seed` classpath locations via
 * Flyway's own Java API (same locations `application.properties` configures), so this exercises
 * the real baseline file, not a hand-copied excerpt of its grants.
 *
 * Found via a live-Postgres test run (2026-09-04) that this gap existed at all — see the sibling
 * commit fixing the two bugs that same run surfaced.
 */
class LtAppRolePrivilegeIT {

    private fun withProvisionedPostgres(block: (PostgreSQLContainer) -> Unit) {
        val postgres = PostgreSQLContainer("postgres:18")
        postgres.start()
        try {
            provisionRoles(postgres)
            val result = Flyway.configure()
                .dataSource(postgres.jdbcUrl, LT_MIGRATOR_USER, LT_MIGRATOR_PASSWORD)
                .locations("classpath:db/migration/ddl", "classpath:db/migration/seed")
                .load()
                .migrate()
            assertTrue(result.success, "the full migration chain must succeed running as lt_migrator")
            block(postgres)
        } finally {
            postgres.stop()
        }
    }

    /** Mirrors docker/postgres/init-roles.sh exactly (including its follow-up fix). */
    private fun provisionRoles(postgres: PostgreSQLContainer) {
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
                st.execute("GRANT CREATE ON DATABASE \"${postgres.databaseName}\" TO $LT_MIGRATOR_USER")
            }
        }
    }

    private fun ltAppConnection(postgres: PostgreSQLContainer): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, LT_APP_USER, LT_APP_PASSWORD).withAppNow()

    private fun assertPermissionDenied(block: () -> Unit) {
        val ex = assertThrows(PSQLException::class.java, block)
        assertEquals("42501", ex.sqlState, "expected a permission-denied SQLSTATE, got: ${ex.message}")
    }

    @Test
    fun `RS-7 - lt_app cannot UPDATE number_journal`() {
        withProvisionedPostgres { postgres ->
            ltAppConnection(postgres).use { conn ->
                assertPermissionDenied {
                    conn.createStatement().execute("UPDATE number_journal SET actor = 'x' WHERE seq = 1")
                }
            }
        }
    }

    @Test
    fun `RS-7 - lt_app cannot DELETE FROM number_journal`() {
        withProvisionedPostgres { postgres ->
            ltAppConnection(postgres).use { conn ->
                assertPermissionDenied {
                    conn.createStatement().execute("DELETE FROM number_journal WHERE seq = 1")
                }
            }
        }
    }

    @Test
    fun `RS-7 - lt_app cannot TRUNCATE number_journal`() {
        withProvisionedPostgres { postgres ->
            ltAppConnection(postgres).use { conn ->
                assertPermissionDenied {
                    conn.createStatement().execute("TRUNCATE number_journal")
                }
            }
        }
    }

    @Test
    fun `RS-7 RS-8 - lt_app cannot UPDATE invoice_audit_journal`() {
        withProvisionedPostgres { postgres ->
            ltAppConnection(postgres).use { conn ->
                assertPermissionDenied {
                    conn.createStatement().execute("UPDATE invoice_audit_journal SET actor = 'x' WHERE seq = 1")
                }
            }
        }
    }

    @Test
    fun `RS-7 RS-8 - lt_app cannot DELETE FROM invoice_audit_journal`() {
        withProvisionedPostgres { postgres ->
            ltAppConnection(postgres).use { conn ->
                assertPermissionDenied {
                    conn.createStatement().execute("DELETE FROM invoice_audit_journal WHERE seq = 1")
                }
            }
        }
    }

    @Test
    fun `RS-7 RS-8 - lt_app cannot TRUNCATE invoice_audit_journal`() {
        withProvisionedPostgres { postgres ->
            ltAppConnection(postgres).use { conn ->
                assertPermissionDenied {
                    conn.createStatement().execute("TRUNCATE invoice_audit_journal")
                }
            }
        }
    }

    @Test
    fun `RS-7 - lt_app cannot run DDL`() {
        withProvisionedPostgres { postgres ->
            ltAppConnection(postgres).use { conn ->
                assertPermissionDenied {
                    conn.createStatement().execute("CREATE TABLE lt_app_should_not_be_able_to_create_this (id INT)")
                }
            }
        }
    }

    @Test
    fun `RS-7 - lt_app can still do normal DML (positive control)`() {
        withProvisionedPostgres { postgres ->
            ltAppConnection(postgres).use { conn ->
                // invoice_number is NOT NULL at the column level; "" (not SQL NULL) is the
                // established convention for "no number yet" — see capture_invoice_audit_event()'s
                // own NULLIF(invoice_number, '') translation of this same convention.
                conn.prepareStatement("INSERT INTO invoice (invoice_number, invoice_date) VALUES ('', '2026-06-15') RETURNING id").use { stmt ->
                    val rs = stmt.executeQuery()
                    assertTrue(rs.next(), "lt_app must still be able to INSERT into invoice — this is not a lockdown, only a targeted revoke")
                }
            }
        }
    }

    companion object {
        private const val LT_MIGRATOR_USER = "lt_migrator"
        private const val LT_MIGRATOR_PASSWORD = "lt_migrator_test"
        private const val LT_APP_USER = "lt_app"
        private const val LT_APP_PASSWORD = "lt_app_test"
    }
}
