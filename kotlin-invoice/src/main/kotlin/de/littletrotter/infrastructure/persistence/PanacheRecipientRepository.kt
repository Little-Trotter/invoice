package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.exception.DuplicateCustomerNumberException
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address.AddressType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.RecipientRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.FlushModeType
import jakarta.persistence.LockModeType
import jakarta.persistence.Tuple
import jakarta.persistence.TypedQuery
import jakarta.transaction.Transactional
import de.littletrotter.domain.shared.likeFilterIndexed
import de.littletrotter.domain.shared.likePattern
import de.littletrotter.domain.shared.filterTokens
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.SortDirection

private const val R_NAME = "r.name"

@ApplicationScoped
class PanacheRecipientRepository : BasePanacheRepository(), RecipientRepository {

    companion object {
        /** Entity-specific allowlist, kept local to this repository and
         *  allocated once instead of on every [findAll] call. */
        private val SORT_ALLOWLIST = mapOf(
            "customerNumber" to "r.customerNumber",
            "name" to R_NAME,
            // Z3 moved city onto the Address entity; a bare `r.city` no longer resolves
            // (UnknownPathException at grid-sort time). Sort by the billing address's city via a
            // scalar subquery — MIN() collapses the at-most-one billing row and keeps recipients
            // without one sortable (NULL orders last/first per direction).
            "city" to "(SELECT MIN(a.city) FROM Address a WHERE a.recipient = r" +
                $$" AND a.addressType = de.littletrotter.domain.masterdata.Address$AddressType.BILLING)",
            "contactPerson" to "r.contactPerson",
        )

        /** ST-B4 — the finding's named unique index (`V1__baseline_schema.sql`, case-insensitive
         *  on `UPPER(customer_number)`). */
        private const val UQ_CUSTOMER_NUMBER = "uq_recipient_customer_number"
        private const val CUSTOMER_NUMBER_LOCK_NAMESPACE = 7101

        /** ST-1 delete-vs-use race — `invoice.recipient_id`'s auto-named FK
         *  (`V1__baseline_schema.sql`, no `ON DELETE` clause given → Postgres default
         *  `<table>_<column>_fkey`). Fires either direction: an invoice insert/update naming a
         *  meanwhile-deleted recipient ([PanacheInvoiceRepository.save]), or, here, a delete
         *  racing a concurrent invoice create/header-update that just committed a reference to
         *  this same row, after [de.littletrotter.application.masterdata.CustomerManagementService
         *  .delete]'s own pre-check already passed. */
        private const val FK_INVOICE_RECIPIENT = "invoice_recipient_id_fkey"
    }

    /** One AND-ed group per token; inside a group the token may match any of the four fields. */
    private fun tokenFilterClause(tokens: List<String>): String =
        tokens.indices.joinToString(" AND ") { i ->
            "(${likeFilterIndexed(R_NAME, i)} OR ${likeFilterIndexed("r.customerNumber", i)}" +
                " OR ${likeFilterIndexed("r.shortHash", i)}" +
                " OR EXISTS (SELECT a FROM Address a WHERE a.recipient = r" +
                " AND a.addressType = :bt AND ${likeFilterIndexed("a.city", i)}))"
        }

    private fun <T> TypedQuery<T>.applyTokenParams(tokens: List<String>): TypedQuery<T> {
        if (tokens.isEmpty()) return this
        tokens.forEachIndexed { i, token -> setParameter("$FILTER_PARAM$i", likePattern(token)) }
        setParameter("bt", AddressType.BILLING)
        return this
    }

    @Transactional
    override fun findById(id: Long): Recipient? {
        val recipient = findByIdInternal(id, Recipient::class.java)
        // Force-initialize lazy addresses while still in transaction so the UI can
        // access billingAddress (→ getBillingAddress()) without LazyInitializationException.
        recipient?.addressList?.size
        // Likewise for the LAZY b2bProfile relation: RecipientView reads it off a
        // detached instance (uiDataFacade.findRecipientById), so it must be initialized here.
        recipient?.b2bProfile?.name
        return recipient
    }

    // :n binds the plain String, not CustomerNumber(number): confirmed empirically
    // against a real Postgres (PanacheRecipientRepositoryQueriesIT) — Hibernate 6 infers a JPQL
    // bind parameter's expected type from the converter's *database* Java type (String), not the
    // converted entity-attribute type, and throws QueryArgumentException on the latter
    // ("Could not convert CustomerNumber to java.lang.String"). The CustomerNumberConverter still
    // applies to the r.customerNumber path on the left of `=` when Hibernate builds the SQL.
    override fun findByCustomerNumber(number: String): Recipient? =
        em.createQuery("SELECT r FROM Recipient r WHERE r.customerNumber = :n", Recipient::class.java)
            .setParameter("n", number)
            .resultList.firstOrNull()

    // MD3-F3 — mirrors the DB's functional unique index (UPPER(customer_number)).
    override fun findByCustomerNumberIgnoreCase(number: String): Recipient? =
        em.createQuery("SELECT r FROM Recipient r WHERE UPPER(r.customerNumber) = UPPER(:n)", Recipient::class.java)
            .setParameter("n", number)
            // The caller's own edit is still pending in the session; auto-flushing it here would
            // hit the unique index before the lookup could report the duplicate.
            .setFlushMode(FlushModeType.COMMIT)
            .resultList.firstOrNull()

    // QOD-3/SqlSourceToSinkFlow — false positive on both [findAll] and [count]: [tokenFilterClause]
    // assembles the WHERE text from compile-time literal fragments (field paths, `:bt`, and the
    // loop-index-derived parameter name `likeFilterIndexed` builds), never from the free-text
    // filter itself — every user-supplied value is bound afterward via [applyTokenParams]'s
    // `setParameter`. Qodana's taint tracker still flags it because `filterText`/`tokens` reach the
    // string builder, even though only their literal-fragment shape (never their content) does.
    @Transactional
    override fun findAll(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<Recipient> {
        val (sortExpr, dir) = resolveSortExpr(sortField, sortDir, SORT_ALLOWLIST, R_NAME)
        val tokens = filterTokens(filterText)
        val base = StringBuilder("SELECT r FROM Recipient r")
        if (tokens.isNotEmpty()) base.append(" WHERE ${tokenFilterClause(tokens)}")
        base.append(" ORDER BY $sortExpr $dir, r.id $dir")
        val q = em.createQuery(base.toString(), Recipient::class.java).applyTokenParams(tokens)
        val results = q.setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
        // Force-initialize lazy addresses while still in transaction so the ComboBox
        // label generator can call r.city (→ getBillingAddress()) without LazyInitializationException.
        results.forEach { it.addressList.size }
        return results
    }

    // QOD-3/SqlSourceToSinkFlow — same false positive as [findAll] above, same justification.
    override fun count(filterText: String?): Long {
        val tokens = filterTokens(filterText)
        val base = StringBuilder("SELECT COUNT(r) FROM Recipient r")
        if (tokens.isNotEmpty()) base.append(" WHERE ${tokenFilterClause(tokens)}")
        val q = em.createQuery(base.toString(), Long::class.java).applyTokenParams(tokens)
        return q.singleResult
    }

    // "Synchronize number counters to existing data" admin action — lean projection query, no
    // full entity load (see the interface KDoc for why the default body is a fallback only).
    // Untyped createQuery (not `String::class.java`): the WHERE literal `''` never goes
    // through CustomerNumberConverter (a literal, not a bind parameter, passes straight through
    // to SQL), but a converter-backed SELECT projection's declared result type is version-
    // sensitive (Hibernate may return the converted CustomerNumber, not the raw String) — the
    // same tolerant `.toString()` unwrap InvoiceReadRepositoryImpl.parseString already relies on.
    override fun findAllCustomerNumbers(): List<String> =
        em.createQuery("SELECT r.customerNumber FROM Recipient r WHERE r.customerNumber <> ''")
            .resultList.map { it.toString() }

    // Two-int advisory key space (namespace, hash) never overlaps the single-key locks used
    // elsewhere. Upper-cased to match the functional unique index.
    override fun lockCustomerNumber(number: String) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:ns, hashtext(upper(:n)))")
            .setParameter("ns", CUSTOMER_NUMBER_LOCK_NAMESPACE)
            .setParameter("n", number)
            .setFlushMode(FlushModeType.COMMIT)
            .singleResult
    }

    // ST-B4 — flushes immediately in the caller's own ambient transaction and translates
    // uq_recipient_customer_number into DuplicateCustomerNumberException; see
    // PanacheSenderProfileRepository.save's own KDoc for why an update call site needs this, not
    // only the auto-numbering create path.
    override fun save(recipient: Recipient): Recipient =
        saveEntityFlushingOrThrow(recipient, { recipient.id }, UQ_CUSTOMER_NUMBER) { cause ->
            DuplicateCustomerNumberException(recipient.customerNumber?.value ?: "", cause)
        }

    // Auto-numbering create path (MasterDataNumberService.saveCustomerWithNumber's persistFlushing
    // lambda): flushes in the caller's transaction so the number allocation, the insert and the
    // follow-up writes share one connection. The raw, untranslated exception on a constraint
    // violation is what the number service's collision check expects.
    override fun saveAndFlush(recipient: Recipient): Recipient = saveEntityFlushingInTransaction(recipient) { recipient.id }

    /** See the interface KDoc. Runs after [save]'s own immediate flush above (same
     *  persistence context/transaction — ST-B4's [saveEntityFlushingOrThrow] no longer suspends
     *  into `REQUIRES_NEW`), so [recipient] is still managed here and this bump is picked up at
     *  the caller's next flush/commit. */
    override fun forceVersionIncrement(recipient: Recipient) {
        em.lock(recipient, LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    }

    // ST-1 delete-vs-use race — flushes the removal immediately so a concurrent invoice
    // referencing this recipient (committed after CustomerManagementService.delete's own
    // "still referenced" pre-check already passed) surfaces here as the SAME readable
    // InvalidParameterException that pre-check throws, not a raw ConstraintViolationException.
    override fun delete(id: Long) {
        deleteByIdFlushingOrThrow(id, Recipient::class.java, FK_INVOICE_RECIPIENT to { cause ->
            InvalidParameterException(
                "Kunde kann nicht gelöscht werden, weil er noch von mindestens einer Rechnung verwendet wird.",
                cause,
            )
        })
    }

    /** See the interface KDoc: one query for every id in [ids], never per row. */
    override fun findNamesByIds(ids: Collection<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return em.createQuery("SELECT r.id, r.name FROM Recipient r WHERE r.id IN :ids")
            .setParameter("ids", ids).resultList.associate { row ->
                val arr = row as Array<*>
                (arr[0] as Number).toLong() to (arr[1] as String)
            }
    }

    override fun findNamesByB2bProfileId(b2bProfileId: Long): List<String> =
        em.createQuery(
            "SELECT r.name FROM Recipient r WHERE r.b2bProfile.id = :id ORDER BY r.name",
            String::class.java,
        ).setParameter("id", b2bProfileId).resultList

    // One GROUP BY query for every profile's usage count at once (B2bProfilesTab's
    // "Verwendung" grid column), instead of calling findNamesByB2bProfileId (or a per-profile
    // COUNT) once per row.
    override fun countGroupedByB2bProfile(): Map<Long, Long> =
        em.createQuery(
            "SELECT r.b2bProfile.id AS profileId, COUNT(r) AS cnt FROM Recipient r" +
                " WHERE r.b2bProfile IS NOT NULL GROUP BY r.b2bProfile.id",
            Tuple::class.java,
        ).resultList.associate { (it.get("profileId") as Long) to (it.get("cnt") as Long) }
}
