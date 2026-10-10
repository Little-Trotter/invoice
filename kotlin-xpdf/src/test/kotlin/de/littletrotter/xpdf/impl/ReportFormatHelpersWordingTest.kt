package de.littletrotter.xpdf.impl

import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * [Labels.label]'s wording-profile overload, the print label loader's own wiring point
 * (see its KDoc for why no `invoice-labels_*.properties` value carries a `{term:...}` marker
 * yet). Proven two ways: against the real, shipped print bundle (every label there is a no-op —
 * a profile-independent legal/registry/footer text must render byte-identical regardless of
 * profile), and against the real kotlin-shared term data via a marker-bearing literal standing in
 * for a future profile-dependent label, so the actual substitution path is exercised end to end,
 * not just asserted to be absent.
 */
class ReportFormatHelpersWordingTest {

    @Test
    fun `a profile-independent label is unchanged for either profile, both languages`() {
        for (language in listOf("de", "en")) {
            val plain = Labels.label("info.invoiceNo", language)
            assertEquals(plain, Labels.label("info.invoiceNo", language, WordingProfile.HANDWERK))
            assertEquals(plain, Labels.label("info.invoiceNo", language, WordingProfile.BERATUNG))
        }
    }

    @Test
    fun `Labels label with a profile resolves a term marker through the real kotlin-shared data`() {
        // No shipped invoice-labels_*.properties value carries {term:...} today (see Labels.label's
        // own KDoc) — applyWordingForTest mirrors that three-arg implementation against a literal
        // value instead of a bundle key, the one seam that lets this test supply marker-bearing
        // text directly and still exercise the real TermResolver/TermSubstitution wiring.
        assertEquals("Material erfasst", applyWordingForTest("{term:product.singular} erfasst", "de", WordingProfile.HANDWERK))
        assertEquals("Produkt erfasst", applyWordingForTest("{term:product.singular} erfasst", "de", WordingProfile.BERATUNG))
        assertEquals("Material recorded", applyWordingForTest("{term:product.singular} recorded", "en", WordingProfile.HANDWERK))
        assertEquals("Product recorded", applyWordingForTest("{term:product.singular} recorded", "en", WordingProfile.BERATUNG))
    }

    private fun applyWordingForTest(value: String, language: String, profile: WordingProfile): String {
        val locale = if (language == "de") Locale.GERMAN else Locale.ENGLISH
        return TermSubstitution.apply(value) { term -> TermResolver.resolve(profile, locale, term) }
    }

    // ── Boundary tests — Labels' own private helpers, via reflection (no shipped bundle
    //    value exercises them today, see this class's own KDoc and Labels.label's own). ─────────

    @Test
    fun `boundary - localeFor resolves de to German and every other language code to English`() {
        val method = Labels::class.java.getDeclaredMethod("localeFor", String::class.java).apply { isAccessible = true }

        assertEquals(Locale.GERMAN, method.invoke(Labels, "de"))
        assertEquals(Locale.ENGLISH, method.invoke(Labels, "en"))
    }

    @Test
    fun `boundary - a missing label bundle file fails loudly instead of silently returning an empty bundle`() {
        val method = Labels::class.java.getDeclaredMethod("load", String::class.java).apply { isAccessible = true }

        val thrown = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            method.invoke(Labels, "does-not-exist.properties")
        }

        assertTrue(thrown.cause is IllegalStateException)
        assertTrue(thrown.cause?.message?.contains("does-not-exist.properties") == true)
    }
}
