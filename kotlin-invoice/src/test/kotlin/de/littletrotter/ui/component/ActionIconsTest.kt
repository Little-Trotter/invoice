package de.littletrotter.ui.component

import com.vaadin.flow.component.icon.VaadinIcon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ActionIconsTest {
    @Test
    fun `each action maps to its icon`() {
        assertEquals(VaadinIcon.PENCIL, ActionIcons.EDIT)
        assertEquals(VaadinIcon.CHECK, ActionIcons.CONFIRM)
        assertEquals(VaadinIcon.CLOSE_SMALL, ActionIcons.CANCEL)
        assertEquals(VaadinIcon.ARROW_LEFT, ActionIcons.BACK)
        assertEquals(VaadinIcon.REFRESH, ActionIcons.REFRESH)
        assertEquals(VaadinIcon.COPY, ActionIcons.COPY)
    }
}
