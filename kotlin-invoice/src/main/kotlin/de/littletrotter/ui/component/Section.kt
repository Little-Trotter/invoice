package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span

class Section(label: String, barMod: String = "", content: Component) : Div() {
    init {
        setWidthFull()
        addClassNames("sp-section", "lt-border-box")
        val bar = Div().apply {
            addClassName("sp-section-bar")
            if (barMod.isNotBlank()) addClassName(barMod)
        }
        val title = Span(label).apply { addClassName("sp-section-title") }
        val header = Div().apply {
            addClassName("sp-section-header")
            add(bar, title)
        }
        add(header, content)
    }
}
