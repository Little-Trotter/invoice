package de.littletrotter.ui.components.freetext

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fireDomEvent
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.dom.DomEvent
import com.vaadin.flow.dom.Element
import de.littletrotter.shared.documents.FreeTextMarkup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

/**
 * [HtmlFreeTextEditor]: the server-side half of the editor wiring — pushing a value out as HTML,
 * reading a `html-changed` DOM event back through [FreeTextMarkup.fromHtml], the limit and
 * read-only plumbing, and [FreeTextChips] wired against [FreeTextValueEditor.insertAtCursor]. None
 * of this exercises the JS module itself (Karibu never runs a browser), so nothing here proves the
 * client-side debounce or the paste matcher actually work — only that the
 * server reacts correctly to whatever the client eventually reports.
 */
class HtmlFreeTextEditorKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    // A full client round trip flushes the attach listener's own setHtml push (mirroring pushHtml
    // on attach), so every test's own pendingCalls() count afterwards is its action's alone.
    private fun attachedEditor(limit: Int = FreeTextMarkup.MAX_LENGTH): HtmlFreeTextEditor =
        HtmlFreeTextEditor("Test", limit).also {
            UI.getCurrent().add(it)
            MockVaadin.clientRoundtrip()
        }

    private fun editorElement(editor: HtmlFreeTextEditor): Element {
        val field = HtmlFreeTextEditor::class.java.getDeclaredField("editorElement")
        field.isAccessible = true
        return field.get(editor) as Element
    }

    private fun fireHtmlChanged(
        editor: HtmlFreeTextEditor,
        html: String,
    ) {
        val data = JsonNodeFactory.instance.objectNode().put("event.detail.html", html)
        editorElement(editor)._fireDomEvent(DomEvent(editorElement(editor), "html-changed", data))
    }

    // Element.callJsFunction("name", args) compiles to the expression "return $0.name($1, …)"
    // with parameters = [theElement, arg1, …] — $0 is the element itself, never "this" and never
    // the function name; the call's own first argument is therefore parameters[1].
    private fun pendingCalls(functionName: String): List<List<Any?>> {
        val ui = UI.getCurrent()
        ui.internals.stateTree.runExecutionsBeforeClientResponse()
        return ui.internals.dumpPendingJavaScriptInvocations()
            .filter { it.invocation.expression.contains(".$functionName(") }
            .map { it.invocation.parameters }
    }

    // ── server → client ───────────────────────────────────────────────────

    @Test
    fun `setting value pushes the converted HTML to the client`() {
        val editor = attachedEditor()

        editor.value = "**bold** line"

        val calls = pendingCalls("setHtml")
        assertEquals(1, calls.size)
        assertEquals(EditorHtml.fromMarkup("**bold** line"), calls.single()[1])
    }

    @Test
    fun `appendLine pushes the merged value, never overwriting existing content`() {
        val editor = attachedEditor()
        editor.value = "first"

        editor.appendLine("second")

        assertEquals("first\nsecond", editor.value)
        assertEquals(EditorHtml.fromMarkup("first\nsecond"), pendingCalls("setHtml").last()[1])
    }

    @Test
    fun `insertAtCursor calls the client's own insertText, without changing the server value yet`() {
        val editor = attachedEditor()
        editor.value = "existing"

        editor.insertAtCursor("chip value")

        val calls = pendingCalls("insertText")
        assertEquals(1, calls.size)
        assertEquals("chip value", calls.single()[1])
        assertEquals("existing", editor.value)
    }

    @Test
    fun `a chip click reaches insertAtCursor and calls the client's insertText`() {
        val editor = attachedEditor()
        val chips = FreeTextChips(ChipSource { listOf(ChipCandidate("L", "Full", "chip text", "g")) }) { candidate ->
            editor.insertAtCursor(candidate.insertText)
        }
        UI.getCurrent().add(chips)
        chips.refresh()
        val button = chips.children.findFirst().orElseThrow()

        ComponentUtil.fireEvent(button, ClickEvent(button))

        val calls = pendingCalls("insertText")
        assertEquals(1, calls.size)
        assertEquals("chip text", calls.single()[1])
    }

    @Test
    fun `setReadOnly calls the client's own setReadOnly`() {
        val editor = attachedEditor()

        editor.setReadOnly(true)

        val calls = pendingCalls("setReadOnly")
        assertEquals(1, calls.size)
        assertEquals(true, calls.single()[1])
    }

    @Test
    fun `invalid with a message renders the alert and marks the host`() {
        val editor = attachedEditor()

        editor.errorMessage = "No emoji"
        editor.isInvalid = true

        val alert = errorSpan(editor)
        assertTrue(alert.isVisible)
        assertEquals("No emoji", alert.text)
        assertEquals("alert", alert.element.getAttribute("role"))
        assertTrue(editor.element.hasAttribute("invalid"))
        assertEquals("true", editorElement(editor).getAttribute("aria-invalid"))
        assertTrue(editor.isInvalid)
        assertEquals("No emoji", editor.errorMessage)
    }

    @Test
    fun `clearing invalid hides the alert and removes the marks`() {
        val editor = attachedEditor()
        editor.errorMessage = "No emoji"
        editor.isInvalid = true

        editor.isInvalid = false

        assertFalse(errorSpan(editor).isVisible)
        assertFalse(editor.element.hasAttribute("invalid"))
        assertFalse(editorElement(editor).hasAttribute("aria-invalid"))
    }

    @Test
    fun `a message set while valid stays hidden`() {
        val editor = attachedEditor()

        editor.errorMessage = "Not yet"

        assertFalse(errorSpan(editor).isVisible)
    }

    @Test
    fun `invalid without a message shows no alert text`() {
        val editor = attachedEditor()

        editor.isInvalid = true

        assertFalse(errorSpan(editor).isVisible)
        assertTrue(editor.element.hasAttribute("invalid"))
    }

    @Test
    fun `focus calls the client's own focus`() {
        val editor = attachedEditor()

        editor.focus()

        assertEquals(1, pendingCalls("focus").size)
    }

    @Test
    fun `label is readable and writable`() {
        val editor = attachedEditor()
        assertEquals("Test", editor.label)

        editor.label = "Freitext"

        assertEquals("Freitext", editor.label)
    }

    private fun errorSpan(editor: HtmlFreeTextEditor): Span =
        editor.children
            .filter { it.element.classList.contains("lt-html-freetext-editor__error") }
            .findFirst()
            .get() as Span

    @Test
    fun `setting the same value again neither pushes nor notifies`() {
        val editor = attachedEditor()
        editor.value = "same"
        pendingCalls("setHtml")
        val seen = mutableListOf<String>()
        editor.addValueChangeListener { seen += it }

        editor.value = "same"

        assertTrue(seen.isEmpty())
        assertTrue(pendingCalls("setHtml").isEmpty())
    }

    @Test
    fun `appendLine on an empty editor sets the text without a leading newline`() {
        val editor = attachedEditor()

        editor.appendLine("first")

        assertEquals("first", editor.value)
    }

    // ── client → server ───────────────────────────────────────────────────

    @Test
    fun `a plain html-changed event updates the value and fires the listener once`() {
        val editor = attachedEditor()
        val seen = mutableListOf<String>()
        editor.addValueChangeListener { seen += it }

        fireHtmlChanged(editor, "<p>hello</p>")

        assertEquals("hello", editor.value)
        assertEquals(listOf("hello"), seen)
    }

    @Test
    fun `removing the registration stops further notifications`() {
        val editor = attachedEditor()
        val seen = mutableListOf<String>()
        val registration = editor.addValueChangeListener { seen += it }

        registration.remove()
        fireHtmlChanged(editor, "<p>hello</p>")

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a pasted span, img, link and heading sanitise exactly like FreeTextMarkup fromHtml`() {
        val editor = attachedEditor()
        val pasted =
            "<h1>Title</h1><p>plain <span style=\"color:red\">styled</span> " +
                "<a href=\"http://x\">link</a><img src=\"y\"> end</p>"

        fireHtmlChanged(editor, pasted)

        assertEquals(FreeTextMarkup.fromHtml(pasted), editor.value)
    }

    @Test
    fun `no listener call when the html changes but the sanitised markup does not`() {
        val editor = attachedEditor()
        editor.value = "hello"
        val seen = mutableListOf<String>()
        editor.addValueChangeListener { seen += it }

        // Paste-normalisation shape: different HTML, same markup once sanitised.
        fireHtmlChanged(editor, "<p><span style=\"color:red\">hello</span></p>")

        assertTrue(seen.isEmpty())
        assertEquals("hello", editor.value)
    }

    @Test
    fun `overflow beyond the limit truncates the value, resyncs the client, and fires once`() {
        val editor = attachedEditor(limit = 5)
        val seen = mutableListOf<String>()
        editor.addValueChangeListener { seen += it }

        fireHtmlChanged(editor, "<p>abcdefgh</p>")

        assertEquals("abcde", editor.value)
        assertEquals(listOf("abcde"), seen)
        assertEquals(EditorHtml.fromMarkup("abcde"), pendingCalls("setHtml").last()[1])
    }

    @Test
    fun `no client resync when the incoming html already fits the limit`() {
        val editor = attachedEditor(limit = 20)

        fireHtmlChanged(editor, "<p>short</p>")

        assertEquals("short", editor.value)
        assertTrue(pendingCalls("setHtml").isEmpty())
    }

    @Test
    fun `read only round trips through the interface`() {
        val editor: FreeTextValueEditor = attachedEditor()

        editor.setReadOnly(true)

        assertFalse(pendingCalls("setReadOnly").isEmpty())
    }
}
