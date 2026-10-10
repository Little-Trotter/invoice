package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.invoice.InvoiceOverviewPartyNames
import de.littletrotter.application.invoice.InvoiceOverviewTextStatusFilter
import de.littletrotter.application.invoice.invoiceOverviewDefaultOrder
import de.littletrotter.application.invoice.invoiceOverviewTextAndStatusPredicates
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.repository.InvoiceNumberAssignment
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.shared.likePattern
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.ParameterExpression
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate

// ST-1 delete-vs-use race — invoice/invoice_item's auto-named FKs (`V1__baseline_schema.sql`, no
// `ON DELETE` clause given → Postgres default `<table>_<column>_fkey`): a recipient/sender
// profile/article deleted concurrently, after its own row-level "still referenced"/"in use"
// pre-check passed (the invoice being created/updated here had not committed yet), still
// violates the FK once THIS flush runs. [save] below maps each to the same readable,
// InvoiceDomainException-classified rejection the deleted row's own pre-check would have thrown,
// instead of letting the raw org.hibernate.exception.ConstraintViolationException reach the UI.
private const val FK_INVOICE_RECIPIENT = "invoice_recipient_id_fkey"
private const val FK_INVOICE_SENDER_PROFILE = "invoice_sender_profile_id_fkey"
private const val FK_INVOICE_ITEM_ARTICLE = "invoice_item_article_id_fkey"

@ApplicationScoped
class PanacheInvoiceRepository : BasePanacheRepository(), InvoiceRepository {

    override fun findById(id: Long): Invoice? = findByIdInternal(id, Invoice::class.java)

    /**
     * IMP-5 — the `WHERE` filter must not use the same alias as `JOIN FETCH`.
     * `JOIN FETCH i.items it WHERE it.id = :itemId` looks like it loads "the invoice of the
     * item", but it actually restricts `items` to just that one matching row — every other line
     * item of the same invoice was left out of the loaded collection. Within the same
     * persistence session, Hibernate does not initialize an already-loaded collection a second
     * time: a second call for a DIFFERENT item of the same invoice saw the same, incomplete
     * collection again and did not find its own item in it — even though it had long existed in
     * the database (`ReceiptAssignmentIT`, where exactly two items of the same invoice
     * are looked up one after another).
     *
     * The subquery decides **which** invoice is loaded without touching the `JOIN FETCH`
     * alias — the collection is therefore always populated completely.
     */
    override fun findInvoiceByItemId(itemId: Long): Invoice? =
        em.createQuery(
            """SELECT DISTINCT i FROM Invoice i JOIN FETCH i.items
               WHERE i.id = (SELECT it.invoice.id FROM InvoiceItem it WHERE it.id = :itemId)""",
            Invoice::class.java
        ).setParameter("itemId", itemId).resultList.firstOrNull()

    /** Overrides the interface's per-id fallback (rationale there) with a real single
     *  query, same idiom as [PanacheReceiptRepository.findFileNames]. */
    override fun findItemPositions(itemIds: Collection<Long>): Map<Long, String> {
        if (itemIds.isEmpty()) return emptyMap()
        return em.createQuery("SELECT it.id, it.positionId FROM InvoiceItem it WHERE it.id IN :ids")
            .setParameter("ids", itemIds).resultList.associate { row ->
                val arr = row as Array<*>
                (arr[0] as Number).toLong() to (arr[1] as String)
            }
    }

    // The items ARE fetched here — the name promises it, and all 11 production
    // callers (lifecycle, storno, generation) read them. Single collection fetch, so no
    // MultipleBagFetchException risk; DISTINCT follows findInvoiceByItemId.
    override fun findByIdWithItemsForStatusChange(id: Long): Invoice? {
        em.find(Invoice::class.java, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) ?: return null
        return findByIdWithItems(id)
    }

    override fun findByIdWithItems(id: Long): Invoice? =
        em.createQuery(
            """SELECT DISTINCT i FROM Invoice i
               LEFT JOIN FETCH i.items
               LEFT JOIN FETCH i.senderProfile
               LEFT JOIN FETCH i.recipient
               LEFT JOIN FETCH i.paymentTerm
               LEFT JOIN FETCH i.b2bProfile
               WHERE i.id = :id""",
            Invoice::class.java
        ).setParameter("id", id).resultList.firstOrNull()

    // Real bug, found by this method's first-ever direct test: `i.invoiceNumber` is
    // InvoiceNumberConverter-mapped, so Hibernate requires the bound parameter's own attribute
    // type (InvoiceNumber), not the raw String this used to pass straight through - every call
    // (MailSendResultProjection's own mail-correlation lookup included) threw a QueryArgumentException
    // at runtime instead of ever finding a row.
    override fun findByInvoiceNumber(number: String): Invoice? =
        em.createQuery("SELECT i FROM Invoice i WHERE i.invoiceNumber = :n", Invoice::class.java)
            .setParameter("n", InvoiceNumber(number)).resultList.firstOrNull()

    override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? =
        em.createQuery("SELECT i FROM Invoice i WHERE i.sourceQuoteId = :q", Invoice::class.java)
            .setParameter("q", quoteId).resultList.firstOrNull()

    /** Overrides the interface's generic fallback (rationale there). */
    override fun findByOriginalInvoiceNumber(number: String): List<Invoice> =
        em.createQuery("SELECT i FROM Invoice i WHERE i.originalInvoiceNumber = :n", Invoice::class.java)
            .setParameter("n", number).resultList

    /** Overrides the interface's generic fallback (rationale there) — projects only
     *  `id`/`invoiceNumberSequence` instead of loading whole [Invoice] aggregates. */
    // A JPA constructor expression projects straight into the target type: no
    // Array<Any?> row shape and no unchecked cast to get there.
    override fun findFinalizedInvoiceNumberAssignments(): List<InvoiceNumberAssignment> =
        em.createQuery(
            "SELECT NEW de.littletrotter.domain.repository.InvoiceNumberAssignment(i.id, i.invoiceNumberSequence) " +
                "FROM Invoice i " +
                "WHERE i.status = de.littletrotter.domain.invoice.InvoiceStatus.SENT AND i.invoiceNumberSequence IS NOT NULL",
            InvoiceNumberAssignment::class.java,
        ).resultList

    override fun findAll(pageIndex: Int, pageSize: Int): List<Invoice> =
        em.createQuery(
            "SELECT i FROM Invoice i " +
            "LEFT JOIN FETCH i.senderProfile " +
            "LEFT JOIN FETCH i.recipient " +
            "ORDER BY i.invoiceDate DESC, i.id DESC",
            Invoice::class.java
        )
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    override fun count(): Long =
        em.createQuery("SELECT COUNT(i) FROM Invoice i", Long::class.javaObjectType).singleResult

    override fun countByStatus(status: InvoiceStatus): Long =
        em.createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.status = :s", Long::class.javaObjectType)
            .setParameter("s", status).singleResult

    /**
     * The overview queries are built with the Criteria API — no query string
     * exists for anything to run into; every value is a bind parameter by construction. The
     * `JOIN FETCH` aliases (cast Fetch→Path) remain the same three to-one relations as in the
     * previous JPQL, as do `DISTINCT` and the deterministic ordering.
     *
     * Tried replacing the `Fetch`→`Join` cast with `.get(attributeName)` navigation
     * (assuming Hibernate would resolve it against the already-registered fetch's
     * `NavigablePath`); `PanacheRepositoryCriteriaOverviewIT` proved that assumption wrong on this
     * Hibernate version — `.get(...)` after `.fetch(..., LEFT)` builds its own separate, default
     * (inner-shaped) join instead of reusing the fetch node, silently narrowing the WHERE-clause
     * predicates and dropping fixtures with a null association.
     *
     * [OverviewJoins]/[overviewPredicates] only ever call `.get(...)` on these three
     * (plain [Path] navigation, never a `Join`-specific method), so the fetch's own registered
     * node — Hibernate's `SqmAttributeJoin` implements both `Fetch` and `Join`/`Path` at runtime,
     * see the note above — only needs bridging to `Path<*>`, not the more specific `Join`.
     * A star-projected `Path<*>` target carries no generic argument for the JVM to leave
     * unverified, so the cast is checked (a plain `Fetch` → `Path` interface narrowing,
     * confirmed by Hibernate's node implementing both) — no `@Suppress` needed.
     */
    override fun findOverviewPage(pageIndex: Int, pageSize: Int, filterText: String?, status: InvoiceStatus?): List<Invoice> {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(Invoice::class.java)
        val i = cq.from(Invoice::class.java)

        val recipient = i.fetch<Invoice, Any>("recipient", JoinType.LEFT) as Path<*>
        val seller = i.fetch<Invoice, Any>("selectedSeller", JoinType.LEFT) as Path<*>
        val senderProfile = i.fetch<Invoice, Any>("senderProfile", JoinType.LEFT) as Path<*>

        val (predicates, filterParam) = overviewPredicates(cb, i, OverviewJoins(recipient, seller, senderProfile), InvoiceOverviewTextStatusFilter(filterText, status))
        cq.select(i).distinct(true).where(*predicates.toTypedArray())
        cq.orderBy(invoiceOverviewDefaultOrder(cb, i))

        val q = em.createQuery(cq).bindOverviewFilterParam(filterParam, filterText)
        q.firstResult = pageIndex * pageSize
        q.maxResults = pageSize
        return q.resultList
    }

    override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int): List<Invoice> =
        em.createQuery("SELECT i FROM Invoice i WHERE i.recipient.id = :rid ORDER BY i.invoiceDate DESC, i.id DESC", Invoice::class.java)
            .setParameter("rid", recipientId)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int): List<Invoice> =
        em.createQuery("SELECT i FROM Invoice i WHERE i.senderProfile.id = :sid ORDER BY i.invoiceDate DESC, i.id DESC", Invoice::class.java)
            .setParameter("sid", senderProfileId)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    // MD2-F2 — see InvoiceRepository.countOpenInvoicesForSellerVatCountry's KDoc.
    override fun countOpenInvoicesForSellerVatCountry(senderProfileId: Long, country: Country): Long =
        em.createQuery(
            "SELECT COUNT(i) FROM Invoice i WHERE i.senderProfile.id = :sid AND i.sellerVatRegistrationCountry = :country" +
                " AND i.status NOT IN (:sent, :cancelled, :voided)",
            Long::class.javaObjectType,
        ).setParameter("sid", senderProfileId)
            .setParameter("country", country)
            .setParameter("sent", InvoiceStatus.SENT)
            .setParameter("cancelled", InvoiceStatus.CANCELLED)
            .setParameter("voided", InvoiceStatus.VOIDED)
            .singleResult

    override fun existsByBillingAddressId(addressId: Long): Boolean =
        em.createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.billingAddress.id = :addressId", Long::class.javaObjectType)
            .setParameter("addressId", addressId)
            .singleResult > 0

    override fun existsByDeliveryAddressId(addressId: Long): Boolean =
        em.createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.deliveryAddress.id = :addressId", Long::class.javaObjectType)
            .setParameter("addressId", addressId)
            .singleResult > 0

    override fun existsByB2bProfileId(b2bProfileId: Long): Boolean =
        em.createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.b2bProfile.id = :id", Long::class.javaObjectType)
            .setParameter("id", b2bProfileId)
            .singleResult > 0

    override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(Long::class.javaObjectType)
        val i = cq.from(Invoice::class.java)
        val recipient = i.join<Invoice, Any>("recipient", JoinType.LEFT)
        val seller = i.join<Invoice, Any>("selectedSeller", JoinType.LEFT)
        val senderProfile = i.join<Invoice, Any>("senderProfile", JoinType.LEFT)

        val (predicates, filterParam) = overviewPredicates(cb, i, OverviewJoins(recipient, seller, senderProfile), InvoiceOverviewTextStatusFilter(filterText, status))
        cq.select(cb.countDistinct(i)).where(*predicates.toTypedArray())

        return em.createQuery(cq).bindOverviewFilterParam(filterParam, filterText).singleResult
    }

    /** Shared by [findOverviewPage] and [countForOverview] — one `filterParam?.let { ... }` site
     *  instead of two, so this null-vs-bound branch only needs covering once (it used to
     *  be duplicated per call site, and [findOverviewPage]'s own null-filter arm is deliberately
     *  never exercised against the shared IT database — see [PanacheRepositoryCriteriaOverviewIT]'s
     *  own KDoc for why an unfiltered page read is avoided there). */
    private fun <T> jakarta.persistence.TypedQuery<T>.bindOverviewFilterParam(
        filterParam: ParameterExpression<String>?,
        filterText: String?,
    ): jakarta.persistence.TypedQuery<T> {
        filterParam?.let { setParameter(it, likePattern(filterText!!)) }
        return this
    }

    /** The three to-one joins [overviewPredicates] needs for its name-based free-text filter.
     *  Bundled into one type so that function stays under detekt's `LongParameterList` threshold. */
    private class OverviewJoins(val recipient: Path<*>, val seller: Path<*>, val senderProfile: Path<*>)

    /**
     * Shared WHERE builder for the overview queries. The like-pattern travels as an explicit
     * [ParameterExpression] (bound by the callers with [likePattern]-escaped input, mirroring the
     * former `likeFilter`/ESCAPE contract); the escape character stays `\` so `%`/`_` in user
     * input keep matching literally. Delegates to [invoiceOverviewDefaultOrder]'s sibling
     * [invoiceOverviewTextAndStatusPredicates] — the application-layer overview query
     * ([de.littletrotter.application.invoice.InvoiceOverviewQueryServiceImpl
     * .overviewFilterPredicates]) built the identical filterText/status predicates independently.
     */
    // S6524 — List<Predicate>, not Array<Predicate>: a Kotlin Array is always mutable, so
    // returning one directly would hand every caller a collection they could mutate underneath
    // this method. Callers spread it into the vararg `where(*predicates)` JPA Criteria API with
    // their own `.toTypedArray()` at the point of use instead.
    private fun overviewPredicates(
        cb: CriteriaBuilder,
        i: Path<Invoice>,
        joins: OverviewJoins,
        filter: InvoiceOverviewTextStatusFilter,
    ): Pair<List<Predicate>, ParameterExpression<String>?> {
        val (predicates, filterParam) = invoiceOverviewTextAndStatusPredicates(
            cb, i,
            InvoiceOverviewPartyNames(joins.recipient.get("name"), joins.seller.get("name"), joins.senderProfile.get("name")),
            filter,
        )
        return predicates to filterParam
    }

    // ST-1 delete-vs-use race — flushes immediately in the caller's own ambient transaction (see
    // saveEntityFlushingOrThrow's own KDoc for why not REQUIRES_NEW: create()/updateHeader()/
    // addItem() etc. all load their own managed Invoice inside their own already-open
    // transaction before calling this) so a recipient/sender profile/article deleted
    // concurrently, after its own pre-check passed but before this transaction committed,
    // surfaces here as a readable domain exception instead of a raw ConstraintViolationException
    // at commit.
    override fun save(invoice: Invoice): Invoice =
        saveEntityFlushingOrThrow(
            invoice, { invoice.id },
            FK_INVOICE_RECIPIENT to { cause: Throwable ->
                InvalidParameterException("Der Kunde wurde inzwischen gelöscht.", cause)
            },
            FK_INVOICE_SENDER_PROFILE to { cause: Throwable ->
                InvalidParameterException("Das Anbieterprofil wurde inzwischen gelöscht.", cause)
            },
            FK_INVOICE_ITEM_ARTICLE to { cause: Throwable ->
                InvalidParameterException("Die Leistungsposition wurde inzwischen gelöscht.", cause)
            },
        )

    /** ST-1 invariant 6 fix — an item/trip write (see [de.littletrotter.application.invoice
     *  .InvoiceItemMutationService]/[de.littletrotter.application.invoice.TripService]) mutates
     *  [Invoice.itemList]/[de.littletrotter.domain.invoice.TripExpense], each its own
     *  `@Version`-ed entity on the FK-owning side of a `mappedBy` collection, not a column of
     *  `invoice` itself; when the edit also leaves [Invoice.totals]/`updatedBy` unchanged (e.g. a
     *  description-only edit), Hibernate's dirty check finds no changed column on the `invoice`
     *  row and skips its `UPDATE` — so [Invoice.version] never increments and a concurrent second
     *  writer carrying the same `expectedVersion` is wrongly let through by [de.littletrotter
     *  .domain.invoice.requireExpectedVersion]. `OPTIMISTIC_FORCE_INCREMENT` makes Hibernate issue
     *  that `UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?` unconditionally at
     *  the next flush, so the second writer's own version check fails instead. Mirrors
     *  [PanacheSenderProfileRepository]/[PanacheRecipientRepository]/[PanacheArticleRepository]'s
     *  own `forceVersionIncrement`. */
    @Transactional
    override fun forceVersionIncrement(invoice: Invoice) {
        em.lock(invoice, jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    }

    /** IMP-6 — unlike [BasePanacheRepository.saveEntityFlushing], deliberately **without**
     *  `REQUIRES_NEW`: the credit note must be created inside the storno call's transaction so
     *  that a later failure rolls it back too. Only the visibility of the index violation is
     *  needed here, not a transaction of its own — the loser of the race throws anyway, rolling
     *  back everything it created. */
    @Transactional
    override fun saveAndFlush(invoice: Invoice): Invoice {
        val saved = save(invoice)
        em.flush()
        return saved
    }

    override fun findAllForRecalculation(): List<Invoice> =
        em.createQuery(
            "SELECT i FROM Invoice i LEFT JOIN FETCH i.items WHERE i.needsRecalculation = true",
            Invoice::class.java,
        ).resultList

    /** Scalar projection — deliberately not `SELECT i`, see the interface method's KDoc. */
    override fun findAllIdsForRecalculation(): List<Long> =
        em.createQuery(
            "SELECT i.id FROM Invoice i WHERE i.needsRecalculation = true ORDER BY i.id",
            Long::class.javaObjectType,
        ).resultList.map { it.toLong() }

    /**
     * DocumentJobPublisher-concurrency-fix — the real bulk `UPDATE`, deliberately without an
     * `AND i.version = :version` predicate (see the interface method's own KDoc for why bypassing
     * optimistic locking is correct for this one column). Hibernate does not check or bump
     * `@Version` for a bulk JPQL `UPDATE` at all — this is the standard, supported way to opt one
     * write out of optimistic locking, the same technique [PanacheGenerationJobRepository
     * .tryMarkDelegated][de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository
     * .tryMarkDelegated] already uses for `GenerationJob.status`.
     *
     * WHERE-clause audit: unlike [PanacheGenerationJobRepository]'s bulk updates, this
     * statement carries no `AND i.validationStatus = ...` guard at all, and none is needed —
     * [status] is always [de.littletrotter.domain.shared.ValidationStatus.PENDING] at this one
     * call site ([de.littletrotter.application.invoice.DocumentJobPublisher
     * .publishTransactional]), and every [de.littletrotter.domain.shared.ValidationStatus] value,
     * PENDING included, legally transitions to PENDING (see [de.littletrotter.domain.shared
     * .ValidationStatus.canTransitionTo]'s own KDoc for the full edge table and why PENDING has a
     * legal self-edge here). An unguarded `WHERE id = :id` can therefore never produce an illegal
     * edge for this call.
     */
    override fun updateValidationStatus(id: Long, status: ValidationStatus): Boolean {
        val updated = em.createQuery("UPDATE Invoice i SET i.validationStatus = :status WHERE i.id = :id")
            .setParameter("status", status)
            .setParameter("id", id)
            .executeUpdate()
        return updated > 0
    }

    /**
     * DocumentJobPublisher-concurrency-fix — see the interface method's own KDoc for the full
     * rationale. `em.contains` is a cheap, no-round-trip identity check against the current
     * persistence context; a genuinely detached [invoice] (e.g. the UI's own copy in
     * `InvoiceExportPanel`) is left untouched, since there is no dirty-checking session tracking
     * it to protect against in the first place.
     */
    override fun detachIfManaged(invoice: Invoice) {
        if (em.contains(invoice)) em.detach(invoice)
    }
}
