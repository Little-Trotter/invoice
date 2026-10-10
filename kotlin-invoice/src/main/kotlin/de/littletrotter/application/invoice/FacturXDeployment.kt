package de.littletrotter.application.invoice

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Is the Factur-X engine container part of this stack at all?
 *
 * "Deployed" is deliberately not "reachable":
 * [de.littletrotter.infrastructure.docker.DockerStatusService] already probes reachability, but
 * the engine is idle-stopped by design ([FacturXEngineIdleStopScheduler]) and only started on
 * demand ([EngineActivation.ensureRunning]). A reachability probe would hide the switch exactly
 * when it is needed to start the engine. `invoice.facturx.deployed` instead mirrors whether the
 * compose overlay (`docker/compose/facturx.yml`, profile `facturx`) is actually applied — set by
 * that overlay's own `INVOICE_FACTURX_DEPLOYED` env var, false on the default stack.
 *
 * Kept in the application layer, not next to
 * [de.littletrotter.infrastructure.docker.DockerStatusService] in `infrastructure.docker` — it is
 * consumed directly by `ui` and `application` code (`ArchitectureRulesTest` forbids either from
 * depending on `infrastructure`), same reasoning as
 * [LlmAnalysisFeatureConfig]'s own placement for its comparable deployment-level switch.
 */
@ApplicationScoped
class FacturXDeployment @Inject constructor(
    @param:ConfigProperty(name = "invoice.facturx.deployed", defaultValue = "false")
    val deployed: Boolean,
)
