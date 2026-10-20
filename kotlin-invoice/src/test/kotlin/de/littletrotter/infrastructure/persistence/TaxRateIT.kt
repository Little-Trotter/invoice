package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Date
import java.time.LocalDate

@Testcontainers
class TaxRateIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")

        private lateinit var conn: Connection

        @JvmStatic
        @BeforeAll
        fun setup() {
            conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            TestSchema.apply(conn)
        }

        @JvmStatic
        @AfterAll
        fun teardown() { conn.close() }
    }

    @Test
    fun `should save and reload TaxRate`() {
        val insertSql = """
            -- code/category sind seit R1 Pflicht (NOT NULL bzw. chk_tax_rate_selectable_needs_category)
            INSERT INTO tax_rate (name, rate, country, valid_from, valid_to, is_hotel_rate, is_default, created_at, code, category)
            VALUES (?, ?, ?, ?, ?, ?, ?, now(), 'IT_TAXRATE_DE_19', 'S') RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSql)
        ps.setString(1, "MwSt 19%")
        ps.setBigDecimal(2, java.math.BigDecimal("19.00"))
        // country stored as numeric ISO code (DE = 276)
        ps.setInt(3, 276)
        ps.setDate(4, Date.valueOf(LocalDate.of(2024, 1, 1)))
        ps.setNull(5, java.sql.Types.DATE)
        ps.setBoolean(6, false)
        ps.setBoolean(7, true)

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val id = rs.getObject(1)
        rs.close()
        ps.close()

        val sel = conn.prepareStatement("SELECT name, rate, country, valid_from, valid_to, is_hotel_rate, is_default FROM tax_rate WHERE id = ?")
        sel.setObject(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals("MwSt 19%", r2.getString("name"))
        assertEquals(java.math.BigDecimal("19.00"), r2.getBigDecimal("rate"))
        // country stored as numeric ISO code (DE = 276)
        assertEquals(276, r2.getInt("country"))
        assertEquals(Date.valueOf(LocalDate.of(2024, 1, 1)), r2.getDate("valid_from"))
        assertNull(r2.getDate("valid_to"))
        assertFalse(r2.getBoolean("is_hotel_rate"))
        assertTrue(r2.getBoolean("is_default"))

        r2.close()
        sel.close()

        // update
        val upd = conn.prepareStatement("UPDATE tax_rate SET is_default = false WHERE id = ?")
        upd.setObject(1, id)
        assertEquals(1, upd.executeUpdate())
        upd.close()

        val r3p = conn.prepareStatement("SELECT is_default FROM tax_rate WHERE id = ?")
        r3p.setObject(1, id)
        val r3 = r3p.executeQuery()
        assertTrue(r3.next())
        assertFalse(r3.getBoolean("is_default"))
        r3.close()
        r3p.close()
    }

    @Test
    fun `database enforces one eligible reference per country`() {
        conn.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO tax_rate (name, rate, country, code, category, is_reference) " +
                    "VALUES ('Referenz', 19, 276, 'IT_REF_DE', 'S', true)",
            )
        }

        assertThrows(java.sql.SQLException::class.java) {
            conn.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO tax_rate (name, rate, country, code, category, is_reference) " +
                        "VALUES ('Zweite Referenz', 7, 276, 'IT_REF_DE_2', 'S', true)",
                )
            }
        }
    }

    @Test
    fun `database rejects an ineligible reference`() {
        assertThrows(java.sql.SQLException::class.java) {
            conn.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO tax_rate (name, rate, country, code, category, is_reference, is_active) " +
                        "VALUES ('Inaktiv', 19, 250, 'IT_REF_INACTIVE', 'S', true, false)",
                )
            }
        }
    }

    // Monkey finding (2026-08-30) — chk_tax_rate_category_values used to only accept S/AE/E/O;
    // ZERO_RATED ('Z') is one of the three VatCategory.kt values (Z/G/K) that constraint silently
    // rejected. Widened in place in V1__baseline_schema.sql; this proves a 'Z' row now round-trips.
    @Test
    fun `should save and reload a ZERO_RATED (Z) TaxRate`() {
        val insertSql = """
            INSERT INTO tax_rate (name, rate, country, valid_from, created_at, code, category)
            VALUES (?, ?, ?, ?, now(), 'IT_TAXRATE_ZERO_RATED', 'Z') RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSql)
        ps.setString(1, "Nullsatz (Ausfuhrgüter)")
        ps.setBigDecimal(2, java.math.BigDecimal("0.00"))
        ps.setInt(3, 276)
        ps.setDate(4, Date.valueOf(LocalDate.of(2024, 1, 1)))

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val id = rs.getObject(1)
        rs.close()
        ps.close()

        val sel = conn.prepareStatement("SELECT name, rate, category FROM tax_rate WHERE id = ?")
        sel.setObject(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals("Nullsatz (Ausfuhrgüter)", r2.getString("name"))
        assertEquals(java.math.BigDecimal("0.00"), r2.getBigDecimal("rate"))
        assertEquals("Z", r2.getString("category"))
        r2.close()
        sel.close()
    }
}
