package de.littletrotter.application.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * SFS — the truth table of the two AND-compositions every consumer shares. The gate itself is
 * covered by [FeatureSettingsGateTest]; these functions are what prevents each consumer
 * (drawer, invoice mail button, dispatch service) from inventing its own combination.
 */
class FeatureOffersTest {

    @ParameterizedTest
    @CsvSource(
        "true,  true,  true",
        "true,  false, false",
        "false, true,  false",
        "false, false, false",
    )
    fun `mail is offered exactly when deployment and flag are both on`(
        deployment: Boolean,
        flag: Boolean,
        expected: Boolean,
    ) {
        assertEqualsOnly(expected, FeatureOffers.mailOffered(deployment, flag))
    }

    @ParameterizedTest
    @CsvSource(
        "true,  true,  true",
        "true,  false, false",
        "false, true,  false",
        "false, false, false",
    )
    fun `quote is offered exactly when deployment and flag are both on`(
        deployment: Boolean,
        flag: Boolean,
        expected: Boolean,
    ) {
        assertEqualsOnly(expected, FeatureOffers.quoteOffered(deployment, flag))
    }

    @ParameterizedTest
    @CsvSource("true, true", "false, false")
    fun `per-diem affordances follow their flag alone`(flag: Boolean, expected: Boolean) {
        assertEqualsOnly(expected, FeatureOffers.perDiemOffered(flag))
    }

    private fun assertEqualsOnly(expected: Boolean, actual: Boolean) {
        if (expected) assertTrue(actual) else assertFalse(actual)
    }
}
