package de.littletrotter.ui.components.freetext

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fireDomEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.dom.DomEvent
import com.vaadin.flow.dom.Element
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

/**
 * What the editor reports after an edit and what the editor stores: edge whitespace around formatted
 * text and indented plain paragraphs must come back as valid markup, never as literal markers.
 */
class HtmlFreeTextEditorRoundTripKaribuTest {
    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun attachedEditor(): HtmlFreeTextEditor =
        HtmlFreeTextEditor("Test", 4000).also {
            UI.getCurrent().add(it)
            MockVaadin.clientRoundtrip()
        }

    private fun editorElement(editor: HtmlFreeTextEditor): Element {
        val field = HtmlFreeTextEditor::class.java.getDeclaredField("editorElement")
        field.isAccessible = true
        return field.get(editor) as Element
    }

    private fun report(
        editor: HtmlFreeTextEditor,
        html: String,
    ) {
        val data = JsonNodeFactory.instance.objectNode().put("event.detail.html", html)
        editorElement(editor)._fireDomEvent(DomEvent(editorElement(editor), "html-changed", data))
    }

    private fun pendingHtmlPushes(): List<Any?> {
        val ui = UI.getCurrent()
        ui.internals.stateTree.runExecutionsBeforeClientResponse()
        return ui.internals.dumpPendingJavaScriptInvocations()
            .filter { it.invocation.expression.contains(".setHtml(") }
            .map { it.invocation.parameters[1] }
    }

    @Test
    fun `bold text with a trailing space inside the tag is stored without literal asterisks`() {
        val editor = attachedEditor()
        report(editor, "<p><strong>Hallo </strong>Welt</p>")
        assertEquals("**Hallo** Welt", editor.value)
    }

    @Test
    fun `a formatted whitespace-only span stores an empty value`() {
        val editor = attachedEditor()
        report(editor, "<p><em> </em></p>")
        assertEquals("", editor.value)
    }

    @Test
    fun `a nested list is stored with four spaces per level`() {
        val editor = attachedEditor()
        report(editor, "<ul><li><p>a</p><ul><li><p>b</p></li></ul></li></ul>")
        assertEquals("- a\n    - b", editor.value)
    }

    @Test
    fun `a nested list in the value is pushed as nested lists`() {
        val editor = attachedEditor()
        editor.value = "- a\n    - b"
        assertTrue(
            pendingHtmlPushes().last().toString().contains("<ul><li><p>a</p><ul><li><p>b</p></li></ul></li></ul>"),
        )
    }
}
