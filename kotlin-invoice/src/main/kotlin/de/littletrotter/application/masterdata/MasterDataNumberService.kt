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
     * case [saveWithNumberCollisionRetry] already handles by silently allocating the next number
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
        recipient.customerNumberPrefix = allocated.prefix
        recipient.customerNumberSequence = allocated.sequence
        recipient.customerNumber = CustomerNumber(allocated.value)
        return allocated
    }

    fun assignArticleNumber(article: Article, proposedNumber: String?, date: LocalDate): AllocatedNumber? {
        if (!numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)) return null
        // A blank-but-non-null articleNumber is unrepresentable now (ArticleNumber's own
        // init), so the guard only needs a null check, not the former isNullOrBlank().
        if (article.articleNumber != null && (proposedNumber == null || article.articleNumber?.value != proposedNumber)) return null
        val derived = NumberPrefixDeriver.fromData(PREFIX_FIELD_ARTICLE, article.category.name)
        val allocated = numberRanges.allocate(NumberRangeSubject.CATALOGUE, NumberContext(date, dataFieldValue = derived))
        article.articleNumberPrefix = allocated.prefix
        article.articleNumberSequence = allocated.sequence
        article.articleNumber = ArticleNumber(allocated.value)
        return allocated
    }

    fun assignSellerNumber(seller: SenderProfile, proposedNumber: String?, date: LocalDate): AllocatedNumber? {
        if (!numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)) return null
        if (!seller.sellerNumber.isNullOrBlank() && (proposedNumber == null || seller.sellerNumber != proposedNumber)) return null
        val derived = NumberPrefixDeriver.fromData(PREFIX_FIELD_SELLER, seller.name)
        val allocated = numberRanges.allocate(NumberRangeSubject.SELLER, NumberContext(date, sellerId = seller.id, dataFieldValue = derived))
        seller.sellerNumberPrefix = allocated.prefix
        seller.sellerNumberSequence = allocated.sequence
        seller.sellerNumber = allocated.value
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
     * An untouched proposal that is already taken is skipped to the next free number through
     * [isTaken] before the insert, so the usual drift case never makes the database log a
     * unique-violation ERROR; only a genuine race between two inserts still reaches the index
     * and is then resolved by the retry below.
     */
    fun <T> saveCustomerWithNumber(
        recipient: Recipient,
        proposedNumber: String?,
        isTaken: (String) -> Boolean = { false },
        persistFlushing: () -> T,
    ): T {
        val effectiveProposal = skipTakenProposal(recipient, proposedNumber, isTaken)
        return saveWithNumberCollisionRetry(
            check = NumberCollisionCheck(recipient.customerNumber?.value, effectiveProposal, UQ_CUSTOMER_NUMBER),
            reassign = { recipient.customerNumber = null; assignCustomerNumber(recipient) },
            persistFlushing = persistFlushing,
        )
    }

    /** Returns the proposal that is finally inserted: each skipped number is replaced by the next
     *  auto-assigned one, which is still an untouched proposal, so a whole run of taken numbers is
     *  skipped and a later race keeps the retry instead of being reported as a user collision. */
    private fun skipTakenProposal(recipient: Recipient, proposedNumber: String?, isTaken: (String) -> Boolean): String? {
        var proposal = proposedNumber
        repeat(MAX_REASSIGN_ATTEMPTS) {
            val current = recipient.customerNumber?.value
            if (current == null || current != proposal || !isTaken(current)) return proposal
            recipient.customerNumber = null
            assignCustomerNumber(recipient)
            proposal = recipient.customerNumber?.value
        }
        return proposal
    }

    /**
     * Siehe [saveCustomerWithNumber]. A typed number (different from the untouched [proposedNumber])
     * that [isTaken] is rejected before the insert, so the expected conflict never reaches the
     * database; only a genuine race still hits the unique index and the retry/collision handling.
     */
    fun <T> saveArticleWithNumber(
        article: Article,
        proposedNumber: String?,
        isTaken: (String) -> Boolean = { false },
        persistFlushing: () -> T,
    ): T {
        val typed = article.articleNumber?.value
        if (typed != null && typed != proposedNumber && isTaken(typed)) throw NumberCollisionException(typed)
        return saveWithNumberCollisionRetry(
            check = NumberCollisionCheck(article.articleNumber?.value, proposedNumber, UQ_ARTICLE_NUMBER),
            reassign = { article.articleNumber = null; assignArticleNumber(article) },
            persistFlushing = persistFlushing,
        )
    }

    /** Siehe [saveCustomerWithNumber]. */
    fun <T> saveSellerWithNumber(seller: SenderProfile, proposedNumber: String?, persistFlushing: () -> T): T =
        saveWithNumberCollisionRetry(
            check = NumberCollisionCheck(seller.sellerNumber, proposedNumber, UQ_SELLER_NUMBER),
            reassign = { seller.sellerNumber = null; assignSellerNumber(seller) },
            persistFlushing = persistFlushing,
        )

    /**
     * Saves with collision handling for UNIQUE_ONLY ranges: the counter supplies a suggestion,
     * the database index enforces uniqueness. Collision retry: if the current value equals the
     * proposed value (user never entered anything), reassign and retry until a free number
     * persists (bounded by [MAX_REASSIGN_ATTEMPTS]). If user typed or reused a value, throw
     * instead (typed value vs untouched proposal). [persistFlushing] must flush immediately to detect
     * constraint violations.
     *
     * The retry is a loop, not a single attempt, because the counter can lag arbitrarily
     * far behind the persisted rows: bulk-loaded or hand-numbered rows never move the counter,
     * and a peeked proposal consumes nothing, so the first reassignment re-derives the very
     * number that just collided. Each reassignment advances the counter by one
     * (`allocateUniqueOnly`, REQUIRES_NEW — the increment survives the failed flush), so the
     * loop walks forward until it clears the stale block. The bound only guards against a
     * pathologically broken state (e.g. a unique index rejecting everything); hitting it
     * surfaces as [NumberCollisionException] instead of looping forever.
     */
    // Sole catch-all of this flow: see the catch block's own comment below —
    // every exception not matching the specific unique-constraint violation is rethrown
    // unchanged, nothing is ever silently dropped. Split into two functions (this one, and
    // retryWithReassignment below) so each stays under ThrowsCount on its own — see
    // retryWithReassignment's own KDoc.
    /** [currentValue]/[proposedValue]/[uniqueConstraintName] bundled so
     *  [saveWithNumberCollisionRetry] takes one parameter for them instead of three
     *  (LongParameterList). */
    private data class NumberCollisionCheck(
        val currentValue: String?,
        val proposedValue: String?,
        val uniqueConstraintName: String,
    )

    private fun <T> saveWithNumberCollisionRetry(
        check: NumberCollisionCheck,
        reassign: () -> Unit,
        persistFlushing: () -> T,
    ): T {
        val (currentValue, proposedValue, uniqueConstraintName) = check
        try {
            return persistFlushing()
        } catch (ex: Exception) {
            // Not a structural handler, but a generic retry wrapper around a caller-supplied
            // persistFlushing lambda whose actual exception type is opaque here by design
            // (Panache/Hibernate can wrap the underlying ConstraintViolationException in
            // different outer exception types depending on the call path). findConstraintViolation
            // walks ex's own cause chain looking for that one specific violation; every other
            // exception is rethrown unchanged below, so nothing is ever silently dropped.
            if (findConstraintViolation(ex, uniqueConstraintName) == null) throw ex
            if (!(currentValue != null && proposedValue != null && currentValue == proposedValue)) {
                throw NumberCollisionException(currentValue ?: "")
            }
        }
        return retryWithReassignment(proposedValue, uniqueConstraintName, reassign, persistFlushing)
    }

    /** The skip-to-free reassignment loop [saveWithNumberCollisionRetry]'s
     *  own KDoc documents, split out into its own function purely so each of the two functions'
     *  throw expressions stays under the throw-count limit on its own; behaviour is unchanged. */
    private fun <T> retryWithReassignment(
        proposedValue: String?,
        uniqueConstraintName: String,
        reassign: () -> Unit,
        persistFlushing: () -> T,
    ): T {
        repeat(MAX_REASSIGN_ATTEMPTS) {
            reassign()
            try {
                return persistFlushing()
            } catch (ex: Exception) {
                if (findConstraintViolation(ex, uniqueConstraintName) == null) throw ex
            }
        }
        // Provably non-null when this function is reached (saveWithNumberCollisionRetry only
        // calls it past its own "currentValue != null && proposedValue != null" guard), but that
        // guard lives in the caller's own smart-cast scope, not this function's declared
        // (nullable) parameter type -- same defensive `?: ""` fallback saveWithNumberCollisionRetry
        // itself already uses one line above for the equivalent currentValue case.
        throw NumberCollisionException(proposedValue ?: "")
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
         *  [saveWithNumberCollisionRetry]. Generous on purpose: the drift equals the number of
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
