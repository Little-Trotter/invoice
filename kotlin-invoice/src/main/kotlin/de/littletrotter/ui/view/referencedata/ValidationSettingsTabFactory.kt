package de.littletrotter.ui.view.referencedata

import de.littletrotter.application.reference.port.inbound.ManageValidationSettingsUseCase
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** Builds the validation settings tab for [ERechnungTabFactory] with exactly what that tab uses. */
@Dependent
class ValidationSettingsTabFactory @Inject constructor(
    private val validationSettingsGate: ManageValidationSettingsUseCase,
) {
    fun build(): ValidationSettingsTab = ValidationSettingsTab(validationSettingsGate)
}
