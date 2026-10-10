package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UI-5 — [AddressFormSection] had no dedicated test before this file, despite carrying real
 * null-safety decisions in both [AddressFormSection.populate] overloads and in [clear]: the
 * `orEmpty()`/`?:` fallbacks for a null street/postalCode/city/country, and the `Address?`
 * overload's own null-vs-present fork on top of that.
 */
class AddressFormSectionTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val tr: (String) -> String = { it }

    private fun section(required: Boolean = false) = AddressFormSection("recipient", tr, required = required)

    @Test
    fun `fields are labeled from the label prefix and default to Germany, not required`() {
        val section = section()

        assertEquals("recipient.street", section.street.label)
        assertEquals("recipient.postalCode", section.postalCode.label)
        assertEquals("recipient.city", section.city.label)
        assertEquals("recipient.country", section.country.label)
        assertEquals(Country.DE, section.country.value)
        assertEquals(false, section.street.isRequired)
    }

    @Test
    fun `required propagates to every field`() {
        val section = section(required = true)

        assertEquals(true, section.street.isRequired)
        assertEquals(true, section.postalCode.isRequired)
        assertEquals(true, section.city.isRequired)
        assertEquals(true, section.country.isRequired)
    }

    // ── populate(String?, String?, String?, Country?) ─────────────────────────

    @Test
    fun `populate with real values sets every field verbatim`() {
        val section = section()

        section.populate("Hauptstr. 1", "12345", "Berlin", Country.FR)

        assertEquals("Hauptstr. 1", section.street.value)
        assertEquals("12345", section.postalCode.value)
        assertEquals("Berlin", section.city.value)
        assertEquals(Country.FR, section.country.value)
    }

    @Test
    fun `populate with all-null values falls back to empty strings and Germany`() {
        val section = section()
        section.populate("old street", "old zip", "old city", Country.FR)

        section.populate(null, null, null, null)

        assertEquals("", section.street.value)
        assertEquals("", section.postalCode.value)
        assertEquals("", section.city.value)
        assertEquals(Country.DE, section.country.value)
    }

    // ── populate(Address?) ─────────────────────────────────────────────────────

    @Test
    fun `populate with a null address clears every field to its blank default`() {
        val section = section()
        section.populate("old street", "old zip", "old city", Country.FR)

        section.populate(null as Address?)

        assertEquals("", section.street.value)
        assertEquals("", section.postalCode.value)
        assertEquals("", section.city.value)
        assertEquals(Country.DE, section.country.value)
    }

    @Test
    fun `populate with a present address forwards its own fields`() {
        val section = section()
        val address = Address.forSender(
            SenderProfile(), Address.AddressType.BILLING, "Bahnhofstr. 9", "54321", "Munchen", Country.AT,
        )

        section.populate(address)

        assertEquals("Bahnhofstr. 9", section.street.value)
        assertEquals("54321", section.postalCode.value)
        assertEquals("Munchen", section.city.value)
        assertEquals(Country.AT, section.country.value)
    }

    // ── populate(AddressDto?) ────────────────────────────────────────

    @Test
    fun `populate with a null AddressDto clears every field to its blank default`() {
        val section = section()
        section.populate("old street", "old zip", "old city", Country.FR)

        section.populate(null as de.littletrotter.application.dto.AddressDto?)

        assertEquals("", section.street.value)
        assertEquals("", section.postalCode.value)
        assertEquals("", section.city.value)
        assertEquals(Country.DE, section.country.value)
    }

    @Test
    fun `populate with a present AddressDto forwards its own fields`() {
        val section = section()
        val address = de.littletrotter.application.dto.AddressDto(
            id = 1L, street = "Bahnhofstr. 9", postalCode = "54321", city = "Munchen", countryAlpha2 = "AT",
        )

        section.populate(address)

        assertEquals("Bahnhofstr. 9", section.street.value)
        assertEquals("54321", section.postalCode.value)
        assertEquals("Munchen", section.city.value)
        assertEquals(Country.AT, section.country.value)
    }

    // ── clear ───────────────────────────────────────────────────────────────

    @Test
    fun `clear resets every field to its blank default`() {
        val section = section()
        section.populate("street", "zip", "city", Country.FR)

        section.clear()

        assertEquals("", section.street.value)
        assertEquals("", section.postalCode.value)
        assertEquals("", section.city.value)
        assertEquals(Country.DE, section.country.value)
    }

    // ── onCountryChange ─────────────────────────────────────────────────────

    @Test
    fun `onCountryChange notifies the listener with the newly selected country`() {
        val section = section()
        var seen: Country? = null
        section.onCountryChange { seen = it }

        section.country.value = Country.CH

        assertEquals(Country.CH, seen)
    }
}
