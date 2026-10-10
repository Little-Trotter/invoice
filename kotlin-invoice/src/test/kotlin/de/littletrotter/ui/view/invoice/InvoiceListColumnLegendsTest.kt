package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.dom.Element
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.i18n.UiTranslations
import java.util.Locale
import java.util.ResourceBundle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * PO follow-up (2026-09-17) — [buildStatusColumnHeader]/[buildValidationColumnHeader] and
 * their legend dialogs ([openStatusLegend]/[openValidationLegend]), generalised onto the shared
 * [de.littletrotter.ui.component.tileColumnHeader]/[de.littletrotter.ui.component.openLegendDialog]
 * mechanics. Both header/legend functions take a plain `tr: (String) -> String` lambda rather than
 * a [de.littletrotter.ui.i18n.TranslationProvider]-bound component, so both locales are checked
 * directly against the two properties bundles instead of switching a UI's locale — same idiom
 * [InvoiceUnitLabelsTest] already uses.
 */
class InvoiceListColumnLegendsTest {

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
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    // ── header: non-blank title/aria-label in both locales ──────────────────────────────────────

    @Test
    fun `status column header has non-blank title and aria-label in German and English`() {
        listOf(german, english).forEach { bundle ->
            val header = buildStatusColumnHeader({ key -> bundle.getString(key) }) {}
            assertTrue(header.element.getAttribute("title").isNotBlank(), bundle.locale.toString())
            assertTrue(header.element.getAttribute("aria-label").isNotBlank(), bundle.locale.toString())
        }
    }

    @Test
    fun `validation column header has non-blank title and aria-label in German and English`() {
        listOf(german, english).forEach { bundle ->
            val header = buildValidationColumnHeader({ key -> bundle.getString(key) }) {}
            assertTrue(header.element.getAttribute("title").isNotBlank(), bundle.locale.toString())
            assertTrue(header.element.getAttribute("aria-label").isNotBlank(), bundle.locale.toString())
        }
    }

    // ── header icon distinct from every cell glyph of its own column ───────────────────────────

    @Test
    fun `status header icon is never one of the InvoiceStatus cell glyphs`() {
        val cellIcons = InvoiceStatus.entries.map { invoiceStatusGlyphIcon(it) }
        assertFalse(VaadinIcon.FLAG in cellIcons, "the header symbol must not collide with a cell tile's own glyph: $cellIcons")
    }

    // ── status legend — parameterised over InvoiceStatus.entries ───────────────────────────────

    @Test
    fun `status legend opens a dialog with one row per InvoiceStatus and a resolved sentence`() {
        openStatusLegend { key -> UiTranslations.tr(key) }

        val dialog = _get<Dialog>()
        assertEquals(UiTranslations.tr("invoice.status.legend.title"), dialog.headerTitle)

        val renderedTexts = collectText(dialog.element)
        InvoiceStatus.entries.forEach { status ->
            val sentence = UiTranslations.tr("invoice.status.legend.${status.cssClass}.sentence")
            assertFalse(
                sentence.startsWith("!{"),
                "missing legend sentence for $status: add invoice.status.legend.${status.cssClass}.sentence to both bundles",
            )
            assertTrue(renderedTexts.any { it == sentence }, "legend is missing the sentence for $status: $sentence")
            val label = UiTranslations.tr(status.labelKey)
            assertTrue(renderedTexts.any { it == label }, "legend is missing the tile label for $status: $label")
        }
    }

    // ── validation legend — still works after generalisation onto LegendDialog ─────────────────

    @Test
    fun `validation legend opens a dialog with one row per ValidationStatus and a resolved sentence`() {
        openValidationLegend { key -> UiTranslations.tr(key) }

        val dialog = _get<Dialog>()
        assertEquals(UiTranslations.tr("invoice.validation.legend.title"), dialog.headerTitle)

        val sentenceKeys = listOf("notValidated", "pending", "valid", "invalid", "unavailable")
        assertEquals(ValidationStatus.entries.size, sentenceKeys.size, "keep this list in sync with ValidationStatus.entries")
        val renderedTexts = collectText(dialog.element)
        sentenceKeys.forEach { key ->
            val sentence = UiTranslations.tr("invoice.validation.legend.$key.sentence")
            assertTrue(renderedTexts.any { it == sentence }, "legend is missing the sentence for '$key': $sentence")
        }
    }

    private fun collectText(element: Element): List<String> {
        val texts = mutableListOf<String>()
        fun walk(el: Element) {
            texts += el.text ?: ""
            el.children.forEach { walk(it) }
        }
        walk(element)
        return texts
    }
}
