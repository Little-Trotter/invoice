package de.littletrotter.ui.settings

import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.settings.InMemoryFeatureSettingRepository
import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.settings.FeatureSetting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * SFS — the availability window's JSON contract, asserted on the returned Map directly (JAX-RS
 * adds nothing worth testing here). The key spellings are what the kotlin-e2e ITs and the
 * admin-tools Playwright probe parse; renaming one is a contract break and this test fails first.
 */
class SettingsAvailabilityResourceTest {

    private fun flags(json: Map<String, Any>): Map<String, Boolean> {
        val raw = json["flags"] as Map<*, *>
        return raw.entries.associate { (k, v) -> (k as String) to (v as Boolean) }
    }

    private fun effective(json: Map<String, Any>): Map<String, Any> {
        val raw = json["effective"] as Map<*, *>
        return raw.entries.associate { (k, v) -> (k as String) to (v as Any) }
    }

    private fun resource(
        emailFlag: Boolean = true,
        perdiemFlag: Boolean = true,
        mailInstalled: Boolean = false,
        quoteFlag: Boolean = true,
        quoteInstalled: Boolean = false,
        wordingGate: WordingSettingsGate = WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE),
    ): SettingsAvailabilityResource {
        val repo = InMemoryFeatureSettingRepository().apply {
            save(FeatureSetting.of(FeatureFlag.EMAIL_SEND, emailFlag))
            save(FeatureSetting.of(FeatureFlag.PER_DIEM_UI, perdiemFlag))
            save(FeatureSetting.of(FeatureFlag.QUOTE, quoteFlag))
        }
        return SettingsAvailabilityResource(
            FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE),
            MailAvailability(mailInstalled),
            QuoteAvailability(quoteInstalled),
            wordingGate,
        )
    }

    @Test
    fun `catalog keys are the feature_setting keys, not enum names`() {
        val json = resource().availability()
        assertEquals(
            setOf(
                "feature.perdiem.enabled",
                "feature.email.enabled",
                "feature.quote.enabled",
                "feature.extended-profile.enabled",
            ),
            flags(json).keys,
        )
        assertEquals(
            setOf("perdiemUi", "emailDispatch", "emailDeployment", "quoteDeployment", "quoteOffered", "wordingProfile"),
            effective(json).keys,
        )
    }

    @Test
    fun `quoteOffered is the AND of deployment and flag, quoteDeployment passes through`() {
        listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false),
        ).forEach { (flagOn, deployed, expected) ->
            val json = resource(quoteFlag = flagOn, quoteInstalled = deployed).availability()
            assertEquals(expected, effective(json)["quoteOffered"], "flag=$flagOn deployed=$deployed")
            assertEquals(deployed, effective(json)["quoteDeployment"])
        }
        assertEquals(false, flags(resource(quoteFlag = false).availability())["feature.quote.enabled"])
    }

    @Test
    fun `wordingProfile reflects the gate, read-only`() {
        val handwerk = resource(wordingGate = WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))
        val beratung = resource(wordingGate = WordingSettingsGate(InMemorySystemSettingRepository(), "BERATUNG", time = FIXED_TIME_SOURCE))
        assertEquals("HANDWERK", effective(handwerk.availability())["wordingProfile"])
        assertEquals("BERATUNG", effective(beratung.availability())["wordingProfile"])
    }

    @Test
    fun `emailDispatch is the AND of deployment and flag, emailDeployment passes through`() {
        listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false),
        ).forEach { (flagOn, deployed, expected) ->
            val json = resource(emailFlag = flagOn, mailInstalled = deployed).availability()
            assertEquals(expected, effective(json)["emailDispatch"], "flag=$flagOn deployed=$deployed")
            assertEquals(deployed, effective(json)["emailDeployment"])
        }
    }

    @Test
    fun `perdiemUi reads the flag directly, with no deployment half`() {
        assertEquals(false, effective(resource(perdiemFlag = false).availability())["perdiemUi"])
        assertEquals(true, effective(resource(perdiemFlag = true).availability())["perdiemUi"])
    }
}
