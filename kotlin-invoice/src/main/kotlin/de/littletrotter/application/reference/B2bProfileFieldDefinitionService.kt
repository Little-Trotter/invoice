package de.littletrotter.application.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.B2bSellerFieldGroup
import de.littletrotter.domain.reference.dedupeB2bSellerFieldGroups
import de.littletrotter.domain.repository.B2bProfileFieldRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Owns the catalog and one profile's own [B2bProfileField] "shape": which
 * terms exist on a profile, where they are placed, and the per-use-case field lists derived from
 * that (seller form, invoice header/positions, customer form, article template union).
 */
@ApplicationScoped
class B2bProfileFieldDefinitionService @Inject constructor(
    private val profileRepository: B2bProfileRepository,
    private val profileFieldRepository: B2bProfileFieldRepository,
    // findSellerFieldsUnion's "still referenced" check needs the same two lookups
    // B2bProfileLifecycleService.deleteProfile's guard uses.
    private val recipientRepository: RecipientRepository,
    private val invoiceRepository: InvoiceRepository,
) {
    fun findCatalog(): List<B2bTerm> = B2bTerm.entries.filter { it.catalogEligible }

    fun findFields(profileId: Long): List<B2bProfileField> = profileFieldRepository.findByProfileId(profileId)

    fun findFieldById(id: Long): B2bProfileField? = profileFieldRepository.findById(id)

    /**
     * The subset of [profileId]'s fields that belong on the seller (Anbieter) form:
     * placement `SELLER`. A `LINE`-level term can never reach this list ([saveField] forces it to
     * `INVOICE`), so no further level filtering is needed here.
     */
    fun findSellerFields(profileId: Long): List<B2bProfileField> =
        findFields(profileId).filter { it.placement == B2bPlacement.SELLER }

    /**
     * The subset of [profileId]'s fields that render on the **invoice header**
     * (Rechnungskopf, owner_type `INVOICE`): placement `INVOICE`, term level `HEADER`, and `FREE`.
     * A `BOUND` header term (BT-13/72/73/74/84) has no widget here — its value already lives on
     * the existing property it is bound to, and its *requirement* is the only thing a profile
     * expresses for it (enforced as a compliance rule, not a second input). The `HEADER` level
     * filter keeps `LINE` terms (forced to `INVOICE` placement by [saveField]) out of the header
     * section: they render per position, see [findInvoiceItemFields].
     */
    fun findInvoiceHeaderFields(profileId: Long): List<B2bProfileField> =
        findFields(profileId)
            .filter { it.placement == B2bPlacement.INVOICE }
            .filter { field -> field.term?.level == B2bTermLevel.HEADER }
            .filter { field -> field.term?.binding == B2bTermBinding.FREE }

    /**
     * The `LINE`-level `FREE` fields of [profileId] a position's B2B section
     * renders and edits (owner_type `INVOICE_ITEM`). Unlike [findLineTemplateFields] (the
     * cross-profile union for the **article** tab), this is scoped to one seller's profile: a
     * position belongs to exactly one invoice and thus one seller, so it shows only that seller's
     * terms, never the union.
     */
    fun findInvoiceItemFields(profileId: Long): List<B2bProfileField> =
        findFields(profileId)
            .filter { field -> field.term?.level == B2bTermLevel.LINE }
            .filter { field -> field.term?.binding == B2bTermBinding.FREE }

    /**
     * The RECIPIENT-placed FREE HEADER fields of [profileId] the customer (Kunde) form's
     * "B2B-Felder" section renders — same HEADER/FREE filter as [findInvoiceHeaderFields], just
     * gated on placement `RECIPIENT` instead of `INVOICE`/`BOTH`.
     */
    fun findRecipientFields(profileId: Long): List<B2bProfileField> =
        findFields(profileId)
            .filter { it.placement == B2bPlacement.RECIPIENT }
            .filter { field -> field.term?.level == B2bTermLevel.HEADER }
            .filter { field -> field.term?.binding == B2bTermBinding.FREE }

    /**
     * The SELLER/BOTH-placed fields the seller
     * (Anbieter) form's `ScopedB2bProfileFieldsSection` renders: the *union* across every profile
     * that is either currently active (governs new invoices/records going forward) OR still
     * [isProfileReferenced] by at least one recipient's standing default or invoice's own manual
     * override (a deactivated profile's already-captured SELLER values must keep rendering for
     * whatever already uses it — deactivation only affects new invoices).
     * Deduplicated by term and resolved to a rendering group (unscoped, or one country) via
     * [dedupeB2bSellerFieldGroups].
     */
    fun findSellerFieldsUnion(): List<B2bSellerFieldGroup> {
        val contributions = profileRepository.findAll(0, Int.MAX_VALUE).filter { profile ->
            profile.active || profile.id?.let { isProfileReferenced(it) } == true
        }.flatMap { profile ->
            val fields = profile.id?.let { profileFieldRepository.findByProfileId(it) }.orEmpty()
                .filter { field -> field.placement == B2bPlacement.SELLER }
            fields.map { field -> field to profile.countries.toSet() }
        }
        return dedupeB2bSellerFieldGroups(contributions)
    }

    /** Whether [profileId] is still in active use anywhere: a recipient's standing
     *  default ([RecipientRepository.findNamesByB2bProfileId], the same lookup
     *  [B2bProfileLifecycleService.deleteProfile]'s guard already runs) or an invoice's own manual
     *  override ([InvoiceRepository.existsByB2bProfileId], the same guard applied there). Either one means a
     *  deactivated profile must keep contributing to [findSellerFieldsUnion] for whatever still
     *  points at it. */
    private fun isProfileReferenced(profileId: Long): Boolean =
        recipientRepository.findNamesByB2bProfileId(profileId).isNotEmpty() ||
            invoiceRepository.existsByB2bProfileId(profileId)

    /**
     * Labels (the anwenderdefinierte
     * [B2bProfileField.businessDefinition]) of every `MANDATORY` seller-placed field of [profileId]
     * whose `values` entry is blank/absent. Empty result = save may proceed. Checks `FREE` terms
     * only: a `BOUND` term's "value" is whatever the existing bound property already holds.
     */
    fun missingMandatorySellerFields(profileId: Long?, values: Map<String, String?>): List<String> {
        if (profileId == null) return emptyList()
        return findSellerFields(profileId)
            .filter { it.requirement == B2bFieldRequirement.MANDATORY }
            .filter { field -> field.term?.binding == B2bTermBinding.FREE }
            .filter { field -> values[field.term?.officialCode].isNullOrBlank() }
            .map { it.businessDefinition }
    }

    /**
     * Terms still selectable in the field dialog for [profileId]: every
     * catalog-eligible [B2bTerm] not already used by *another* field of the same profile.
     * [currentFieldId] excludes the field being edited from that "already used" check, so its own
     * term stays selectable while editing it.
     */
    fun findSelectableCatalogEntries(profileId: Long?, currentFieldId: Long? = null): List<B2bTerm> {
        val usedTerms = profileId
            ?.let { profileFieldRepository.findByProfileId(it) }
            ?.filter { it.id != currentFieldId }
            ?.mapNotNull { it.term }
            ?.toSet()
            .orEmpty()
        return findCatalog().filterNot { it in usedTerms }
    }

    @Transactional
    fun saveField(field: B2bProfileField): B2bProfileField {
        val profile = field.profile ?: throw InvalidParameterException("Profilfeld benötigt ein Profil.")
        profile.id ?: throw InvalidParameterException("Profil muss zuerst gespeichert werden, bevor Felder hinzugefügt werden können.")
        val term = field.term ?: throw InvalidParameterException("Business-Term darf nicht fehlen.")
        // Only catalog-eligible terms may be chosen into a profile field — a reference term
        // (binding == null, e.g. BT-1) has no xpath/boundProperty a profile could ever express.
        if (!term.catalogEligible) {
            throw InvalidParameterException("Business-Term '${term.officialCode}' ist kein katalogfähiger Term.")
        }

        // A pure-reference term (binding == null, e.g. most BT-X terms) has neither a
        // capture widget (FREE) nor a modeled property (BOUND) — a hard requirement on it could
        // never be satisfied, so only OPTIONAL (documentation only) is allowed.
        if (term.binding == null && field.requirement != B2bFieldRequirement.OPTIONAL) {
            throw InvalidParameterException(
                "Business-Term '${term.officialCode}' hat keine Datenanbindung (weder Erfassungsfeld noch " +
                    "bestehende Eigenschaft) und kann daher nur mit der Anforderung 'Optional' verwendet werden."
            )
        }

        // A LINE-level term is fixed to INVOICE placement, not just validated against it
        // ("bei LINE-Terms auf INVOICE festgelegt und nicht wählbar") — the UI disables
        // the choice, this is the server-side half of the same rule.
        if (term.level == B2bTermLevel.LINE) field.placement = B2bPlacement.INVOICE

        field.validate()
        B2bProfileField.assertPlacementCompatibleWithLevel(field.placement, term.level)
        B2bProfileField.assertDefaultValueCompatibleWithTerm(field, term)

        val siblings = profileFieldRepository.findByProfileId(requireNotNull(profile.id))
        val siblingsWithCandidate = siblings.filter { it.id != field.id } + field
        B2bProfile.assertUniqueTerms(siblingsWithCandidate)
        B2bProfileField.assertValidConditionTarget(field, siblingsWithCandidate)

        return profileFieldRepository.save(field)
    }

    @Transactional
    fun deleteField(id: Long) = profileFieldRepository.delete(id)

    /**
     * The `LINE`-level fields an article's B2B tab may template: the *union* of
     * every active profile's `LINE` fields, deduplicated by term. An article belongs to no single
     * seller, so it cannot be scoped to one profile the way [findSellerFields] is —
     * [B2bFieldValueService.copyLineTemplateValuesToInvoiceItem] is what narrows the union back
     * down to one seller's actual profile when a position is created.
     */
    fun findLineTemplateFields(): List<B2bProfileField> =
        profileRepository.findAllActive()
            .flatMap { profile -> profile.id?.let { profileFieldRepository.findByProfileId(it) }.orEmpty() }
            .filter { field -> field.term?.level == B2bTermLevel.LINE }
            .distinctBy { it.term }
}
