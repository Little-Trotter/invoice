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
class GenerationJobIT {

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

            // create minimal sender/recipient/payment_term and invoice
            val sPs = conn.prepareStatement("INSERT INTO sender_profile (name, created_at) VALUES ('S', now()) RETURNING id")
            val sRs = sPs.executeQuery(); sRs.next(); val senderId = sRs.getObject(1); sRs.close(); sPs.close()

            val rPs = conn.prepareStatement("INSERT INTO recipient (customer_number, name, created_at, document_language) VALUES ('KD', 'R', now(), 'de') RETURNING id")
            val rRs = rPs.executeQuery(); rRs.next(); val recipientId = rRs.getObject(1); rRs.close(); rPs.close()

            val paymentTermId = TestSchema.insertPaymentTerm(conn, "PT")

            val iPs = conn.prepareStatement("INSERT INTO invoice (invoice_number, status, sender_profile_id, recipient_id, payment_term_id, invoice_date, created_at) VALUES ('INV-1', 0, ?, ?, ?, ?, '2026-06-15T10:00:00Z') RETURNING id")
            iPs.setObject(1, senderId); iPs.setObject(2, recipientId); iPs.setObject(3, paymentTermId); iPs.setDate(4, Date.valueOf(LocalDate.now()))
            val iRs = iPs.executeQuery(); iRs.next(); invoiceId = iRs.getObject(1); iRs.close(); iPs.close()
        }

        @JvmStatic
        @AfterAll
        fun teardown() { conn.close() }
    }

    @Test
    fun `should save and reload generation job with jsonb refs`() {
        val sql = """
            INSERT INTO generation_job (invoice_id, job_type, status, result_refs, kosit_report, correlation_id, started_at, finished_at, created_at, job_lane)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, '2026-06-15T10:00:00Z', 0) RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(sql)
        ps.setObject(1, invoiceId)
        ps.setString(2, "xml_pdf")
        ps.setInt(3, 0) // PENDING
        ps.setString(4, "{\"pdf\": \"inv-1.pdf\"}")
        ps.setString(5, "kosit ok")
        ps.setString(6, "corr-123")
        ps.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()))
        ps.setNull(8, java.sql.Types.TIMESTAMP)

        val rs = ps.executeQuery(); rs.next(); val gjId = rs.getObject(1); rs.close(); ps.close()

        val sel = conn.prepareStatement("SELECT job_type, status, result_refs->>'pdf' as pdf, kosit_report, correlation_id, created_at FROM generation_job WHERE id = ?")
        sel.setObject(1, gjId)
        val r = sel.executeQuery(); assertTrue(r.next())
        assertEquals("xml_pdf", r.getString("job_type"))
        assertEquals(0, r.getInt("status")) // PENDING
        assertEquals("inv-1.pdf", r.getString("pdf"))
        assertEquals("kosit ok", r.getString("kosit_report"))
        assertEquals("corr-123", r.getString("correlation_id"))
        assertNotNull(r.getTimestamp("created_at"))
        r.close(); sel.close()

        // cascade delete invoice -> generation_job
        val del = conn.prepareStatement("DELETE FROM invoice WHERE id = ?")
        del.setObject(1, invoiceId)
        assertEquals(1, del.executeUpdate())
        del.close()

        val check = conn.prepareStatement("SELECT COUNT(*) FROM generation_job WHERE id = ?")
        check.setObject(1, gjId)
        val cr = check.executeQuery(); cr.next(); assertEquals(0, cr.getInt(1)); cr.close(); check.close()
    }
}
