package de.littletrotter.ui.components.freetext

import com.vaadin.flow.component.HasValidation
import com.vaadin.flow.shared.Registration

/**
 * The role interface of a free-text editing surface, so a host (and a binder adapter) depends on
 * the contract instead of the concrete widget. `FreeTextMarkup` text is the only thing that ever
 * leaves an implementation.
 *
 * It carries its own validation state because a rejected text must be shown where it was typed:
 * a binder can only mark a field that holds the invalid flag and error message itself.
 */
interface FreeTextValueEditor : HasValidation {
    /** The current content in the one storage syntax. Setting it re-renders whichever surface
     *  this editor presents; reading it never triggers a conversion the surface hasn't already
     *  done on its own last change. */
    var value: String

    /** Appends [text] as its own new line, never overwriting existing content — a chip click
     *  appends just the chip's value. */
    fun appendLine(text: String)

    /**
     * Inserts [text] at the current caret position, which lives in the browser; the server value
     * follows once the client reports the change.
     */
    fun insertAtCursor(text: String)

    /** Notified with the new markup whenever [value] actually changes — by typing, paste, or a
     *  programmatic [appendLine]/[insertAtCursor] — but never merely because the underlying
     *  representation changed without the markup itself changing (e.g. paste normalisation on the
     *  HTML surface). */
    fun addValueChangeListener(listener: (String) -> Unit): Registration

    /** Switches the editing surface between interactive and read-only without discarding [value]. */
    fun setReadOnly(readOnly: Boolean)
}
