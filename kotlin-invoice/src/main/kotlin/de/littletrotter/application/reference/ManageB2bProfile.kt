package de.littletrotter.application.reference

import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.reference.B2bProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [ManageB2bProfileUseCase] on top of [B2bProfileLifecycleService]; every write needs reference-data edit rights. */
@ApplicationScoped
class ManageB2bProfile @Inject constructor(
    private val lifecycleService: B2bProfileLifecycleService,
) : ManageB2bProfileUseCase {

    private val rows = CatalogueWrites(lifecycleService::findProfileById, B2bProfile::version) { B2bProfile() }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun saveB2bProfile(profile: B2bProfileDto): B2bProfileDto {
        val target = rows.rowFor(profile.id, profile.version)
        profile.applyTo(target)
        return lifecycleService.saveProfile(target).toDto()
    }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun deleteB2bProfile(id: Long) = lifecycleService.deleteProfile(id)

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun copyB2bProfile(profileId: Long): B2bProfileDto = lifecycleService.copy(profileId).toDto()
}
