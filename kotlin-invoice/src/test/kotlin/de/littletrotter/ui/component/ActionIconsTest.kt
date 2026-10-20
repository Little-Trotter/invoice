package de.littletrotter.ui.component

import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.ui.view.system.ICON_GLOSSARY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionIconsTest {
    /** Actions that deliberately share a glyph because they are the same thing seen from two sides. */
    private val intendedAliases = setOf(
        setOf("PDF", "INVOICE"),
        setOf("SEND_MAIL", "TEST_MAIL"),
    )

    private val iconFields = ActionIcons::class.java.declaredFields
        .filter { it.type == VaadinIcon::class.java }
        .onEach { it.isAccessible = true }

    @Test
    fun `each action maps to its icon`() {
        assertEquals(VaadinIcon.PENCIL, ActionIcons.EDIT)
        assertEquals(VaadinIcon.CHECK, ActionIcons.CONFIRM)
        assertEquals(VaadinIcon.CLOSE_SMALL, ActionIcons.CANCEL)
        assertEquals(VaadinIcon.ARROW_LEFT, ActionIcons.BACK)
        assertEquals(VaadinIcon.REFRESH, ActionIcons.REFRESH)
        assertEquals(VaadinIcon.COPY, ActionIcons.COPY)
    }

    @Test
    fun `every action icon is listed in the style guide glossary`() {
        val listed = ICON_GLOSSARY.map { it.first.name }
        assertTrue(iconFields.isNotEmpty())
        assertEquals(iconFields.map { it.name }.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size, "glossary lists an icon twice")
    }

    @Test
    fun `actions share a glyph only when declared as alias`() {
        val groups = iconFields.groupBy({ it.get(ActionIcons) as VaadinIcon }, { it.name })
            .values.filter { it.size > 1 }.map { it.toSet() }
        assertEquals(intendedAliases, groups.toSet())
    }
}
