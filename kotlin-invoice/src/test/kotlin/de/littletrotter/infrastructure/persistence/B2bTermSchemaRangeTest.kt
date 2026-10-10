package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the baseline's bt_code range CHECKs
 * (`chk_*_bt_code_range`, V1__baseline_schema.sql) to the enum they guard: the SQL literal upper
 * bound must equal the greatest existing [B2bTerm.code]. A plain-resource parse rather than a
 * Testcontainers round trip so ordinary `mvn test` already fails when someone adds a term above
 * the current ceiling (BT-X block = 10000 + nn) without bumping the baseline —
 * [B2bProfileMigrationIT] proves the constraints' runtime behavior against real Postgres.
 */
class B2bTermSchemaRangeTest {

    private val constraintPattern = Regex(
        """CONSTRAINT\s+(chk_\w+_bt_code_range)\s+CHECK\s+\((\w+)\s+BETWEEN\s+(\d+)\s+AND\s+(\d+)\)""",
    )

    private fun baselineDdl(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("db/migration/ddl/V1__baseline_schema.sql")) {
            "Missing migration resource: db/migration/ddl/V1__baseline_schema.sql"
        }.bufferedReader().readText()

    private fun rangeConstraints(): List<MatchResult> = constraintPattern.findAll(baselineDdl()).toList()

    @Test
    fun `all three bt_code columns carry the range CHECK`() {
        assertEquals(
            listOf(
                "chk_b2b_field_value_bt_code_range",
                "chk_b2b_profile_field_bt_code_range",
                "chk_b2b_profile_field_condition_bt_code_range",
            ),
            rangeConstraints().map { it.groupValues[1] }.sorted(),
        )
    }

    @Test
    fun `every range CHECK spans exactly 0 to the greatest existing B2bTerm code`() {
        val maxCode = B2bTerm.entries.maxOf { it.code }
        rangeConstraints().forEach { match ->
            val (name, _, lower, upper) = match.destructured
            assertEquals(0, lower.toInt(), "$name must reject negative codes starting from the 0 floor")
            assertEquals(
                maxCode,
                upper.toInt(),
                "$name's upper bound drifted from B2bTerm — bump the baseline literal alongside the enum",
            )
        }
    }

    @Test
    fun `every B2bTerm code is positive and fits the SMALLINT columns`() {
        // The DB floor is 0 (PO 30.08.2026), the enum itself never uses it — official BT numbers
        // start at 1. The ceiling check keeps future BT-X constants (10000 + nn) from silently
        // outgrowing the SMALLINT columns long before Postgres would reject them.
        assertTrue(B2bTerm.entries.all { it.code >= 1 }, "B2bTerm codes must be positive")
        assertTrue(B2bTerm.entries.all { it.code <= Short.MAX_VALUE }, "B2bTerm codes must fit SMALLINT")
    }
}
