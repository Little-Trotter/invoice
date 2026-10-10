package de.littletrotter.ui.view.referencedata

import de.littletrotter.application.invoice.EngineActivation
import de.littletrotter.application.invoice.FacturXDeployment
import de.littletrotter.application.reference.ValidationSettingsGate
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** Builds the validation settings tab for [ERechnungTabFactory] with exactly what that tab uses. */
@Dependent
class ValidationSettingsTabFactory @Inject constructor(
    private val validationSettingsGate: ValidationSettingsGate,
    private val engineActivation: EngineActivation,
    private val facturXDeployment: FacturXDeployment,
) {
    fun build(): ValidationSettingsTab = ValidationSettingsTab(validationSettingsGate, engineActivation, facturXDeployment)
}
