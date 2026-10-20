package de.littletrotter.spi.party

import de.littletrotter.shared.country.Country

/**
 * A single value+scheme identifier on a party (e.g. the electronic address: e-mail/ICD value and
 * its scheme like `EM`, `9930`, `0204`). The pair is intentionally one unit: *both blank* means
 * "not set"; *exactly one filled* is a validation error. Encoding that invariant in one type keeps
 * it out of the form-view logic.
 *
 * @param id    stable, module-unique identifier (also the key passed to the read/write accessor on
 *              [PartyFormExtension]).
 * @param i18nKeyPrefix message-bundle key prefix for this pair's labels/helpers. The view appends
 *              a stable suffix; the bundle keys live in the module that contributes the pair.
 * @param hasScheme `true` (default) is the original shape: value **and** scheme are
 *              both editable, exactly like the electronic address (H7). Some identifiers have no scheme choice at all — the scheme is implicit
 *              and fixed by the identifier itself. `false` tells the generic renderer
 *              ([de.littletrotter.ui.component.PartyExtensionsSection]) to show only the value
 *              field; [PartyPairState.schemeId] stays unused (always blank) for such a spec. Additive
 *              with a default that preserves every existing module's current rendering.
 * @param countries the country scope this identifier is restricted to, or `null` (the
 *              default) for country-independent — every pair that already existed (e.g. the
 *              electronic address) keeps that default, so adding this parameter changes no
 *              existing module's behavior. When set, [de.littletrotter.ui.component
 *              .PartyExtensionsSection] shows the pair only while the party's billing-address
 *              country or VAT-id prefix is in this set (`applyCountryContext`) — decision 11's
 *              per-party scope test ("or likewise has a matching VAT ID"), generalized to
 *              an arbitrary *per-spec* country set.
 *
 *              Two SPI shapes were weighed for this: this data field, or an
 *              `appliesTo(kind, country)` method on [PartyFormExtension]. The data field won —
 *              it fits [hasScheme]'s existing shape (declarative render config that
 *              `PartyExtensionsSection` reads directly, no call into the extension needed) and
 *              composes for free with any future country-scoped identifier (just a different
 *              `countries` value, no interface method to override). An `appliesTo` method would
 *              force a round-trip into every registered extension, for every pair, on every
 *              country/VAT-id change, to answer a question the section can already answer from
 *              data it holds — extra indirection whose only justification would be a
 *              per-extension visibility rule more complex than "country membership", which
 *              nothing here needs (today or foreseeably, e.g. a later BE Peppol identifier).
 */
data class PartyIdentifierSpec(
    val id: String,
    val i18nKeyPrefix: String,
    val hasScheme: Boolean = true,
    val countries: Set<Country>? = null,
)

/**
 * The current editable state of a [PartyIdentifierSpec] pair as displayed in (and read back from)
 * the form: the raw value and its scheme/ICD code.
 */
data class PartyPairState(
    val value: String = "",
    val schemeId: String = "",
) {
    /** `true` when neither value nor scheme is entered — the "not set" case. */
    val isBlank: Boolean get() = value.isBlank() && schemeId.isBlank()
}
