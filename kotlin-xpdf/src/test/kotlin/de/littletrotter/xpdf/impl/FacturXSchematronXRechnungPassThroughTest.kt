package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXSchematronValidator
import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Split out of kotlin-facturx's `FacturXSchematronValidatorTest` (see that class's own
 * KDoc on the split): `golden/community-invoice-cii.xml` is a cross-engine fixture — a real
 * Mustang XRechnung-profile export, also read by non-Factur-X tests
 * (`CiiXsdValidatorTest`/`FixtureValidationTest`/`ComplianceMatrixFixtureTest`/mustang's
 * `En16931TermsExportTest`) — so it stays in kotlin-xpdf's `src/test/
 * resources/golden/` rather than moving into kotlin-facturx with the rest of the Factur-X-only
 * fixtures. This test exercises [FacturXSchematronValidator] (now in kotlin-facturx, reached here
 * as an ordinary compile dependency) against that fixture from the only module where it is
 * actually on the test classpath.
 *
 * Does not additionally assert `extendedTemplates.isInitialized() == false` here (the original,
 * same-module version of this test did): [FacturXSchematronValidator.extendedTemplates] is
 * `internal`, and Kotlin's `internal` is scoped to the compilation module — kotlin-facturx's own
 * `internal` members are invisible from kotlin-xpdf even though kotlin-xpdf compiles against the
 * jar. The general form of that invariant ("an EN16931-only/skip-routed run never touches
 * `extendedTemplates`") stays fully covered by `FacturXSchematronValidatorTest`'s own
 * `` `an EN16931-only run never compiles the EXTENDED stylesheet` `` test, which sees the field
 * from inside the same module — this class only adds the specific real-fixture routing proof.
 */
class FacturXSchematronXRechnungPassThroughTest {

    private val validator = FacturXSchematronValidator().apply { init() }

    private fun goldenXRechnungFixture(): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream("golden/community-invoice-cii.xml")) {
            "golden fixture not found"
        }.use { it.readBytes() }

    @Test
    fun `an XRechnung CIUS document passes through untouched instead of being flagged`() {
        val stage = validator.validate(goldenXRechnungFixture())

        assertEquals(FacturXSchematronValidator.STAGE, stage.stage)
        assertEquals(KositOutcome.VALID, stage.outcome)
        assertTrue(stage.skipped, "an XRechnung CIUS document is not this validator's domain")
        assertEquals(
            FacturXSchematronValidator.SKIP_REASON_XRECHNUNG_CIUS,
            stage.skipReason,
            "the pass-through reason must be the stable token the report UI translates",
        )
        assertTrue(stage.findings.isEmpty())
    }
}
