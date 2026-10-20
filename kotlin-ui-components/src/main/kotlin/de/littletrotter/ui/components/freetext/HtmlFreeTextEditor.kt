package de.littletrotter.ui.components.freetext

import com.vaadin.flow.component.Focusable
import com.vaadin.flow.component.HasLabel
import com.vaadin.flow.component.HasValidation
import com.vaadin.flow.component.dependency.JsModule
import com.vaadin.flow.component.dependency.NpmPackage
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.dom.Element
import com.vaadin.flow.shared.Registration
import de.littletrotter.shared.documents.FreeTextMarkup
import java.util.UUID

private const val TAG_NAME = "lt-html-free-text-editor"
private const val EVENT_HTML_CHANGED = "html-changed"
private const val EVENT_DETAIL_HTML = "event.detail.html"

/**
 * The Tiptap-based WYSIWYG free-text editor, shared by the mail bodies and the invoice free text:
 * [FreeTextMarkup] text is the only thing that ever leaves the widget, through the
 * [FreeTextValueEditor] contract.
 * Every layout decision stays server-side in [EditorHtml]/[FreeTextMarkup]; the browser only ever
 * receives HTML this class already rendered from markup, and only ever reports HTML back through
 * [FreeTextMarkup.fromHtml] — the same allowlist a pasted document goes through — so the client is
 * never trusted with the storage format on its own, no matter what a paste or a crafted DOM event
 * carries.
 */
@NpmPackage(value = "@tiptap/core", version = "3.31.4")
@NpmPackage(value = "@tiptap/pm", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-document", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-paragraph", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-text", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-hard-break", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-bold", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-italic", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-underline", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-strike", version = "3.31.4")
@NpmPackage(value = "@tiptap/extension-list", version = "3.31.4")
@NpmPackage(value = "@tiptap/extensions", version = "3.31.4")
@JsModule("./lt-free-text/html-free-text-editor.js")
class HtmlFreeTextEditor(labelText: String, private val limit: Int) :
    Div(),
    FreeTextValueEditor,
    HasValidation,
    HasLabel,
    Focusable<HtmlFreeTextEditor> {

    private val editorElement = Element(TAG_NAME)
    private val counter = Span().apply { addClassName("lt-html-freetext-editor__counter") }
    private val labelSpan =
        Span(labelText).apply {
            addClassName("lt-html-freetext-editor__label")
            setId("lt-ft-label-${UUID.randomUUID()}")
            element.setAttribute("aria-hidden", "true")
        }

    // Hidden until the editor is invalid with a message; role=alert so assistive technology
    // announces the rejection reason the moment it appears.
    private val errorSpan =
        Span().apply {
            addClassName("lt-html-freetext-editor__error")
            element.setAttribute("role", "alert")
            isVisible = false
        }
    private var invalidState = false
    private var errorText = ""
    private val listeners = mutableListOf<(String) -> Unit>()
    private var currentValue = ""

    override var value: String
        get() = currentValue
        set(newValue) {
            val truncated = FreeTextMarkup.truncate(newValue, limit)
            if (truncated == currentValue) return
            currentValue = truncated
            pushHtml(truncated)
            updateCounter()
            notifyListeners(truncated)
        }

    init {
        addClassName("lt-html-freetext-editor")
        editorElement.setAttribute("aria-label", FreeTextTexts.tr("freeText.toolbar.aria"))
        editorElement.setProperty("boldLabel", FreeTextTexts.tr("freeText.toolbar.bold"))
        editorElement.setProperty("italicLabel", FreeTextTexts.tr("freeText.toolbar.italic"))
        editorElement.setProperty("underlineLabel", FreeTextTexts.tr("freeText.toolbar.underline"))
        editorElement.setProperty("strikeLabel", FreeTextTexts.tr("freeText.toolbar.strike"))
        editorElement.setProperty("bulletLabel", FreeTextTexts.tr("freeText.toolbar.bullet"))
        editorElement.setProperty("numberedLabel", FreeTextTexts.tr("freeText.toolbar.numbered"))
        editorElement.setAttribute("aria-labelledby", labelSpan.id.orElse(""))
        editorElement.addEventListener(EVENT_HTML_CHANGED) { event ->
            onHtmlChanged(event.eventData.get(EVENT_DETAIL_HTML).asString())
        }.addEventData(EVENT_DETAIL_HTML)

        val helper = Span(FreeTextTexts.tr("freeText.html.helper")).apply { addClassName("lt-html-freetext-editor__helper") }
        val card =
            Div().apply {
                addClassName("lt-html-freetext-editor__card")
                element.appendChild(editorElement)
            }
        add(labelSpan, card, errorSpan, Div(helper, counter).apply { addClassName("lt-html-freetext-editor__foot") })
        updateCounter()
        // The client-side custom element is re-created whenever this component reattaches (e.g. a
        // navigated-away-and-back view), so its content is pushed again on every attach.
        addAttachListener { pushHtml(currentValue) }
    }

    override fun appendLine(text: String) {
        value = if (currentValue.isEmpty()) text else "$currentValue\n$text"
    }

    /** Inserts [text] at the browser's own caret — the one place the selection lives once the
     *  editing surface is a real rich-text area rather than a plain textarea round-tripping every
     *  keystroke through the server. */
    override fun insertAtCursor(text: String) {
        editorElement.callJsFunction("insertText", text)
    }

    override fun addValueChangeListener(listener: (String) -> Unit): Registration {
        listeners += listener
        return Registration { listeners.remove(listener) }
    }

    override fun getLabel(): String = labelSpan.text

    override fun setLabel(label: String?) {
        labelSpan.text = label.orEmpty()
    }

    override fun setErrorMessage(errorMessage: String?) {
        errorText = errorMessage.orEmpty()
        refreshError()
    }

    override fun getErrorMessage(): String = errorText

    override fun setInvalid(invalid: Boolean) {
        invalidState = invalid
        if (invalid) {
            element.setAttribute("invalid", "")
            editorElement.setAttribute("aria-invalid", "true")
        } else {
            element.removeAttribute("invalid")
            editorElement.removeAttribute("aria-invalid")
        }
        refreshError()
    }

    override fun isInvalid(): Boolean = invalidState

    /** Moves the caret into the editing surface, which only the browser can do. */
    override fun focus() {
        editorElement.callJsFunction("focus")
    }

    private fun refreshError() {
        errorSpan.text = errorText
        errorSpan.isVisible = invalidState && errorText.isNotBlank()
    }

    override fun setReadOnly(readOnly: Boolean) {
        editorElement.callJsFunction("setReadOnly", readOnly)
    }

    private fun onHtmlChanged(html: String) {
        val converted = FreeTextMarkup.fromHtml(html)
        val truncated = FreeTextMarkup.truncate(converted, limit)
        if (truncated == currentValue) return
        currentValue = truncated
        updateCounter()
        // Only resync the client when it actually typed past the limit — every other change
        // already matches what the browser holds, and pushing setHtml back on every keystroke
        // would fight the browser's own caret for no reason.
        if (truncated != converted) pushHtml(truncated)
        notifyListeners(truncated)
    }

    private fun pushHtml(markup: String) {
        editorElement.callJsFunction("setHtml", EditorHtml.fromMarkup(markup))
    }

    private fun updateCounter() {
        counter.text = "${currentValue.length} / $limit"
    }

    private fun notifyListeners(markup: String) {
        listeners.toList().forEach { it(markup) }
    }
}
