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
class InvoiceWithItemsIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")

        private lateinit var conn: Connection
        private var senderId: Any? = null
        private var recipientId: Any? = null
        private var paymentTermId: Any? = null
        private var taxRateId: Any? = null
        private var invoiceId: Any? = null

        @JvmStatic
        @BeforeAll
        fun setup() {
            conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).withAppNow()
            TestSchema.apply(conn)

            val sPs = conn.prepareStatement("INSERT INTO sender_profile (name, created_at) VALUES ('S', now()) RETURNING id")
            val sRs = sPs.executeQuery(); sRs.next(); senderId = sRs.getObject(1); sRs.close(); sPs.close()
            val rPs = conn.prepareStatement("INSERT INTO recipient (customer_number, name, created_at, document_language) VALUES ('KD', 'R', now(), 'de') RETURNING id")
            val rRs = rPs.executeQuery(); rRs.next(); recipientId = rRs.getObject(1); rRs.close(); rPs.close()
            paymentTermId = TestSchema.insertPaymentTerm(conn, "PT")
            val tPs = conn.prepareStatement("INSERT INTO tax_rate (name, rate, country, valid_from, is_default, created_at, code, category) " +
                // code/category are mandatory (NOT NULL)
                "VALUES ('T19', 19.00, 276, now(), false, now(), 'IT_INVOICEITEMS_DE_19', 'S') RETURNING id")
            val tRs = tPs.executeQuery(); tRs.next(); taxRateId = tRs.getObject(1); tRs.close(); tPs.close()

            val iPs = conn.prepareStatement("INSERT INTO invoice (invoice_number, status, sender_profile_id, recipient_id, payment_term_id, invoice_date, created_at) VALUES ('INV-1', 0, ?, ?, ?, ?, '2026-06-15T10:00:00Z') RETURNING id")
            iPs.setObject(1, senderId); iPs.setObject(2, recipientId); iPs.setObject(3, paymentTermId); iPs.setDate(4, Date.valueOf(LocalDate.now()))
            val iRs = iPs.executeQuery(); iRs.next(); invoiceId = iRs.getObject(1); iRs.close(); iPs.close()
        }

        @JvmStatic
        @AfterAll
        fun teardown() { conn.close() }
    }

    @Test
    fun `should save invoice with one item and travel expense entry`() {
        // insert item
        val insertItem = """
            INSERT INTO invoice_item (invoice_id, position_id, category, description, quantity, unit, unit_price_net, tax_rate_id, tax_category, net_amount, vat_amount, gross_amount, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'S', ?, ?, ?, ?) RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertItem)
        ps.setObject(1, invoiceId)
        ps.setString(2, "1")
        ps.setInt(3, 0)
        ps.setString(4, "Entwicklung Sprint 12")
        ps.setBigDecimal(5, java.math.BigDecimal("8.0000"))
        ps.setInt(6, 0)
        ps.setBigDecimal(7, java.math.BigDecimal("95.0000"))
        ps.setObject(8, taxRateId)
        ps.setBigDecimal(9, java.math.BigDecimal("760.0000"))
        ps.setBigDecimal(10, java.math.BigDecimal("144.4000"))
        ps.setBigDecimal(11, java.math.BigDecimal("904.4000"))
        ps.setInt(12, 1)

        val rs = ps.executeQuery(); rs.next(); val itemId = rs.getObject(1); rs.close(); ps.close()

        // insert travel expense entry
        // `description` (renamed to `participant`) plus the new, mandatory block-level
        // `description` column; this raw-SQL insert has no Kotlin-level reference to either field
        // name, so it was not caught by grepping for usages during that rename (see this
        // test's own module KDoc "past mistakes" section).
        val teSql = """
            INSERT INTO travel_expense_entry (short_name, invoice_item_id, per_diem_rate_id, country, participant, description, travel_date, is_arrival_departure, breakfast_deduction, lunch_deduction, dinner_deduction, full_day_rate_snapshot, arrival_departure_rate_snapshot, calculated_amount, employer_contribution)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
        """.trimIndent()

        val tePs = conn.prepareStatement(teSql)
        tePs.setString(1, "TE-1")
        tePs.setObject(2, itemId)
        tePs.setNull(3, java.sql.Types.BIGINT)
        tePs.setInt(4, 276)
        tePs.setString(5, "Verpflegung Sprint 12")
        tePs.setString(6, "Verpflegungspauschale Sprint 12")
        tePs.setDate(7, Date.valueOf(LocalDate.now()))
        tePs.setBoolean(8, false)
        tePs.setBoolean(9, true)
        tePs.setBoolean(10, false)
        tePs.setBoolean(11, false)
        tePs.setBigDecimal(12, java.math.BigDecimal("28.00"))
        tePs.setBigDecimal(13, java.math.BigDecimal("14.00"))
        tePs.setBigDecimal(14, java.math.BigDecimal("20.00"))
        tePs.setBigDecimal(15, java.math.BigDecimal("0.00"))

        val teRs = tePs.executeQuery(); teRs.next(); val teId = teRs.getObject(1); teRs.close(); tePs.close()

        // verify item and travel entry
        val selItem = conn.prepareStatement("SELECT position_id, net_amount, vat_amount, gross_amount FROM invoice_item WHERE id = ?")
        selItem.setObject(1, itemId)
        val rItem = selItem.executeQuery(); assertTrue(rItem.next())
        assertEquals("1", rItem.getString("position_id"))
        // net/vat/gross are NUMERIC(14,2) (V002), not (x,4).
        assertEquals(java.math.BigDecimal("760.00"), rItem.getBigDecimal("net_amount"))
        assertEquals(java.math.BigDecimal("144.40"), rItem.getBigDecimal("vat_amount"))
        assertEquals(java.math.BigDecimal("904.40"), rItem.getBigDecimal("gross_amount"))
        rItem.close(); selItem.close()

        val selTE = conn.prepareStatement("SELECT country, breakfast_deduction, full_day_rate_snapshot FROM travel_expense_entry WHERE id = ?")
        selTE.setObject(1, teId)
        val rte = selTE.executeQuery(); assertTrue(rte.next())
        assertEquals(276, rte.getInt("country"))
        assertTrue(rte.getBoolean("breakfast_deduction"))
        assertEquals(java.math.BigDecimal("28.00"), rte.getBigDecimal("full_day_rate_snapshot"))
        rte.close(); selTE.close()

        // cascade delete invoice -> items
        val delInv = conn.prepareStatement("DELETE FROM invoice WHERE id = ?")
        delInv.setObject(1, invoiceId)
        assertEquals(1, delInv.executeUpdate())
        delInv.close()

        val checkItem = conn.prepareStatement("SELECT COUNT(*) FROM invoice_item WHERE id = ?")
        checkItem.setObject(1, itemId)
        val cr = checkItem.executeQuery(); cr.next(); assertEquals(0, cr.getInt(1)); cr.close(); checkItem.close()
    }
}
