package de.littletrotter.ui.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle
import kotlin.text.Regex

class InfoTranslationsTest {

    @Test
    fun `info keys exist in german bundle`() {
        val german = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)

        val infoKeys = listOf(
            "info.title",
            "info.version.heading",
            "info.version.value",
            "info.disclaimer.title",
            "info.disclaimer.subtitle",
            "info.disclaimer.intro",
            "info.disclaimer.item1",
            "info.disclaimer.item2",
            "info.disclaimer.item3",
            "info.disclaimer.item4",
        )

        infoKeys.forEach { key ->
            assertFalse(german.getString(key).isBlank(), "German translation missing for $key")
        }

        assertEquals("Hinweise", german.getString("info.title"))
    }

    @Test
    fun `german bundle contains no replacement character and umlauts are intact`() {
        val german = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)
        val brokenPrefixPattern = Regex("^\\?\\s")

        german.keySet().forEach { key ->
            assertFalse(german.getString(key).contains('\uFFFD'), "German bundle has replacement character in $key")
            assertFalse(brokenPrefixPattern.containsMatchIn(german.getString(key)), "German bundle has broken marker in $key")
        }

        assertEquals("Ausstellerprofile", german.getString("nav.senderProfiles"))
        assertEquals("Rechnungsempfänger", german.getString("recipient.title"))
        assertEquals("Fälligkeitsdatum", german.getString("invoiceDetail.dueDate"))
        // article.title is the ArticleView page title (renamed from artikel.title, arc-improvements
        // AR0); a stale, unreferenced duplicate under the same key with value "Artikel" was removed
        // when the messages_de.properties/messages.properties bundles were merged. It was then
        // made a {term:product.singular} marker too, same reason as nav.articles below.
        assertEquals("{term:product.singular}katalog", german.getString("article.title"))
        assertEquals("Leistungsverzeichnis", german.getString("nav.articles"))
    }
}
