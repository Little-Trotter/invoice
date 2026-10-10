package de.littletrotter.ui.component

import com.vaadin.flow.component.html.Span
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverviewFieldsTest {

    @Test
    fun `addressLines joins postal code, city and country on the second row`() {
        assertEquals(listOf("Hauptstr. 1", "10115 Berlin, DE"), addressLines("Hauptstr. 1", "10115", "Berlin", "DE"))
    }

    @Test
    fun `addressLines drops blank parts`() {
        assertEquals(listOf("Berlin"), addressLines(" ", null, "Berlin", ""))
        assertEquals(listOf("Hauptstr. 1", "DE"), addressLines("Hauptstr. 1", null, null, "DE"))
        assertEquals(emptyList<String>(), addressLines(null, " ", "", null))
    }

    @Test
    fun `overviewField is null for a null or blank value`() {
        assertNull(overviewField("Label", null))
        assertNull(overviewField("Label", "  "))
    }

    @Test
    fun `overviewField renders label and value and marks a wide field`() {
        val field = overviewField("Label", "Value", wide = true)
        assertNotNull(field)
        assertTrue(field!!.hasClassName("cockpit-field"))
        assertTrue(field.hasClassName("overview-field--wide"))
        val texts = field.children.toList().filterIsInstance<Span>().map { it.text }
        assertEquals(listOf("Label", "Value"), texts)
        assertTrue(!overviewField("Label", "Value")!!.hasClassName("overview-field--wide"))
    }

    @Test
    fun `overviewAddress is null when every line is blank`() {
        assertNull(overviewAddress(emptyList()))
        assertNull(overviewAddress(listOf("", "  ")))
    }

    @Test
    fun `overviewAddress renders one row per non-blank line`() {
        val address = overviewAddress(listOf("Hauptstr. 1", "", "10115 Berlin"))
        assertNotNull(address)
        assertTrue(address!!.hasClassName("overview-field__address"))
        assertEquals(2, address.children.count())
    }
}
