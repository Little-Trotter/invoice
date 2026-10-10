package de.littletrotter.application.masterdata

import de.littletrotter.spi.party.SellerVatRegistrationsExtension
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * The single point from which the sender master-data form obtains the registered
 * [SellerVatRegistrationsExtension] (mirrors [PartyFormExtensionRegistry]'s pattern).
 */
@ApplicationScoped
class SellerVatRegistrationsExtensionRegistry(
    private val extensionList: List<SellerVatRegistrationsExtension>,
) {
    @Inject
    constructor(extensions: Instance<SellerVatRegistrationsExtension>) : this(extensions.toList())

    /** The registered extension, if any — `null` when no such bean is on the classpath. At most
     *  one is expected in practice, same assumption the electronic-address module makes for its
     *  own extension point. */
    fun enabledExtension(): SellerVatRegistrationsExtension? = extensionList.firstOrNull()
}
