package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InvoiceDetailTextFieldMapperTest {

    private fun tuple(overrides: Map<String, Any?> = emptyMap()): MapTuple {
        val base = mapOf(
            "freeText" to "Bitte überweisen",
            "invoiceNote" to "Anmerkung",
            "invoiceNoteOrigin" to "SELLER_DEFAULT",
            "consumerNote" to "Privatkundenhinweis",
            "consumerNoteOrigin" to "MANUAL",
            "buyerReference" to "LW-1",
            "purchaseOrderReference" to "PO-1",
            "orderReference" to "SO-1",
            "orderReferenceDate" to LocalDate.of(2026, 5, 20),
            "projectNumber" to "PROJ-77",
        )
        return MapTuple(base + overrides)
    }

    @Test
    fun `maps every free-text and reference column`() {
        val result = InvoiceDetailTextFieldMapper.map(tuple())

        assertEquals("Bitte überweisen", result.freeText)
        assertEquals("Anmerkung", result.invoiceNote)
        assertEquals("SELLER_DEFAULT", result.invoiceNoteOrigin)
        assertEquals("Privatkundenhinweis", result.consumerNote)
        assertEquals("MANUAL", result.consumerNoteOrigin)
        assertEquals("LW-1", result.buyerReference)
        assertEquals("PO-1", result.purchaseOrderReference)
        assertEquals("SO-1", result.orderReference)
        assertEquals(LocalDate.of(2026, 5, 20), result.orderReferenceDate)
        assertEquals("PROJ-77", result.projectNumber)
    }

    @Test
    fun `a null freeText, note and reference cell decodes to null`() {
        val result = InvoiceDetailTextFieldMapper.map(
            tuple(
                mapOf(
                    "freeText" to null, "invoiceNote" to null, "consumerNote" to null, "buyerReference" to null,
                    "purchaseOrderReference" to null, "orderReference" to null,
                    "orderReferenceDate" to null, "projectNumber" to null,
                ),
            ),
        )

        assertNull(result.freeText)
        assertNull(result.invoiceNote)
        assertNull(result.consumerNote)
        assertNull(result.buyerReference)
        assertNull(result.purchaseOrderReference)
        assertNull(result.orderReference)
        assertNull(result.orderReferenceDate)
        assertNull(result.projectNumber)
    }

    @Test
    fun `a non-String freeText and reference cell falls back to toString`() {
        val result = InvoiceDetailTextFieldMapper.map(tuple(mapOf("freeText" to 42L, "buyerReference" to 7L)))

        assertEquals("42", result.freeText)
        assertEquals("7", result.buyerReference)
    }
}
