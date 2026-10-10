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
class RecipientIT {

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
    fun `should save and reload Recipient and Address`() {
        val insertRecipient = """
            INSERT INTO recipient (customer_number, name, vat_id, routing_id, email, phone, contact_person, created_at, document_language)
            VALUES (?, ?, ?, ?, ?, ?, ?, now(), 'de') RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertRecipient)
        ps.setString(1, "KD-0001")
        ps.setString(2, "Mustermann GmbH")
        ps.setString(3, "DE123456789")
        ps.setString(4, "ROUTE-01")
        ps.setString(5, "info@mustermann.de")
        ps.setString(6, "+49 30 123456")
        ps.setString(7, "Max Mustermann")

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val recipientId = rs.getObject(1)
        rs.close()
        ps.close()

        // insert billing address
        val insertAddress = """
            INSERT INTO address (recipient_id, address_type, street, postal_code, city, country, is_default, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now()) RETURNING id
        """.trimIndent()

        val ps2 = conn.prepareStatement(insertAddress)
        ps2.setObject(1, recipientId)
        ps2.setInt(2, 0) // BILLING
        ps2.setString(3, "Musterstraße 1")
        ps2.setString(4, "12345")
        ps2.setString(5, "Berlin")
        ps2.setInt(6, 276)
        ps2.setBoolean(7, true)

        val rs2 = ps2.executeQuery()
        assertTrue(rs2.next())
        val addressId = rs2.getObject(1)
        rs2.close()
        ps2.close()

        // join select
        val sel = conn.prepareStatement("SELECT r.customer_number, r.name, a.street, a.city, a.postal_code FROM recipient r JOIN address a ON a.recipient_id = r.id WHERE r.id = ?")
        sel.setObject(1, recipientId)
        val r3 = sel.executeQuery()
        assertTrue(r3.next())
        assertEquals("KD-0001", r3.getString("customer_number"))
        assertEquals("Mustermann GmbH", r3.getString("name"))
        assertEquals("Musterstraße 1", r3.getString("street"))
        assertEquals("Berlin", r3.getString("city"))
        assertEquals("12345", r3.getString("postal_code"))

        r3.close()
        sel.close()

        // update recipient name
        val upd = conn.prepareStatement("UPDATE recipient SET name = ? WHERE id = ?")
        upd.setString(1, "Mustermann AG")
        upd.setObject(2, recipientId)
        assertEquals(1, upd.executeUpdate())
        upd.close()

        val r4p = conn.prepareStatement("SELECT name FROM recipient WHERE id = ?")
        r4p.setObject(1, recipientId)
        val r4 = r4p.executeQuery()
        assertTrue(r4.next())
        assertEquals("Mustermann AG", r4.getString("name"))
        r4.close()
        r4p.close()

        // delete address first, then recipient (DB has no ON DELETE CASCADE)
        val delAddr = conn.prepareStatement("DELETE FROM address WHERE id = ?")
        delAddr.setObject(1, addressId)
        assertEquals(1, delAddr.executeUpdate())
        delAddr.close()

        val del = conn.prepareStatement("DELETE FROM recipient WHERE id = ?")
        del.setObject(1, recipientId)
        assertEquals(1, del.executeUpdate())
        del.close()

        val checkAddr = conn.prepareStatement("SELECT COUNT(*) FROM address WHERE id = ?")
        checkAddr.setObject(1, addressId)
        val cr = checkAddr.executeQuery()
        assertTrue(cr.next())
        assertEquals(0, cr.getInt(1))
        cr.close()
        checkAddr.close()
    }
}
