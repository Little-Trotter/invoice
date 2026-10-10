package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.HasValidation
import com.vaadin.flow.component.HasValue
import com.vaadin.flow.shared.Registration
import de.littletrotter.ui.components.freetext.FreeTextValueEditor

/**
 * Lets the header binder treat a free-text editor like any other field: the editor is not a Vaadin
 * field component, but it already reports every markup change, which is all the binder's dirty
 * tracking and read/write round trip need. Validation state is delegated to the editor so a
 * rejected text is marked on the editor itself, where the user typed it.
 */
internal class FreeTextEditorField(val editor: FreeTextValueEditor) :
    HasValue<HasValue.ValueChangeEvent<String>, String>,
    HasValidation {

    private var readOnly = false
    private var requiredIndicator = false

    override fun setValue(value: String) {
        editor.value = value
    }

    override fun getValue(): String = editor.value

    override fun getEmptyValue(): String = ""

    override fun addValueChangeListener(
        listener: HasValue.ValueChangeListener<in HasValue.ValueChangeEvent<String>>,
    ): Registration {
        var previous = editor.value
        return editor.addValueChangeListener { current ->
            val event = Change(this, previous, current)
            previous = current
            listener.valueChanged(event)
        }
    }

    override fun setReadOnly(readOnly: Boolean) {
        this.readOnly = readOnly
        editor.setReadOnly(readOnly)
    }

    override fun isReadOnly(): Boolean = readOnly

    override fun setRequiredIndicatorVisible(requiredIndicatorVisible: Boolean) {
        requiredIndicator = requiredIndicatorVisible
    }

    override fun isRequiredIndicatorVisible(): Boolean = requiredIndicator

    override fun setErrorMessage(errorMessage: String?) {
        editor.errorMessage = errorMessage
    }

    override fun getErrorMessage(): String? = editor.errorMessage

    override fun setInvalid(invalid: Boolean) {
        editor.isInvalid = invalid
    }

    override fun isInvalid(): Boolean = editor.isInvalid

    private class Change(
        private val source: HasValue<*, String>,
        private val old: String,
        private val new: String,
    ) : HasValue.ValueChangeEvent<String> {
        override fun getHasValue(): HasValue<*, String> = source

        override fun isFromClient(): Boolean = false

        override fun getOldValue(): String = old

        override fun getValue(): String = new
    }
}
