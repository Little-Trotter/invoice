package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.validation.CiiSchematronValidator
import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real-fixture routing proof for [CiiSchematronValidator]: `golden/community-invoice-cii.xml` is a
 * real Mustang XRechnung-profile export, also read by other tests
 * (`CiiXsdValidatorTest`/`FixtureValidationTest`/`ComplianceMatrixFixtureTest`/mustang's
 * `En16931TermsExportTest`), so it lives in the shared `src/test/resources/golden/` directory.
 * The general invariant that an EN16931-only run never compiles the EXTENDED stylesheet is
 * covered by `CiiSchematronValidatorTest`; this class only adds the XRechnung pass-through.
 */
class CiiSchematronXRechnungPassThroughTest {

    private val validator = CiiSchematronValidator().apply { init() }

    private fun goldenXRechnungFixture(): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream("golden/community-invoice-cii.xml")) {
            "golden fixture not found"
        }.use { it.readBytes() }

    @Test
    fun `an XRechnung CIUS document passes through untouched instead of being flagged`() {
        val stage = validator.validate(goldenXRechnungFixture())

        assertEquals(CiiSchematronValidator.STAGE, stage.stage)
        assertEquals(KositOutcome.VALID, stage.outcome)
        assertTrue(stage.skipped, "an XRechnung CIUS document is not this validator's domain")
        assertEquals(
            CiiSchematronValidator.SKIP_REASON_XRECHNUNG_CIUS,
            stage.skipReason,
            "the pass-through reason must be the stable token the report UI translates",
        )
        assertTrue(stage.findings.isEmpty())
    }
}
