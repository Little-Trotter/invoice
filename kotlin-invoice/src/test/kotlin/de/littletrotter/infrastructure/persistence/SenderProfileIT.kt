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

@Testcontainers
class SenderProfileIT {

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
    fun `should save and reload SenderProfile and Address`() {
        val insertSender = """
            INSERT INTO sender_profile (name, tax_number, vat_id, iban, bic, bank_name, invoice_prefix, is_default, phone, email, commercial_register_number, managing_director, court_of_jurisdiction, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSender)
        ps.setString(1, "Littletrotter GmbH")
        ps.setString(2, "12/345/67890")
        ps.setString(3, "DE987654321")
        ps.setString(4, "DE89370400440532013000")
        ps.setString(5, "COBADEFFXXX")
        ps.setString(6, "Commerzbank")
        ps.setString(7, "IN-")
        ps.setBoolean(8, true)
        ps.setString(9, "+49 89 987654")
        ps.setString(10, "billing@littletrotter.de")
        ps.setString(11, "HRB 12345")
        ps.setString(12, "Erika Musterfrau")
        ps.setString(13, "Amtsgericht München")

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val senderId = rs.getObject(1)
        rs.close()
        ps.close()

        val insertAddress = """
            INSERT INTO address (sender_profile_id, address_type, street, postal_code, city, country, is_default, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now()) RETURNING id
        """.trimIndent()

        val ps2 = conn.prepareStatement(insertAddress)
        ps2.setObject(1, senderId)
        ps2.setInt(2, 0)
        ps2.setString(3, "Musterstraße 1")
        ps2.setString(4, "12345")
        ps2.setString(5, "München")
        ps2.setInt(6, 276)
        ps2.setBoolean(7, true)

        val rs2 = ps2.executeQuery()
        assertTrue(rs2.next())
        val addressId = rs2.getObject(1)
        rs2.close()
        ps2.close()

        val sel = conn.prepareStatement("SELECT s.name, a.street, a.city FROM sender_profile s JOIN address a ON a.sender_profile_id = s.id WHERE s.id = ?")
        sel.setObject(1, senderId)
        val r3 = sel.executeQuery()
        assertTrue(r3.next())
        assertEquals("Littletrotter GmbH", r3.getString("name"))
        assertEquals("Musterstraße 1", r3.getString("street"))
        assertEquals("München", r3.getString("city"))
        r3.close()
        sel.close()

        // toggle default: revoke and set
        val upd = conn.prepareStatement("UPDATE sender_profile SET is_default = false WHERE id = ?")
        upd.setObject(1, senderId)
        assertEquals(1, upd.executeUpdate())
        upd.close()

        val r4p = conn.prepareStatement("SELECT is_default FROM sender_profile WHERE id = ?")
        r4p.setObject(1, senderId)
        val r4 = r4p.executeQuery()
        assertTrue(r4.next())
        assertFalse(r4.getBoolean("is_default"))
        r4.close()
        r4p.close()

        // cleanup
        val delAddr = conn.prepareStatement("DELETE FROM address WHERE id = ?")
        delAddr.setObject(1, addressId)
        delAddr.executeUpdate()
        delAddr.close()
        val del = conn.prepareStatement("DELETE FROM sender_profile WHERE id = ?")
        del.setObject(1, senderId)
        del.executeUpdate()
        del.close()
    }
}
