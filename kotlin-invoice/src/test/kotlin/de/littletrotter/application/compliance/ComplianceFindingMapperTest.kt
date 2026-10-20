package de.littletrotter.application.compliance

import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.compliance.ComplianceSeverity
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Unit, DB-free. Exercises every
 * [ComplianceSeverity] × every [FindingTarget] variant (both sealed/exhaustive `when`s in
 * [ComplianceFindingMapper]), `ruleId` passthrough, and the `BUSINESS` stage outcome rule. This
 * class is the sole test target of the `check-arc6-compliance-mapper` JaCoCo gate in
 * `kotlin-invoice`'s `pom.xml` (LINE = BRANCH = 1.00, scoped to
 * `de.littletrotter.application.compliance`).
 */
class ComplianceFindingMapperTest {

    companion object {
        @JvmStatic
        fun allTargets(): List<FindingTarget> = listOf(
            FindingTarget.Header,
            FindingTarget.Position("42"),
            FindingTarget.Seller,
            FindingTarget.Buyer,
            FindingTarget.Article("Beratung"),
        )
    }

    // ── severity mapping — every ComplianceSeverity branch ──────────────────────────────────

    @ParameterizedTest
    @EnumSource(ComplianceSeverity::class)
    fun `severity maps to the lowercase report severity`(severity: ComplianceSeverity) {
        val finding = ComplianceFinding("BR-X", severity, "some.key")

        val message = ComplianceFindingMapper.toValidationMessage(finding)

        val expected = when (severity) {
            ComplianceSeverity.ERROR -> "error"
            ComplianceSeverity.WARNING -> "warning"
            ComplianceSeverity.INFO -> "info"
        }
        assertEquals(expected, message.severity)
    }

    // ── location mapping — every FindingTarget branch ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("allTargets")
    fun `target maps to the location convention`(target: FindingTarget) {
        val finding = ComplianceFinding("BR-X", ComplianceSeverity.ERROR, "some.key", target = target)

        val location = ComplianceFindingMapper.toValidationMessage(finding).location

        val expected = when (target) {
            is FindingTarget.Header -> "header"
            is FindingTarget.Position -> "position:42"
            is FindingTarget.Seller -> "seller"
            is FindingTarget.Buyer -> "buyer"
            is FindingTarget.Article -> "article:Beratung"
        }
        assertEquals(expected, location)
    }

    // ── constant fields + passthrough ────────────────────────────────────────────────────────

    @Test
    fun `stage is always BUSINESS and validator is always compliance`() {
        val finding = ComplianceFinding("BR-CO-25", ComplianceSeverity.WARNING, "some.key")

        val message = ComplianceFindingMapper.toValidationMessage(finding)

        assertEquals("BUSINESS", message.stage)
        assertEquals("compliance", message.validator)
    }

    @Test
    fun `ruleId passes through unchanged`() {
        val finding = ComplianceFinding("LT-99", ComplianceSeverity.INFO, "some.key")

        assertEquals("LT-99", ComplianceFindingMapper.toValidationMessage(finding).ruleId)
    }

    @Test
    fun `message carries the raw messageKey, not resolved text`() {
        val finding = ComplianceFinding("CORE.INVOICE_NUMBER", ComplianceSeverity.ERROR, "invoice.number.missing")

        assertEquals("invoice.number.missing", ComplianceFindingMapper.toValidationMessage(finding).message)
    }

    // ── args passthrough — without this, a resolving UI has nothing to fill the messageKey's
    // {0}/{1} placeholders with (the raw-i18n-key defect this mapping exists to fix). ───────────

    @Test
    fun `args pass through as strings`() {
        val finding = ComplianceFinding(
            "TAX.CATEGORY_MISMATCH", ComplianceSeverity.ERROR, "compliance.tax.categoryMismatch",
            args = listOf("S", "AE"),
        )

        assertEquals(listOf("S", "AE"), ComplianceFindingMapper.toValidationMessage(finding).args)
    }

    @Test
    fun `a finding with no args maps to an empty args list, not null`() {
        val finding = ComplianceFinding("CORE.X", ComplianceSeverity.ERROR, "some.key")

        assertEquals(emptyList<String>(), ComplianceFindingMapper.toValidationMessage(finding).args)
    }

    @Test
    fun `a non-string arg is stringified`() {
        val finding = ComplianceFinding(
            "CORE.X", ComplianceSeverity.ERROR, "some.key",
            args = listOf(42, null),
        )

        assertEquals(listOf("42", ""), ComplianceFindingMapper.toValidationMessage(finding).args)
    }

    // ── stage outcome rule ───────────────────────────────────────────────────────────────────

    @Test
    fun `no findings yields a VALID, non-skipped BUSINESS stage`() {
        val result = ComplianceFindingMapper.toValidationStageResult(emptyList())

        assertEquals("BUSINESS", result.stage)
        assertEquals(KositOutcome.VALID, result.outcome)
        assertFalse(result.skipped)
        assertEquals(emptyList<Any>(), result.findings)
    }

    @Test
    fun `only WARNING and INFO findings still yield VALID`() {
        val findings = listOf(
            ComplianceFinding("LT-1", ComplianceSeverity.WARNING, "k1"),
            ComplianceFinding("LT-2", ComplianceSeverity.INFO, "k2"),
        )

        val result = ComplianceFindingMapper.toValidationStageResult(findings)

        assertEquals(KositOutcome.VALID, result.outcome)
        assertEquals(2, result.findings.size)
    }

    @Test
    fun `at least one ERROR finding yields INVALID`() {
        val findings = listOf(
            ComplianceFinding("LT-1", ComplianceSeverity.WARNING, "k1"),
            ComplianceFinding("CORE.X", ComplianceSeverity.ERROR, "k2"),
        )

        val result = ComplianceFindingMapper.toValidationStageResult(findings)

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertEquals(2, result.findings.size)
    }

    @Test
    fun `stage result is never skipped, even for an empty finding list`() {
        assertFalse(ComplianceFindingMapper.toValidationStageResult(emptyList()).skipped)
    }
}
