package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.shared.SortDirection

interface RecipientRepository {
    fun findById(id: Long): Recipient?
    fun findByCustomerNumber(number: String): Recipient?

    /**
     * MD3-F3 — case-insensitive counterpart to [findByCustomerNumber]: matches [number] against
     * `UPPER(customer_number)`, the same rule the DB's functional unique index
     * (`uq_recipient_customer_number`, V1 baseline) enforces. Used by
     * [de.littletrotter.application.masterdata.MasterDataNumberService.assertCustomerNumberAvailable]
     * so a case-differing duplicate is rejected with a readable error before ever reaching that
     * index. Default delegates to the case-sensitive lookup — sufficient for test doubles that
     * never exercise case-insensitivity; [de.littletrotter.infrastructure.persistence
     * .PanacheRecipientRepository] overrides it with a real `UPPER()` comparison.
     */
    fun findByCustomerNumberIgnoreCase(number: String): Recipient? = findByCustomerNumber(number)

    /** Serialises concurrent saves of the same (case-normalised) customer number until the
     *  surrounding transaction ends, so the availability pre-check and the write form one
     *  atomic step. No-op default for test doubles without a database. */
    fun lockCustomerNumber(number: String) {
        // Nothing to lock without a database: a test double runs its saves one after another.
    }
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<Recipient>
    fun count(filterText: String? = null): Long

    /**
     * "Synchronize number counters to existing data" admin action (L4 follow-up) — every
     * persisted, non-blank customer number, feeding
     * [de.littletrotter.domain.reference.NumberSuffixParser.maxSuffix]. Default loads full rows
     * and projects in memory (fine for in-memory test doubles);
     * [de.littletrotter.infrastructure.persistence.PanacheRecipientRepository] overrides it with
     * a lean projection query — production never runs this body.
     */
    fun findAllCustomerNumbers(): List<String> =
        findAll(0, Int.MAX_VALUE).mapNotNull { it.customerNumber?.value }

    fun save(recipient: Recipient): Recipient

    /** Acquires a database-side optimistic force-increment lock for an existing
     *  recipient, so a save that only touches a child row (an Address, via
     *  [de.littletrotter.application.masterdata.AddressManagementService]) still bumps this
     *  aggregate's own version and makes a concurrent second writer fail with
     *  [jakarta.persistence.OptimisticLockException] instead of silently losing one of the two
     *  writes. Mirrors [SenderProfileRepository.forceVersionIncrement] /
     *  [ArticleRepository.forceVersionIncrement]. */
    fun forceVersionIncrement(recipient: Recipient)

    /** Like [save], but with an immediate flush so a UNIQUE violation on
     *  `customer_number` (`uq_recipient_customer_number`, V065) is still visible within the same
     *  transaction — see `MasterDataNumberService.saveWithNumberCollisionRetry`. The default
     *  delegates to [save]: fakes/in-memory implementations without a real DB constraint don't
     *  need the distinction. */
    fun saveAndFlush(recipient: Recipient): Recipient = save(recipient)
    fun delete(id: Long)

    /** Names of every recipient currently assigned [b2bProfileId] as their standing
     *  default, for the delete-guard message. Moved here from [SenderProfileRepository] (decision
     *  12): the B2B profile assignment sits on the customer relationship now, not on the seller,
     *  so that's what a profile deletion must check for outstanding references to. */
    fun findNamesByB2bProfileId(b2bProfileId: Long): List<String>

    /** Usage counts of every B2B profile currently assigned as a recipient's standing
     *  default, keyed by profile id (a profile with no recipients has no entry, i.e. a count of
     *  0). One grouped query backing the whole profile grid's "Verwendung" column — never one
     *  query per row. */
    fun countGroupedByB2bProfile(): Map<Long, Long>

    /** Display names for many recipients in ONE query, keyed by id. `invoice.recipient_id`
     *  is only a foreign key (no persisted `recipient_name` column on `invoice` — see the
     *  baseline's `v_stat_invoice_base` view, which resolves it via a join instead), so
     *  [de.littletrotter.ui.view.journal.composer.HeaderRecipientFamilyComposer]'s "Empfänger
     *  geändert: A → B" sentence needs this lookup. Default is a per-id [findById] fold (fine for
     *  the small hand-written test fakes of this interface); [de.littletrotter.infrastructure
     *  .persistence.PanacheRecipientRepository] overrides it with one batched query. */
    fun findNamesByIds(ids: Collection<Long>): Map<Long, String> =
        ids.mapNotNull { id -> findById(id)?.let { id to it.name } }.toMap()
}
