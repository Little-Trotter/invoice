package de.littletrotter.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Isolated baseline reference data used by synthetic datagen; no instance or customer data. */
class CatalogueNumberRangeMigrationIT {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `fresh baseline seeds catalogue range and repeated startup preserves customization`(customName: Boolean) {
        PostgreSQLContainer("postgres:18").use { postgres ->
            postgres.start()
            fun flyway() = Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration/ddl", "classpath:db/migration/seed")
                .load()
            val migration = flyway().migrate()
            assertTrue(migration.success)
            assertEquals(2, migration.migrationsExecuted)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { statement ->
                    val id = statement.executeQuery("SELECT id FROM number_range WHERE code = 'CATALOGUE_DEFAULT'").use {
                        assertTrue(it.next()); it.getLong(1)
                    }
                    statement.executeUpdate("INSERT INTO number_counter (number_range_id, scope_key, period_key, last_value) VALUES ($id, '*', '', 42)")
                    if (customName) statement.executeUpdate("UPDATE number_range SET display_name = 'Demo 2 Katalog' WHERE id = $id")
                    assertTrue(flyway().validateWithResult().validationSuccessful)
                    statement.executeQuery("SELECT id, display_name, subject, prefix_data_field FROM number_range WHERE code = 'CATALOGUE_DEFAULT'").use {
                        assertTrue(it.next())
                        assertEquals(id, it.getLong(1))
                        assertEquals(if (customName) "Demo 2 Katalog" else "Katalog", it.getString(2))
                        assertEquals(2, it.getInt(3))
                        assertEquals(2, it.getInt(4))
                    }
                    statement.executeQuery("SELECT last_value FROM number_counter WHERE number_range_id = $id").use {
                        assertTrue(it.next()); assertEquals(42L, it.getLong(1))
                    }
                    assertEquals(0, flyway().migrate().migrationsExecuted)
                }
            }
        }
    }
}
