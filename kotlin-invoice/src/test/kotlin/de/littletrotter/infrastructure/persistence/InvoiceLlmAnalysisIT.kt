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
import java.sql.Timestamp
import java.sql.Date
import java.time.LocalDate
import java.time.LocalDateTime

@Testcontainers
class InvoiceLlmAnalysisIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")

        private lateinit var conn: Connection
        private var invoiceId: Any? = null

        @JvmStatic
        @BeforeAll
        fun setup() {
            conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).withAppNow()
            TestSchema.apply(conn)

            val sPs = conn.prepareStatement("INSERT INTO sender_profile (name, created_at) VALUES ('S', now()) RETURNING id")
            val sRs = sPs.executeQuery(); sRs.next(); val senderId = sRs.getObject(1); sRs.close(); sPs.close()

            val rPs = conn.prepareStatement("INSERT INTO recipient (customer_number, name, created_at, document_language) VALUES ('KD', 'R', now(), 'de') RETURNING id")
            val rRs = rPs.executeQuery(); rRs.next(); val recipientId = rRs.getObject(1); rRs.close(); rPs.close()

            val paymentTermId = TestSchema.insertPaymentTerm(conn, "PT")

            val iPs = conn.prepareStatement("INSERT INTO invoice (invoice_number, status, sender_profile_id, recipient_id, payment_term_id, invoice_date, created_at) VALUES ('INV-LLM', 0, ?, ?, ?, ?, '2026-06-15T10:00:00Z') RETURNING id")
            iPs.setObject(1, senderId); iPs.setObject(2, recipientId); iPs.setObject(3, paymentTermId); iPs.setDate(4, Date.valueOf(LocalDate.now()))
            val iRs = iPs.executeQuery(); iRs.next(); invoiceId = iRs.getObject(1); iRs.close(); iPs.close()
        }

        @JvmStatic
        @AfterAll
        fun teardown() { conn.close() }
    }

    @Test
    fun `should save and reload invoice llm analysis`() {
        val sql = """
            INSERT INTO invoice_llm_analysis (short_name, invoice_id, status, rating, summary, issues, suggestions, improved_xml, model_name, analyzed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(sql)
        ps.setString(1, "LLM-1")
        ps.setObject(2, invoiceId)
        ps.setInt(3, 0)
        ps.setInt(4, 5)
        ps.setString(5, "Short summary")
        ps.setString(6, "No issues")
        ps.setString(7, "No suggestions")
        ps.setString(8, "<xml/>")
        ps.setString(9, "mistral")
        ps.setTimestamp(10, Timestamp.valueOf(LocalDateTime.now()))

        val rs = ps.executeQuery(); rs.next(); val id = rs.getObject(1); rs.close(); ps.close()

        val sel = conn.prepareStatement("SELECT status, rating, summary, model_name, analyzed_at FROM invoice_llm_analysis WHERE id = ?")
        sel.setObject(1, id)
        val r = sel.executeQuery(); assertTrue(r.next())
        assertEquals(0, r.getInt("status"))
        assertEquals(5, r.getInt("rating"))
        assertEquals("Short summary", r.getString("summary"))
        assertEquals("mistral", r.getString("model_name"))
        assertNotNull(r.getTimestamp("analyzed_at"))
        r.close(); sel.close()

        // cascade delete
        val del = conn.prepareStatement("DELETE FROM invoice WHERE id = ?")
        del.setObject(1, invoiceId)
        assertEquals(1, del.executeUpdate())
        del.close()

        val check = conn.prepareStatement("SELECT COUNT(*) FROM invoice_llm_analysis WHERE id = ?")
        check.setObject(1, id)
        val cr = check.executeQuery(); cr.next(); assertEquals(0, cr.getInt(1)); cr.close(); check.close()
    }
}
