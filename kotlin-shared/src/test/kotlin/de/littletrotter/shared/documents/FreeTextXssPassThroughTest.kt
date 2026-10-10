package de.littletrotter.shared.documents

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hostile editor input must come out of the preview rendering as inert text: whatever the
 * browser sends is untrusted, and the stored markup is rendered back into a page.
 */
class FreeTextXssPassThroughTest {
    @Test
    fun `imported payloads render as inert preview html`() {
        PAYLOADS.forEach { payload ->
            val html = FreeTextMarkup.toHtml(FreeTextMarkup.fromHtml(payload))

            val body = Jsoup.parseBodyFragment(html).body()
            body.allElements.filter { it !== body }.forEach { element ->
                assertTrue(element.tagName() in ALLOWED_TAGS, "tag <${element.tagName()}> in $html")
                element.attributes().forEach { attribute ->
                    val name = attribute.key.lowercase()
                    assertFalse(name.startsWith("on") || name in FORBIDDEN_ATTRIBUTES, "attribute $name in $html")
                }
            }
            assertFalse(html.contains("<script", ignoreCase = true), html)
        }
    }

    @Test
    fun `re-importing the preview html is stable`() {
        PAYLOADS.forEach { payload ->
            val markup = FreeTextMarkup.fromHtml(payload)
            assertEquals(markup, FreeTextMarkup.fromHtml(FreeTextMarkup.toHtml(markup)), payload)
        }
    }

    companion object {
        private val ALLOWED_TAGS = setOf("p", "br", "strong", "b", "em", "i", "u", "s", "ul", "ol", "li", "div", "span", "mark")
        private val FORBIDDEN_ATTRIBUTES = setOf("href", "src", "style")

        private val PAYLOADS =
            listOf(
                "<script>alert(1)</script>",
                "<img src=x onerror=alert(1)>",
                "<svg onload=alert(1)>",
                "<a href=\"javascript:alert(1)\">x</a>",
                "<iframe src=//x>",
                "<style>*{}</style>",
                "&lt;script&gt;alert(1)&lt;/script&gt;",
                "\"><script>alert(1)</script>",
                "{{<b onclick=x>name</b>}}",
                "<p onclick=alert(1)>t</p>",
            )
    }
}
