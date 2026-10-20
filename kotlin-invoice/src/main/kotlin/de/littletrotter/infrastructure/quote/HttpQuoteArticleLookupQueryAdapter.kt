package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.outbound.QuoteArticleLookupRemote
import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteArticleOptionView
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Article picker against `GET /quote/api/quotes/{id}/articles`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteArticleLookupQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteArticleLookupRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun articles(quoteId: String, category: InvoiceItemCategory, text: String, limit: Int): List<QuoteArticleOption> =
        apiClient.get(
            QuoteRoutes.of(QuoteApiPaths.ARTICLES, quoteId),
            listOf("category" to category.name, "text" to text, "limit" to limit.toString()),
            "article lookup",
            object : TypeReference<List<QuoteArticleOptionView>>() {},
        ).map(QuoteViewTranslator::toArticleOption)
}
