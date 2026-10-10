package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout

/**
 * A row of page or panel actions, right-aligned by default so every page puts its actions in the
 * same place. [leading] components (a count, a hint) sit on the left; the actions stay on the right.
 * Spacing comes from the stylesheet so the theme controls it in one place.
 */
class ActionBar(
    leading: List<Component>,
    vararg components: Component,
) : HorizontalLayout() {

    constructor(vararg components: Component) : this(emptyList(), *components)

    init {
        addClassName("sp-action-bar")
        setWidthFull()
        justifyContentMode = FlexComponent.JustifyContentMode.END
        isSpacing = false
        if (leading.isNotEmpty()) {
            val group = HorizontalLayout(*leading.toTypedArray()).apply {
                isSpacing = false
                isPadding = false
                addClassName("sp-action-bar-leading")
            }
            add(group)
            expand(group)
        }
        add(*components)
    }
}
