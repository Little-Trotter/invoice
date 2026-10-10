package de.littletrotter.application.masterdata

import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import jakarta.enterprise.context.ApplicationScoped

/**
 * The electronic address (value + ICD scheme) of a party.
 *
 * The seller's address is BT-34, the buyer's is BT-49. Storage ([ElectronicAddress]) and the
 * XRechnung export mapping are handled elsewhere; this module owns only the *frontend visibility
 * and editing* of the pair. The pair invariant (both blank = not set, exactly one filled =
 * validation error) is enforced by [ElectronicAddress.of].
 */
@ApplicationScoped
class ElectronicAddressModule : PartyFormExtension {

    override val id: String = "electronicAddress"

    override fun pairs(kind: PartyKind): List<PartyIdentifierSpec> = listOf(
        PartyIdentifierSpec(
            id = "electronicAddress",
            i18nKeyPrefix = when (kind) {
                PartyKind.SELLER -> "senderProfile.electronicAddress"
                PartyKind.BUYER -> "recipient.electronicAddress"
            },
        ),
    )

    override fun read(party: Any, kind: PartyKind, spec: PartyIdentifierSpec): PartyPairState =
        when (kind) {
            PartyKind.SELLER -> (party as SenderProfile).electronicAddress
            PartyKind.BUYER -> (party as Recipient).electronicAddress
        }?.let { PartyPairState(it.value, it.schemeId) } ?: PartyPairState()

    override fun write(party: Any, kind: PartyKind, spec: PartyIdentifierSpec, state: PartyPairState) {
        val address = if (state.isBlank) null else ElectronicAddress.of(state.value.trim(), state.schemeId.trim())
        when (kind) {
            PartyKind.SELLER -> (party as SenderProfile).electronicAddress = address
            PartyKind.BUYER -> (party as Recipient).electronicAddress = address
        }
    }
}
