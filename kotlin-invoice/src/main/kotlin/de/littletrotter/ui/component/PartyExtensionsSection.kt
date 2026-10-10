package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.shared.country.Country
import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import de.littletrotter.application.masterdata.FacturXPartyFormExtension

/**
 * H7 — generic rendering for the value+scheme identifier pairs contributed by the enabled
 * [PartyFormExtension]s. The view only knows *that* a party may have extension pairs; it renders
 * them generically here and delegates the actual read/write to the owning extension, so the
 * storage model (e.g. the electronic address) never leaks into the view.
 *
 * The caller passes whatever [pairs] the registered extensions currently contribute. An empty
 * list renders nothing — the case where no extension is on the classpath.
 */
class PartyExtensionsSection(
    private val kind: PartyKind,
    private val pairs: List<Pair<PartyIdentifierSpec, PartyFormExtension>>,
    private val tr: (String) -> String,
) : VerticalLayout() {

    private val valueFields: MutableMap<PartyIdentifierSpec, TextField> = mutableMapOf()
    private val schemeFields: MutableMap<PartyIdentifierSpec, TextField> = mutableMapOf()

    // One container per spec, kept in the component tree for the section's whole
    // lifetime; [applyCountryContext] only ever toggles its *visibility*, never adds/removes it.
    // A group that got removed on a country change would need extra bookkeeping just to not lose
    // whatever the anwender already typed into it the moment it disappears — keeping it in the
    // tree and hiding it is what makes "hiding never deletes a stored value" trivially true
    // instead of something the view has to remember to preserve.
    private val groups: MutableMap<PartyIdentifierSpec, Component> = mutableMapOf()

    val hasContent: Boolean get() = pairs.isNotEmpty()

    init {
        isPadding = false
        isSpacing = true
        setWidthFull()
        pairs.forEach { (spec, _) ->
            val value = TextField().apply {
                label = tr("${spec.i18nKeyPrefix}.value")
                helperText = tr("${spec.i18nKeyPrefix}.helper")
                if (spec.id == FacturXPartyFormExtension.NAF_ID) {
                    maxLength = NAF_CODE_MAX_LENGTH
                }
            }
            valueFields[spec] = value
            val group = if (spec.hasScheme) {
                val scheme = TextField().apply {
                    label = tr("${spec.i18nKeyPrefix}.scheme")
                    placeholder = tr("${spec.i18nKeyPrefix}.placeholder")
                    maxLength = SCHEME_ID_MAX_LENGTH
                }
                schemeFields[spec] = scheme
                btPairGroup(tr("${spec.i18nKeyPrefix}.heading"), value, scheme)
            } else {
                // No scheme choice at all (e.g. SIRET/NAF): a single value field,
                // no second "schema" text field the anwender would never need to fill in.
                btSingleFieldGroup(tr("${spec.i18nKeyPrefix}.heading"), value)
            }
            groups[spec] = group
            add(group)
        }
    }

    /** Loads every pair's current state from the domain [party] via its owning extension. Still
     *  used by [de.littletrotter.ui.view.masterdata.SenderProfileView] (the seller path keeps
     *  reading off the entity; only the recipient path moved to [readFrom] with a pre-read
     *  [PartyPairState] map). */
    fun readFrom(party: Any) {
        pairs.forEach { (spec, extension) ->
            applyState(spec, extension.read(party, kind, spec))
        }
    }

    /** Loads every pair's current state from a pre-read `spec.id -> state` map (e.g.
     *  [de.littletrotter.application.dto.RecipientDetailDto.partyPairs]) instead of calling back
     *  into the owning extension — the recipient path no longer holds a `Recipient` entity to read
     *  at this point. A spec missing from [pairs] (an extension registered after the map was built)
     *  is treated as "not set", the same [PartyPairState]-blank default [readFrom] would show for
     *  an entity with nothing stored. */
    fun readFrom(pairs: Map<String, PartyPairState>) {
        this.pairs.forEach { (spec, _) ->
            applyState(spec, pairs[spec.id] ?: PartyPairState())
        }
    }

    private fun applyState(spec: PartyIdentifierSpec, state: PartyPairState) {
        // valueFields always has an entry for every spec in `pairs` — built from that very
        // list in init — unlike schemeFields, which only exists for a hasScheme spec.
        valueFields.getValue(spec).value = state.value
        schemeFields[spec]?.value = state.schemeId
    }

    /**
     * Writes every pair's current field state onto the domain [party] via its owning extension.
     *
     * Deliberately unconditional on [applyCountryContext]'s visibility: a pair
     * currently hidden because the country moved away from its scope still writes back whatever
     * its [TextField] holds — untouched since the last [readFrom] unless the anwender edited it —
     * so a stored SIRET/NAF is never silently dropped by, say, a typo in the country field that
     * gets corrected before saving.
     */
    fun writeTo(party: Any) {
        pairs.forEach { (spec, extension) ->
            extension.write(party, kind, spec, currentState(spec))
        }
    }

    /** Same field state as [writeTo], as a `spec.id -> state` map instead of writing through an
     *  owning extension onto a domain object — the recipient path no longer holds a `Recipient`
     *  entity at this point; [de.littletrotter.application.masterdata.ManageRecipient] writes the
     *  pairs onto the entity it loads, via the same [de.littletrotter.spi.party.PartyFormExtension
     *  .write] the entity variant above uses. */
    fun writeToPairs(): Map<String, PartyPairState> = pairs.associate { (spec, _) -> spec.id to currentState(spec) }

    private fun currentState(spec: PartyIdentifierSpec): PartyPairState =
        PartyPairState(valueFields.getValue(spec).value.orEmpty(), schemeFields[spec]?.value.orEmpty())

    /**
     * Recomputes which pairs are visible for the given party country context. Called by
     * the owning view on every relevant field change (billing-address country, general-address
     * country fallback, VAT id) so visibility toggles **reactively**, not only after save/reopen
     * (step 3's acceptance requirement).
     *
     * [PartyIdentifierSpec.countries] `== null` (the default — every pair that already existed,
     * e.g. the electronic address) is always visible, unconditionally: that is the "no
     * existing module changes behavior" default from step 1. A non-`null` set (SIRET/NAF's
     * `{FR}`) is visible when either [billingCountry] or the country prefix of [vatId] is a
     * member — decision 11's "or likewise has a matching VAT ID" test, generalized from
     * `FacturXEligibility`'s *fixed* FR/LU/BE membership question to this spec's own, narrower
     * country set. That's a deliberate, not accidental, difference from `FacturXEligibility`:
     * reusing its fixed-set answer here would make every Factur-X-scope party show SIRET/NAF
     * (including LU/BE parties, for whom SIRET does not exist) instead of only FR ones.
     *
     * Takes plain values, not a `Recipient`/`SenderProfile` — this component is generic over
     * [PartyKind] and must not know either domain type. The owning view resolves "the effective
     * billing country" (e.g. a billing-address override falling back to the general address) and
     * "the current VAT id" itself and passes the result here.
     */
    fun applyCountryContext(billingCountry: Country?, vatId: String?) {
        if (pairs.isEmpty()) return
        val vatCountry = Country.fromVatIdPrefix(vatId)
        pairs.forEach { (spec, _) ->
            val countries = spec.countries
            val visible = countries == null || billingCountry in countries || vatCountry in countries
            // groups always has an entry for every spec in `pairs` — built from that very list in
            // init, one group Div per pair, never removed afterwards (class KDoc).
            groups.getValue(spec).isVisible = visible
        }
    }

    private fun btPairGroup(heading: String, valueField: TextField, schemeField: TextField): Div = Div().apply {
        addClassName("sp-bt-pair-group")
        add(Span(heading).apply { addClassNames("fs-08", "font-weight-semibold", "text-muted") })
        // PO-Feedback 2026-08-25 — the value field carries the electronic address (often an
        // e-mail like "EM:buyer.department@example-company.example" or a Peppol id), the scheme
        // field only a short code ("EM", "0088", "9930"). An even FormLayout split squeezed the
        // value field down to the scheme field's width; a HorizontalLayout with an explicit,
        // narrow scheme width lets the value field take the rest.
        schemeField.width = "7rem"
        add(HorizontalLayout(valueField, schemeField).also {
            it.isSpacing = true
            it.setWidthFull()
            it.setFlexGrow(1.0, valueField)
            it.setFlexGrow(0.0, schemeField)
        })
    }

    /** The [PartyIdentifierSpec.hasScheme] == `false` case: a single value field,
     *  no second column. */
    private fun btSingleFieldGroup(heading: String, valueField: TextField): Div = Div().apply {
        addClassName("sp-bt-pair-group")
        add(Span(heading).apply { addClassNames("fs-08", "font-weight-semibold", "text-muted") })
        add(FormLayout(valueField).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1))
        })
    }
}
