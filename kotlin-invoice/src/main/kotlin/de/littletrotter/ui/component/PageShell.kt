package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span

/**
 * The frame every page sits in: title (with optional subtitle) on the left and the page actions on
 * the right of one line, then an optional filter row, then the content. Layout lives in the
 * stylesheet so spacing and widths change in one place. The shell uses the full width; form and
 * detail pages call [narrow] to keep one readable content width.
 */
class PageShell(title: String, subtitle: String? = null) : Div() {
    // The bar is full width elsewhere; next to the title that inline width would push it past the
    // right edge, so the header lets it size to its buttons.
    val actions = ActionBar().apply { width = null }
    val filters = Div().apply {
        addClassName("lt-page-shell__filters")
        isVisible = false
    }
    val content = Div().apply { addClassName("lt-page-shell__content") }

    init {
        addClassName("lt-page-shell")
        val heading = Div(H2(title).apply { addClassName("lt-page-shell__title") }).apply {
            addClassName("lt-page-shell__heading")
            if (subtitle != null) add(Span(subtitle).apply { addClassName("lt-page-shell__subtitle") })
        }
        val header = Div(heading, actions).apply { addClassName("lt-page-shell__header") }
        add(header, filters, content)
    }

    /** Limits the page to the shared content width; meant for forms and detail pages. */
    fun narrow(): PageShell {
        addClassName("lt-page-shell--narrow")
        return this
    }

    /** Puts [components] into the filter row and shows it; the row stays hidden while unused. */
    fun filters(vararg components: Component): PageShell {
        filters.add(*components)
        filters.isVisible = true
        return this
    }

    fun content(vararg components: Component): PageShell {
        content.add(*components)
        return this
    }

    fun actions(vararg components: Component): PageShell {
        actions.add(*components)
        return this
    }
}
