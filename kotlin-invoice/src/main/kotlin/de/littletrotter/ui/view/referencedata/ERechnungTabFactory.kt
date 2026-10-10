package de.littletrotter.ui.view.referencedata

import com.vaadin.flow.component.Component
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * See [AbrechnungTabFactory]'s own KDoc for why this exists. Covers
 * [ReferenceDataGroup.E_RECHNUNG] (B2B profiles, validation settings); the
 * validation settings tab has its own factory because it needs none of these ports.
 */
@Dependent
class ERechnungTabFactory @Inject constructor(
    private val b2bProfileQueries: B2bProfileQueryPort,
    private val manageB2bProfile: ManageB2bProfileUseCase,
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase,
    private val validationSettingsTabFactory: ValidationSettingsTabFactory,
    private val time: TimeSource,
) {
    fun build(tab: ReferenceDataTab): Component = when (tab) {
        ReferenceDataTab.B2B_PROFILES ->
            B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time)
        ReferenceDataTab.VALIDATION -> validationSettingsTabFactory.build()
        else -> error("ERechnungTabFactory cannot build $tab (not in ${ReferenceDataGroup.E_RECHNUNG})")
    }
}
