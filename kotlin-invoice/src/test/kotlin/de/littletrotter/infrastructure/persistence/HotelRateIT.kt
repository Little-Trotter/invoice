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
class HotelRateIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")

        private lateinit var conn: Connection

        @JvmStatic
        @BeforeAll
        fun setup() {
            if (!postgres.isRunning) {
                postgres.start()
            }
            conn = openConnectionWithRetry()
            TestSchema.apply(conn)
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            if (::conn.isInitialized) {
                conn.close()
            }
        }

        private fun openConnectionWithRetry(maxAttempts: Int = 5): Connection {
            val jdbcUrl = if (postgres.jdbcUrl.contains("?")) {
                "${postgres.jdbcUrl}&sslmode=disable"
            } else {
                "${postgres.jdbcUrl}?sslmode=disable"
            }

            var lastError: Exception? = null
            repeat(maxAttempts) {
                try {
                    return DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: error("Could not connect to test Postgres container")
        }
    }

    @Test
    fun `should save and reload HotelRate`() {
        val insertSql = """
            -- source ist seit R2 fuer jede aktive Zeile Pflicht (chk_hotel_rate_active_needs_source)
            INSERT INTO hotel_rate (country, rate_per_night, valid_from, valid_to, created_at, source)
            VALUES (?, ?, ?, ?, now(), 'Testdaten HotelRateIT') RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSql)
        // country stored as numeric ISO code (DE = 276)
        ps.setInt(1, 276)
        ps.setBigDecimal(2, java.math.BigDecimal("100.00"))
        ps.setDate(3, Date.valueOf(LocalDate.of(2024, 1, 1)))
        ps.setNull(4, java.sql.Types.DATE)

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val id = rs.getObject(1)
        rs.close()
        ps.close()

        val sel = conn.prepareStatement("SELECT country, rate_per_night, valid_from, valid_to FROM hotel_rate WHERE id = ?")
        sel.setObject(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals(276, r2.getInt("country"))
        assertEquals(java.math.BigDecimal("100.00"), r2.getBigDecimal("rate_per_night"))
        assertEquals(Date.valueOf(LocalDate.of(2024, 1, 1)), r2.getDate("valid_from"))
        assertNull(r2.getDate("valid_to"))

        r2.close()
        sel.close()

        // update
        val upd = conn.prepareStatement("UPDATE hotel_rate SET valid_to = ? WHERE id = ?")
        upd.setDate(1, Date.valueOf(LocalDate.of(2024, 12, 31)))
        upd.setObject(2, id)
        assertEquals(1, upd.executeUpdate())
        upd.close()

        val r3p = conn.prepareStatement("SELECT valid_to FROM hotel_rate WHERE id = ?")
        r3p.setObject(1, id)
        val r3 = r3p.executeQuery()
        assertTrue(r3.next())
        assertEquals(Date.valueOf(LocalDate.of(2024, 12, 31)), r3.getDate("valid_to"))
        r3.close()
        r3p.close()
    }
}
