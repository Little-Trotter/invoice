package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** Guards the two-file greenfield baseline contract (ADR-011, PO 2026-09-16).
 * Schema changes belong in V1 and initial reference data in V2; incremental migrations
 * are not retained while no existing installation needs an upgrade path.
 */
class MigrationDirectoryHygieneTest {

    private fun migrationDir(name: String): File {
        // Test working directory is the module root (kotlin-invoice/) under both Maven Surefire
        // and IDE runners for this project.
        val candidates = listOf(
            File("src/main/resources/db/migration/$name"),
            File("kotlin-invoice/src/main/resources/db/migration/$name"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate db/migration/$name from working dir ${File(".").absolutePath}")
    }

    @Test
    fun `greenfield migration locations contain only schema and seed baselines`() {
        val root = migrationDir("ddl").parentFile
        val actual = root.walkTopDown().filter { it.isFile && it.extension == "sql" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        assertEquals(
            listOf("ddl/V1__baseline_schema.sql", "seed/V2__baseline_seed.sql"),
            actual,
            "Edit the greenfield baselines directly instead of adding upgrade scripts.",
        )
    }
}
