package de.littletrotter.shared.wording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.Locale

/** Every [Term] must resolve for all three [WordingProfile]s and both bundle locales; a
 *  gap here would mean [TermSubstitution] silently degrades to a `!{term:...}!` placeholder at
 *  runtime instead of failing the build. */
class TermResolverTest {

    @Test
    fun `every term resolves for all three profiles in German`() {
        for (profile in WordingProfile.entries) {
            for (term in Term.entries) {
                val resolved = TermResolver.resolve(profile, Locale.GERMAN, term)
                assertNotNull(resolved, "$profile/$term (de)")
                assertFalse(resolved!!.isBlank(), "$profile/$term (de) is blank")
            }
        }
    }

    @Test
    fun `every term resolves for all three profiles in English`() {
        for (profile in WordingProfile.entries) {
            for (term in Term.entries) {
                val resolved = TermResolver.resolve(profile, Locale.ENGLISH, term)
                assertNotNull(resolved, "$profile/$term (en)")
                assertFalse(resolved!!.isBlank(), "$profile/$term (en) is blank")
            }
        }
    }

    @Test
    fun `HANDWERK calls the product noun Material, HANDEL calls it Artikel, BERATUNG calls it Produkt`() {
        assertEquals("Material", TermResolver.resolve(WordingProfile.HANDWERK, Locale.GERMAN, Term.PRODUCT_SINGULAR))
        assertEquals("Artikel", TermResolver.resolve(WordingProfile.HANDEL, Locale.GERMAN, Term.PRODUCT_SINGULAR))
        assertEquals("Produkt", TermResolver.resolve(WordingProfile.BERATUNG, Locale.GERMAN, Term.PRODUCT_SINGULAR))
    }

    @Test
    fun `border case 13 - the English interface shows Materials-Items-Products`() {
        assertEquals("Materials", TermResolver.resolve(WordingProfile.HANDWERK, Locale.ENGLISH, Term.PRODUCT_PLURAL))
        assertEquals("Items", TermResolver.resolve(WordingProfile.HANDEL, Locale.ENGLISH, Term.PRODUCT_PLURAL))
        assertEquals("Products", TermResolver.resolve(WordingProfile.BERATUNG, Locale.ENGLISH, Term.PRODUCT_PLURAL))
    }

    @Test
    fun `service wording follows the active profile`() {
        assertEquals("Arbeitsstunde", TermResolver.resolve(WordingProfile.HANDWERK, Locale.GERMAN, Term.SERVICE_SINGULAR))
        assertEquals("Dienstleistung", TermResolver.resolve(WordingProfile.HANDEL, Locale.GERMAN, Term.SERVICE_SINGULAR))
        assertEquals("Dienstleistung", TermResolver.resolve(WordingProfile.BERATUNG, Locale.GERMAN, Term.SERVICE_SINGULAR))
        assertEquals("Work hour", TermResolver.resolve(WordingProfile.HANDWERK, Locale.ENGLISH, Term.SERVICE_SINGULAR))
    }
    @Test
    fun `service category uses work hours only for the craft profile`() {
        for (profile in WordingProfile.entries) {
            assertEquals(
                if (profile == WordingProfile.HANDWERK) "Arbeitsstunden" else "Dienstleistung",
                TermResolver.resolve(profile, Locale.GERMAN, Term.SERVICE_CATEGORY),
            )
            assertEquals(
                if (profile == WordingProfile.HANDWERK) "Work hours" else "Service",
                TermResolver.resolve(profile, Locale.ENGLISH, Term.SERVICE_CATEGORY),
            )
        }
    }
}
