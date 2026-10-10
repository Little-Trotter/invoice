package de.littletrotter.application.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import jakarta.enterprise.context.ApplicationScoped

/**
 * Ardennes-X: SIRET and NAF-code on the buyer. Modeled on
 * [ElectronicAddressModule], with one structural difference: SIRET and NAF are **single**
 * values, not value+scheme pairs — the scheme is implicit and fixed (SIRET is SIRET, NAF is NAF),
 * so both specs set [PartyIdentifierSpec.hasScheme] = `false` and the generic renderer shows only
 * the value field.
 *
 * No seller pairs: the German anbieter never needs a SIRET (only the French recipient does), so
 * [pairs] returns an empty list for [PartyKind.SELLER] — the same "nothing to contribute for this
 * kind" shape [PartyFormExtension] already allows any module to use.
 */
@ApplicationScoped
class FacturXPartyFormExtension : PartyFormExtension {

    override val id: String = "facturX"

    override fun pairs(kind: PartyKind): List<PartyIdentifierSpec> = when (kind) {
        PartyKind.SELLER -> emptyList()
        PartyKind.BUYER -> listOf(SIRET_SPEC, NAF_SPEC)
    }

    override fun read(party: Any, kind: PartyKind, spec: PartyIdentifierSpec): PartyPairState {
        if (kind != PartyKind.BUYER) return PartyPairState()
        val recipient = party as Recipient
        val value = when (spec.id) {
            SIRET_ID -> recipient.siret
            NAF_ID -> recipient.nafCode
            else -> null
        }
        return PartyPairState(value.orEmpty())
    }

    override fun write(party: Any, kind: PartyKind, spec: PartyIdentifierSpec, state: PartyPairState) {
        if (kind != PartyKind.BUYER) return
        val recipient = party as Recipient
        val trimmed = state.value.trim().ifBlank { null }
        when (spec.id) {
            SIRET_ID -> {
                if (trimmed != null && !Recipient.isValidSiret(trimmed)) {
                    throw InvalidParameterException(
                        "SIRET muss aus genau 14 Ziffern bestehen (Art. 289 CGI): '$trimmed'.",
                    )
                }
                recipient.siret = trimmed
            }
            NAF_ID -> {
                if (trimmed != null && !Recipient.isValidNafCode(trimmed)) {
                    throw InvalidParameterException(
                        "NAF-Code muss aus 4 Ziffern und einem Buchstaben bestehen, z. B. '6201Z': '$trimmed'.",
                    )
                }
                recipient.nafCode = trimmed
            }
        }
    }

    companion object {
        const val SIRET_ID = "siret"
        const val NAF_ID = "nafCode"

        // SIRET/NAF are purely French identifiers (Art. 289 CGI is a French statute); their
        // own scope is `{FR}`, not the broader FR/LU/BE Factur-X scope of decision 11 — that
        // broader set governs whether Factur-X is offered at all
        // ([de.littletrotter.domain.compliance.FacturXEligibility]), a different question from
        // which *country* a given identifier belongs to.
        val SIRET_SPEC = PartyIdentifierSpec(id = SIRET_ID, i18nKeyPrefix = "recipient.facturX.siret", hasScheme = false, countries = setOf(Country.FR))
        val NAF_SPEC = PartyIdentifierSpec(id = NAF_ID, i18nKeyPrefix = "recipient.facturX.nafCode", hasScheme = false, countries = setOf(Country.FR))
    }
}
