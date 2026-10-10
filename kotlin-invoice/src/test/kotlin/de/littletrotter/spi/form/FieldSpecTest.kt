package de.littletrotter.spi.form

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [XmlTarget]: `businessTerm` is optional (`null` &rarr; PDF/UI-only field with no XML export
 * slot, per the class KDoc), while `xpath` is always required.
 */
class FieldSpecTest {

    @Test
    fun `happy path - XmlTarget carries its business term and xpath`() {
        val target = XmlTarget(businessTerm = de.littletrotter.shared.b2b.B2bTerm.BT_11, xpath = "/Invoice/ProjectNumber")

        assertEquals(de.littletrotter.shared.b2b.B2bTerm.BT_11, target.businessTerm)
        assertEquals("/Invoice/ProjectNumber", target.xpath)
    }

    @Test
    fun `boundary - XmlTarget without a business term defaults to null, meaning no XML export slot`() {
        val target = XmlTarget(xpath = "/Invoice/Notes")

        assertNull(target.businessTerm)
        assertEquals("/Invoice/Notes", target.xpath)
    }
}
