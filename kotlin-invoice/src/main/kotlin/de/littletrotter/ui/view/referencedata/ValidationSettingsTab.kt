package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.invoice.EngineActivation
import de.littletrotter.application.invoice.FacturXDeployment
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications

/** Validations configuration tab — not CRUD, just on/off toggles for capabilities defined in code. */
class ValidationSettingsTab(
    private val gate: ValidationSettingsGate,
    // The only place FACTURX_ENGINE switch is actually flipped; EngineActivation decides
    // whether it means anything (a no-op is valid for inactive deployments).
    private val engineActivation: EngineActivation,
    // Without the engine deployed, the row is a dummy: gates it out of the grid instead
    // of leaving a switch that can never do anything.
    private val facturXDeployment: FacturXDeployment,
) : VerticalLayout(), TranslationProvider {

    private val grid = DataTable(ValidationCapabilityId::class.java, false)

    override fun getComponent(): Component = this

    init {
        setSizeFull()
        isPadding = false
        isSpacing = false
        add(Paragraph(tr("referenceData.validations.info")))
        add(GridWrapper(buildGrid()))
    }

    private fun buildGrid(): DataTable<ValidationCapabilityId> {
        grid.setSizeFull()

        grid.addColumn { tr(it.labelKey) }
            .setHeader(tr("referenceData.validations.check"))
            .asTextColumn(TextColumnWidth.MEDIUM)

        // The capability description is a full sentence and can be long; ellipsized
        // with the full text as a mouse-over tooltip.
        grid.ellipsizedTextColumn { capability -> tr(capability.descriptionKey) }
            .setHeader(tr("referenceData.validations.description"))
            .asTextColumn(TextColumnWidth.LARGE)

        grid.addComponentColumn { capability -> toggleFor(capability) }
            .setHeader(tr("referenceData.validations.enabled"))
            .asCompactColumn()

        grid.setItems(visibleValidationCapabilities(facturXDeployment.deployed))
        return grid
    }

    private fun toggleFor(capability: ValidationCapabilityId): Checkbox {
        val current = gate.allSettings()[capability] ?: true
        return Checkbox(current).apply {
            addValueChangeListener { event ->
                // event.isFromClient: distinguish user input from programmatic set in row initialization.
                if (!event.isFromClient) return@addValueChangeListener
                runCatching { gate.setEnabled(capability, event.value) }
                    .onSuccess {
                        applyEngineActivation(capability, event.value)
                    }
                    .onFailure { ex ->
                        value = !event.value
                        UiNotifications.error(ex.message ?: tr("common.error"))
                    }
            }
        }
    }

    /** Reacts to engine enable/disable. Pulled out of checkbox listener (`internal`, not
     *  `private`) for testability without Vaadin client plumbing. */
    internal fun applyEngineActivation(capability: ValidationCapabilityId, enabled: Boolean) {
        // The fachlich switch is the only signal this port needs; no other
        // ValidationCapabilityId drives a container, so DocumentEngine.MUSTANG never reaches
        // either EngineActivation call.
        if (capability != ValidationCapabilityId.FACTURX_ENGINE) return
        if (enabled) engineActivation.ensureRunning(DocumentEngine.FACTURX)
        else engineActivation.releaseIdle(DocumentEngine.FACTURX)
    }
}

/** The grid's rows: every capability, except FACTURX_ENGINE when the engine isn't
 *  deployed at all (a dummy switch on a stack without the container). Pulled out to a top-level
 *  function so this decision is unit-testable without building the Vaadin [DataTable]. */
internal fun visibleValidationCapabilities(deployed: Boolean): List<ValidationCapabilityId> =
    ValidationCapabilityId.entries.filter { it != ValidationCapabilityId.FACTURX_ENGINE || deployed }
