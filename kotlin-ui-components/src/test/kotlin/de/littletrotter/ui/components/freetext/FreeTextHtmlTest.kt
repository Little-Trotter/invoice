package de.littletrotter.ui.components.freetext

import com.vaadin.flow.component.Html
import de.littletrotter.shared.documents.FreeTextMarkup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * [freeTextHtml]: returns an Html component that renders the markup from [FreeTextMarkup.toHtml],
 * preserving the same structure (div wrapper, line divs, and inline formatting) as the print and
 * JSON renderings. The rendered HTML equals what [FreeTextMarkup.toHtml] produces.
 */
class FreeTextHtmlTest {

    @Test
    fun `plain text returns Html component with correct content`() {
        val source = "hello"
        val component = freeTextHtml(source)

        assertInstanceOf(Html::class.java, component)
        val expected = FreeTextMarkup.toHtml(source)
        val actual = component.element.getProperty("innerHTML").toString()
        // Html component extracts the inner content, so check for the line content
        assertEquals("<div class=\"lt-ft-line\">hello</div>", actual)
    }

    @Test
    fun `markup text with bold and italic formats correctly`() {
        val source = "**bold** and *italic*"
        val component = freeTextHtml(source)

        assertInstanceOf(Html::class.java, component)
        val actual = component.element.getProperty("innerHTML").toString()
        // Verify the rendered markup is present
        assertEquals("<div class=\"lt-ft-line\"><b>bold</b> and <i>italic</i></div>", actual)
    }

    @Test
    fun `underline markup renders correctly`() {
        val source = "++underlined++"
        val component = freeTextHtml(source)

        assertInstanceOf(Html::class.java, component)
        val actual = component.element.getProperty("innerHTML").toString()
        assertEquals("<div class=\"lt-ft-line\"><u>underlined</u></div>", actual)
    }

    @Test
    fun `empty string produces Html with empty content`() {
        val component = freeTextHtml("")

        assertInstanceOf(Html::class.java, component)
        // Empty input to toHtml returns "<div class=\"lt-ft\"></div>"
        // Html component renders this; the element's innerHTML is empty (null property)
        val actual = component.element.getProperty("innerHTML")
        assertEquals(null, actual)
    }
}
