package de.littletrotter.application.reference

import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.reference.B2bProfileField
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [ManageB2bFieldDefinitionUseCase] on top of [B2bProfileFieldDefinitionService]; every write needs reference-data edit rights. */
@ApplicationScoped
class ManageB2bFieldDefinition @Inject constructor(
    private val fieldDefinitionService: B2bProfileFieldDefinitionService,
    private val lifecycleService: B2bProfileLifecycleService,
) : ManageB2bFieldDefinitionUseCase {

    private val rows = CatalogueWrites(fieldDefinitionService::findFieldById, B2bProfileField::version) { B2bProfileField() }

    /** The owning profile is resolved from [B2bProfileFieldDto.profileId]; an unknown or missing
     *  id leaves the field without a profile, which the service rejects with its own message. */
    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun saveB2bProfileField(field: B2bProfileFieldDto): B2bProfileFieldDto {
        val target = rows.rowFor(field.id, field.version)
        field.applyTo(target, field.profileId?.let(lifecycleService::findProfileById))
        return fieldDefinitionService.saveField(target).toDto()
    }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun deleteB2bProfileField(id: Long) = fieldDefinitionService.deleteField(id)
}
