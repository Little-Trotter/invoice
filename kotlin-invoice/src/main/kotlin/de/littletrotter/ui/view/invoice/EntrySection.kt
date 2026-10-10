package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout

internal fun entrySection(title: String, content: Component, headerAction: Component? = null): Div = Div().apply {
    setWidthFull()
    addClassNames("entry-section", "lt-border-box")
    add(headerAction?.let { entrySectionHeaderRow(title, it) } ?: entrySectionTitle(title))
    add(content)
}

private fun entrySectionTitle(title: String): Span = Span(title).apply { addClassName("bau-eyebrow") }

private fun entrySectionHeaderRow(title: String, action: Component): HorizontalLayout =
    HorizontalLayout(entrySectionTitle(title), action).apply {
        addClassName("entry-section__header")
        setWidthFull()
        justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
        alignItems = FlexComponent.Alignment.CENTER
    }
