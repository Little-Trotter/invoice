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
class MileageRateIT {

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
    fun `should save and reload MileageRate`() {
        val insertSql = """
            -- vehicle_type ist seit R2 fuer jede aktive Zeile Pflicht
            -- (chk_mileage_rate_active_needs_vehicle_type); seit H27-32 als Ordinal (CAR = 0)
            INSERT INTO mileage_rate (country, rate_per_km, valid_from, valid_to, created_at, vehicle_type)
            VALUES (?, ?, ?, ?, now(), 0) RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSql)
        // country stored as numeric ISO code (DE = 276)
        ps.setInt(1, 276)
        ps.setBigDecimal(2, java.math.BigDecimal("0.3000"))
        ps.setDate(3, Date.valueOf(LocalDate.of(2024, 1, 1)))
        ps.setNull(4, java.sql.Types.DATE)

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val id = rs.getObject(1)
        rs.close()
        ps.close()

        val sel = conn.prepareStatement("SELECT country, rate_per_km, valid_from, valid_to FROM mileage_rate WHERE id = ?")
        sel.setObject(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals(276, r2.getInt("country"))
        assertEquals(java.math.BigDecimal("0.300"), r2.getBigDecimal("rate_per_km"))
        assertEquals(Date.valueOf(LocalDate.of(2024, 1, 1)), r2.getDate("valid_from"))
        assertNull(r2.getDate("valid_to"))

        r2.close()
        sel.close()

        // update
        val upd = conn.prepareStatement("UPDATE mileage_rate SET rate_per_km = ? WHERE id = ?")
        upd.setBigDecimal(1, java.math.BigDecimal("0.350"))
        upd.setObject(2, id)
        assertEquals(1, upd.executeUpdate())
        upd.close()

        val r3p = conn.prepareStatement("SELECT rate_per_km FROM mileage_rate WHERE id = ?")
        r3p.setObject(1, id)
        val r3 = r3p.executeQuery()
        assertTrue(r3.next())
        assertEquals(java.math.BigDecimal("0.350"), r3.getBigDecimal("rate_per_km"))
        r3.close()
        r3p.close()
    }
}
