package de.littletrotter.application.masterdata

import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** [RecipientB2bFieldsQueries] only delegates; each method must reach the facade that owns the data. */
class RecipientB2bFieldsQueriesTest {

    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val port = RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue)

    @Test
    fun `findAllActiveB2bProfiles delegates to the profile facade`() {
        val profile = B2bProfileDto(id = 1L)
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile))

        assertEquals(listOf(profile), port.findAllActiveB2bProfiles())
    }

    @Test
    fun `findB2bProfileById delegates to the profile facade`() {
        val profile = B2bProfileDto(id = 2L)
        whenever(b2bProfileQueries.findB2bProfileById(2L)).thenReturn(profile)

        assertEquals(profile, port.findB2bProfileById(2L))
        assertNull(port.findB2bProfileById(3L))
    }

    @Test
    fun `findB2bRecipientFields delegates to the field-definition facade`() {
        val field = B2bProfileFieldDto(id = 4L)
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(7L)).thenReturn(listOf(field))

        assertEquals(listOf(field), port.findB2bRecipientFields(7L))
    }

    @Test
    fun `findB2bRecipientFieldValues delegates to the field-value facade`() {
        whenever(b2bFieldValueQueries.findB2bRecipientFieldValues(5L, 7L)).thenReturn(mapOf("BT-10" to "REF"))

        assertEquals(mapOf("BT-10" to "REF"), port.findB2bRecipientFieldValues(5L, 7L))
    }

    @Test
    fun `saveB2bRecipientFieldValues delegates to the field-value facade`() {
        port.saveB2bRecipientFieldValues(5L, 7L, mapOf("BT-10" to "REF"))

        verify(manageB2bFieldValue).saveB2bRecipientFieldValues(5L, 7L, mapOf("BT-10" to "REF"))
    }
}
