package de.littletrotter.shared.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [InvoiceItemCategory.fromCode] is the throwing counterpart to
 * [InvoiceItemCategory.fromCodeOrNull] (exercised elsewhere via
 * [de.littletrotter.domain.shared.converters.InvoiceItemCategoryConverter]); this class covers
 * both lookup directions directly.
 */
class InvoiceItemCategoryTest {

    @Test
    fun `happy path - fromCode resolves every declared category by its persisted code`() {
        InvoiceItemCategory.entries.forEach { category ->
            assertEquals(category, InvoiceItemCategory.fromCode(category.code))
        }
    }

    @Test
    fun `boundary - fromCode throws for an unknown code, never silently substitutes a neighbour`() {
        assertThrows(NoSuchElementException::class.java) { InvoiceItemCategory.fromCode(999) }
    }

    @Test
    fun `boundary - fromCodeOrNull resolves every declared category by its persisted code`() {
        InvoiceItemCategory.entries.forEach { category ->
            assertEquals(category, InvoiceItemCategory.fromCodeOrNull(category.code))
        }
    }

    @Test
    fun `boundary - fromCodeOrNull returns null for an unknown code`() {
        assertNull(InvoiceItemCategory.fromCodeOrNull(999))
    }
}
