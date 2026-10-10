package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.masterdata.port.inbound.RecipientB2bFieldsQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [RecipientB2bFieldsQueryPort] on top of the B2B profile, field-definition and
 *  field-value facades, whose permission checks stay in force for every call. */
@ApplicationScoped
class RecipientB2bFieldsQueries @Inject constructor(
    private val b2bProfileQueries: B2bProfileQueryPort,
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val b2bFieldValueQueries: B2bFieldValueQueryPort,
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase,
) : RecipientB2bFieldsQueryPort {

    override fun findAllActiveB2bProfiles(): List<B2bProfileDto> = b2bProfileQueries.findAllActiveB2bProfiles()

    override fun findB2bProfileById(id: Long): B2bProfileDto? = b2bProfileQueries.findB2bProfileById(id)

    override fun findB2bRecipientFields(profileId: Long?): List<B2bProfileFieldDto> =
        b2bFieldDefinitionQueries.findB2bRecipientFields(profileId)

    override fun findB2bRecipientFieldValues(recipientId: Long, profileId: Long): Map<String, String?> =
        b2bFieldValueQueries.findB2bRecipientFieldValues(recipientId, profileId)

    override fun saveB2bRecipientFieldValues(recipientId: Long, profileId: Long, values: Map<String, String?>) =
        manageB2bFieldValue.saveB2bRecipientFieldValues(recipientId, profileId, values)
}
