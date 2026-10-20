package de.littletrotter.ui.components.freetext

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Html
import de.littletrotter.shared.documents.FreeTextMarkup

/** The read-only rendering of a free text: the same markup and CSS as the printed block. */
fun freeTextHtml(source: String): Component = Html(FreeTextMarkup.toHtml(source))
