package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.dom.Element
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.ui.i18n.UiTranslations
import java.util.Locale
import java.util.ResourceBundle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class QuoteStatusLegendKaribuTest {

    private val german = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)
    private val english = ResourceBundle.getBundle(
        "i18n.messages",
        Locale.ENGLISH,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    @Test
    fun `the header names the column and the legend in both languages`() {
        listOf(german, english).forEach { bundle ->
            val header = buildQuoteStatusColumnHeader({ key -> bundle.getString(key) }) {}
            assertTrue(header.element.getAttribute("title").isNotBlank(), bundle.locale.toString())
            assertTrue(header.element.getAttribute("aria-label").contains(bundle.getString("quote.status.legend.open")))
        }
    }

    @Test
    fun `both bundles explain every offer status and carry no retired one`() {
        QuoteStatus.known.forEach { status ->
            listOf(german, english).forEach { bundle ->
                assertTrue(bundle.containsKey("quote.status.legend.${status.name}.sentence"), "$status ${bundle.locale}")
                assertTrue(bundle.containsKey("quote.status.${status.name}"), "$status ${bundle.locale}")
            }
        }
        listOf(german, english).forEach { assertFalse(it.containsKey("quote.status.INVOICED"), it.locale.toString()) }
    }

    @Test
    fun `the legend shows one row per offer status with its chip word and sentence`() {
        openQuoteStatusLegend { key -> UiTranslations.tr(key) }

        val dialog = _get<Dialog>()
        assertEquals(UiTranslations.tr("quote.status.legend.title"), dialog.headerTitle)
        val texts = collectText(dialog.element)
        QuoteStatus.known.forEach { status ->
            assertTrue(texts.any { it == UiTranslations.tr("quote.status.${status.name}") }, "word of $status")
            assertTrue(texts.any { it == UiTranslations.tr("quote.status.legend.${status.name}.sentence") }, "sentence of $status")
        }
    }

    private fun collectText(element: Element): List<String> =
        listOf(element.text.orEmpty()) + element.children.toList().flatMap(::collectText)
}
