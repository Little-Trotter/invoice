package de.littletrotter.ui.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle

/**
 * Pins the reason `messages_en.properties` could be deleted along with the no-fallback control
 * both bundle loaders used to carry.
 *
 * That control existed for one concrete failure: on a JVM whose *default* locale is English — our
 * containers — `getBundle("i18n.messages", GERMAN)` finds no `messages_de.properties`, and the
 * default control then tries the default locale before the base bundle. While an English bundle
 * existed, that resolved to it and the frontend showed English for exactly the keys it carried.
 *
 * With no `messages_en.properties` in the module there is nothing for that step to find, so the
 * German base bundle wins on its own. This test forces the English default locale and proves it,
 * so that re-adding an English bundle without also restoring locale handling fails here instead of
 * silently switching parts of a German-only UI to English.
 */
class GermanBundleResolutionTest {

    @Test
    fun `a GERMAN request resolves to the German base bundle even under an English default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            ResourceBundle.clearCache(GermanBundleResolutionTest::class.java.classLoader)

            val bundle = ResourceBundle.getBundle("i18n.messages", TranslationService.GERMAN)

            assertEquals(
                "Alle Status",
                bundle.getString("invoice.filter.status.all"),
                "the German value must win; an English resolution means a locale-specific bundle reappeared",
            )
        } finally {
            Locale.setDefault(previous)
            ResourceBundle.clearCache(GermanBundleResolutionTest::class.java.classLoader)
        }
    }
}
