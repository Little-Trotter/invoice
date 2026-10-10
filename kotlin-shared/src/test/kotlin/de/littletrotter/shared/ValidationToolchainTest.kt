package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shape checks for [ValidationToolchain]. The real pin tests — binding each number to the
 * resolved dependency, the scenario resource path and the veraPDF default — live in kotlin-xpdf
 * (`ValidationToolchainPinTest`), where those artifacts are on the classpath; this test only
 * guards against a constant degenerating into something that is obviously not a version at all.
 */
class ValidationToolchainTest {

    private val semver = Regex("""\d+\.\d+\.\d+""")

    @Test
    fun `tool versions look like versions`() {
        assertNotNull(ValidationToolchain)
        assertTrue(semver.matches(ValidationToolchain.KOSIT_VALIDATOR_VERSION))
        assertTrue(semver.matches(ValidationToolchain.XRECHNUNG_VERSION))
        assertTrue(semver.matches(ValidationToolchain.VERAPDF_DEFAULT_VERSION))
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(ValidationToolchain.XRECHNUNG_CONFIG_RELEASE))
    }

    @Test
    fun `deliberate constants carry their exact agreed wording`() {
        // PD-01 conformance target and the untranslated product name — both are decisions,
        // not sourced versions, so their exact spelling IS the contract.
        assertEquals("PDF/A-3b", ValidationToolchain.PDFA_CONFORMANCE_LEVEL)
        assertEquals("Little Trotter", ValidationToolchain.BUSINESS_VALIDATION_NAME)
    }
}
