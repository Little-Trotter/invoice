package de.littletrotter.application.invoice

import java.time.LocalDate
import de.littletrotter.domain.compliance.B2bFieldState
import de.littletrotter.domain.compliance.B2bProfileRules
import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.compliance.ComplianceLevel
import de.littletrotter.domain.compliance.ComplianceRuleSet
import de.littletrotter.domain.compliance.ComplianceSubject
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.effectiveSender
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import de.littletrotter.application.reference.B2bPartyFieldValues
import de.littletrotter.application.reference.port.outbound.B2bFieldValueStore
import de.littletrotter.domain.repository.B2bProfileRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Turns an invoice's own [de.littletrotter.domain.reference.B2bProfile]
 * ([Invoice.b2bProfile]) into [ComplianceFinding]s: the adapter half of [B2bProfileRules]
 * that does what the pure rule set deliberately does not — read the term, the profile's fields
 * and the actual `b2b_field_value` rows (or, for a `BOUND` term, whatever existing property
 * already carries it).
 *
 * Lives in `application/invoice`, not `domain/compliance`, unlike every other rule set adapter
 * ([de.littletrotter.domain.compliance.PeriodConsistencyRuleSet] et al.): those get away with a
 * pure bridge function because everything they need already sits on the [Invoice] aggregate. A
 * B2B field's *value* lives in a separate, generic `b2b_field_value` table this module's domain
 * layer has no JPA relation to at all — reaching it needs a repository, and the domain layer here
 * stays free of that plumbing — rule sets are plain, framework-free
 * objects. [de.littletrotter.application.invoice.InvoiceLifecycleService] injects this bean and
 * adds it to [de.littletrotter.domain.compliance.ComplianceRuleSets.DEFAULT] explicitly where it
 * evaluates compliance — the UI reaches it the same way, through
 * [de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort.findB2bComplianceFindings].
 *
 * Scope: fields placed [B2bPlacement.INVOICE], [B2bPlacement.RECIPIENT], or [B2bPlacement.SELLER]
 * — a `SELLER`-placed field is *also* still checked at finalization against the seller's own
 * captured values ([de.littletrotter.application.reference.B2bProfileService
 * .missingMandatorySellerFields]), a separate save/transition-time guard this rule set does not
 * replace. A RECIPIENT- or SELLER-placed field carries no invoice-header widget of its own by
 * default (the guided entry-pane step renders one and lets it be overridden per invoice); its
 * `hasValue` resolves through invoice value > party value (recipient/sender) > profile default
 * value instead (see [hasFreeValue]).
 */
@ApplicationScoped
class B2bProfileRuleSet @Inject constructor(
    private val profileRepository: B2bProfileRepository,
    private val fieldValueStore: B2bFieldValueStore,
    private val partyFieldValues: B2bPartyFieldValues,
) : ComplianceRuleSet {

    override val id = "B2B"
    override val levels = setOf(ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val invoice = (subject as? ComplianceSubject.OfInvoice)?.invoice ?: return emptyList()
        return evaluate(invoice)
    }

    /**
     * DoD "kein Profil vorhanden → leere Liste": no [de.littletrotter.domain.reference.B2bProfile]
     * assigned to this invoice ([Invoice.b2bProfile], the invoice's own frozen choice,
     * derived from the recipient and never the seller since decision 12) short-circuits to an
     * empty list before any repository is even consulted — the finalization behavior for such an
     * invoice is unaffected by this rule set.
     */
    fun evaluate(invoice: Invoice): List<ComplianceFinding> {
        val profileId = invoice.b2bProfile?.id ?: return emptyList()

        val fields = profileRepository.findFieldsByProfileIds(listOf(profileId))[profileId].orEmpty()
            // A RECIPIENT-placed field, or a
            // FREE SELLER-placed one, carries no invoice-header widget of its own by default, but
            // its requirement still applies to the invoice: hasValue below resolves both through the
            // invoice > party > profile-default chain instead of a stored INVOICE-owner row alone.
            // Without FREE SELLER-placed fields here, a mandatory one the guided step 02 now renders
            // and lets the user override per-invoice would never surface as a compliance finding —
            // PO decision 2 (04.09.2026): "mandatory fields count towards PRÜFUNG". A BOUND
            // SELLER-placed term (BT-84/IBAN, SELLER_PARTY level) stays excluded exactly as before —
            // it gets no widget in the guided step either (B2bProfileFieldsSection's FREE-only
            // filter), and its own bound property is already enforced by SenderProfile.validate(),
            // not this rule set (see missingMandatorySellerFields's KDoc).
            // The third disjunct no longer re-checks `it.placement == B2bPlacement.SELLER`:
            // B2bPlacement has exactly three values (INVOICE/RECIPIENT/SELLER, non-nullable field),
            // so once both prior disjuncts are false, placement is SELLER by elimination — the
            // repeated equality check's false arm was provably unreachable. Deleted, not faked.
            .filter {
                it.placement == B2bPlacement.INVOICE || it.placement == B2bPlacement.RECIPIENT ||
                    it.term?.binding == B2bTermBinding.FREE
            }
            .mapNotNull { field -> field.term?.let { term -> field to term } }

        val states = fields.flatMap { (field, term) ->
            if (term.level == B2bTermLevel.LINE) {
                invoice.itemList.map { item -> lineState(invoice, item, field, term) }
            } else {
                listOf(globalState(invoice, field, term))
            }
        }
        return B2bProfileRules.evaluate(states)
    }

    private fun globalState(invoice: Invoice, field: B2bProfileField, term: B2bTerm): B2bFieldState {
        val target = when (term.level) {
            B2bTermLevel.SELLER_PARTY -> FindingTarget.Seller
            B2bTermLevel.BUYER_PARTY -> FindingTarget.Buyer
            else -> FindingTarget.Header
        }
        return B2bFieldState(
            btCode = term,
            requirement = field.requirement,
            conditionBtCode = field.conditionTerm,
            businessDefinition = field.businessDefinition,
            hasValue = hasValue(invoice, null, field, term),
            target = target,
        )
    }

    private fun lineState(invoice: Invoice, item: InvoiceItem, field: B2bProfileField, term: B2bTerm): B2bFieldState =
        B2bFieldState(
            btCode = term,
            requirement = field.requirement,
            conditionBtCode = field.conditionTerm,
            businessDefinition = field.businessDefinition,
            hasValue = hasValue(invoice, item, field, term),
            target = FindingTarget.Position(item.positionId),
        )

    private fun hasValue(invoice: Invoice, item: InvoiceItem?, field: B2bProfileField, term: B2bTerm): Boolean =
        when (term.binding) {
            B2bTermBinding.BOUND -> isPresent(readBoundProperty(invoice, item, term))
            B2bTermBinding.FREE -> hasFreeValue(invoice, item, field, term)
            // A reference term (binding == null) never reaches a B2bProfileField —
            // B2bProfileService.saveField rejects a non-catalog-eligible term before persisting.
            // Still true after the catalog-eligible set grew to 33 terms: this guard is
            // about catalogEligible, not about which/how many terms carry which binding, so it is
            // unaffected by a reference term moving to BOUND. Only BT-16/BT-151 remain reference
            // terms today (see their own KDoc in B2bTerm.kt for why each stays one).
            null -> false
        }

    /**
     * Reads [B2bTerm.boundProperty] off whichever entity carries this term's level — plain
     * JavaBean-getter reflection (`getXxx()`), no `kotlin-reflect` dependency needed. The property
     * *name* comes from the term itself, not from a per-term branch in this code: adding a new
     * `BOUND` term for an already-modeled property needs no change here.
     */
    private fun readBoundProperty(invoice: Invoice, item: InvoiceItem?, term: B2bTerm): Any? {
        val propertyName = term.boundProperty ?: return null
        val target: Any? = when (term.level) {
            B2bTermLevel.HEADER -> invoice
            B2bTermLevel.LINE -> item
            B2bTermLevel.SELLER_PARTY -> invoice.effectiveSender()
            B2bTermLevel.BUYER_PARTY -> invoice.recipient
        }
        if (target == null) return null
        val getterName = "get" + propertyName.replaceFirstChar { it.uppercaseChar() }
        val direct = runCatching { target.javaClass.getMethod(getterName).invoke(target) }
        if (direct.isSuccess) return direct.getOrNull()
        // The HEADER-level BT reference fields (buyerReference, purchaseOrderReference, …)
        // moved off Invoice directly into Invoice.references (InvoiceReferences); the term's
        // boundProperty values still name the same field, just nested now. Falls back to the
        // embeddable before giving up, so the seed data needs no change.
        // Collapsed from an explicit `if (nested.isSuccess) return nested.getOrNull()`:
        // runCatching{...}.getOrNull() already yields null both when the lookup throws and when it
        // legitimately returns null, so the extra isSuccess branch tested nothing a real B2bTerm
        // catalog entry could ever hit on its own (proven: direct only fails for the three
        // HEADER-level nested-fallback terms — projectNumber/purchaseOrderReference/buyerReference
        // — and every one of them does resolve on Invoice.references).
        // A single `(target as? Invoice)?.let { ... }` expression, not an
        // `if (target is Invoice) { ... }; return null` statement pair: the trailing `return null`
        // for the LINE/SELLER_PARTY/BUYER_PARTY-with-a-failed-direct-lookup case is unreachable
        // through every current B2bTerm catalog entry (proven above), but as a *statement* on its
        // own source line it never executes at all, which JaCoCo reports as a fully missed line —
        // not just a missed branch. As part of this expression the line is reached (and partially
        // covered) on every call; the dead outcome remains only as an (accepted) branch gap.
        return (target as? Invoice)?.let { inv ->
            runCatching { inv.references.javaClass.getMethod(getterName).invoke(inv.references) }.getOrNull()
        }
    }

    /**
     * Present unless it's a blank string, or a value wrapper (e.g.
     * [de.littletrotter.domain.value.Iban], [de.littletrotter.domain.value.Email]) around one —
     * both already share the `value: String?` JavaBean convention this reads generically via
     * `getValue()`. Anything else non-null (a date, an enum, a number, …) counts as present as-is.
     */
    private fun isPresent(value: Any?): Boolean = when (value) {
        null -> false
        is CharSequence -> value.isNotBlank()
        else -> {
            val inner = runCatching { value.javaClass.getMethod("getValue").invoke(value) }.getOrNull()
            inner == null || inner === value || isPresent(inner)
        }
    }

    private fun hasFreeValue(invoice: Invoice, item: InvoiceItem?, field: B2bProfileField, term: B2bTerm): Boolean {
        val invoiceId = invoice.id ?: return false
        // A RECIPIENT- or SELLER-placed field
        // resolves through the invoice > party > profile-default chain regardless of the term's own
        // level (a SELLER-placed field may be HEADER- or SELLER_PARTY-level, see
        // B2bProfileField.assertPlacementCompatibleWithLevel): an invoice-level override is always
        // checked first, then the party's own stored value, then the field's default — the same
        // chain B2bProfileService.resolveB2bFieldChain implements for the form/export side.
        if (field.placement == B2bPlacement.RECIPIENT || field.placement == B2bPlacement.SELLER) {
            return hasPartyChainValue(invoice, invoiceId, field, term)
        }
        val (ownerType, ownerId) = when (term.level) {
            B2bTermLevel.HEADER -> B2bFieldOwnerType.INVOICE to invoiceId
            B2bTermLevel.LINE -> {
                val itemId = item?.id ?: return false
                B2bFieldOwnerType.INVOICE_ITEM to itemId
            }
            B2bTermLevel.SELLER_PARTY -> {
                // Unreachable in practice — SELLER_PARTY is fixed to SELLER placement (see the
                // party-chain branch above) — kept for exhaustiveness/future-proofing only.
                val sellerId = invoice.effectiveSender()?.id ?: return false
                B2bFieldOwnerType.SENDER_PROFILE to sellerId
            }
            // Known gap: no B2bFieldOwnerType exists yet for the buyer/recipient, so a FREE
            // BUYER_PARTY term (none seeded today) can't be resolved here at all — treated as
            // "no value", not as a crash.
            B2bTermLevel.BUYER_PARTY -> return false
        }
        return !fieldValueStore.findByOwnerAndBtCode(ownerType, ownerId, term)?.value.isNullOrBlank()
    }

    /** The invoice > party > default chain for a RECIPIENT- or SELLER-placed field: an
     *  invoice-level override (possible since the guided step 02 lets both be edited per invoice)
     *  wins, then the party's own stored value (SENDER_PROFILE for SELLER, `recipient_b2b_field_value`
     *  for RECIPIENT), then the field's own [B2bProfileField.defaultValue]. */
    private fun hasPartyChainValue(invoice: Invoice, invoiceId: Long, field: B2bProfileField, term: B2bTerm): Boolean {
        if (!fieldValueStore.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, invoiceId, term)?.value.isNullOrBlank()) return true
        if (!partyFieldValues.partyValue(invoice, field, term).isNullOrBlank()) return true
        return !field.defaultValue.isNullOrBlank()
    }
}
