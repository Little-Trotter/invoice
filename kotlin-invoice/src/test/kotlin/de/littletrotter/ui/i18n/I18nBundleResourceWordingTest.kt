package de.littletrotter.ui.i18n

import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * `/api/i18n/{locale}` is what the Playwright suite reads instead of a hand-copied
 * fixture, so it must carry the active wording profile's term the same way the UI does. Uses the
 * real bundle keys `item.category.product` (`{term:product.singular}`, varies by profile) and
 * `item.category.trip` (a plain constant since the PO decision of 13.09.2026 dropped the trip
 * term — the bundle must carry the exact same value regardless of the active profile).
 */
class I18nBundleResourceWordingTest {

    @Test
    fun `de locale carries the product term for all three profiles, and Dienstreise for all of them`() {
        val handwerk = I18nBundleResource(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))
        val handel = I18nBundleResource(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDEL", time = FIXED_TIME_SOURCE))
        val beratung = I18nBundleResource(WordingSettingsGate(InMemorySystemSettingRepository(), "BERATUNG", time = FIXED_TIME_SOURCE))

        assertEquals("Material", handwerk.bundle("de")["item.category.product"])
        assertEquals("Artikel", handel.bundle("de")["item.category.product"])
        assertEquals("Artikel", handel.bundle("de")["serviceCatalog.category.material"])
        assertEquals("Dienstleistung", handel.bundle("de")["serviceCatalog.category.service"])
        assertEquals("Produkt", beratung.bundle("de")["item.category.product"])
        assertEquals("Produkt", beratung.bundle("de")["serviceCatalog.category.material"])
        assertEquals("Dienstleistung", beratung.bundle("de")["serviceCatalog.category.service"])

        assertEquals("Dienstreise", handwerk.bundle("de")["item.category.trip"])
        assertEquals("Dienstreise", handel.bundle("de")["item.category.trip"])
        assertEquals("Dienstreise", beratung.bundle("de")["item.category.trip"])
    }

    @Test
    fun `en locale carries the product term for all three profiles, and Business trip for all of them`() {
        val handwerk = I18nBundleResource(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))
        val handel = I18nBundleResource(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDEL", time = FIXED_TIME_SOURCE))
        val beratung = I18nBundleResource(WordingSettingsGate(InMemorySystemSettingRepository(), "BERATUNG", time = FIXED_TIME_SOURCE))

        assertEquals("Material", handwerk.bundle("en")["item.category.product"])
        assertEquals("Item", handel.bundle("en")["item.category.product"])
        assertEquals("Item", handel.bundle("en")["serviceCatalog.category.material"])
        assertEquals("Service", handel.bundle("en")["serviceCatalog.category.service"])
        assertEquals("Product", beratung.bundle("en")["item.category.product"])
        assertEquals("Product", beratung.bundle("en")["serviceCatalog.category.material"])
        assertEquals("Service", beratung.bundle("en")["serviceCatalog.category.service"])

        assertEquals("Business trip", handwerk.bundle("en")["item.category.trip"])
        assertEquals("Business trip", handel.bundle("en")["item.category.trip"])
        assertEquals("Business trip", beratung.bundle("en")["item.category.trip"])
    }

    @Test
    fun `de locale uses neutral profile wording in filters details and reference data`() {
        val bundle = I18nBundleResource(
            WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE)
        ).bundle("de")

        assertEquals("Ausstellerprofile", bundle["nav.senderProfiles"])
        assertEquals("Alle Empfängerprofile", bundle["invoice.filter.recipient.all"])
        assertEquals("Alle Ausstellerprofile", bundle["invoice.filter.sender.all"])
        assertEquals("E-Mail im Empfängerprofil ist ein Pflichtfeld.", bundle["validation.recipient.email.required"])
        assertEquals("Neues Empfängerprofil", bundle["recipient.dialog.new"])
        assertEquals("Empfängerprofil bearbeiten", bundle["recipient.dialog.edit"])
        assertEquals("Als Standard im Ausstellerprofil verwenden", bundle["senderProfile.defaultSeller"])
        assertEquals("Kundenprofil: {0}", bundle["invoiceListExport.scope.recipient"])
        assertEquals("Anbieterprofil: {0}", bundle["invoiceListExport.scope.sender"])
        assertEquals("Empfängerprofil", bundle["printJobs.filter.customer"])
    }

    @Test
    fun `party terminology is consistent across industry profiles in German and English`() {
        listOf("HANDWERK", "HANDEL", "BERATUNG").forEach { profile ->
            val resource = I18nBundleResource(WordingSettingsGate(InMemorySystemSettingRepository(), profile, time = FIXED_TIME_SOURCE))
            val de = resource.bundle("de")
            val en = resource.bundle("en")
            assertEquals("Ausstellerprofile", de["nav.senderProfiles"], profile)
            assertEquals("Neues Empfängerprofil", de["recipient.dialog.new"], profile)
            assertEquals("Alle Empfängerprofile", de["invoice.filter.recipient.all"], profile)
            assertEquals("Issuer Profiles", en["nav.senderProfiles"], profile)
            assertEquals("New invoice recipient", en["recipient.dialog.new"], profile)
            assertEquals("All invoice recipients", en["invoice.filter.recipient.all"], profile)
        }
    }
}
