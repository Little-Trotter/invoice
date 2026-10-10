package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.spi.masterdata.ArticleRecipientRefOption
import de.littletrotter.spi.masterdata.ArticleRecipientRefsExtension
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * An article's per-recipient identifier mappings (0..n rows: recipient + buyer's
 * article number/name).
 *
 * [recipientSearch]/[recipientCount]/[findRecipientById] are plain lookup functions rather than
 * the [RecipientQueryPort] interface directly, so unit tests can supply a fake without
 * implementing that interface's full surface; the `@Inject` constructor is the CDI-discovered one
 * in production.
 *
 * [recipientSearch]/[recipientCount] ride [RecipientQueryPort.findRecipients]/
 * [RecipientQueryPort.countRecipients]'s server-side token search rather than loading every
 * recipient once into one eager `setItems(...)` call: a page-capped eager load would leave any
 * recipient beyond that page's alphabetical position unable to receive a per-recipient article
 * reference.
 *
 * The CDI constructor takes the narrow [RecipientQueryPort], because this module only ever reads
 * recipients.
 *
 * The mapping rows themselves travel on the article's read model and write command; this module
 * only serves the recipient picker.
 */
@ApplicationScoped
class ArticleRecipientRefsModule(
    private val recipientSearch: (filter: String?, pageIndex: Int, pageSize: Int) -> List<RecipientSummaryDto>,
    private val recipientCount: (String?) -> Long,
    private val findRecipientById: (Long) -> RecipientSummaryDto?,
) : ArticleRecipientRefsExtension {

    @Inject
    constructor(recipientQuery: RecipientQueryPort) : this(
        { filter, pageIndex, pageSize -> recipientQuery.findRecipients(pageIndex, pageSize, filter) },
        recipientQuery::countRecipients,
        recipientQuery::findRecipientById,
    )

    override val id: String = "articleRecipientRefs"

    override fun searchRecipients(filterText: String?, offset: Int, limit: Int): List<ArticleRecipientRefOption> {
        val pageIndex = if (limit == 0) 0 else offset / limit
        return recipientSearch(filterText, pageIndex, limit)
            .map { recipient -> ArticleRecipientRefOption(recipient.id, recipientLabel(recipient)) }
    }

    override fun countRecipients(filterText: String?): Int = recipientCount(filterText).toInt()

    override fun recipientOption(recipientId: Long): ArticleRecipientRefOption? =
        findRecipientById(recipientId)?.let { ArticleRecipientRefOption(recipientId, recipientLabel(it)) }

    private fun recipientLabel(recipient: RecipientSummaryDto): String =
        if (recipient.customerNumber != null) "${recipient.name} – Kd. ${recipient.customerNumber}" else recipient.name
}
