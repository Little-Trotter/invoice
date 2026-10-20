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
class ReceiptIT {

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
    fun `should save and reload Receipt and enforce unique object_key`() {
        val insertSql = """
            INSERT INTO receipt (file_name, description, object_key, size_bytes, sha256_checksum, uploaded_at, document_date)
            VALUES (?, ?, ?, ?, ?, now(), DATE '2026-03-14') RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSql)
        ps.setString(1, "Rechnung Hotel")
        ps.setString(2, "Hotelrechnung Berlin")
        ps.setString(3, "receipts/2024/hotel_receipt.pdf")
        ps.setLong(4, 204800L)
        ps.setString(5, "abc123def456abc123def456abc123def456ab12")

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val id = rs.getObject(1)
        rs.close()
        ps.close()

        val sel = conn.prepareStatement("SELECT file_name, description, object_key, size_bytes, sha256_checksum, uploaded_at FROM receipt WHERE id = ?")
        sel.setObject(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals("Rechnung Hotel", r2.getString("file_name"))
        assertEquals("Hotelrechnung Berlin", r2.getString("description"))
        assertEquals("receipts/2024/hotel_receipt.pdf", r2.getString("object_key"))
        assertEquals(204800L, r2.getLong("size_bytes"))
        assertEquals("abc123def456abc123def456abc123def456ab12", r2.getString("sha256_checksum"))
        assertNotNull(r2.getTimestamp("uploaded_at"))
        r2.close()
        sel.close()

        // uniqueness constraint: inserting another with same object_key should fail
        val ps2 = conn.prepareStatement(insertSql)
        ps2.setString(1, "Duplicate")
        ps2.setString(2, "Dup")
        ps2.setString(3, "receipts/2024/hotel_receipt.pdf")
        ps2.setLong(4, 100L)
        ps2.setString(5, "deadbeef")

        assertThrows(java.sql.SQLException::class.java) { ps2.executeQuery() }
        ps2.close()
    }

    @Test
    fun `should refuse a null description and a null document date at database level`() {
        // receipt-mandatory-fields (PO 2026-09-04): description and document_date are NOT NULL —
        // the schema itself is the last wall beneath ReceiptUploadService.upload's own refusal.
        val insertSql = """
            INSERT INTO receipt (file_name, description, object_key, size_bytes, sha256_checksum, uploaded_at, document_date)
            VALUES (?, ?, ?, ?, ?, now(), ?)
        """.trimIndent()

        conn.prepareStatement(insertSql).use { ps ->
            ps.setString(1, "NullDesc.pdf")
            ps.setNull(2, java.sql.Types.VARCHAR)
            ps.setString(3, "receipts/2026/null-desc.pdf")
            ps.setLong(4, 123L)
            ps.setString(5, "f".repeat(64))
            ps.setDate(6, java.sql.Date.valueOf("2026-03-14"))

            val ex = assertThrows(java.sql.SQLException::class.java) { ps.executeUpdate() }
            assertTrue(ex.message!!.contains("description"), "the not-null violation names the description column")
        }

        conn.prepareStatement(insertSql).use { ps ->
            ps.setString(1, "NullDate.pdf")
            ps.setString(2, "Beleg NullDate.pdf")
            ps.setString(3, "receipts/2026/null-date.pdf")
            ps.setLong(4, 123L)
            ps.setString(5, "e".repeat(64))
            ps.setNull(6, java.sql.Types.DATE)

            val ex = assertThrows(java.sql.SQLException::class.java) { ps.executeUpdate() }
            assertTrue(ex.message!!.contains("document_date"), "the not-null violation names the document_date column")
        }
    }
}
