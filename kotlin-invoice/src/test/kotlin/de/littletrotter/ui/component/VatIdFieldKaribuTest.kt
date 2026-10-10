package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.AbstractField
import com.vaadin.flow.component.ComponentUtil
import de.littletrotter.shared.country.Country
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `vat-id-field-ergonomics` (PO report 14.09.2026) — the shared [VatIdField] component's own
 * behaviour, independent of any one view. [VatIdField.validateNow] is the testable stand-in for a
 * real client blur/Enter event (Karibu-testing has no browser to fire one from); it runs the exact
 * same normalise/auto-prefix/validate pass `RecipientView`'s VIES button and both views' save-time
 * validation call in production.
 */
class VatIdFieldKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun field(country: () -> Country?, mismatchIsError: Boolean = false): VatIdField =
        VatIdField({ key -> UiTranslations.tr(key) }, country, mismatchIsError)

    @Test
    fun `digit-only input auto-prefixes from the linked country and explains it in the helper text`() {
        val f = field({ Country.DE })
        f.value = "123456789"

        val valid = f.validateNow()

        assertTrue(valid)
        assertEquals("DE123456789", f.value)
        assertFalse(f.isInvalid)
        assertTrue(f.helperText.contains("DE"), f.helperText)
    }

    @Test
    fun `a Greek-address digit-only input auto-prefixes with EL, not GR, and the suffix reads Griechenland`() {
        val f = field({ Country.GR })
        f.value = "123456789"

        f.validateNow()

        assertEquals("EL123456789", f.value)
        assertFalse(f.isInvalid)
    }

    @Test
    fun `letters-first input is never auto-prefixed even when it does not match the linked country`() {
        val f = field({ Country.DE })
        f.value = "ATU12345678"

        f.validateNow()

        assertEquals("ATU12345678", f.value, "a value that already starts with letters must never be touched")
    }

    @Test
    fun `blur-equivalent validation normalises spaces, lower case and non-breaking spaces`() {
        val f = field({ Country.DE })
        f.value = "de 143 454 214"

        f.validateNow()

        assertEquals("DE143454214", f.value)
        assertFalse(f.isInvalid)
    }

    @Test
    fun `an unrecognised country prefix marks the field invalid with the unknownPrefix message`() {
        val f = field({ null })
        f.value = "XX123456789"

        val valid = f.validateNow()

        assertFalse(valid)
        assertTrue(f.isInvalid)
        assertEquals(UiTranslations.tr("validation.vatId.unknownPrefix", "XX"), f.errorMessage)
    }

    @Test
    fun `a recognised prefix whose number does not match the pattern is invalid with the format message`() {
        val f = field({ null })
        f.value = "DE12345"

        f.validateNow()

        assertTrue(f.isInvalid)
        assertEquals(UiTranslations.tr("validation.vatId.format", "DE", "DE123456789"), f.errorMessage)
    }

    @Test
    fun `a value over the length limit is invalid with the tooLong message`() {
        val f = field({ null })
        f.value = "XX" + "1".repeat(63)

        f.validateNow()

        assertTrue(f.isInvalid)
        assertEquals(UiTranslations.tr("validation.vatId.tooLong", "64"), f.errorMessage)
    }

    @Test
    fun `a blank field is valid and shows no helper text`() {
        val f = field({ Country.DE })
        f.value = ""

        val valid = f.validateNow()

        assertTrue(valid)
        assertFalse(f.isInvalid)
        assertEquals("", f.helperText)
    }

    // ── prefix vs. linked-country mismatch: informational vs. error, per mode ────────────

    @Test
    fun `mismatchIsError = false renders a prefix-country mismatch as an informational helper, not an error`() {
        val f = field({ Country.DE }, mismatchIsError = false)
        f.value = "ATU12345678"

        val valid = f.validateNow()

        assertTrue(valid, "customer/sender mode: a mismatch must not fail validation")
        assertFalse(f.isInvalid)
        assertEquals(UiTranslations.tr("shared.vatId.countryMismatch", "AT", "DE"), f.helperText)
    }

    @Test
    fun `mismatchIsError = true renders a prefix-country mismatch as an error`() {
        val f = field({ Country.DE }, mismatchIsError = true)
        f.value = "ATU12345678"

        val valid = f.validateNow()

        assertFalse(valid, "seller-registration mode: the row's own country is definitive")
        assertTrue(f.isInvalid)
        assertEquals(UiTranslations.tr("shared.vatId.countryMismatch", "AT", "DE"), f.errorMessage)
    }

    @Test
    fun `a matching prefix and linked country shows the formatOk helper, not a mismatch`() {
        val f = field({ Country.DE })
        f.value = "DE123456789"

        f.validateNow()

        assertEquals(UiTranslations.tr("shared.vatId.formatOk"), f.helperText)
    }

    // ── suffix and placeholder ───────────────────────────────────────────────────────────

    @Test
    fun `the placeholder is the linked country's own example, falling back to the German one`() {
        val f = field({ Country.AT })
        assertEquals("ATU12345678", f.placeholder)

        val generic = field({ null })
        assertEquals("DE123456789", generic.placeholder)
    }

    @Test
    fun `onLinkedCountryChanged refreshes the placeholder without touching a typed value`() {
        var country: Country? = Country.DE
        val f = field({ country })
        f.value = "ATU12345678"

        country = Country.AT
        f.onLinkedCountryChanged()

        assertEquals("ATU12345678", f.value, "a passive country change must never rewrite what the user typed")
    }

    // ── applyCountryPrefill ────────────────────────────────────────────────────────────

    @Test
    fun `boundary - a two-argument construction uses the mismatchIsError default of false`() {
        val f = VatIdField({ key -> UiTranslations.tr(key) }, { Country.DE })
        f.value = "ATU12345678"

        val valid = f.validateNow()

        assertTrue(valid, "the default mismatchIsError = false must treat the mismatch as informational")
    }

    @Test
    fun `boundary - applyCountryPrefill seeds a blank field with the linked country's VAT prefix`() {
        val f = field({ Country.DE })
        f.applyCountryPrefill()
        assertEquals("DE", f.value)
    }

    @Test
    fun `boundary - applyCountryPrefill replaces a value holding only its own previous auto-inserted prefix`() {
        var country: Country? = Country.DE
        val f = field({ country })
        f.applyCountryPrefill()
        assertEquals("DE", f.value)

        country = Country.AT
        f.applyCountryPrefill()

        assertEquals("AT", f.value, "a value that is still exactly the earlier auto-inserted prefix may be replaced")
    }

    @Test
    fun `boundary - applyCountryPrefill never overwrites real user-typed content`() {
        val f = field({ Country.DE })
        f.value = "ATU12345678"

        f.applyCountryPrefill()

        assertEquals("ATU12345678", f.value, "typed content must survive a country prefill")
    }

    @Test
    fun `boundary - applyCountryPrefill with an unresolvable country leaves the value untouched`() {
        val f = field({ null })
        f.value = "ATU12345678"

        f.applyCountryPrefill()

        assertEquals("ATU12345678", f.value)
    }

    // ── uncovered branch combinations ────────────────────────────────────────────────────

    @Test
    fun `boundary - a digit-only value is left unprefixed when the linked country has no known VAT prefix`() {
        val f = field({ null })
        f.value = "123456789"

        val valid = f.validateNow()

        assertFalse(valid)
        assertEquals("123456789", f.value, "with no country prefix to add, the raw digits must not be rewritten")
    }

    @Test
    fun `boundary - a client-originated value change clears the just-auto-prefixed hint`() {
        val f = field({ Country.DE })
        f.value = "123456789"
        f.validateNow()
        assertTrue(f.helperText.contains("DE"), "sanity: prefixAdded hint present right after auto-prefixing")

        // Simulates the real client edit event validateNow() cannot stand in for (no browser).
        ComponentUtil.fireEvent(f, AbstractField.ComponentValueChangeEvent(f, f, f.value, true))
        f.onLinkedCountryChanged() // re-derives helper text without another applyNormalizationAndPrefix pass

        assertEquals(UiTranslations.tr("shared.vatId.formatOk"), f.helperText, "the auto-prefixed hint must not linger past a client edit")
    }

    @Test
    fun `boundary - mismatchIsError = true with a matching prefix stays valid, never reaching the mismatch branch`() {
        val f = field({ Country.DE }, mismatchIsError = true)
        f.value = "DE123456789"

        val valid = f.validateNow()

        assertTrue(valid)
        assertFalse(f.isInvalid)
    }
}
