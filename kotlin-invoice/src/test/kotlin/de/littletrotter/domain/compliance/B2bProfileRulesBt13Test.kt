package de.littletrotter.domain.compliance

import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** BT-13 (purchase order reference) gates BT-155/BT-156 on each line (Nekar profile). */
class B2bProfileRulesBt13Test {

    private fun state(term: B2bTerm, req: B2bFieldRequirement, cond: B2bTerm?, has: Boolean, target: FindingTarget) =
        B2bFieldState(term, req, cond, "def", has, target)

    private fun fields(bt13Set: Boolean, line155: Boolean = false, line156: Boolean = false) = listOf(
        state(B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL, null, bt13Set, FindingTarget.Header),
        state(B2bTerm.BT_155, B2bFieldRequirement.CONDITIONAL, B2bTerm.BT_13, line155, FindingTarget.Position("1")),
        state(B2bTerm.BT_156, B2bFieldRequirement.CONDITIONAL, B2bTerm.BT_13, line156, FindingTarget.Position("1")),
    )

    @Test
    fun `with BT-13 set and empty line article numbers BT-155 and BT-156 become required`() {
        val findings = B2bProfileRules.evaluate(fields(bt13Set = true))
        assertEquals(
            setOf(listOf(B2bTerm.BT_155), listOf(B2bTerm.BT_156)),
            findings.map { it.businessTerms }.toSet(),
        )
        assertTrue(findings.all { it.target == FindingTarget.Position("1") })
    }

    @Test
    fun `without BT-13 the BT-155 and BT-156 pair stays optional`() {
        assertTrue(B2bProfileRules.evaluate(fields(bt13Set = false)).isEmpty())
    }

    @Test
    fun `with BT-13 set and both line article numbers filled there is no finding`() {
        assertTrue(B2bProfileRules.evaluate(fields(bt13Set = true, line155 = true, line156 = true)).isEmpty())
    }
}
