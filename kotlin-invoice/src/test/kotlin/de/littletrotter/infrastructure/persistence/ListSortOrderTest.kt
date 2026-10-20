package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListSortOrderTest {

    @Test
    fun `happy path - every allowlisted sender and receipt field builds an ORDER BY expression carrying the requested direction`() {
        for (direction in SortDirection.entries) {
            assertEquals("ORDER BY LOWER(s.name) ${direction.name} NULLS LAST, s.id ASC", ListSortOrder.senders("name", direction))
            assertEquals("ORDER BY LOWER(s.invoicePrefix) ${direction.name} NULLS LAST, s.id ASC", ListSortOrder.senders("invoicePrefix", direction))

            assertEquals("ORDER BY LOWER(r.fileName) ${direction.name} NULLS LAST, r.id ASC", ListSortOrder.receipts("fileName", direction))
            assertEquals("ORDER BY LOWER(r.description) ${direction.name} NULLS LAST, r.id ASC", ListSortOrder.receipts("description", direction))
            assertEquals("ORDER BY r.documentDate ${direction.name} NULLS LAST, r.id ASC", ListSortOrder.receipts("documentDate", direction))
            assertEquals("ORDER BY r.uploadedAt ${direction.name} NULLS LAST, r.id ASC", ListSortOrder.receipts("uploadedAt", direction))
            assertEquals("ORDER BY r.sizeBytes ${direction.name} NULLS LAST, r.id ASC", ListSortOrder.receipts("sizeBytes", direction))
        }
    }

    @Test
    fun `boundary - unknown sort fields cannot enter a query and restore the default order`() {
        val untrusted = "name DESC; DELETE FROM Receipt"
        for (direction in SortDirection.entries) {
            assertEquals(ListSortOrder.senders("", direction), ListSortOrder.senders(untrusted, direction))
            assertEquals(ListSortOrder.receipts("", direction), ListSortOrder.receipts(untrusted, direction))
            assertFalse(ListSortOrder.receipts(untrusted, direction).contains(untrusted))
        }
    }

    @Test
    fun `boundary - the unknown-field default for senders is the fixed isDefault-then-name order, ignoring direction`() {
        for (direction in SortDirection.entries) {
            assertEquals("ORDER BY s.isDefault DESC, s.name ASC, s.id ASC", ListSortOrder.senders("does-not-exist", direction))
        }
    }

    @Test
    fun `boundary - cityCountry builds the deterministic single-billing-address subquery expression`() {
        val expr = ListSortOrder.senders("cityCountry", SortDirection.ASC)
        assertTrue(expr.startsWith("ORDER BY LOWER((SELECT a.city FROM Address a WHERE a.senderProfile = s"))
        assertTrue(expr.contains("a.addressType = :sortBillingType"))
        assertTrue(expr.endsWith("ASC NULLS LAST, s.id ASC"))
    }
}
