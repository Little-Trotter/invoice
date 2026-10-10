package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.exception.DefaultSenderProfileAlreadySetException
import de.littletrotter.domain.exception.DuplicateInvoicePrefixException
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address.AddressType
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.shared.likePattern
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.persistence.LockModeType
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.likeFilter
import jakarta.persistence.Tuple
import org.hibernate.Session

@ApplicationScoped
class PanacheSenderProfileRepository : BasePanacheRepository(), SenderProfileRepository {

    companion object {
        /** ST-B4 — the finding's named unique index (`V1__baseline_schema.sql`). */
        private const val UQ_INVOICE_PREFIX = "uq_sender_profile_invoice_prefix"

        /** ST-1 invariant 1 — the partial unique index backing "only one default sender
         *  profile" (`V1__baseline_schema.sql`). No advisory lock guards this (tried and reverted
         *  — see git history: a lock spanning the whole `SellerProfileService.save` transaction,
         *  which itself nests a `REQUIRES_NEW` sub-transaction for the number-collision retry
         *  loop, serialized every concurrent sender write behind one global lock for the ENTIRE
         *  method body and starved the Agroal connection pool under ST-1's 100-thread load —
         *  observed as `ContextNotActiveException`/JDBC "pool acquisition timeout" across
         *  unrelated ops and a 21s -> 137s runtime blowup). The unique index itself already
         *  serializes the two conflicting writes correctly (Postgres blocks the second `INSERT`/
         *  `UPDATE` on the pending conflicting index entry, then raises the violation once the
         *  first commits); this constant only names it for [save]'s translation below. */
        private const val UQ_SINGLE_DEFAULT = "uq_sender_profile_single_default"

        /** ST-1 delete-vs-use race — `invoice.sender_profile_id`'s auto-named FK
         *  (`V1__baseline_schema.sql`, no `ON DELETE` clause given → Postgres default
         *  `<table>_<column>_fkey`). Fires either direction: an invoice insert/update naming a
         *  meanwhile-deleted sender ([PanacheInvoiceRepository.save]), or, here, a delete racing
         *  a concurrent invoice create/header-update that just committed a reference to this same
         *  row, after [de.littletrotter.application.masterdata.SellerProfileService.delete]'s own
         *  pre-check already passed. */
        private const val FK_INVOICE_SENDER_PROFILE = "invoice_sender_profile_id_fkey"
    }

    @Transactional
    override fun forceVersionIncrement(profile: SenderProfile) {
        em.lock(profile, LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    }

    @Transactional
    override fun findById(id: Long): SenderProfile? {
        val sender = findByIdInternal(id, SenderProfile::class.java)
        // Force-initialize lazy addresses while still in transaction so the UI can
        // access billingAddress (→ getBillingAddress()) without LazyInitializationException.
        sender?.addresses?.size
        return sender
    }

    /**
     * anbieter-improve A1.1 — loads everything the edit dialog reads, in one query and inside the
     * transaction: `addresses` (the source of the derived `billingAddress`) and
     * `defaultPaymentTerm` (`@ManyToOne(LAZY)` since V022 — the relation that made the dialog fail).
     *
     * `findById` above only force-initializes `addresses`, which is why the payment term still
     * arrived as an uninitialized proxy on a detached entity and blew up in the form builder.
     *
     * `invoiceNote` (a `@OneToOne`, not a collection) is `JOIN FETCH`-ed here too, but its
     * own `entries` collection is deliberately *not*: a second `JOIN FETCH`-ed collection
     * alongside `addresses` in the same query would throw `MultipleBagFetchException`. `entries`
     * (and each entry's `language`) are force-initialized below instead, the same trick [findById]
     * already uses for `addresses`.
     */
    @Transactional
    override fun findByIdForEdit(id: Long): SenderProfile? {
        val sender = em.createQuery(
            "SELECT DISTINCT s FROM SenderProfile s " +
                "LEFT JOIN FETCH s.addresses " +
                "LEFT JOIN FETCH s.defaultPaymentTerm " +
                "LEFT JOIN FETCH s.invoiceNote " +
                "WHERE s.id = :id",
            SenderProfile::class.java,
        ).setParameter("id", id).resultList.firstOrNull()
        // LocalizedTextEntry.language is `Language?` at the Kotlin type level, but
        // every entry is only ever constructed through LocalizedTextGroup.addEntry(language:
        // Language, ...), which unconditionally assigns its non-null parameter, and the FK column
        // itself is NOT NULL -- a persisted entry's language can never be null, so the `?.` null
        // arm was unreachable.
        sender?.invoiceNote?.entries?.forEach { it.language!!.code }
        // The edit dialog's payment-term picker labels the loaded default term outside the
        // session (PaymentTerm.nameFor), so its name texts are initialised here as well.
        sender?.defaultPaymentTerm?.nameGroup?.entries?.forEach { it.language!!.code }
        return sender
    }

    override fun findByInvoicePrefix(prefix: String): SenderProfile? =
        em.createQuery("SELECT s FROM SenderProfile s WHERE s.invoicePrefix = :p", SenderProfile::class.java)
            .setParameter("p", prefix)
            .resultList.firstOrNull()

    override fun findDefault(): SenderProfile? =
        findDefaultInternal(SenderProfile::class.java)

    override fun findDefaults(): List<SenderProfile> =
        findDefaultsInternal(SenderProfile::class.java)

    @Transactional
    override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfile> =
        findAllSorted(pageIndex, pageSize, filterText, de.littletrotter.domain.shared.GridSort("", de.littletrotter.domain.shared.SortDirection.ASC))

    @Transactional
    override fun findAllSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: de.littletrotter.domain.shared.GridSort): List<SenderProfile> {
        val (sortField, sortDir) = sort
        val cityFilter = "EXISTS (SELECT a FROM Address a WHERE a.senderProfile = s" +
            " AND a.addressType = :bt AND ${likeFilter("a.city")})"
        val base = StringBuilder("SELECT s FROM SenderProfile s")
        if (!filterText.isNullOrBlank()) base.append(" WHERE ${likeFilter("s.name")} OR $cityFilter")
        base.append(" " + ListSortOrder.senders(sortField, sortDir))
        // SqlSourceToSinkFlow — false positive: every dynamic fragment above is either a
        // compile-time literal (likeFilter's hardcoded column paths) or ListSortOrder.senders'
        // own closed `when` allowlist (an unrecognized sortField falls through to its fixed
        // default ORDER BY, direction only ever contributes its enum .name); filterText itself
        // only ever reaches the query as a bound FILTER_PARAM (":f") value below, never
        // concatenated into the JPQL text.
        val q = em.createQuery(base.toString(), SenderProfile::class.java)
        if (sortField == "cityCountry") q.setParameter("sortBillingType", AddressType.BILLING)
        if (!filterText.isNullOrBlank()) {
            q.setParameter(FILTER_PARAM, likePattern(filterText))
            q.setParameter("bt", AddressType.BILLING)
        }
        val results = q.setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
        // Force-initialize lazy addresses while still in transaction so the grid's
        // label generator can call s.billingAddress (→ getBillingAddress()) without
        // LazyInitializationException.
        results.forEach { it.addresses.size }
        return results
    }

    override fun count(filterText: String?): Long {
        val cityFilter = "EXISTS (SELECT a FROM Address a WHERE a.senderProfile = s" +
            " AND a.addressType = :bt AND ${likeFilter("a.city")})"
        val base = StringBuilder("SELECT COUNT(s) FROM SenderProfile s")
        if (!filterText.isNullOrBlank()) base.append(" WHERE ${likeFilter("s.name")} OR $cityFilter")
        val q = em.createQuery(base.toString(), Long::class.java)
        if (!filterText.isNullOrBlank()) {
            q.setParameter(FILTER_PARAM, likePattern(filterText))
            q.setParameter("bt", AddressType.BILLING)
        }
        return q.singleResult
    }

    // MD2-F2 — deliberately a native query, see the interface KDoc: Hibernate does not
    // auto-flush ahead of native SQL the way it would a JPQL query touching the same entity, so
    // this always reflects the last-flushed row set, never an in-memory-only removal.
    override fun findPersistedAdditionalVatCountries(senderProfileId: Long): Set<Country> {
        // Session.createNativeQuery(sql, Tuple::class.java) uses Hibernate's dedicated
        // NativeQueryTupleTransformer (unlike a plain scalar Class<R> such as Number, which is
        // abstract and has no matching constructor — Hibernate's implicit constructor-result
        // mapping for a non-entity Class<R> then fails at runtime). Tuple.get(index, Class<X>) is
        // a checked type read, not a cast, and keeps the deliberate native-SQL, no-auto-flush
        // semantics from the MD2-F2 comment above.
        val rows = em.unwrap(Session::class.java).createNativeQuery(
            "SELECT country FROM seller_vat_registration WHERE sender_profile_id = :sid",
            Tuple::class.java,
        ).setParameter("sid", senderProfileId).resultList
        return rows.mapNotNull { Country.fromNumeric(it.get(0, Number::class.java).toInt()) }.toSet()
    }

    // "Synchronize number counters to existing data" admin action — see RecipientRepository
    // .findAllCustomerNumbers KDoc for why the interface default body is a fallback only.
    override fun findAllSellerNumbers(): List<String> =
        em.createQuery(
            "SELECT s.sellerNumber FROM SenderProfile s WHERE s.sellerNumber IS NOT NULL AND s.sellerNumber <> ''",
            String::class.java,
        ).resultList

    // ST-B4 — flushes immediately in the caller's own ambient transaction and translates
    // uq_sender_profile_invoice_prefix into DuplicateInvoicePrefixException: the finding fires
    // "under concurrent creation" but an update (editing an existing profile's invoicePrefix into
    // collision with another's) reaches the same constraint too — SellerProfileService.save's
    // update branch first loads the profile being saved inside its own already-open transaction,
    // so this must flush in THAT transaction (see saveEntityFlushingOrThrow's own KDoc), never a
    // REQUIRES_NEW one.
    override fun save(profile: SenderProfile): SenderProfile =
        saveEntityFlushingOrThrow(
            profile, { profile.id },
            UQ_INVOICE_PREFIX to { cause: Throwable -> DuplicateInvoicePrefixException(profile.invoicePrefix, cause) },
            // ST-1 invariant 1 — the ACTUAL fix for the concurrent-default race (see this
            // constant's own KDoc above for why no advisory lock guards it instead): translates
            // the unique index's own violation into a readable, EXPECTED domain exception.
            UQ_SINGLE_DEFAULT to { cause: Throwable ->
                DefaultSenderProfileAlreadySetException(profile.name.ifBlank { profile.id?.toString() ?: "?" }, cause)
            },
        )

    // ST-B4-followup — deliberately NOT [save] above: this is the auto-numbering create path's
    // own entry point (MasterDataNumberService.saveWithNumberCollisionRetry's persistFlushing
    // lambda, via SellerProfileService.save's isNewProfile branch). That retry loop needs a FRESH
    // session for every attempt after a failed flush (see saveEntityFlushing's own KDoc) — the
    // opposite of [save]'s ambient-transaction requirement. The raw, untranslated exception this
    // throws on a constraint violation is exactly what the retry loop's own
    // findConstraintViolation lookup already expects.
    override fun saveAndFlush(profile: SenderProfile): SenderProfile = saveEntityFlushing(profile) { profile.id }

    // ST-1 delete-vs-use race — flushes the removal immediately so a concurrent invoice
    // referencing this sender (committed after SellerProfileService.delete's own "still
    // referenced" pre-check already passed) surfaces here as the SAME readable
    // InvalidParameterException that pre-check throws, not a raw ConstraintViolationException.
    override fun delete(id: Long) {
        deleteByIdFlushingOrThrow(id, SenderProfile::class.java, FK_INVOICE_SENDER_PROFILE to { cause ->
            InvalidParameterException(
                "Anbieter kann nicht gelöscht werden, weil er noch von mindestens einer Rechnung verwendet wird.",
                cause,
            )
        })
    }
}
