package de.littletrotter.domain.compliance

import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [B2bProfileRules] in isolation: given already-resolved [B2bFieldState]s, does
 * the mandatory/conditional logic produce exactly the findings it should? No profile, no catalog,
 * no database — that resolution is [de.littletrotter.application.invoice.B2bProfileRuleSet]'s job
 * and has its own test. This file only proves the rule set "kennt kein einziges BT literal": every
 * assertion below reasons about `requirement`/`conditionBtCode`/`hasValue`, never about which
 * business term a field happens to carry — the concrete [B2bTerm] constants below are picked arbitrarily and interchangeably per test, never for
 * their real-world meaning.
 */
class B2bProfileRulesTest {

    private fun header(btCode: B2bTerm, requirement: B2bFieldRequirement, hasValue: Boolean, conditionBtCode: B2bTerm? = null) =
        B2bFieldState(btCode, requirement, conditionBtCode, "Definition of ${btCode.officialCode}", hasValue, FindingTarget.Header)

    private fun line(positionId: String, btCode: B2bTerm, requirement: B2bFieldRequirement, hasValue: Boolean, conditionBtCode: B2bTerm? = null) =
        B2bFieldState(btCode, requirement, conditionBtCode, "Definition of ${btCode.officialCode}", hasValue, FindingTarget.Position(positionId))

    @Nested
    inner class Mandatory {

        @Test
        fun `a missing mandatory field produces an ERROR finding`() {
            val findings = B2bProfileRules.evaluate(listOf(header(B2bTerm.BT_19, B2bFieldRequirement.MANDATORY, hasValue = false)))

            assertEquals(1, findings.size)
            val finding = findings.single()
            assertEquals(B2bProfileRules.MANDATORY_FIELD, finding.ruleId)
            assertEquals(ComplianceSeverity.ERROR, finding.severity)
            assertEquals(listOf(B2bTerm.BT_19), finding.businessTerms)
            assertEquals(FindingTarget.Header, finding.target)
        }

        @Test
        fun `the anwenderdefinierte definition travels along as an arg, not just the bt code`() {
            val state = header(B2bTerm.BT_19, B2bFieldRequirement.MANDATORY, hasValue = false)
            val finding = B2bProfileRules.evaluate(listOf(state)).single()

            assertEquals(listOf("BT-19", "Definition of BT-19"), finding.args)
        }

        @Test
        fun `a present mandatory field produces no finding`() {
            val findings = B2bProfileRules.evaluate(listOf(header(B2bTerm.BT_19, B2bFieldRequirement.MANDATORY, hasValue = true)))
            assertTrue(findings.isEmpty())
        }

        @Test
        fun `a missing optional field never produces a finding`() {
            val findings = B2bProfileRules.evaluate(listOf(header(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL, hasValue = false)))
            assertTrue(findings.isEmpty())
        }

        @Test
        fun `an empty input produces an empty result`() {
            assertTrue(B2bProfileRules.evaluate(emptyList()).isEmpty())
        }
    }

    /** DoD: "Bedingte Pflicht wirkt datengetrieben ... ein Test belegt das, indem er die
     *  Bedingung im Profil umhängt statt Code zu ändern" — every case below is the *same* call to
     *  [B2bProfileRules.evaluate], only the [B2bFieldState] data differs. */
    @Nested
    inner class Conditional {

        @Test
        fun `a conditional field is required once its trigger has a value`() {
            val trigger = header(B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL, hasValue = true)
            val conditional = header(B2bTerm.BT_19, B2bFieldRequirement.CONDITIONAL, hasValue = false, conditionBtCode = B2bTerm.BT_13)

            val findings = B2bProfileRules.evaluate(listOf(trigger, conditional))

            assertEquals(1, findings.size)
            assertEquals(listOf(B2bTerm.BT_19), findings.single().businessTerms)
        }

        @Test
        fun `a conditional field is not required while its trigger has no value`() {
            val trigger = header(B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL, hasValue = false)
            val conditional = header(B2bTerm.BT_19, B2bFieldRequirement.CONDITIONAL, hasValue = false, conditionBtCode = B2bTerm.BT_13)

            assertTrue(B2bProfileRules.evaluate(listOf(trigger, conditional)).isEmpty())
        }

        @Test
        fun `re-pointing the trigger in the profile data alone flips the outcome`() {
            val btA = header(B2bTerm.BT_12, B2bFieldRequirement.OPTIONAL, hasValue = true)
            val btB = header(B2bTerm.BT_18, B2bFieldRequirement.OPTIONAL, hasValue = false)

            val conditionedOnA = header(B2bTerm.BT_56, B2bFieldRequirement.CONDITIONAL, hasValue = false, conditionBtCode = B2bTerm.BT_12)
            val conditionedOnB = header(B2bTerm.BT_56, B2bFieldRequirement.CONDITIONAL, hasValue = false, conditionBtCode = B2bTerm.BT_18)

            assertEquals(1, B2bProfileRules.evaluate(listOf(btA, btB, conditionedOnA)).size, "BT-12 hat einen Wert -> Pflicht")
            assertTrue(B2bProfileRules.evaluate(listOf(btA, btB, conditionedOnB)).isEmpty(), "BT-18 hat keinen Wert -> keine Pflicht")
        }

        @Test
        fun `an unresolvable trigger (not part of the profile) is simply not required`() {
            val conditional = header(B2bTerm.BT_19, B2bFieldRequirement.CONDITIONAL, hasValue = false, conditionBtCode = B2bTerm.BT_84)
            assertTrue(B2bProfileRules.evaluate(listOf(conditional)).isEmpty())
        }

        @Test
        fun `a LINE field's condition can be triggered by a header field`() {
            val trigger = header(B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL, hasValue = true)
            val lineField = line("1", B2bTerm.BT_155, B2bFieldRequirement.CONDITIONAL, hasValue = false, conditionBtCode = B2bTerm.BT_13)

            val findings = B2bProfileRules.evaluate(listOf(trigger, lineField))

            assertEquals(1, findings.size)
            assertEquals(FindingTarget.Position("1"), findings.single().target)
        }
    }

    /** DoD: "LINE-Befunde zeigen auf die richtige Position; ein Test mit drei Positionen, davon
     *  einer unvollständig, belegt es." */
    @Nested
    inner class LinePositionTargeting {

        @Test
        fun `only the incomplete position produces a finding, and it names that position`() {
            val fields = listOf(
                line("1", B2bTerm.BT_155, B2bFieldRequirement.MANDATORY, hasValue = true),
                line("2", B2bTerm.BT_155, B2bFieldRequirement.MANDATORY, hasValue = false),
                line("3", B2bTerm.BT_155, B2bFieldRequirement.MANDATORY, hasValue = true),
            )

            val findings = B2bProfileRules.evaluate(fields)

            assertEquals(1, findings.size)
            assertEquals(FindingTarget.Position("2"), findings.single().target)
        }

        @Test
        fun `a line field's trigger among another position's siblings does not satisfy this position's condition`() {
            // Position "1" carries BT-13 with a value; position "2" does not. A conditional field
            // on position "2" must not be satisfied by position "1"'s trigger.
            val fields = listOf(
                line("1", B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL, hasValue = true),
                line("2", B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL, hasValue = false),
                line("2", B2bTerm.BT_155, B2bFieldRequirement.CONDITIONAL, hasValue = false, conditionBtCode = B2bTerm.BT_13),
            )

            val findings = B2bProfileRules.evaluate(fields)

            assertTrue(findings.isEmpty(), "Position 2s eigenes BT-13 ist leer, das Feld darf nicht als Pflicht erscheinen: $findings")
        }

        @Test
        fun `a line field's own sibling trigger on the same position is honored`() {
            val fields = listOf(
                line("1", B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL, hasValue = true),
                line("1", B2bTerm.BT_155, B2bFieldRequirement.CONDITIONAL, hasValue = false, conditionBtCode = B2bTerm.BT_13),
            )

            val findings = B2bProfileRules.evaluate(fields)

            assertEquals(1, findings.size)
            assertEquals(FindingTarget.Position("1"), findings.single().target)
        }
    }

    /** [B2bFieldRequirement.CONDITIONAL_IF_EMPTY], the either-or/XOR counterpart to
     *  CONDITIONAL: required exactly when the trigger is *absent*. The full 4-cell matrix
     *  (both empty / one filled either way / both filled), plus the LINE-level "absent on
     *  at least one line" predicate for a header field conditioned on a line-level term. */
    @Nested
    inner class ConditionalIfEmpty {

        @Test
        fun `both empty - both fields are required, two findings`() {
            val a = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_134)
            val b = header(B2bTerm.BT_134, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_72)

            val findings = B2bProfileRules.evaluate(listOf(a, b))

            assertEquals(2, findings.size)
            assertEquals(setOf(B2bTerm.BT_72, B2bTerm.BT_134), findings.map { it.businessTerms.first() }.toSet())
        }

        @Test
        fun `A filled, B empty - A is already satisfied and B's trigger (A) has a value, so neither is required`() {
            val a = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = true, conditionBtCode = B2bTerm.BT_134)
            val b = header(B2bTerm.BT_134, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_72)

            assertTrue(B2bProfileRules.evaluate(listOf(a, b)).isEmpty(), "filling either side of the XOR pair fully satisfies it")
        }

        @Test
        fun `B filled, A empty - the mirror case, also no findings`() {
            val a = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_134)
            val b = header(B2bTerm.BT_134, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = true, conditionBtCode = B2bTerm.BT_72)

            assertTrue(B2bProfileRules.evaluate(listOf(a, b)).isEmpty())
        }

        @Test
        fun `both filled - neither is required`() {
            val a = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = true, conditionBtCode = B2bTerm.BT_134)
            val b = header(B2bTerm.BT_134, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = true, conditionBtCode = B2bTerm.BT_72)

            assertTrue(B2bProfileRules.evaluate(listOf(a, b)).isEmpty())
        }

        @Test
        fun `an if-empty finding names both terms via businessTerms, unlike a plain MANDATORY finding`() {
            val a = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_134)
            val b = header(B2bTerm.BT_134, B2bFieldRequirement.OPTIONAL, hasValue = false)

            val finding = B2bProfileRules.evaluate(listOf(a, b)).single()

            assertEquals(listOf(B2bTerm.BT_72, B2bTerm.BT_134), finding.businessTerms)
            assertEquals("compliance.b2b.mandatoryFieldMissingIfEmpty", finding.messageKey)
            // args stays [btCode.officialCode, businessDefinition] — index 1 is load-bearing for
            // the detail view's B2B finding read model (its definition) and must not shift.
            assertEquals(listOf("BT-72", "Definition of BT-72"), finding.args)
        }

        @Test
        fun `an unresolvable trigger not part of the profile at all is treated as empty - required`() {
            val a = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_84)
            assertEquals(1, B2bProfileRules.evaluate(listOf(a)).size)
        }

        @Test
        fun `a field with no conditionBtCode at all is never required, even under CONDITIONAL_IF_EMPTY`() {
            // conditionBtCode = null (no trigger term named at all) -- distinct from "a trigger
            // term was named but is unresolvable", which the test above covers.
            val a = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = null)
            assertEquals(0, B2bProfileRules.evaluate(listOf(a)).size)
        }

        // ── LINE-level trigger: "absent" means absent on at least one line ──

        @Test
        fun `header field if-empty a LINE trigger - required while any line lacks it`() {
            val header = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_134)
            val fields = listOf(
                header,
                line("1", B2bTerm.BT_134, B2bFieldRequirement.OPTIONAL, hasValue = true),
                line("2", B2bTerm.BT_134, B2bFieldRequirement.OPTIONAL, hasValue = false),
                line("3", B2bTerm.BT_134, B2bFieldRequirement.OPTIONAL, hasValue = true),
            )

            val findings = B2bProfileRules.evaluate(fields)

            assertEquals(1, findings.size)
            assertEquals(B2bTerm.BT_72, findings.single().businessTerms.first())
            assertEquals(FindingTarget.Header, findings.single().target)
        }

        @Test
        fun `header field if-empty a LINE trigger - not required once every line carries it`() {
            val header = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_134)
            val fields = listOf(
                header,
                line("1", B2bTerm.BT_134, B2bFieldRequirement.OPTIONAL, hasValue = true),
                line("2", B2bTerm.BT_134, B2bFieldRequirement.OPTIONAL, hasValue = true),
            )

            assertTrue(B2bProfileRules.evaluate(fields).isEmpty())
        }

        @Test
        fun `header field if-empty a LINE trigger - no lines at all counts as empty, required`() {
            val header = header(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_134)
            assertEquals(1, B2bProfileRules.evaluate(listOf(header)).size)
        }

        @Test
        fun `a LINE field if-empty a header trigger is resolved per line, not via the any-line predicate`() {
            // The header trigger is header-level and *is* in scope, so this must use the direct
            // hasValue lookup (CONDITIONAL's own mechanism, inverted) — not the LINE-only fallback.
            val trigger = header(B2bTerm.BT_72, B2bFieldRequirement.OPTIONAL, hasValue = false)
            val fields = listOf(
                trigger,
                line("1", B2bTerm.BT_134, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, hasValue = false, conditionBtCode = B2bTerm.BT_72),
            )

            val findings = B2bProfileRules.evaluate(fields)

            assertEquals(1, findings.size)
            assertEquals(FindingTarget.Position("1"), findings.single().target)
        }
    }
}
