package de.littletrotter.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/**
 * Factur-X is an optional feature. Same pattern as
 * [OmlxDefaultOffIT] (its own KDoc explains the reasoning in full): the seed baseline
 * (`seed/V2__baseline_seed.sql`) inserts `FACTURX_ENGINE` as `enabled = FALSE` directly, so there
 * is no longer a "does a later migration respect an operator's already-made choice" scenario to
 * prove — a fresh install only ever runs this one INSERT once, and
 * `ON CONFLICT (capability_id) DO NOTHING` (not a second migration step) is what protects a real
 * toggle on a database that migrates a second time.
 */
class FacturXDefaultOffIT {

    private fun freshFlyway(postgres: PostgreSQLContainer) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration/ddl", "classpath:db/migration/seed")
        .load()

    private fun withFreshPostgres(block: (PostgreSQLContainer) -> Unit) {
        val postgres = PostgreSQLContainer("postgres:18")
        postgres.start()
        try {
            block(postgres)
        } finally {
            postgres.stop()
        }
    }

    @Test
    fun `a fresh system disables FACTURX_ENGINE by default but leaves the other capability switches unchanged`() {
        withFreshPostgres { postgres ->
            val result = freshFlyway(postgres).migrate()
            assertTrue(result.success, "the full migration chain must succeed on a fresh database")

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                val ps = conn.prepareStatement("SELECT capability_id, enabled FROM validation_setting ORDER BY capability_id")
                val settings = mutableMapOf<String, Boolean>()
                ps.executeQuery().use { rs -> while (rs.next()) settings[rs.getString("capability_id")] = rs.getBoolean("enabled") }

                assertEquals(false, settings["FACTURX_ENGINE"], "the Factur-X engine switch must be off by default on a fresh install")
                assertEquals(true, settings["OMLX_LLM_CHECK"], "unrelated to this switch — stays ON (PO 2026-08-30, Welle H)")
                assertEquals(true, settings["VERAPDF_CHECK"], "unrelated to this switch — veraPDF stays on")
            }
        }
    }

    @Test
    fun `re-migrating an already-seeded database never overwrites a real operator choice`() {
        // ON CONFLICT (capability_id) DO NOTHING is what protects a real toggle today (not a
        // second migration step) — this proves it against Flyway's own idempotent re-run
        // (migrate() on an already-fully-migrated schema is a documented no-op, exercised here
        // for real rather than assumed). Mirrors OmlxDefaultOffIT's equivalent test.
        withFreshPostgres { postgres ->
            val baseline = freshFlyway(postgres).migrate()
            assertTrue(baseline.success)

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "UPDATE validation_setting SET enabled = TRUE, updated_by = 'operator', updated_at = NOW() " +
                        "WHERE capability_id = 'FACTURX_ENGINE'",
                ).executeUpdate()
            }

            val rerun = freshFlyway(postgres).migrate()
            assertTrue(rerun.success, "re-running Flyway against an already-migrated schema must succeed as a no-op")

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                val ps = conn.prepareStatement("SELECT enabled FROM validation_setting WHERE capability_id = 'FACTURX_ENGINE'")
                val rs = ps.executeQuery()
                assertTrue(rs.next())
                assertEquals(true, rs.getBoolean("enabled"), "a real operator choice must survive Flyway re-running an already-applied chain")
            }
        }
    }
}
