package de.littletrotter.application.masterdata

import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import de.littletrotter.application.masterdata.port.inbound.PartyFormExtensionQueryPort

/**
 * The single point from which the master-data (party) forms obtain the registered
 * [PartyFormExtension]s. Any such bean is discovered by CDI simply because it is present on the
 * classpath and contributes unconditionally.
 *
 * The primary constructor resolves the bean list through CDI; the secondary [List]-based one lets
 * unit/Karibu tests wire the registry without a container.
 */
@ApplicationScoped
class PartyFormExtensionRegistry(
    private val extensionList: List<PartyFormExtension>,
) : PartyFormExtensionQueryPort {
    @Inject
    constructor(extensions: Instance<PartyFormExtension>) : this(extensions.toList())

    override fun extensions(): List<PartyFormExtension> = extensionList

    /** All (pair, owning-extension) of every registered extension. */
    override fun enabledAspects(kind: PartyKind): List<Pair<PartyIdentifierSpec, PartyFormExtension>> =
        extensionList.flatMap { extension -> extension.pairs(kind).map { it to extension } }
}
