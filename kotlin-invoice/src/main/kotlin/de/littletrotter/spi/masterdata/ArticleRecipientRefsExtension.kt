package de.littletrotter.spi.masterdata

/**
 * Extension point for an article's per-recipient identifier mappings: a variable-
 * length (0..n) list of recipient + buyer-article-number/-name rows, docked into the article
 * master-data form. Same repeatable-row shape as [ArticleTaxVariantsExtension], just for a
 * different sub-entity — kept as its own interface for the same reason: a small, concrete
 * contract instead of a leaky abstraction over two unrelated domain shapes.
 */
interface ArticleRecipientRefsExtension {

    /** Stable identifier of this module (module-scope unique). */
    val id: String

    /**
     * Server-side paged/filtered lookup for the recipient picker's
     * [com.vaadin.flow.component.combobox.ComboBox]: an eager, page-capped `setItems(...)` load
     * would leave every recipient beyond the first page's alphabetical position unable to receive
     * a per-recipient article reference. [filterText]/[offset]/[limit] are the same token-search/
     * paging shape the ComboBox's own [de.littletrotter.ui.component.lazyItems] hands in from its
     * `CallbackDataProvider`, riding the same
     * [de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort.findRecipients]
     * search as every other lazy recipient combo. Results are [ArticleRecipientRefOption] rather than
     * [de.littletrotter.domain.masterdata.Recipient] directly — informational for the view; the
     * article save re-validates the chosen recipient. Recipient has no deactivation flag today, so this
     * only ever excludes on [filterText], never on any recipient's own state.
     */
    fun searchRecipients(filterText: String?, offset: Int, limit: Int): List<ArticleRecipientRefOption>

    /** Counterpart to [searchRecipients] — the lazy combo's total row count for the same
     *  [filterText], backing the ComboBox `CallbackDataProvider`'s size query. */
    fun countRecipients(filterText: String?): Int

    /** The picker option for a single, already-known [recipientId] — resolves an existing row's
     *  recipient for pre-selection without paging through the (possibly large) recipient list a
     *  stored id might not even appear on the first page of. `null` when the id no longer resolves
     *  (e.g. the recipient master-data row was deleted after the mapping was created). */
    fun recipientOption(recipientId: Long): ArticleRecipientRefOption?
}

/** A recipient option for the picker: its id plus a human-readable label (name + customer number). */
data class ArticleRecipientRefOption(val recipientId: Long, val label: String)
