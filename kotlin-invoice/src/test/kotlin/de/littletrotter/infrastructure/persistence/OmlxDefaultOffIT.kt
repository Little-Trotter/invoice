package de.littletrotter.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/**
 * AP-M10 — proves the fresh-system default for `OMLX_LLM_CHECK` against a *real* migration run:
 * Flyway's own Java API against an isolated Testcontainers Postgres, not Quarkus's shared
 * dev-services database (which other `@QuarkusTest`s may have already mutated by the time any one
 * test runs in the same fork).
 *
 * PO 2026-08-30 (spätabends) — the fresh-install default flipped to ENABLED: the KI-Prüfung must
 * be available at print without manual unlocking (Welle H); without a configured/reachable oMLX
 * the check simply does not run. The seed baseline (`seed/V2__baseline_seed.sql`) inserts
 * `OMLX_LLM_CHECK` as `enabled = TRUE` directly — a fresh install only ever runs this one INSERT
 * once, and
 * `ON CONFLICT (capability_id) DO NOTHING` (not a second migration step) is what protects a real
 * toggle on a database that migrates a second time. That guarantee is exercised by
 * [ValidationSettingsGateTest] at the unit level and proven against a real re-run below.
 */
class OmlxDefaultOffIT {

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
    fun `a fresh system enables OMLX_LLM_CHECK by default and seeds only the two remaining capability switches`() {
        withFreshPostgres { postgres ->
            val result = freshFlyway(postgres).migrate()
            assertTrue(result.success, "the full migration chain must succeed on a fresh database")

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                val ps = conn.prepareStatement("SELECT capability_id, enabled FROM validation_setting ORDER BY capability_id")
                val settings = mutableMapOf<String, Boolean>()
                ps.executeQuery().use { rs -> while (rs.next()) settings[rs.getString("capability_id")] = rs.getBoolean("enabled") }

                assertEquals(true, settings["OMLX_LLM_CHECK"], "the local-LLM check starts ENABLED on a fresh install (PO 2026-08-30)")
                assertEquals(true, settings["VERAPDF_CHECK"], "veraPDF does not depend on a local LLM and must stay on")
                assertEquals(true, settings["KOSIT_CHECK"], "KoSIT validation stays on unless a system is deliberately set up without it")
                assertEquals(setOf("OMLX_LLM_CHECK", "VERAPDF_CHECK", "KOSIT_CHECK"), settings.keys, "no other capability is seeded")
            }
        }
    }

    @Test
    fun `re-migrating an already-seeded database never overwrites a real operator choice`() {
        // ON CONFLICT (capability_id) DO NOTHING is what protects a real toggle today (not a
        // second migration step) — this proves it against Flyway's own idempotent re-run
        // (migrate() on an already-fully-migrated schema is a documented no-op, exercised here
        // for real rather than assumed).
        withFreshPostgres { postgres ->
            val baseline = freshFlyway(postgres).migrate()
            assertTrue(baseline.success)

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "UPDATE validation_setting SET enabled = FALSE, updated_at = NOW() WHERE capability_id = 'OMLX_LLM_CHECK'",
                ).executeUpdate()
            }

            val rerun = freshFlyway(postgres).migrate()
            assertTrue(rerun.success, "re-running Flyway against an already-migrated schema must succeed as a no-op")

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                val ps = conn.prepareStatement("SELECT enabled FROM validation_setting WHERE capability_id = 'OMLX_LLM_CHECK'")
                val rs = ps.executeQuery()
                assertTrue(rs.next())
                assertEquals(false, rs.getBoolean("enabled"), "a real operator opt-out must survive Flyway re-running an already-applied chain")
            }
        }
    }
}
