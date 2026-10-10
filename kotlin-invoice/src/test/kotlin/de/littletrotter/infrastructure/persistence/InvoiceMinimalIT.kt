package de.littletrotter.infrastructure.persistence

import de.littletrotter.test.withAppNow
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
class InvoiceMinimalIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")

        private lateinit var conn: Connection
        private var senderId: Any? = null
        private var recipientId: Any? = null
        private var paymentTermId: Any? = null

        @JvmStatic
        @BeforeAll
        fun setup() {
            conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).withAppNow()
            TestSchema.apply(conn)

            // create fixtures: sender, recipient, payment term
            val sPs = conn.prepareStatement("INSERT INTO sender_profile (name, created_at) VALUES ('FixtureSender', now()) RETURNING id")
            val sRs = sPs.executeQuery()
            sRs.next(); senderId = sRs.getObject(1); sRs.close(); sPs.close()

            val rPs = conn.prepareStatement("INSERT INTO recipient (customer_number, name, created_at, document_language) VALUES ('KD-FIX', 'FixRecipient', now(), 'de') RETURNING id")
            val rRs = rPs.executeQuery()
            rRs.next(); recipientId = rRs.getObject(1); rRs.close(); rPs.close()

            paymentTermId = TestSchema.insertPaymentTerm(conn, "FixturePT")
        }

        @JvmStatic
        @AfterAll
        fun teardown() { conn.close() }
    }

    @Test
    fun `should save and reload minimal Invoice`() {
        val insertSql = """
            INSERT INTO invoice (invoice_number, status, sender_profile_id, recipient_id, payment_term_id, invoice_date, payment_terms_days, free_text, reverse_charge, net_amount, vat_amount, gross_amount, validation_status, type_code, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-06-15T10:00:00Z') RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSql)
        ps.setString(1, "UNASSIGNED")
        ps.setInt(2, 0) // DRAFT
        ps.setObject(3, senderId)
        ps.setObject(4, recipientId)
        ps.setObject(5, paymentTermId)
        ps.setDate(6, Date.valueOf(LocalDate.now()))
        ps.setInt(7, 14)
        ps.setString(8, "Bitte überweisen")
        ps.setBoolean(9, false)
        ps.setBigDecimal(10, java.math.BigDecimal("0.00"))
        ps.setBigDecimal(11, java.math.BigDecimal("0.00"))
        ps.setBigDecimal(12, java.math.BigDecimal("0.00"))
        ps.setInt(13, 0) // PENDING
        ps.setInt(14, 0) // COMMERCIAL_INVOICE

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val id = rs.getObject(1)
        rs.close()
        ps.close()

        val sel = conn.prepareStatement("SELECT invoice_number, status, payment_terms_days, free_text, reverse_charge, net_amount FROM invoice WHERE id = ?")
        sel.setObject(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals("UNASSIGNED", r2.getString("invoice_number"))
        assertEquals(0, r2.getInt("status"))
        assertEquals(14, r2.getInt("payment_terms_days"))
        assertEquals("Bitte überweisen", r2.getString("free_text"))
        assertFalse(r2.getBoolean("reverse_charge"))
        assertEquals(java.math.BigDecimal("0.00"), r2.getBigDecimal("net_amount"))
        r2.close()
        sel.close()

        // update status
        val upd = conn.prepareStatement("UPDATE invoice SET status = ? WHERE id = ?")
        upd.setInt(1, 1) // REVIEWS
        upd.setObject(2, id)
        assertEquals(1, upd.executeUpdate())
        upd.close()

        val r3p = conn.prepareStatement("SELECT status FROM invoice WHERE id = ?")
        r3p.setObject(1, id)
        val r3 = r3p.executeQuery()
        assertTrue(r3.next())
        assertEquals(1, r3.getInt("status"))
        r3.close()
        r3p.close()
    }
}
