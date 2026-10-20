package de.littletrotter.domain.compliance

import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TODAY

/**
 * The resolver-level half of the tax-rule Pflicht-Testmatrix: every row's expected rule
 * code/category/rate (for the seven `gültig` rows) or blocked/unsupported outcome (for the
 * three others), against the real [TaxRuleResolver]. The document-level (KoSIT/XSD) half lives
 * in kotlin-xpdf's `ComplianceMatrixFixtureTest` — see [ComplianceMatrix]'s own KDoc for why
 * this is split across two files instead of one.
 */
class ComplianceMatrixResolverTest {

    @TestFactory
    fun `every matrix row resolves as documented`(): List<DynamicTest> =
        ComplianceMatrix.rows.map { row ->
            DynamicTest.dynamicTest(row.id) {
                val result = TaxRuleResolver.resolve(row.request, today = FIXED_TODAY)
                when (row.expectation) {
                    ComplianceMatrixRow.Expectation.VALID -> {
                        assertTrue(result is TaxRuleResolution.Proposed, "${row.id}: expected Proposed, got $result")
                        result as TaxRuleResolution.Proposed
                        assertEquals(row.expectedRuleCode, result.ruleCode, "${row.id}: ruleCode")
                        assertEquals(row.expectedCategory, result.category, "${row.id}: category")
                        assertEquals(
                            0, BigDecimal(row.expectedRate!!).compareTo(result.rate),
                            "${row.id}: rate — expected ${row.expectedRate}, got ${result.rate}",
                        )
                    }
                    ComplianceMatrixRow.Expectation.BLOCKED, ComplianceMatrixRow.Expectation.UNSUPPORTED -> {
                        assertTrue(result is TaxRuleResolution.Blocked, "${row.id}: expected Blocked, got $result")
                        result as TaxRuleResolution.Blocked
                        assertTrue(result.detail.isNotBlank(), "${row.id}: blocked detail must explain why")
                    }
                }
            }
        }
}
