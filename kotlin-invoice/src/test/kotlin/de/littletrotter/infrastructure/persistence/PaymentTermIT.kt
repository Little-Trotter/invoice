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

/**
 * `payment_term.name`/`english_name` are gone (`name_group_id` points at a
 * `localized_text_group` instead, see `TestSchema.insertPaymentTerm`'s own KDoc); this test now
 * inserts/reads/updates through that helper and a direct `localized_text` read/update instead of
 * the former plain `name` column.
 */
@Testcontainers
class PaymentTermIT {

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
    fun `should save and reload PaymentTerm`() {
        // insert
        val id = TestSchema.insertPaymentTerm(
            conn,
            germanName = "30 Tage netto",
            dueInDays = 30,
            description = "Zahlung innerhalb 30 Tagen",
            discountPercent = java.math.BigDecimal("2.00"),
            discountDays = 10,
            isDefault = true,
        )

        // select
        val sel = conn.prepareStatement("SELECT description, due_in_days, discount_percent, discount_days, is_default, created_at FROM payment_term WHERE id = ?")
        sel.setLong(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals("30 Tage netto", TestSchema.germanPaymentTermName(conn, id))
        assertEquals("Zahlung innerhalb 30 Tagen", r2.getString("description"))
        assertEquals(30, r2.getInt("due_in_days"))
        assertEquals(java.math.BigDecimal("2.00"), r2.getBigDecimal("discount_percent"))
        assertEquals(10, r2.getInt("discount_days"))
        assertTrue(r2.getBoolean("is_default"))
        assertNotNull(r2.getTimestamp("created_at"))

        r2.close()
        sel.close()

        // update
        TestSchema.updateGermanPaymentTermName(conn, id, "14 Tage netto")

        assertEquals("14 Tage netto", TestSchema.germanPaymentTermName(conn, id))
    }
}
