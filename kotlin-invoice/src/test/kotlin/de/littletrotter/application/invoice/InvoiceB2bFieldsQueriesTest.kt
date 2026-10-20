package de.littletrotter.application.invoice

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit coverage for [InvoiceB2bFieldsQueries], the role interface
 *  [InvoiceLifecycleSupport] injects instead of the two underlying services directly. */
class InvoiceB2bFieldsQueriesTest {

    @Test
    fun `happy path - findFields delegates to the field-definition service`() {
        val fieldValueService = mock<B2bFieldValueService>()
        val fieldDefinitionService = mock<B2bProfileFieldDefinitionService>()
        val field = B2bProfileField().edit { id = 1L }
        whenever(fieldDefinitionService.findFields(5L)).thenReturn(listOf(field))
        val queries = InvoiceB2bFieldsQueries(fieldValueService, fieldDefinitionService)

        val result = queries.findFields(5L)

        assertEquals(listOf(field.toDto()), result)
    }

    @Test
    fun `happy path - saveFieldValues delegates to the field-value service`() {
        val fieldValueService = mock<B2bFieldValueService>()
        val fieldDefinitionService = mock<B2bProfileFieldDefinitionService>()
        val queries = InvoiceB2bFieldsQueries(fieldValueService, fieldDefinitionService)
        val values = mapOf("BT-12" to "VERTRAG-1")

        queries.saveFieldValues(B2bFieldOwnerType.INVOICE, 7L, values)

        verify(fieldValueService).saveFieldValues(B2bFieldOwnerType.INVOICE, 7L, values)
    }

    @Test
    fun `boundary - findFields for a profile with no fields returns an empty list, not null`() {
        val fieldValueService = mock<B2bFieldValueService>()
        val fieldDefinitionService = mock<B2bProfileFieldDefinitionService>()
        whenever(fieldDefinitionService.findFields(9L)).thenReturn(emptyList())
        val queries = InvoiceB2bFieldsQueries(fieldValueService, fieldDefinitionService)

        val result = queries.findFields(9L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `boundary - saveFieldValues with an empty value map still reaches the underlying service`() {
        val fieldValueService = mock<B2bFieldValueService>()
        val fieldDefinitionService = mock<B2bProfileFieldDefinitionService>()
        val queries = InvoiceB2bFieldsQueries(fieldValueService, fieldDefinitionService)

        queries.saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 3L, emptyMap())

        verify(fieldValueService).saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 3L, emptyMap())
    }

    @Test
    fun `boundary - saveFieldValues with a null value clears that field instead of dropping the key`() {
        val fieldValueService = mock<B2bFieldValueService>()
        val fieldDefinitionService = mock<B2bProfileFieldDefinitionService>()
        val queries = InvoiceB2bFieldsQueries(fieldValueService, fieldDefinitionService)
        val values = mapOf("BT-18" to null)

        queries.saveFieldValues(B2bFieldOwnerType.INVOICE, 4L, values)

        verify(fieldValueService).saveFieldValues(B2bFieldOwnerType.INVOICE, 4L, values)
    }
}
