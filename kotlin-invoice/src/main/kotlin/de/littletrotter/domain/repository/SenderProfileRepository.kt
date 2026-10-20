package de.littletrotter.domain.repository

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SenderProfile

interface SenderProfileRepository {
    fun findById(id: Long): SenderProfile?

    /**
     * MD2-F2 — the countries of [senderProfileId]'s `additionalVatRegistrations` exactly as
     * currently PERSISTED, independent of any not-yet-flushed in-memory change. A plain
     * `findById(senderProfileId)?.additionalVatRegistrations` read is not safe for this purpose: a
     * caller operating within one transaction (read, mutate via
     * [de.littletrotter.domain.masterdata.SenderProfile.updateVatRegistrations], then
     * save) hands [de.littletrotter.application.masterdata.SellerProfileService.save] the very
     * same managed entity its own internal `findById` would return from the persistence context's
     * first-level cache — already mutated, so the "before" state would already be gone.
     * [de.littletrotter.infrastructure.persistence.PanacheSenderProfileRepository] overrides this
     * with a native query, which Hibernate does not auto-flush ahead of, so it always reflects the
     * last-flushed row set rather than an unflushed in-memory removal. Default delegates to
     * [findById] — sufficient for in-memory test doubles, which have no such flush-timing hazard.
     */
    fun findPersistedAdditionalVatCountries(senderProfileId: Long): Set<Country> =
        findById(senderProfileId)?.additionalVatRegistrations?.map { it.country }?.toSet() ?: emptySet()

    /** anbieter-improve A1.1 — the editor's load path: one query that brings the relations the
     *  form reads (`addresses`, from which `billingAddress` is derived, and `defaultPaymentTerm`).
     *  Deliberately separate from [findAll]: the grid must stay lean, a JOIN FETCH there would
     *  load both relations for every listed row. */
    fun findByIdForEdit(id: Long): SenderProfile?
    /** Exact (case-sensitive, like `uq_sender_profile_invoice_prefix`) lookup used as the app-level
     *  pre-check so a duplicate prefix never reaches the DB constraint. */
    fun findByInvoicePrefix(prefix: String): SenderProfile? =
        findAll(0, Int.MAX_VALUE).firstOrNull { it.invoicePrefix == prefix }
    fun findDefault(): SenderProfile?
    fun findDefaults(): List<SenderProfile>
    fun findAll(pageIndex: Int = 0, pageSize: Int = 60, filterText: String? = null): List<SenderProfile>
    /** Sort the complete result before pagination. */
    fun findAllSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: de.littletrotter.domain.shared.GridSort): List<SenderProfile> =
        findAll(pageIndex, pageSize, filterText)
    fun count(filterText: String? = null): Long

    /** Siehe [de.littletrotter.domain.repository.RecipientRepository.findAllCustomerNumbers] —
     *  same purpose and same fallback-default reasoning, for seller numbers. */
    fun findAllSellerNumbers(): List<String> =
        findAll(0, Int.MAX_VALUE).mapNotNull { it.sellerNumber }.filter { it.isNotBlank() }

    fun save(profile: SenderProfile): SenderProfile

    /** Acquires a database-side optimistic force-increment lock for an existing profile. */
    fun forceVersionIncrement(profile: SenderProfile)

    /** Like [save], but with an immediate flush so a UNIQUE violation on
     *  `seller_number` (`uq_sender_profile_seller_number`) is still visible within the same
     *  transaction — see `MasterDataNumberService.saveWithNumberCollisionCheck`. The default
     *  delegates to [save]: fakes/in-memory implementations without a real DB constraint don't
     *  need the distinction. */
    fun saveAndFlush(profile: SenderProfile): SenderProfile = save(profile)
    fun delete(id: Long)
}
