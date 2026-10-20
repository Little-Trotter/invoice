package de.littletrotter.application.masterdata

import de.littletrotter.spi.masterdata.ArticleTaxVariantsExtension
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import de.littletrotter.application.masterdata.port.inbound.ArticleTaxVariantsExtensionQueryPort

/**
 * The single point from which the article master-data form obtains the registered
 * [ArticleTaxVariantsExtension] (mirrors [PartyFormExtensionRegistry]'s pattern and
 * [SellerVatRegistrationsExtensionRegistry]'s own one).
 */
@ApplicationScoped
class ArticleTaxVariantsExtensionRegistry(
    private val extensionList: List<ArticleTaxVariantsExtension>,
) : ArticleTaxVariantsExtensionQueryPort {
    @Inject
    constructor(extensions: Instance<ArticleTaxVariantsExtension>) : this(extensions.toList())

    /** The registered extension, if any — `null` when no such bean is on the classpath. */
    override fun enabledExtension(): ArticleTaxVariantsExtension? = extensionList.firstOrNull()
}
