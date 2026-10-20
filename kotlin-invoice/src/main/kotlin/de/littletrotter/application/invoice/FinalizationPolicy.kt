package de.littletrotter.application.invoice

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.compliance.ComplianceRuleSets
import de.littletrotter.domain.compliance.FinalizationGate
import de.littletrotter.domain.compliance.FinalizationOverride
import de.littletrotter.domain.compliance.GateBlockReason
import de.littletrotter.domain.compliance.GateDecision
import de.littletrotter.domain.compliance.GateStage
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.compliance.SellerPhoneRule
import de.littletrotter.domain.compliance.blocking
import de.littletrotter.domain.compliance.evaluateCompliance
import de.littletrotter.domain.compliance.toValidationMessage
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.effectiveSender
import de.littletrotter.domain.invoice.ensureTextRenderableInPdf
import de.littletrotter.domain.invoice.requiresExtendedProfile
import de.littletrotter.domain.invoice.resolveExportProfileId
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.ValidationProfileRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationMessage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Bundles the finalization-time gate/rule-stage orchestration that used to be spread across
 * five separate [InvoiceLifecycleService] collaborators (`validationProfileRepo`,
 * `gateStageConfig`, `complianceStageConfig`, `b2bProfileRuleSet`, `b2bProfileService`) plus
 * `generationJobRepo` (the finalization gate's own KoSIT-finding lookup). [InvoiceLifecycleService
 * .transition] asks this policy for each finalization-time verdict instead of owning the stage
 * configuration and rule-set wiring itself. Domain types ([FinalizationGate],
 * [ComplianceRuleSets]) stay in `domain/compliance` untouched — this is an application-layer
 * orchestration extraction, not a domain change.
 *
 * OBSERVE/BLOCK stage semantics of `littletrotter.validation-gate.stage` and
 * `littletrotter.compliance-rules.stage` are preserved exactly: both stages always compute their
 * full verdict and log it; only `BLOCK` actually throws.
 *
 * PO decision 2026-08-31 (Variante b) — `littletrotter.compliance-rules.blocking-rules` adds a
 * per-rule override on top of `littletrotter.compliance-rules.stage`: a `ruleId` named there
 * enforces (blocks) [enforceComplianceRules]'s finding even while the global stage is still
 * OBSERVE/WARN, without switching every other rule's enforcement on along with it. See
 * [blockingRuleIds]'s own KDoc.
 */
@ApplicationScoped
class FinalizationPolicy @Inject constructor(
    private val time: TimeSource,
    private val validationProfileRepo: ValidationProfileRepository,
    private val generationJobRepo: GenerationJobRepository,
    private val b2bProfileRuleSet: B2bProfileRuleSet,
    private val b2bFieldValueService: B2bFieldValueService,
    private val b2bProfileFieldDefinitionService: B2bProfileFieldDefinitionService,
    // PO decision 2026-09-28 — the EXTENDED-profile escalation is an admin opt-in
    // (FeatureFlag.EXTENDED_PROFILE); resolveValidationProfileId reads it from here so the gate's
    // own verdict agrees with resolveExportProfileId, the same reasoning InvoiceDtoMapper follows
    // for the actual XML export.
    private val featureSettingsGate: FeatureSettingsGate,
    // ── Gate before ISSUED ─────────────────────────────────────────────────
    @param:ConfigProperty(name = "littletrotter.validation-gate.stage", defaultValue = "OBSERVE")
    private val gateStageConfig: String = "OBSERVE",
    // Its own stage for the rule evaluation, deliberately separate from the finalization gate:
    // one judges the validation run (KoSIT/profile), the other judges the invoice itself. Default
    // OBSERVE because these rules are stricter than anything that blocked before — switching
    // straight to BLOCK would leave no existing invoice finalizable. Stage A delivers exactly the
    // signal needed to decide when BLOCK becomes reasonable.
    @param:ConfigProperty(name = "littletrotter.compliance-rules.stage", defaultValue = "OBSERVE")
    private val complianceStageConfig: String = "OBSERVE",
    // PO decision 2026-08-31 (Variante b) — per-rule escalation on top of the global
    // complianceStage above: littletrotter.compliance-rules.stage stays OBSERVE as the
    // application-wide default (a blanket switch to BLOCK would still make every existing invoice
    // with any blocking finding un-finalizable), but individual rules that are already known-safe
    // to enforce can be named here and block regardless — comma-separated ComplianceFinding.ruleId
    // values, e.g. "CORE.SERVICE_PERIOD_XOR,CORE.OTHER_RULE". Blank/unset (the default) enforces
    // nothing beyond whatever complianceStage itself already does — every pre-existing caller
    // (this parameter is appended last, with a default) keeps its old behavior unchanged.
    @param:ConfigProperty(name = "littletrotter.compliance-rules.blocking-rules", defaultValue = "")
    private val blockingRulesConfig: String = "",
) {
    private val logger: Logger = Logger.getLogger(FinalizationPolicy::class.java)

    private val gateStage: GateStage
        get() = runCatching { GateStage.valueOf(gateStageConfig.trim().uppercase()) }.getOrDefault(GateStage.OBSERVE)

    private val complianceStage: GateStage
        get() = runCatching { GateStage.valueOf(complianceStageConfig.trim().uppercase()) }.getOrDefault(GateStage.OBSERVE)

    /** PO decision 2026-08-31 (Variante b) — the parsed [blockingRulesConfig] rule-id allowlist;
     *  see that property's own KDoc. Blank entries (a trailing comma, accidental whitespace) are
     *  dropped rather than matching an empty
     *  [de.littletrotter.domain.compliance.ComplianceFinding.ruleId], which never occurs. */
    private val blockingRuleIds: Set<String>
        get() = blockingRulesConfig.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Text the PDF fonts cannot render: the invoice's own texts plus the B2B field values that are
     * printed (invoice, position, seller and resolved recipient values). Unconditional, like the
     * other structural checks, because the document job would otherwise fail after issuing.
     */
    fun enforceRenderableText(invoice: Invoice) {
        invoice.ensureTextRenderableInPdf(b2bTexts(invoice))
    }

    private fun b2bTexts(invoice: Invoice): Map<String, String?> {
        val texts = linkedMapOf<String, String?>()
        fun add(prefix: String, values: Map<String, String?>) =
            values.forEach { (code, value) -> texts["$prefix $code"] = value }
        invoice.id?.let { add("B2B-Feld", b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, it)) }
        invoice.itemList.mapNotNull { it.id }.let { ids ->
            if (ids.isNotEmpty()) {
                b2bFieldValueService.findFieldValuesByOwners(B2bFieldOwnerType.INVOICE_ITEM, ids)
                    .values.forEach { add("B2B-Feld Position", it) }
            }
        }
        invoice.effectiveSender()?.id?.let { add("B2B-Feld Absender", b2bFieldValueService.findFieldValues(B2bFieldOwnerType.SENDER_PROFILE, it)) }
        add("B2B-Feld Empfänger", b2bFieldValueService.resolvedRecipientFieldValues(invoice))
        return texts
    }

    /**
     * Mandatory SELLER-placed fields of [invoice]'s own [Invoice.b2bProfile] must have a
     * captured `SENDER_PROFILE`-owner value before finalization. Before decision 12 this was
     * checked once, at sender-profile save time, against whichever single profile the seller had
     * chosen; now that the assignment lives on the customer relationship (with a per-invoice
     * override), there is no single moment "the seller's profile" is fixed anymore — so the check
     * runs at the one moment that *is* fixed for a B2B field set: this invoice's own frozen
     * [Invoice.b2bProfile], right before it is finalized. No profile or no seller resolves to an
     * empty missing-list, i.e. never blocks (same as [B2bProfileRuleSet]'s "no profile"
     * short-circuit).
     */
    fun enforceMandatorySellerFields(invoice: Invoice) {
        val profileId = invoice.b2bProfile?.id ?: return
        val sellerId = invoice.effectiveSender()?.id ?: return
        val values = b2bFieldValueService.findFieldValues(B2bFieldOwnerType.SENDER_PROFILE, sellerId)
        val missing = b2bProfileFieldDefinitionService.missingMandatorySellerFields(profileId, values)
        if (missing.isNotEmpty()) {
            throw InvalidParameterException(
                "Pflichtfelder des B2B-Profils fehlen beim Anbieter und müssen vor der Finalisierung ergänzt werden: " +
                    missing.joinToString(", ")
            )
        }
    }

    /**
     * An invoice under review whose SEND job is still running must not be voided or sent back to
     * draft: the job would finish, store a document for an invoice that has moved on, and its
     * follow-up transition to ISSUED could only fail. Rejecting the user transition keeps the
     * document and the status consistent; the user retries once the job has finished.
     */
    fun enforceNoSendInFlight(invoice: Invoice) {
        val id = invoice.id ?: return
        if (generationJobRepo.hasNonTerminalTriggerJob(id, GenerationJobTrigger.SEND)) {
            throw InvoiceSendAlreadyInProgressException(invoice.invoiceNumber.value)
        }
    }

    /**
     * BR-DE-6/BR-DE-27 (German KoSIT CIUS) — BT-42, the seller's telephone number, unconditionally,
     * the same way [enforceMandatorySellerFields] is: no [gateStage]/[complianceStage] rollout applies
     * here because there is no installed base of already-`ISSUED` invoices this could retroactively
     * break — the field was never captured before, so nothing that already reached ISSUED is affected.
     * Alongside [enforceMandatorySellerFields] rather than folded into it: that one enforces a
     * per-*B2B-profile* catalog of mandatory fields (`b2b_field_value`), which BT-42 is not — it
     * applies to every invoice regardless of which B2B profile, if any, is assigned. No effective
     * seller resolves to no check, same as [enforceMandatorySellerFields]'s identical short-circuit.
     */
    fun enforceSellerTelephoneNumber(invoice: Invoice) {
        val sender = invoice.effectiveSender() ?: return
        val finding = SellerPhoneRule.evaluate(sender.phone) ?: return
        throw InvoiceFinalizationBlockedException(GateBlockReason.COMPLIANCE_RULE_VIOLATION, listOf(finding.toValidationMessage()))
    }

    /**
     * The actual gate check, applied at `→ ISSUED` only. Always computes the Stage-C
     * ("would this block?") verdict via [FinalizationGate.evaluate] so Stage A/B can log the
     * would-be outcome (staged rollout: Stage A needs exactly this signal to decide when Stage C
     * is safe to switch on) — only [gateStage] decides whether that verdict actually throws.
     * Returns the [GateDecision] it computed.
     */
    fun enforceFinalizationGate(invoice: Invoice, overrideReason: String?, actor: String): GateDecision {
        val override = overrideReason?.let { FinalizationOverride(reason = it, actor = actor, at = time.nowUtc()) }
        val profileId = resolveValidationProfileId(invoice)
        val profileState = validationProfileRepo.findCurrentByProfileId(profileId)?.stateEnum
        val findings = if (invoice.validationStatus == ValidationStatus.INVALID) latestFindings(invoice) else emptyList()
        val decision = FinalizationGate.evaluate(invoice.validationStatus, profileState, findings, override)

        when (decision) {
            is GateDecision.Block -> when (gateStage) {
                GateStage.OBSERVE -> logger.infof(
                    "F4 gate (OBSERVE, no block): invoice %s would be blocked from ISSUED — %s",
                    invoice.invoiceNumber.value, decision.reason,
                )
                GateStage.WARN -> logger.warnf(
                    "F4 gate (WARN, not blocking yet): invoice %s would be blocked from ISSUED — %s",
                    invoice.invoiceNumber.value, decision.reason,
                )
                GateStage.BLOCK -> throw InvoiceFinalizationBlockedException(decision.reason, decision.findings)
            }
            is GateDecision.AllowWithWarning ->
                logger.infof("F4 gate: invoice %s finalized with warning — %s", invoice.invoiceNumber.value, decision.warning)
            GateDecision.Allow -> {
                // Allowed: proceeding without extra logging is intentional.
            }
        }

        // "no override without a logged trail": logged whenever it actually took effect,
        // regardless of gateStage (an override is only consulted for UNAVAILABLE at all).
        if (override != null && override.isValid && invoice.validationStatus == ValidationStatus.UNAVAILABLE) {
            logger.infof(
                "F4 override: invoice %s finalized despite UNAVAILABLE validation — actor=%s at=%s reason=%s",
                invoice.invoiceNumber.value, override.actor, override.at, override.reason,
            )
        }
        return decision
    }

    /**
     * The rule evaluation at the status transition. It always runs and always reports what
     * it finds; only [complianceStage] decides whether the finding also blocks.
     *
     * Deliberately after [enforceFinalizationGate]: if the validation run itself is already
     * negative, that is the older and more tangible message for the user. And deliberately only
     * here, not on the save path: a draft must stay intermediately savable, even incomplete.
     */
    fun enforceComplianceRules(invoice: Invoice) {
        // B2bProfileRuleSet isn't part of ComplianceRuleSets.DEFAULT (it needs repositories
        // the domain layer's plain rule-set objects don't have access to, see its own KDoc); this
        // is the one place that adds it back for the call that actually gates on a finding.
        val ruleSets = ComplianceRuleSets.DEFAULT + b2bProfileRuleSet
        val blocking = invoice.evaluateCompliance(time.today(), ruleSets).blocking()
        if (blocking.isEmpty()) return
        val summary = blocking.joinToString(", ") { it.ruleId }

        // PO decision 2026-08-31 (Variante b) — a finding whose ruleId is named in
        // littletrotter.compliance-rules.blocking-rules enforces regardless of complianceStage:
        // it escalates OBSERVE/WARN straight to blocking, exactly like BLOCK already would. Checked
        // before the stage switch below so the escalation cannot be bypassed by leaving
        // complianceStage at its OBSERVE default.
        //
        // finding-fixes (2026-08-31) — bug found via FinalizationGateIT: only the FORCED findings
        // (ruleId actually named in blockingRuleIds) go into the thrown exception, never the full
        // `blocking` list. The earlier version threw with every blocking finding once ANY one of
        // them was listed, which smuggled unlisted, still-OBSERVE findings (e.g. CORE.BUYER_VAT_ID,
        // CORE.BUYER_ADDRESS on that IT's fixture) into a block they must not cause on their own —
        // "only listed rules may force-block in OBSERVE" applies to which findings are REPORTED as
        // the block's cause, not just to whether a block happens at all.
        val forced = blocking.filter { it.ruleId in blockingRuleIds }
        if (forced.isNotEmpty()) {
            logger.warnf(
                "Regelauswertung (per-rule BLOCK via littletrotter.compliance-rules.blocking-rules): " +
                    "Rechnung %s hat %d erzwungen blockierende Befunde (von %d insgesamt blockierenden) — %s",
                invoice.invoiceNumber.value, forced.size, blocking.size, forced.joinToString(", ") { it.ruleId },
            )
            throw InvoiceFinalizationBlockedException(GateBlockReason.COMPLIANCE_RULE_VIOLATION, forced.map { it.toValidationMessage() })
        }

        when (complianceStage) {
            GateStage.OBSERVE -> logger.infof(
                "Regelauswertung (OBSERVE, blockiert nicht): Rechnung %s hat %d blockierende Befunde — %s",
                invoice.invoiceNumber.value, blocking.size, summary,
            )
            GateStage.WARN -> logger.warnf(
                "Regelauswertung (WARN, blockiert noch nicht): Rechnung %s hat %d blockierende Befunde — %s",
                invoice.invoiceNumber.value, blocking.size, summary,
            )
            GateStage.BLOCK -> throw InvoiceFinalizationBlockedException(
                GateBlockReason.COMPLIANCE_RULE_VIOLATION,
                blocking.map { it.toValidationMessage() },
            )
        }
    }

    /**
     * Mirrors `XmlExportServiceImpl.resolveProfile()` (kotlin-xpdf) exactly. Duplicated rather
     * than shared: `little-trotter-invoice` and `little-trotter-job-worker` are separate
     * deployables with no dependency edge from this module onto kotlin-xpdf. This gate only reads
     * this decision, it doesn't improve it — the profile-selection logic itself belongs to the
     * export profile resolver. Keep both in sync.
     */
    // Delegates to the shared resolver so this gate and DocumentJobPublisher's DTO
    // profileId can never disagree about which profile the invoice would export as.
    private fun resolveValidationProfileId(invoice: Invoice): String =
        resolveExportProfileId(
            invoice.senderProfile?.vatId?.value,
            invoice.recipient?.vatId?.value,
            invoice.requiresExtendedProfile(featureSettingsGate.isEnabled(FeatureFlag.EXTENDED_PROFILE)),
        )

    private fun latestFindings(invoice: Invoice): List<ValidationMessage> {
        val invoiceId = invoice.id ?: return emptyList()
        val latest = generationJobRepo.findByInvoiceId(invoiceId)
            .filter { it.status == GenerationJobStatus.COMPLETED }
            .maxByOrNull { it.createdAt }
            ?: return emptyList()
        return KositReportPayload.decode(latest.kositReport)?.details ?: emptyList()
    }
}
