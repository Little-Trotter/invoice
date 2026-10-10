package de.littletrotter.ui.view.settings

import com.vaadin.flow.component.html.Span
import de.littletrotter.ui.i18n.UiTranslations

/** Paints a settings card's status chip: label and colour class always change together. */
internal fun Span.showSwitchState(on: Boolean) {
    text = UiTranslations.tr(this, if (on) "settings.state.aktiv" else "settings.state.inaktiv")
    element.classList.remove("settings-chip-aktiv")
    element.classList.remove("settings-chip-inaktiv")
    addClassName(if (on) "settings-chip-aktiv" else "settings-chip-inaktiv")
}
