package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout

object SystemUiComponents {
    fun statusDot(up: Boolean): Div = Div().apply {
        addClassNames("system-status-dot", "lt-status-dot", if (up) "lt-status-dot--up" else "lt-status-dot--down")
    }

    /** Uppercase secondary-text caption — the one look every system-view metric label shares
     *  ([infoCard]'s title, NatsPanel's metric-card labels). */
    fun captionSpan(text: String): Span = Span(text).apply { addClassName("lt-caption") }

    fun infoCard(title: String, value: String, vararg extra: Component): Div {
        val titleSpan = captionSpan(title)
        val valueSpan = Span(value).apply {
            addClassName("lt-sys-info-value")
            if (value.isNumberLike()) {
                addClassName("text-right")
            }
        }

        return Div().apply {
            addClassName("system-info-card")
            addClassName("lt-sys-card")
            add(titleSpan, valueSpan)
            if (extra.isNotEmpty()) add(*extra)
        }
    }

    private fun String.isNumberLike(): Boolean =
        trim().matches(Regex("""^-?[\d.,\s€%+]+$"""))

    fun sectionHeader(text: String): H3 = H3(text).apply {
        addClassName("system-section-header")
        addClassName("lt-sys-section-header")
    }

    /** [name]/[description]/[detail] bundled so [serviceCard] takes one parameter for them
     *  instead of three (LongParameterList). */
    data class ServiceCardContent(val name: String, val description: String, val detail: String)

    /** [label]/[up] bundled for the same reason as [ServiceCardContent] — the card's own status
     *  text and dot colour, always resolved together from one dependency's up/down state. */
    data class ServiceCardStatus(val label: String, val up: Boolean)

    fun serviceCard(content: ServiceCardContent, status: ServiceCardStatus): Div {
        val (name, description, detail) = content
        val (statusLabel, up) = status
        val header = serviceCardHeader(name, statusLabel, up)
        val descriptionSpan = serviceCardDescription(description)
        val detailSpan = serviceCardDetail(detail)

        val content = VerticalLayout(header, descriptionSpan, detailSpan).apply {
            setWidthFull()
            isPadding = false
            isSpacing = false
            addClassNames("system-service-card-content", "lt-sys-service-content")
        }

        return Div(content).apply {
            addClassName("system-service-card")
            addClassName("lt-sys-service")
            // No fixed width: .system-service-card sizes itself responsively (width: 100% inside
            // minmax(0, 1fr) grid columns) so cards never outgrow the container.
        }
    }

    /** [serviceCard]'s status/name/status-label row, extracted to keep that method's own
     *  LongMethod down. */
    private fun serviceCardHeader(name: String, statusLabel: String, up: Boolean): HorizontalLayout {
        val statusIndicator = statusDot(up).apply { addClassName("lt-status-dot--small") }

        val nameSpan = Span(name).apply {
            addClassName("lt-sys-service-name")
            // Already ellipsized by the stylesheet; carry the full name as a tooltip like
            // detailSpan below does, so a long service name stays reachable when clipped.
            element.setAttribute("title", name)
        }

        val statusText = Span(statusLabel).apply {
            addClassNames("lt-sys-service-status", if (up) "lt-text-ok" else "lt-text-error")
        }

        return HorizontalLayout(statusIndicator, nameSpan, statusText).apply {
            setWidthFull()
            alignItems = FlexComponent.Alignment.CENTER
            isPadding = false
            isSpacing = false
        }
    }

    private fun serviceCardDescription(description: String): Span =
        Span(description).apply { addClassName("lt-sys-service-description") }

    private fun serviceCardDetail(detail: String): Span = Span(detail).apply {
        addClassName("lt-sys-service-detail")
        element.setAttribute("title", detail)
    }
}
