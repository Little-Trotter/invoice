package de.littletrotter.application.invoice

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * P3 — deployment-level switch that takes the whole LLM analysis path out of service.
 *
 * This is a **different** mechanism from
 * [de.littletrotter.application.reference.ValidationSettingsGate] with
 * [de.littletrotter.domain.reference.ValidationCapabilityId.OMLX_LLM_CHECK], and the two must not
 * be confused:
 * - `ValidationSettingsGate` is a *runtime, reference-data* switch. An operator flips it in the
 *   UI without a restart, it is re-read (via a short-lived cache) on every job dispatch, and it
 *   only decides whether *one* export job is allowed to ask for an LLM check.
 * - This property is a *deployment* decision, read once at startup. It exists for targets that
 *   structurally cannot run a local model at all — the Raspberry Pi 5 target ships
 *   without a local LLM, so trying anyway would only produce connection timeouts in the log. In
 *   the UI it does not remove the LLM element (PO decision 24.08.2026 — visibility follows the
 *   reference-data switch alone); it shows the element disabled, with a helper text naming the
 *   deployment as the reason. `ValidationSettingsGate` is what decides visibility for this
 *   capability, and it also informs `DocumentJobPublisher`'s per-job decision.
 *
 * Rule of thumb: reach for `ValidationSettingsGate` to let a user toggle a check at runtime; reach
 * for this property to take a whole feature off a target that cannot run it, independent of what
 * the reference-data switch says. Both gates are consulted independently by their respective
 * call sites — this class does not layer on top of `ValidationSettingsGate` or replace it.
 */
@ApplicationScoped
class LlmAnalysisFeatureConfig(
    @param:ConfigProperty(name = "littletrotter.features.llm-analysis.enabled", defaultValue = "true")
    val enabled: Boolean,
)
