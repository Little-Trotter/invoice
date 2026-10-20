package de.littletrotter.ui.view.invoice

import com.vaadin.flow.shared.Registration
import de.littletrotter.ui.components.freetext.FreeTextValueEditor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FreeTextEditorFieldTest {

    private class FakeEditor : FreeTextValueEditor {
        override var value: String = ""
            set(newValue) {
                field = newValue
                listeners.forEach { it(newValue) }
            }
        val listeners = mutableListOf<(String) -> Unit>()
        var lastReadOnly = false
        private var invalidFlag = false
        private var message: String? = null

        override fun setErrorMessage(errorMessage: String?) {
            message = errorMessage
        }

        override fun getErrorMessage(): String? = message

        override fun setInvalid(invalid: Boolean) {
            invalidFlag = invalid
        }

        override fun isInvalid(): Boolean = invalidFlag

        override fun appendLine(text: String) = Unit

        override fun insertAtCursor(text: String) = Unit

        override fun addValueChangeListener(listener: (String) -> Unit): Registration {
            listeners += listener
            return Registration { listeners.remove(listener) }
        }

        override fun setReadOnly(readOnly: Boolean) {
            lastReadOnly = readOnly
        }
    }

    @Test
    fun `value and empty value map onto the editor`() {
        val editor = FakeEditor()
        val field = FreeTextEditorField(editor)

        field.value = "a"
        assertEquals("a", field.value)
        assertEquals("", field.emptyValue)
    }

    @Test
    fun `listeners get old and new value and stop after removal`() {
        val editor = FakeEditor().apply { value = "x" }
        val field = FreeTextEditorField(editor)
        val seen = mutableListOf<Triple<String, String, Boolean>>()
        val sources = mutableListOf<Any?>()
        val registration = field.addValueChangeListener {
            seen += Triple(it.oldValue, it.value, it.isFromClient)
            sources += it.hasValue
        }

        editor.value = "y"
        editor.value = "z"
        registration.remove()
        editor.value = "w"

        assertEquals(listOf(Triple("x", "y", false), Triple("y", "z", false)), seen)
        assertEquals(listOf<Any?>(field, field), sources, "the event names the adapter, not the wrapped editor")
    }

    @Test
    fun `read-only reaches the editor and the required indicator is remembered`() {
        val editor = FakeEditor()
        val field = FreeTextEditorField(editor)

        field.isReadOnly = true
        field.isRequiredIndicatorVisible = true

        assertTrue(editor.lastReadOnly)
        assertTrue(field.isReadOnly)
        assertTrue(field.isRequiredIndicatorVisible)
        field.isReadOnly = false
        assertFalse(editor.lastReadOnly)
    }

    @Test
    fun `invalid state and error message round-trip through the adapter`() {
        val editor = FakeEditor()
        val field = FreeTextEditorField(editor)

        field.errorMessage = "bad"
        field.isInvalid = true

        assertTrue(editor.isInvalid)
        assertEquals("bad", editor.errorMessage)
        assertTrue(field.isInvalid)
        assertEquals("bad", field.errorMessage)
        field.isInvalid = false
        assertFalse(editor.isInvalid)
    }
}
