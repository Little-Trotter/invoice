package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [ReverseChargeField]: a client-side pick on the segmented control becomes the field
 * value, a cleared segment falls back to "no", and the visibility rule hides (and resets) the
 * field for a domestic pair, an unknown recipient country and a read-only invoice.
 */
class ReverseChargeFieldKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun field() = ReverseChargeField("Reverse Charge", "Ja", "Nein", "Hinweis")

    private fun Component.descendants(): List<Component> = children.toList().flatMap { listOf(it) + it.descendants() }

    private fun segments(field: ReverseChargeField): RadioButtonGroup<Boolean> =
        field.descendants().single { it.id.orElse(null) == REVERSE_CHARGE_SEGMENTS_ID }.requireAs()

    @Test
    fun `happy path - a client pick of Ja sets the field to true for a cross-border pair`() {
        val field = field()
        field.applyVisibility(Country.DE, Country.FR, readOnly = false)

        segments(field)._setValue(true, true)

        assertTrue(field.isVisible)
        assertTrue(field.value)
    }

    @Test
    fun `boundary - a client-cleared segment falls back to no`() {
        val field = field()
        segments(field)._setValue(true, true)

        segments(field)._setValue(null, true)

        assertFalse(field.value)
    }

    @Test
    fun `boundary - a domestic pair hides the field and resets a stale yes`() {
        val field = field()
        field.value = true

        field.applyVisibility(Country.DE, Country.DE, readOnly = false)

        assertFalse(field.isVisible)
        assertFalse(field.value)
        assertEquals(false, segments(field).value)
    }

    @Test
    fun `boundary - an unknown recipient country or a read-only invoice hides the field`() {
        val field = field()

        field.applyVisibility(Country.DE, null, readOnly = false)
        assertFalse(field.isVisible)

        field.applyVisibility(Country.DE, Country.FR, readOnly = true)
        assertFalse(field.isVisible)
    }

    @Test
    fun `boundary - the segment labels read Ja and Nein`() {
        val labels = segments(field()).itemLabelGenerator

        assertEquals("Ja", labels.apply(true))
        assertEquals("Nein", labels.apply(false))
    }
}
