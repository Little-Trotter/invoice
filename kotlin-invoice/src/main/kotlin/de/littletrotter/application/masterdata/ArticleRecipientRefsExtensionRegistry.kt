package de.littletrotter.application.masterdata

import de.littletrotter.spi.masterdata.ArticleRecipientRefsExtension
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * The single point from which the article master-data form obtains the registered
 * [ArticleRecipientRefsExtension] (mirrors [ArticleTaxVariantsExtensionRegistry]'s pattern).
 */
@ApplicationScoped
class ArticleRecipientRefsExtensionRegistry(
    private val extensionList: List<ArticleRecipientRefsExtension>,
) {
    @Inject
    constructor(extensions: Instance<ArticleRecipientRefsExtension>) : this(extensions.toList())

    /** The registered extension, if any — `null` when no such bean is on the classpath. */
    fun enabledExtension(): ArticleRecipientRefsExtension? = extensionList.firstOrNull()
}
