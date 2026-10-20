package de.littletrotter.application.masterdata

import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.reference.CounterSyncResult
import de.littletrotter.domain.reference.NumberCollisionException
import de.littletrotter.domain.reference.NumberContext
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.AllocatedNumber
import de.littletrotter.domain.reference.NumberPrefixDeriver
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.NumberSuffixParser
import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.CustomerNumber
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.exception.ConstraintViolationException
import java.time.LocalDate

/**
 * Orchestrates customer, article, and seller numbers via the number-range service.
 * Separates proposal (peek, non-binding, reversible) from allocation (allocate, consuming).
 * The derived prefix is frozen at allocation time and never changes if data is renamed later.
 * No active range means `null` — user types freely, the normal case.
 */
@ApplicationScoped
class MasterDataNumberService @Inject constructor(
    private val numberRanges: NumberRangeService,
    // Sync-only dependencies (see syncCustomerCounter/syncArticleCounter/syncSellerCounter):
    // reading the persisted number strings is exactly the master-data knowledge this service
    // already has (Article/Recipient/SenderProfile) that NumberRangeService deliberately does not.
    private val recipients: RecipientRepository,
    private val articles: ArticleRepository,
    private val sellerProfiles: SenderProfileRepository,
    private val time: TimeSource,
) {
    // "Today" comes in through overloads, never as a default parameter value `= time.today()`:
    // Kotlin evaluates a default in a static helper against the receiver, and for a CDI client
    // proxy that receiver is the proxy itself, whose [time] field is always null.

    fun proposeCustomer(name: String?): String? = proposeCustomer(name, time.today())

    fun proposeArticle(category: InvoiceItemCategory): String? = proposeArticle(category, time.today())

    fun proposeSeller(sellerName: String): String? = proposeSeller(sellerName, time.today())

    fun assignCustomerNumber(recipient: Recipient, proposedNumber: String? = null): AllocatedNumber? =
        assignCustomerNumber(recipient, proposedNumber, time.today())

    fun assignArticleNumber(article: Article, proposedNumber: String? = null): AllocatedNumber? =
        assignArticleNumber(article, proposedNumber, time.today())

    fun assignSellerNumber(seller: SenderProfile, proposedNumber: String? = null): AllocatedNumber? =
        assignSellerNumber(seller, proposedNumber, time.today())

    fun proposeCustomer(name: String?, date: LocalDate): String? {
        if (!numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)) return null
        val derived = NumberPrefixDeriver.fromData(PREFIX_FIELD_CUSTOMER, name.orEmpty())
        return numberRanges.peek(NumberRangeSubject.CUSTOMER, NumberContext(date, dataFieldValue = derived))
    }

    fun proposeArticle(category: InvoiceItemCategory, date: LocalDate): String? {
        if (!numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)) return null
        val derived = NumberPrefixDeriver.fromData(PREFIX_FIELD_ARTICLE, category.name)
        return numberRanges.peek(NumberRangeSubject.CATALOGUE, NumberContext(date, dataFieldValue = derived))
    }

    fun proposeSeller(sellerName: String, date: LocalDate): String? {
        if (!numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)) return null
        val derived = NumberPrefixDeriver.fromData(PREFIX_FIELD_SELLER, sellerName)
        return numberRanges.peek(NumberRangeSubject.SELLER, NumberContext(date, dataFieldValue = derived))
    }

    /**
     * Case-insensitive collision guard for a customer number [candidate] a caller is
     * about to persist for a NEW recipient, run before the DB's own functional unique index
     * (`uq_recipient_customer_number` on `UPPER(customer_number)`, V1 baseline) ever sees it.
     * [candidate] equal to [proposedValue] is deliberately exempt: that is the untouched-proposal
     * case [saveWithNumberCollisionCheck] already handles by silently allocating the next number
     * — this guard only rejects a value the user actually typed or deliberately kept,
     * mirroring that method's own `currentValue == proposedValue` branch. [lookup] is the
     * case-insensitive DB read ([de.littletrotter.domain.repository.RecipientRepository
     * .findByCustomerNumberIgnoreCase]) — passed in rather than injected because this service has
     * no [de.littletrotter.domain.repository.RecipientRepository] of its own (it also allocates
     * article/seller numbers, which know nothing about recipients).
     */
    fun assertCustomerNumberAvailable(candidate: String, proposedValue: String?, lookup: (String) -> Recipient?) {
        if (candidate.isBlank() || candidate == proposedValue) return
        if (lookup(candidate) != null) throw NumberCollisionException(candidate)
    }

    fun assignCustomerNumber(recipient: Recipient, proposedNumber: String?, date: LocalDate): AllocatedNumber? {
        if (!numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)) return null
        if (recipient.customerNumber != null && (proposedNumber == null || recipient.customerNumber?.value != proposedNumber)) return null
        val derived = NumberPrefixDeriver.fromData(PREFIX_FIELD_CUSTOMER, recipient.name)
        val allocated = numberRanges.allocate(NumberRangeSubject.CUSTOMER, NumberContext(date, dataFieldValue = derived))
        recipient.assignCustomerNumber(allocated.prefix, allocated.sequence, CustomerNumber(allocated.value))
        return allocated
    }

    fun assignArticleNumber(article: Article, proposedNumber: String?, date: LocalDate): AllocatedNumber? {
        if (!numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)) return null
        // A blank-but-non-null articleNumber is unrepresentable now (ArticleNumber's own
        // init), so the guard only needs a null check, not the former isNullOrBlank().
        if (article.articleNumber != null && (proposedNumber == null || article.articleNumber?.value != proposedNumber)) return null
        val derived = NumberPrefixDeriver.fromData(PREFIX_FIELD_ARTICLE, article.category.name)
        val allocated = numberRanges.allocate(NumberRangeSubject.CATALOGUE, NumberContext(date, dataFieldValue = derived))
        article.assignArticleNumber(allocated.prefix, allocated.sequence, ArticleNumber(allocated.value))
        return allocated
    }

    fun assignSellerNumber(seller: SenderProfile, proposedNumber: String?, date: LocalDate): AllocatedNumber? {
        if (!numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)) return null
        if (!seller.sellerNumber.isNullOrBlank() && (proposedNumber == null || seller.sellerNumber != proposedNumber)) return null
        val derived = NumberPrefixDeriver.fromData(PREFIX_FIELD_SELLER, seller.name)
        val allocated = numberRanges.allocate(NumberRangeSubject.SELLER, NumberContext(date, sellerId = seller.id, dataFieldValue = derived))
        seller.assignSellerNumber(allocated.prefix, allocated.sequence, allocated.value)
        return allocated
    }

    /**
     * "Synchronize number counters to existing data" admin maintenance action —
     * computes the highest numeric suffix among every persisted customer number
     * ([NumberSuffixParser]) and raises the CUSTOMER counter to it if it is behind (see
     * [NumberRangeService.syncCounterTo]; never lowers). Heals the drift a bulk import or datagen
     * run leaves behind in one explicit call, instead of relying on
     * [saveCustomerWithNumber]'s per-save skip-to-free retry to walk past every stale number one
     * at a time.
     *
     * numbersync-to-admin-tools — this rule exists TWICE: here (contract-tested by
     * `NumberCounterSyncIT`) and as plain SQL in `admin-tools/scripts/db/number-sync.sh`, the
     * operator-facing admin job that replaced the former System-view Wartung tab. Any change to
     * the sync semantics (suffix parsing in [NumberSuffixParser], row selection in the
     * `findAll*Numbers` repository queries, or the guard/raise logic in
     * [NumberRangeService.syncCounterTo]) MUST be mirrored in that script.
     */
    fun syncCustomerCounter(): CounterSyncResult =
        numberRanges.syncCounterTo(NumberRangeSubject.CUSTOMER, NumberSuffixParser.maxSuffix(recipients.findAllCustomerNumbers()))

    /** Siehe [syncCustomerCounter]. */
    fun syncArticleCounter(): CounterSyncResult =
        numberRanges.syncCounterTo(NumberRangeSubject.CATALOGUE, NumberSuffixParser.maxSuffix(articles.findAllArticleNumbers()))

    /** Siehe [syncCustomerCounter]. */
    fun syncSellerCounter(): CounterSyncResult =
        numberRanges.syncCounterTo(NumberRangeSubject.SELLER, NumberSuffixParser.maxSuffix(sellerProfiles.findAllSellerNumbers()))

    /**
     * Saves a customer with collision handling. [persistFlushing] is left to the caller.
     *
     * Runs in the caller's transaction, so the number counter, the insert and every follow-up
     * write share one connection. A unique violation poisons that transaction and cannot be
     * retried inside it; an untouched proposal that is already taken is therefore skipped to the
     * next free number through [isTaken] before the insert. The counter row lock held until
     * commit serializes concurrent auto-numbering, so only a number written past the counter
     * between check and insert still reaches the index and is reported as a collision.
     */
    fun <T> saveCustomerWithNumber(
        recipient: Recipient,
        proposedNumber: String?,
        isTaken: (String) -> Boolean = { false },
        persistFlushing: () -> T,
    ): T {
        val effectiveProposal = skipTakenProposal(
            ProposalSlot({ recipient.customerNumber?.value }, { recipient.clearCustomerNumber(); assignCustomerNumber(recipient) }),
            proposedNumber,
            isTaken,
        )
        return saveWithNumberCollisionCheck(
            NumberCollisionCheck(recipient.customerNumber?.value, effectiveProposal, UQ_CUSTOMER_NUMBER),
            persistFlushing,
        )
    }

    /** Siehe [saveCustomerWithNumber]. */
    fun <T> saveSellerWithNumber(seller: SenderProfile, proposedNumber: String?, persistFlushing: () -> T): T {
        val taken = sellerProfiles.findAllSellerNumbers().toSet()
        val effectiveProposal = skipTakenProposal(
            ProposalSlot({ seller.sellerNumber }, { seller.clearSellerNumber(); assignSellerNumber(seller) }),
            proposedNumber,
        ) { it in taken }
        return saveWithNumberCollisionCheck(
            NumberCollisionCheck(seller.sellerNumber, effectiveProposal, UQ_SELLER_NUMBER),
            persistFlushing,
        )
    }

    /**
     * Siehe [saveCustomerWithNumber]. A typed number (different from the untouched [proposedNumber])
     * that [isTaken] is rejected before the insert, so the expected conflict never reaches the
     * database; only a genuine race still hits the unique index and is reported as a collision.
     */
    fun <T> saveArticleWithNumber(
        article: Article,
        proposedNumber: String?,
        isTaken: (String) -> Boolean = { false },
        persistFlushing: () -> T,
    ): T {
        val typed = article.articleNumber?.value
        if (typed != null && typed != proposedNumber && isTaken(typed)) throw NumberCollisionException(typed)
        val effectiveProposal = skipTakenProposal(
            ProposalSlot({ article.articleNumber?.value }, { article.clearArticleNumber(); assignArticleNumber(article) }),
            proposedNumber,
            isTaken,
        )
        return saveWithNumberCollisionCheck(
            NumberCollisionCheck(article.articleNumber?.value, effectiveProposal, UQ_ARTICLE_NUMBER),
            persistFlushing,
        )
    }

    /** Read and reassign access to the number field of the entity being saved, so one skip loop
     *  serves customers, articles and sellers. */
    private class ProposalSlot(val current: () -> String?, val reassign: () -> Unit)

    /** Returns the proposal that is finally inserted: each taken untouched proposal is replaced
     *  by the next auto-assigned number, so a whole run of taken numbers is skipped. The counter
     *  can lag arbitrarily far behind the persisted rows (bulk-loaded or hand-numbered rows never
     *  move it, and a peeked proposal consumes nothing), hence a bounded loop, not one attempt. */
    private fun skipTakenProposal(slot: ProposalSlot, proposedNumber: String?, isTaken: (String) -> Boolean): String? {
        var proposal = proposedNumber
        repeat(MAX_REASSIGN_ATTEMPTS) {
            val current = slot.current()
            if (current == null || current != proposal || !isTaken(current)) return proposal
            slot.reassign()
            proposal = slot.current()
        }
        return proposal
    }

    /** [currentValue]/[proposedValue]/[uniqueConstraintName] bundled so
     *  [saveWithNumberCollisionCheck] takes one parameter for them instead of three
     *  (LongParameterList). */
    private data class NumberCollisionCheck(
        val currentValue: String?,
        val proposedValue: String?,
        val uniqueConstraintName: String,
    )

    // Not a structural handler: the persistFlushing lambda's exception type is opaque here by
    // design (Hibernate wraps the ConstraintViolationException differently per call path).
    // findConstraintViolation walks the cause chain for the one named violation; every other
    // exception is rethrown unchanged, nothing is silently dropped.
    private fun <T> saveWithNumberCollisionCheck(check: NumberCollisionCheck, persistFlushing: () -> T): T {
        try {
            return persistFlushing()
        } catch (ex: Exception) {
            if (findConstraintViolation(ex, check.uniqueConstraintName) == null) throw ex
            throw NumberCollisionException(check.currentValue ?: "")
        }
    }

    private fun findConstraintViolation(ex: Throwable?, name: String): ConstraintViolationException? {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is ConstraintViolationException && cause.constraintName == name) return cause
            cause = cause.cause
        }
        return null
    }

    companion object {
        /** Upper bound for the skip-to-free reassignment loop; see
         *  [saveWithNumberCollisionCheck]. Generous on purpose: the drift equals the number of
         *  consecutively taken numbers ahead of the counter (a bulk import can leave dozens). */
        private const val MAX_REASSIGN_ATTEMPTS = 100

        private const val UQ_CUSTOMER_NUMBER = "uq_recipient_customer_number"
        private const val UQ_ARTICLE_NUMBER = "uq_article_article_number"
        private const val UQ_SELLER_NUMBER = "uq_sender_profile_seller_number"

        private val PREFIX_FIELD_CUSTOMER = PrefixDataField.NAME_INITIALS
        private val PREFIX_FIELD_ARTICLE = PrefixDataField.CATALOGUE_CATEGORY
        private val PREFIX_FIELD_SELLER = PrefixDataField.NAME_INITIALS
    }
}
