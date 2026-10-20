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
class ArticleIT {

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
    fun `should save and reload Article with and without TaxRateRef`() {
        // insert tax rate for FK scenario
        val trSql = "INSERT INTO tax_rate (name, rate, country, valid_from, is_default, created_at, code, category) " +
            // code/category are mandatory (NOT NULL)
            "VALUES ('MwSt19', 19.00, 276, now(), false, now(), 'IT_ARTICLE_DE_19', 'S') RETURNING id"
        val trPs = conn.prepareStatement(trSql)
        val trRs = trPs.executeQuery()
        trRs.next()
        val taxId = trRs.getObject(1)
        trRs.close()
        trPs.close()

        val insertSql = """
            INSERT INTO article (name, description, category, default_unit, default_quantity, default_unit_price_net, default_tax_rate_ref_id, is_default, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0) RETURNING id
        """.trimIndent()

        val ps = conn.prepareStatement(insertSql)
        ps.setString(1, "Beratungsleistung")
        ps.setString(2, "Stundensatz IT-Beratung")
        ps.setInt(3, 0) // SERVICE
        ps.setInt(4, 0) // HOUR
        ps.setBigDecimal(5, java.math.BigDecimal("1.0000"))
        ps.setBigDecimal(6, java.math.BigDecimal("120.0000"))
        ps.setObject(7, taxId)
        ps.setBoolean(8, true)

        val rs = ps.executeQuery()
        assertTrue(rs.next())
        val id = rs.getObject(1)
        rs.close()
        ps.close()

        val sel = conn.prepareStatement("SELECT name, description, category, default_unit, default_quantity, default_unit_price_net, default_tax_rate_ref_id, is_default FROM article WHERE id = ?")
        sel.setObject(1, id)
        val r2 = sel.executeQuery()
        assertTrue(r2.next())
        assertEquals("Beratungsleistung", r2.getString("name"))
        assertEquals("Stundensatz IT-Beratung", r2.getString("description"))
        assertEquals(0, r2.getInt("category"))
        assertEquals(0, r2.getInt("default_unit"))
        assertEquals(java.math.BigDecimal("1.0000"), r2.getBigDecimal("default_quantity"))
        // default_unit_price_net is NUMERIC(14,2) (V001) — the decimal places are part of the
        // assertion, hence the exact value instead of compareTo.
        assertEquals(java.math.BigDecimal("120.00"), r2.getBigDecimal("default_unit_price_net"))
        assertEquals(true, r2.getBoolean("is_default"))
        r2.close()
        sel.close()

        // update default_unit_price_net
        val upd = conn.prepareStatement("UPDATE article SET default_unit_price_net = ? WHERE id = ?")
        upd.setBigDecimal(1, java.math.BigDecimal("130.0000"))
        upd.setObject(2, id)
        assertEquals(1, upd.executeUpdate())
        upd.close()

        val r3p = conn.prepareStatement("SELECT default_unit_price_net FROM article WHERE id = ?")
        r3p.setObject(1, id)
        val r3 = r3p.executeQuery()
        assertTrue(r3.next())
        assertEquals(java.math.BigDecimal("130.00"), r3.getBigDecimal("default_unit_price_net"))
        r3.close()
        r3p.close()

        // cleanup
        val del = conn.prepareStatement("DELETE FROM article WHERE id = ?")
        del.setObject(1, id)
        del.executeUpdate()
        del.close()
        val delTax = conn.prepareStatement("DELETE FROM tax_rate WHERE id = ?")
        delTax.setObject(1, taxId)
        delTax.executeUpdate()
        delTax.close()
    }
}
