package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.UiTranslations

/**
 * Persistent, dismissible warning banner for the last failed print validation.
 * Shows a warning icon and message; hidden by default. The close button hides the banner
 * without removing it from the layout, so [show] can re-use it. The transient error toast
 * (UiNotifications) stays in place — the banner makes the failure visible beyond it, so a
 * missed toast no longer means "nothing happens" (outage analysis §4).
 */
class ValidationFailureBanner : Div() {

    private val messageSpan = Span().apply { addClassName("lt-flex-grow") }

    init {
        isVisible = false
        addClassNames("validation-failure-banner", "lt-warning-banner")

        val layout = HorizontalLayout().apply {
            isSpacing = false
            addClassName("lt-gap-050")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        }

        layout.add(VaadinIcon.WARNING.create().apply { addClassName("lt-flex-shrink-0") })
        layout.add(messageSpan)

        val closeButton = AppButtons.icon(ActionIcons.CANCEL, UiTranslations.tr("app.close")) { isVisible = false }.apply { addClassName("lt-flex-shrink-0") }
        layout.add(closeButton)

        add(layout)
    }

    /** Shows the banner with [message] and makes it visible. */
    fun show(message: String) {
        messageSpan.text = message
        isVisible = true
    }
}
