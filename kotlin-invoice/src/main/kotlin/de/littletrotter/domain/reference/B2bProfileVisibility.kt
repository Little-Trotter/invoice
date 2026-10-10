package de.littletrotter.domain.reference

import de.littletrotter.shared.country.Country

/**
 * anbieter-einvoice-ergonomie §3 — whether one [B2bProfile]'s SELLER/BOTH fields are visible for
 * the current seller: an unscoped profile ([profileCountries] empty) is always visible; a scoped
 * one only when the seller is registered (see
 * [de.littletrotter.domain.masterdata.sellerRegistrationCountries]) in at least one of its
 * [profileCountries]. Pure, no Vaadin/JPA types — mirrors `PartyExtensionsSection
 * .applyCountryContext`'s reasoning, generalized from one fixed FR/LU/BE set to any profile's own
 * scope.
 */
fun isB2bProfileVisibleForSeller(profileCountries: Set<Country>, sellerCountries: Set<Country>): Boolean =
    profileCountries.isEmpty() || profileCountries.any { it in sellerCountries }

/**
 * anbieter-einvoice-ergonomie §4 — one [de.littletrotter.shared.b2b.B2bTerm]'s rendering assignment
 * after deduping the seller field union across every active, possibly country-scoped, [B2bProfile]
 * that contributes it.
 *
 * @param group `null` for the unscoped group (PROFILFELDER); otherwise the single country group
 *   (PROFILFELDER · <Land>) this field renders in — never more than one, even when contributed by
 *   several differently-scoped profiles (the dedupe rule, see [dedupeB2bSellerFieldGroups]).
 * @param triggerCountries the union of every contributing profile's own [B2bProfile.countries] —
 *   used by [isB2bSellerFieldGroupVisible] to decide the group's visibility. Meaningless when
 *   [group] is `null` (an unscoped contributor makes the field always visible regardless of any
 *   other contributor's scope).
 */
data class B2bSellerFieldGroup(
    val field: B2bProfileField,
    val group: Country?,
    val triggerCountries: Set<Country>,
)

/**
 * anbieter-einvoice-ergonomie §4 — dedupes [contributions] (one entry per field per contributing
 * profile, paired with that profile's own [B2bProfile.countries]) by term, exactly like the
 * pre-existing `distinctBy { it.term }` union did, but additionally resolves *where* the single
 * remaining instance renders:
 *
 * - If any contributing profile is unscoped (`countries.isEmpty()`), the field always renders in
 *   the unscoped group ([B2bSellerFieldGroup.group] `== null`) — unscoped sorts first in the
 *   tab's group order (§4), and an unscoped contributor is by definition always visible, so no
 *   country group could ever add anything a seller wouldn't already see there.
 * - Otherwise, the field renders in the alpha2-first country among every contributing profile's
 *   scope — the "first group by this order: unscoped group, then countries in alpha2 order" rule.
 *
 * The kept [B2bProfileField] instance is the first one encountered (stable profile order, same
 * convention as [de.littletrotter.application.reference.B2bProfileFieldDefinitionService.findLineTemplateFields]) — an inherent ambiguity when two
 * profiles define the same term differently, not something this function can resolve.
 *
 * Two or more profiles contributing the *same* term under *different* country scopes is an edge
 * case (typically one profile = one country scope in practice): the field then renders once, in
 * its assigned group, but [B2bSellerFieldGroup.triggerCountries] still carries every contributing
 * scope, so [isB2bSellerFieldGroupVisible] correctly shows it once the seller matches *any* of
 * them — even a country other than the one named in the group heading.
 */
fun dedupeB2bSellerFieldGroups(contributions: List<Pair<B2bProfileField, Set<Country>>>): List<B2bSellerFieldGroup> =
    contributions
        .groupBy { it.first.term }
        .values
        .map { sameTerm ->
            val field = sameTerm.first().first
            val scopes = sameTerm.map { it.second }
            val anyUnscoped = scopes.any { it.isEmpty() }
            val triggerCountries = scopes.flatten().toSet()
            val group = if (anyUnscoped) null else triggerCountries.minByOrNull { it.alpha2 }
            B2bSellerFieldGroup(field, group, triggerCountries)
        }

/**
 * anbieter-einvoice-ergonomie §4 — whether one deduped [B2bSellerFieldGroup] is visible for the
 * current [sellerCountries]. `group == null` (unscoped) is always visible; otherwise delegates to
 * [isB2bProfileVisibleForSeller] with [triggerCountries] standing in for "the scope of whichever
 * profile(s) contributed this field" — [triggerCountries] is never empty here (a non-`null` group
 * only arises when no contributor is unscoped, see [dedupeB2bSellerFieldGroups]), so this reduces
 * to the same "does the seller match any contributing scope" OR-rule, which may include a country
 * other than [group] itself in the rare multiple-different-scopes case (see that function's KDoc).
 */
fun isB2bSellerFieldGroupVisible(group: Country?, triggerCountries: Set<Country>, sellerCountries: Set<Country>): Boolean =
    group == null || isB2bProfileVisibleForSeller(triggerCountries, sellerCountries)
