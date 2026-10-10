package de.littletrotter.application.mail

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Is the mail component part of this instance at all?
 *
 * `mail.enabled` (`MAIL_ENABLED`, default **false**) mirrors whether the admin-tools compose
 * overlay `docker/compose/email.yml` is applied for this instance, exactly the way
 * [de.littletrotter.application.invoice.FacturXDeployment] mirrors the Factur-X overlay. It is
 * deliberately *not* a reachability probe: the send button and the menu entry must be decided
 * without a synchronous call into a component that may be starting, and an instance without the
 * component must pay nothing for it — not even a health check.
 *
 * Lives in the application layer, not next to the HTTP adapter in `infrastructure.mail`, because
 * `ui` and `application` code read it directly and `ArchitectureRulesTest` forbids either from
 * depending on `infrastructure` — the same placement reasoning
 * [de.littletrotter.application.invoice.LlmAnalysisFeatureConfig] records for its own
 * deployment-level switch.
 */
@ApplicationScoped
class MailAvailability @Inject constructor(
    @param:ConfigProperty(name = "mail.enabled", defaultValue = "false")
    val enabled: Boolean,
)
