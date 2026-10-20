package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.converters.B2bFieldRequirementConverter
import de.littletrotter.domain.reference.converters.B2bPlacementConverter
import de.littletrotter.domain.reference.converters.B2bTermConverter
import de.littletrotter.shared.b2b.B2bFieldKind
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size

/** The editable configuration of a [B2bProfileField]; owner, identity and revision stay the entity's. */
data class B2bProfileFieldDefinition(
    val term: B2bTerm?,
    val businessDefinition: String,
    val requirement: B2bFieldRequirement,
    val conditionTerm: B2bTerm?,
    val placement: B2bPlacement,
    val sortOrder: Int,
    val defaultValue: String?,
)

/**
 * One field of a [B2bProfile]: a chosen term ([term]), the anwender's own
 * [businessDefinition], a [requirement] and a [placement]. At most one row per `(profile, term)`
 * pair (DB unique index `uq_b2b_profile_field_profile_bt_code`, checked again by
 * [B2bProfile.assertUniqueTerms]).
 */
@Entity
@Table(name = "b2b_profile_field")
class B2bProfileField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    // LAZY: the owning profile is already in hand when its fields are read.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    var profile: B2bProfile? = null
        protected set

    /** The [B2bTerm] this field selects, persisted as its official BT number
     *  ([B2bTermConverter]). [B2bTerm] carries the term's [B2bTermLevel]/[B2bFieldKind]/
     *  [B2bTermBinding] directly — no catalog lookup needed. */
    @Convert(converter = B2bTermConverter::class)
    @Column(name = "bt_code", nullable = false)
    var term: B2bTerm? = null
        protected set

    @Column(name = "business_definition", columnDefinition = "TEXT", nullable = false)
    var businessDefinition: String = ""
        protected set

    @Convert(converter = B2bFieldRequirementConverter::class)
    @Column(name = "requirement", nullable = false)
    var requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL
        protected set

    /** The other profile field's [term] that triggers this field's mandatory-ness. Set exactly
     *  when [requirement] is [B2bFieldRequirement.CONDITIONAL] or
     *  [B2bFieldRequirement.CONDITIONAL_IF_EMPTY] — the two only differ in which direction of
     *  "has a value" makes this field mandatory. */
    @Convert(converter = B2bTermConverter::class)
    @Column(name = "condition_bt_code")
    var conditionTerm: B2bTerm? = null
        protected set

    @Convert(converter = B2bPlacementConverter::class)
    @Column(name = "placement", nullable = false)
    var placement: B2bPlacement = B2bPlacement.INVOICE
        protected set

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
        protected set

    /** Optional value that pre-fills this field's widget on a **new** record with no
     *  stored [B2bFieldValue] yet (never overwrites an existing one, never back-fills an
     *  already-saved record — see [de.littletrotter.ui.component.B2bProfileFieldsSection
     *  .applyDefaults]). Only meaningful for a [B2bTermBinding.FREE] term; a [B2bTermBinding.BOUND]
     *  one's value already lives on the property it's bound to, so it may never carry one — enforced
     *  by [assertDefaultValueCompatibleWithTerm], not here, since the binding is a [B2bTerm]
     *  property this class does not look up on its own (see [validate]'s KDoc). */
    @field:Size(max = 512)
    @Column(name = "default_value")
    var defaultValue: String? = null
        protected set

    @Version
    var version: Long? = 0
        protected set

    /** Replaces the whole configuration and attaches the field to [owner]; call [validate] afterwards. */
    fun redefine(owner: B2bProfile?, definition: B2bProfileFieldDefinition) {
        profile = owner
        term = definition.term
        businessDefinition = definition.businessDefinition
        requirement = definition.requirement
        conditionTerm = definition.conditionTerm
        placement = definition.placement
        sortOrder = definition.sortOrder
        defaultValue = definition.defaultValue
    }

    /** The configuration as a value, e.g. to copy this field into another profile. */
    fun definition(): B2bProfileFieldDefinition = B2bProfileFieldDefinition(
        term = term,
        businessDefinition = businessDefinition,
        requirement = requirement,
        conditionTerm = conditionTerm,
        placement = placement,
        sortOrder = sortOrder,
        defaultValue = defaultValue,
    )

    /** Pins the placement to the only one the term's level allows (checked by [validate] callers). */
    fun placeAt(newPlacement: B2bPlacement) {
        placement = newPlacement
    }

    /** Self-contained invariants — no term lookup, no sibling list required. */
    fun validate() {
        if (term == null) throw InvalidParameterException("Business-Term darf nicht fehlen.")
        if (businessDefinition.isBlank()) throw InvalidParameterException("Eigene Definition darf nicht leer sein.")
        val condition = conditionTerm
        when (requirement) {
            // CONDITIONAL_IF_EMPTY shares CONDITIONAL's self-contained invariants: a
            // trigger term is required either way, and a field may never be its own trigger
            // (positive or negated). The kinds only differ in how B2bProfileRules reads hasValue.
            B2bFieldRequirement.CONDITIONAL, B2bFieldRequirement.CONDITIONAL_IF_EMPTY -> {
                if (condition == null)
                    throw InvalidParameterException("Bedingte Pflicht verlangt einen auslösenden Term.")
                if (condition == term)
                    throw InvalidParameterException("Der auslösende Term darf nicht das Feld selbst sein.")
            }
            else ->
                if (condition != null)
                    throw InvalidParameterException("Ein auslösender Term ist nur bei bedingter Pflicht zulässig.")
        }
    }

    companion object {
        /** A new field of [owner] carrying [definition]; call [validate] afterwards. */
        fun create(owner: B2bProfile?, definition: B2bProfileFieldDefinition): B2bProfileField =
            B2bProfileField().apply { redefine(owner, definition) }

        /**
         * A [B2bTermLevel.LINE] (or [B2bTermLevel.BUYER_PARTY]) term can only ever be placed on
         * the invoice — the level is a property of the term, not a choice the profile makes.
         *
         * [B2bTermLevel.SELLER_PARTY] exists ONLY for
         * mandatory self-service fields of the seller, so it is fixed to [B2bPlacement.SELLER]
         * too: the free combinability of level x placement this function used to allow for
         * SELLER_PARTY was misleading, not a real choice. Before this, a
         * SELLER_PARTY term placed RECIPIENT/INVOICE/BOTH saved silently and simply never
         * rendered anywhere (every `find*Fields` query in [de.littletrotter.application.reference
         * .B2bProfileFieldDefinitionService] surfaces a SELLER_PARTY-level field only via
         * [findSellerFields][de.littletrotter.application.reference.B2bProfileFieldDefinitionService.findSellerFields]-style
         * SELLER/BOTH filtering) — a config-time reject now closes that gap at the source instead
         * of leaving an inert row in the profile.
         *
         * Only [B2bTermLevel.HEADER] keeps the full, freely combinable [B2bPlacement] choice.
         */
        fun assertPlacementCompatibleWithLevel(placement: B2bPlacement, level: B2bTermLevel) {
            val allowed = when (level) {
                B2bTermLevel.HEADER -> B2bPlacement.entries.toSet()
                B2bTermLevel.SELLER_PARTY -> setOf(B2bPlacement.SELLER)
                B2bTermLevel.LINE, B2bTermLevel.BUYER_PARTY -> setOf(B2bPlacement.INVOICE)
            }
            if (placement !in allowed) {
                val allowedText = allowed.joinToString(", ") { placementGermanName(it) }
                throw InvalidParameterException(
                    "Ein Term der Ebene $level kann nicht am Anzeigeort ${placementGermanName(placement)} " +
                        "angezeigt werden (erlaubter Anzeigeort: $allowedText)."
                )
            }
        }

        /** Plain German placement name for [assertPlacementCompatibleWithLevel]'s error message —
         *  the domain layer has no [de.littletrotter.ui.i18n.UiTranslations] dependency, so this is
         *  a small literal mapping, not a lookup into the UI's message bundle. */
        private fun placementGermanName(placement: B2bPlacement): String = when (placement) {
            B2bPlacement.INVOICE -> "Rechnung"
            B2bPlacement.RECIPIENT -> "Kunde"
            B2bPlacement.SELLER -> "Anbieter"
        }

        /**
         * Validates [candidate]'s [conditionTerm] against the rest of its profile: the
         * triggering term must exist among [siblings] (fields of the same profile, excluding
         * [candidate] itself) and following the chain of conditional triggers must never lead
         * back to [candidate] — a cycle would make the requirement unresolvable.
         *
         * No-op unless [candidate] is [B2bFieldRequirement.CONDITIONAL] or
         * [B2bFieldRequirement.CONDITIONAL_IF_EMPTY] — [B2bProfileField.validate] already
         * guarantees [conditionTerm] is non-null and not self-referencing in that case.
         *
         * The deeper chain walk below (rejecting a *cycle* of triggers, not just direct
         * self-reference) follows both [B2bFieldRequirement.CONDITIONAL] and
         * [B2bFieldRequirement.CONDITIONAL_IF_EMPTY] edges, each only ever chasing its own kind (a
         * `CONDITIONAL` candidate's chain only continues through further `CONDITIONAL` siblings, an
         * `CONDITIONAL_IF_EMPTY` candidate's only through further `CONDITIONAL_IF_EMPTY` ones,
         * parameterized on [candidate]'s own kind.
         *
         * The two kinds differ on exactly one point, decided by `Ep6ConfigRejectionTest`'s (not
         * linkable from this domain class, that test class lives in kotlin-invoice's test source
         * set) own "mutual CONDITIONAL_IF_EMPTY XOR pair is explicitly allowed" control case: a direct
         * mutual pair (A if-empty B, B if-empty A — a 2-node cycle) is not a bug to reject, it *is*
         * the XOR pattern `CONDITIONAL_IF_EMPTY` exists for (resolved by a single direct `hasValue`
         * lookup on the immediate trigger only — [de.littletrotter.domain.compliance.B2bProfileRules
         * .evaluate] never recurses into the trigger's own requirement, so it can never spin). A
         * `CONDITIONAL` mutual pair has no such reading — "A required when B has a value, B
         * required when A has a value" is a real logical loop with no resolvable base case — so it
         * stays rejected at 2 nodes. A *longer* if-empty
         * chain (length ≥ 3, e.g. A if-empty B, B if-empty C, C if-empty A) is not exempted either:
         * nothing distinguishes it from the CONDITIONAL case's own loop-with-no-base-case, so it is
         * rejected exactly like a CONDITIONAL cycle of the same length — only the direct 2-node
         * mutual pair is exempted, not the kind as a whole.
         */
        fun assertValidConditionTarget(candidate: B2bProfileField, siblings: List<B2bProfileField>) {
            if (candidate.requirement != B2bFieldRequirement.CONDITIONAL &&
                candidate.requirement != B2bFieldRequirement.CONDITIONAL_IF_EMPTY
            ) return
            val condition = candidate.conditionTerm ?: return
            // validate() already rejects a null term before this invariant is meaningfully
            // reachable, but this function is a standalone public entry point, so the guard stays
            // — same defensive shape as the conditionTerm check just above.
            val candidateTerm = candidate.term ?: return
            val others = siblings.filter { it.term != candidateTerm }
            val byTerm = others.associateBy { it.term }
            if (condition !in byTerm) {
                throw InvalidParameterException(
                    "Auslösender Term '${condition.officialCode}' ist in diesem Profil nicht vorhanden."
                )
            }
            rejectConditionCycle(candidate, condition, candidateTerm, byTerm)
        }

        /** [assertValidConditionTarget]'s cycle walk, extracted so that method's own cognitive
         *  complexity stays low and this loop has no break/continue
         *  (LoopWithTooManyJumpStatements): every "stop scanning" case sets `current = null`
         *  instead, ending the `while` naturally. */
        private fun rejectConditionCycle(
            candidate: B2bProfileField,
            condition: B2bTerm,
            candidateTerm: B2bTerm,
            byTerm: Map<B2bTerm?, B2bProfileField>,
        ) {
            var current: B2bTerm? = condition
            val visited = mutableSetOf(candidateTerm)
            var hops = 0
            while (current != null) {
                hops++
                current = when {
                    current == candidateTerm -> {
                        rejectCycleUnlessExemptPair(hops, candidate.requirement, condition, candidateTerm)
                        null
                    }
                    // cycle among other terms, not involving candidate — not this field's concern
                    !visited.add(current) -> null
                    else -> byTerm[current]?.takeIf { it.requirement == candidate.requirement }?.conditionTerm
                }
            }
        }

        // The one exemption: a direct mutual CONDITIONAL_IF_EMPTY pair closes the cycle
        // in exactly 2 hops (candidate -> condition -> candidateTerm) and is the legitimate XOR
        // pattern, not a footgun. Every other case — a CONDITIONAL 2-hop mutual pair, or any
        // if-empty/CONDITIONAL cycle of 3+ hops — is rejected.
        private fun rejectCycleUnlessExemptPair(hops: Int, requirement: B2bFieldRequirement, condition: B2bTerm, candidateTerm: B2bTerm) {
            val exemptMutualPair = hops == 2 && requirement == B2bFieldRequirement.CONDITIONAL_IF_EMPTY
            if (exemptMutualPair) return
            throw InvalidParameterException(
                "Zyklische bedingte Pflicht: '${condition.officialCode}' führt über weitere " +
                    "auslösende Terme zurück auf '${candidateTerm.officialCode}'."
            )
        }

        /**
         * Validates [candidate]'s [defaultValue] against [term], the same split
         * [assertPlacementCompatibleWithLevel] uses (a term lookup [validate] deliberately doesn't
         * do on its own): a `null`/blank default is always fine (nothing to check), and otherwise —
         * - a [B2bTermBinding.BOUND] term may never carry one: its value already lives on the
         *   property it's bound to, which has its own master-data defaults (e.g. IBAN on the
         *   sender profile); a second, profile-level default would be a second source of truth
         *   for the same value.
         * - a [B2bFieldKind.DATE] term may not carry one either — v1 decision: [B2bFieldKind.DATE]
         *   renders as a `DatePicker` that only accepts an ISO-8601 `LocalDate`
         *   ([de.littletrotter.ui.component.B2bFieldWidgetFactory.setWidgetValue]), and defining/
         *   validating that format on top of the plain-string column isn't worth it for the single
         *   motivating case (BT-58, a `TEXT` term) — revisit if a DATE default is ever actually
         *   asked for.
         * - the default must not exceed [B2bTerm.maxLength], same ceiling [B2bFieldValue] itself
         *   would eventually be validated against.
         */
        fun assertDefaultValueCompatibleWithTerm(candidate: B2bProfileField, term: B2bTerm) {
            val default = candidate.defaultValue
            if (default.isNullOrBlank()) return
            // DATE-kind checked before BOUND: every catalog-eligible DATE term today also happens
            // to be BOUND (BT-72/73/74/134/135) — checking BOUND first would make this branch
            // structurally unreachable with real B2bTerm data (a closed enum, unlike the former
            // catalogEntry() test fixture, offers no way to fake a FREE+DATE combination), and
            // B2bProfileField$Companion sits under a 100%-branch jacoco gate (kotlin-invoice/pom.xml).
            if (term.fieldKind == B2bFieldKind.DATE) {
                throw InvalidParameterException(
                    "Datums-Terme (${term.officialCode}) unterstützen aktuell keinen Vorgabewert."
                )
            }
            if (term.binding == B2bTermBinding.BOUND) {
                throw InvalidParameterException(
                    "Ein gebundener Term (${term.officialCode}) kann keinen Vorgabewert tragen — " +
                        "der Wert stammt aus der bestehenden Eigenschaft."
                )
            }
            term.maxLength?.let { max ->
                if (default.length > max) {
                    throw InvalidParameterException(
                        "Vorgabewert überschreitet die zulässige Länge von $max Zeichen für ${term.officialCode}."
                    )
                }
            }
        }
    }
}
