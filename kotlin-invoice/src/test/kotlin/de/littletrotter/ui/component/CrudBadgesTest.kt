package de.littletrotter.ui.component

import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RDS-1 — [createDefaultBadge] and [createInlandBadge] are plain functions; no Vaadin session
 * (Karibu) needed to construct and inspect the returned components.
 */
class CrudBadgesTest {

    private val tr: (String) -> String = { key ->
        when (key) {
            "referenceData.defaultBadge" -> "Standard"
            "referenceData.inlandBadge" -> "Inland"
            else -> key
        }
    }

    @Test
    fun `createDefaultBadge true renders a STAR icon with title and aria-label from the translation`() {
        val badge = createDefaultBadge(true, tr)

        assertTrue(badge is Icon, "expected a Vaadin Icon component, got ${badge::class}")
        val icon = badge as Icon
        assertEquals("vaadin:star", icon.icon)
        assertEquals("Standard", icon.element.getAttribute("aria-label"))
        assertTrue(icon.element.getAttribute("title")?.isNotEmpty() == true, "title must be non-empty")
        assertEquals("Standard", icon.element.getAttribute("title"))
    }

    @Test
    fun `createDefaultBadge false renders no icon and no text, just an aria-hidden placeholder`() {
        val badge = createDefaultBadge(false, tr)

        assertFalse(badge is Icon, "false must not render any icon")
        assertEquals("true", badge.element.getAttribute("aria-hidden"))
        assertTrue((badge as? Span)?.text.isNullOrEmpty(), "false must render no text")
    }

    @Test
    fun `createInlandBadge true renders a HOME icon with aria-label Inland`() {
        val badge = createInlandBadge(true, tr)

        assertTrue(badge is Icon, "expected a Vaadin Icon component, got ${badge::class}")
        val icon = badge as Icon
        assertEquals("vaadin:home", icon.icon)
        assertEquals("Inland", icon.element.getAttribute("aria-label"))
        assertEquals("Inland", icon.element.getAttribute("title"))
    }

    @Test
    fun `createInlandBadge false renders the same hidden placeholder as the default badge`() {
        val badge = createInlandBadge(false, tr)

        assertFalse(badge is Icon)
        assertEquals("true", badge.element.getAttribute("aria-hidden"))
    }
}
