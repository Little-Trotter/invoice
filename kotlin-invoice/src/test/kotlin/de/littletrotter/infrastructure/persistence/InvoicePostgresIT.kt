package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

@Testcontainers
class InvoicePostgresIT {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:18").apply {
            withDatabaseName("invoice")
            withUsername("invoice_user")
            withPassword("invoice")
        }

        @JvmStatic
        @BeforeAll
        fun setup() {
            // Start container and apply SQL migrations from resources/db/migration/ddl and seed
            postgres.start()
            val conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            TestSchema.apply(conn)

            fun executeSqlResource(path: String) {
                val res = InvoicePostgresIT::class.java.classLoader.getResource(path)
                if (res == null) return
                val content = res.readText()
                    .lineSequence()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                // naive split on semicolon; good enough for these migration files
                content.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { stmt ->
                    conn.createStatement().execute(stmt)
                }
            }

            executeSqlResource("db/migration/seed/V901__seed_reference_data.sql")
            conn.close()
        }
    }

    @Test
    fun `migrations should create invoice table`() {
        val conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        val rs = conn.createStatement().executeQuery("SELECT count(*) FROM invoice")
        rs.next()
        val count = rs.getInt(1)
        assertEquals(0, count)
        conn.close()
    }
}
