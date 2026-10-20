package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.port.inbound.LlmAnalysisAvailabilityQueryPort
import de.littletrotter.application.reference.port.inbound.ValidationSettingsQueryPort
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.ui.component.blankToNull
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * Extracted from `InvoiceExportPanel.buildLlmCheckbox`/`renderControls`: the three
 * independent reasons the print panel's "LLM-Check" checkbox can be unavailable, folded into one
 * status read by [InvoicePrintControls].
 *
 * T9 — two different kinds of reasons, two different effects, and that is deliberate:
 *  • **switched off by the business** ([ValidationCapabilityId.OMLX_LLM_CHECK]): the control
 *    disappears entirely — a user decided this check does not exist here.
 *  • **technically unavailable** ([LlmAnalysisAvailabilityQueryPort] off, or the oMLX endpoint not
 *    configured): the control stays and names the reason (V3). When both technical reasons
 *    apply, the deployment reason wins as the more fundamental one.
 */
@Dependent
class LlmCheckAvailability(
    /** The business-level switch. Null in component tests that don't consider it; then the
     *  check counts as switched on (behavior before T9). */
    private val validationSettingsGate: ValidationSettingsQueryPort?,
    /** The deployment-level switch, independent of [validationSettingsGate]. Null in
     *  component tests that don't consider it, then the feature counts as enabled. */
    private val llmAnalysisFeatureConfig: LlmAnalysisAvailabilityQueryPort?,
    /** V3 availability probe for the oMLX configuration — replaceable in component tests, which
     *  cannot alter the process environment. Never CDI-injected (no bean of this function type
     *  exists) — see the `@Inject` secondary constructor below, the one CDI actually calls. */
    private val mlxConfigured: () -> Boolean,
) {
    // QOD — CDI's own entry point: [mlxConfigured] above has no matching bean (a plain
    // `() -> Boolean`), so the `@Inject`-annotated constructor cannot be the primary one; it
    // delegates to it with the real env reader instead. This ALSO doubles as the component-test
    // convenience for the two-argument call sites (same nullable-CDI-bean-parameter idiom as
    // every other optional collaborator in this package).
    @Inject
    constructor(validationSettingsGate: ValidationSettingsQueryPort?, llmAnalysisFeatureConfig: LlmAnalysisAvailabilityQueryPort?) :
        this(validationSettingsGate, llmAnalysisFeatureConfig, ::readMlxConfiguredFromEnv)

    // QOD — plain (non-`@Inject`) convenience for the zero-argument call sites. A primary
    // constructor whose parameters are ALL defaulted would make kotlinc synthesize an additional
    // no-arg constructor that also carries `@Inject` (verified via javap), which Quarkus ARC then
    // rejects as "Multiple @Inject constructors found" — this hand-written overload avoids that
    // trap entirely (neither constructor above has any default-valued parameter).
    constructor() : this(null, null, ::readMlxConfiguredFromEnv)

    internal fun isAvailable(): LlmCheckStatus = when {
        validationSettingsGate?.isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK) == false -> LlmCheckStatus.SwitchedOff
        llmAnalysisFeatureConfig?.enabled == false -> LlmCheckStatus.DisabledByDeployment
        !mlxConfigured() -> LlmCheckStatus.NotConfigured
        else -> LlmCheckStatus.Available
    }
}

/** The four states [LlmCheckAvailability.isAvailable] distinguishes — see
 *  [InvoicePrintControls]'s own checkbox builder for how each renders. */
internal enum class LlmCheckStatus { SwitchedOff, DisabledByDeployment, NotConfigured, Available }

/** V3 — mirrors the worker's `de.littletrotter.xpdf.MlxClient` availability check: an
 *  LLM run is only possible when endpoint + key + model are all configured. The reader lives
 *  in a different module than the oMLX client, so it re-derives this from the shared env. */
private fun readMlxConfiguredFromEnv(): Boolean = isMlxConfigured(System::getenv)

/** The decision behind [readMlxConfiguredFromEnv], over an injectable [env] lookup so every
 *  endpoint/key/model combination is testable without touching the process environment:
 *  `MLX_MODEL` wins, otherwise the first entry of the comma-separated `MLX_MODELS`. */
internal fun isMlxConfigured(env: (String) -> String?): Boolean {
    val endpoint = env("MLX_ENDPOINT")
    val apiKey = env("MLX_API_KEY")
    val model = env("MLX_MODEL").blankToNull()
        ?: env("MLX_MODELS")?.split(",")?.firstOrNull()?.trim().blankToNull()
    return !endpoint.isNullOrBlank() && !apiKey.isNullOrBlank() && !model.isNullOrBlank()
}
