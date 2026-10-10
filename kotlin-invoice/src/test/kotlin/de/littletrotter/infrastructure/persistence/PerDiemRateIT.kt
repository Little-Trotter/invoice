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
class PerDiemRateIT {

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
    fun `should save and reload PerDiemRate`() {
        val insertSql = """
            -- source ist seit R2 fuer jede aktive Zeile Pflicht (chk_per_diem_rate_active_needs_source)
            INSERT INTO per_diem_rate (short_name, country, region, full_day_rate, arrival_departure_rate, inland_rules, valid_from, valid_to, created_at, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), 'Testdaten PerDiemRateIT') RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSql)
        ps.setString(1, "DE Inland")
        // country stored as numeric ISO code (DE = 276)
        ps.setInt(2, 276)
        ps.setNull(3, java.sql.Types.VARCHAR)
        ps.setBigDecimal(4, java.math.BigDecimal("28.00"))
        ps.setBigDecimal(5, java.math.BigDecimal("14.00"))
        ps.setBoolean(6, true)
        ps.setDate(7, Date.valueOf(LocalDate.of(2024, 1, 1)))
        ps.setNull(8, java.sql.Types.DATE)

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val id = rs.getObject(1)
        rs.close()
        ps.close()

        val sel = conn.prepareStatement("SELECT short_name, country, region, full_day_rate, arrival_departure_rate, inland_rules, valid_from, valid_to FROM per_diem_rate WHERE id = ?")
        sel.setObject(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals("DE Inland", r2.getString("short_name"))
        assertEquals(276, r2.getInt("country"))
        assertNull(r2.getString("region"))
        assertEquals(java.math.BigDecimal("28.00"), r2.getBigDecimal("full_day_rate"))
        assertEquals(java.math.BigDecimal("14.00"), r2.getBigDecimal("arrival_departure_rate"))
        assertTrue(r2.getBoolean("inland_rules"))
        assertEquals(Date.valueOf(LocalDate.of(2024, 1, 1)), r2.getDate("valid_from"))
        assertNull(r2.getDate("valid_to"))

        r2.close()
        sel.close()

        // update
        val upd = conn.prepareStatement("UPDATE per_diem_rate SET region = ? WHERE id = ?")
        upd.setString(1, "Bayern")
        upd.setObject(2, id)
        assertEquals(1, upd.executeUpdate())
        upd.close()

        val r3p = conn.prepareStatement("SELECT region FROM per_diem_rate WHERE id = ?")
        r3p.setObject(1, id)
        val r3 = r3p.executeQuery()
        assertTrue(r3.next())
        assertEquals("Bayern", r3.getString("region"))
        r3.close()
        r3p.close()
    }
}
