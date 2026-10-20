package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.shared.likePattern
import de.littletrotter.domain.repository.MasterdataChangeListener
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.SortDirection
import org.hibernate.exception.ConstraintViolationException

/**
 * Small base providing common JPA boilerplate used across multiple Panache-style repositories.
 * Subclasses should call the protected helpers with the entity class and an id-provider.
 */
abstract class BasePanacheRepository {

    @Inject
    protected lateinit var em: EntityManager

    /** Reports every master data write at the persistence boundary, whichever caller made it. */
    @Inject
    protected lateinit var masterdataChanges: MasterdataChangeListener

    protected fun <E : Any> findByIdInternal(id: Long, entityClass: Class<E>): E? = em.find(entityClass, id)

    @Transactional
    protected fun <E : Any> saveEntity(entity: E, idProvider: () -> Long?): E {
        val saved = when {
            idProvider() == null -> {
                em.persist(entity)
                entity
            }
            // Already managed in the caller's transaction (own insert or load): a merge onto
            // itself would rebuild its owned collections and orphan removal would then delete
            // elements the persistence context no longer knows. Flush cascades the changes.
            em.contains(entity) -> entity
            else -> em.merge(entity) as E
        }
        masterdataChanges.saved(saved)
        return saved
    }

    /**
     * Like [saveEntity], but with an immediate `flush()` in its **own**, new
     * transaction (`REQUIRES_NEW`), not the caller's. Two reasons:
     *
     * 1. `em.persist()`/`merge()` alone only write at some point before transaction end — a
     *    UNIQUE violation would stay invisible until then; the flush surfaces it immediately.
     * 2. If this flush fails, this transaction's Hibernate session becomes unusable for further
     *    write attempts ("Entry ... has a null identifier" on every subsequent `persist`/`flush`
     *    in the same transaction — Hibernate requires a real rollback).
     *    A caller that retries after a failure therefore needs a **fresh** transaction with a
     *    fresh session for the next attempt. The cost is a second pooled connection while the
     *    caller's own transaction is suspended but still holds its connection, so write paths
     *    that need one connection use [saveEntityFlushingInTransaction] instead.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    protected fun <E : Any> saveEntityFlushing(entity: E, idProvider: () -> Long?): E {
        val saved = saveEntity(entity, idProvider)
        em.flush()
        return saved
    }

    /**
     * Like [saveEntity] plus an immediate `flush()` in the caller's transaction: a unique
     * violation surfaces at the call site, and the write shares the caller's connection. After a
     * failed flush the transaction is unusable and must roll back; there is no retry in it.
     */
    protected fun <E : Any> saveEntityFlushingInTransaction(entity: E, idProvider: () -> Long?): E {
        val saved = saveEntity(entity, idProvider)
        em.flush()
        return saved
    }

    @Transactional
    protected fun <E : Any> deleteById(id: Long, entityClass: Class<E>) {
        em.find(entityClass, id)?.let {
            masterdataChanges.deleting(it)
            em.remove(it)
        }
    }

    /**
     * Like [saveEntity], but flushes immediately in the **caller's own (ambient)**
     * transaction and maps one named unique-constraint violation
     * ([ConstraintViolationException.getConstraintName]) to a typed domain exception [onViolation]
     * builds, instead of letting the raw violation escape to the view. Any other failure (a
     * different constraint, or none found in the cause chain) is rethrown unchanged — the DB
     * constraint stays the truth under concurrency; this only translates the one violation the
     * caller names.
     *
     * Deliberately **not** [saveEntityFlushing]'s `REQUIRES_NEW` (found the hard way in a real
     * server run): several callers ([de.littletrotter.application.masterdata.SellerProfileService
     * .save]'s update branch, [de.littletrotter.application.reference.TaxRateService
     * .savePaymentTerm]'s "revoke the old default" loop) first load the entity being saved with a
     * repository call *inside their own already-open transaction*, then pass that same managed
     * instance back into `save`. `REQUIRES_NEW` suspends that transaction and hands the entity to
     * a brand-new session, where it is no longer the *managed* copy — Hibernate treats it as an
     * ordinary detached object and throws `OptimisticLockException` comparing its (unflushed,
     * possibly stale) version against the DB, even though nothing is actually stale. Flushing in
     * the ambient transaction instead keeps the whole call one continuous session, exactly
     * matching `saveEntity`'s pre-existing semantics plus an immediate flush — and correctly rolls
     * back a caller's other pending writes (e.g. the "revoke old default" updates) together with
     * this one if it fails, instead of leaving them separately committed.
     *
     * The broad `catch (ex: Exception)` is deliberate (mirrors
     * [de.littletrotter.application.masterdata.MasterDataNumberService
     * .saveWithNumberCollisionCheck]'s own justification): Panache/Hibernate wraps the
     * underlying `ConstraintViolationException` in different outer exception types depending on
     * the call path, so the exact type reaching here is opaque by design —
     * [findConstraintViolation] walks the cause chain for the one violation this call site knows
     * how to explain; everything else is rethrown unchanged below, never swallowed.
     */
    // Sole catch-all of this flow: see this method's own KDoc above ("The broad
    // catch (ex: Exception) is deliberate").
    protected fun <E : Any> saveEntityFlushingOrThrow(
        entity: E,
        idProvider: () -> Long?,
        uniqueConstraintName: String,
        onViolation: (cause: Throwable) -> RuntimeException,
    ): E = saveEntityFlushingOrThrow(entity, idProvider, uniqueConstraintName to onViolation)

    /**
     * As the 4-parameter [saveEntityFlushingOrThrow] overload above, but for a save that can hit
     * more than one named unique constraint (e.g. [de.littletrotter.infrastructure.persistence
     * .PanacheSenderProfileRepository.save]: `uq_sender_profile_invoice_prefix` AND
     * `uq_sender_profile_single_default`, on the very same flush). [checks] is tried in order;
     * the first name found in [ex]'s cause chain wins. Any violation none of [checks] names is
     * rethrown unchanged, same as the single-constraint overload.
     */
    protected fun <E : Any> saveEntityFlushingOrThrow(
        entity: E,
        idProvider: () -> Long?,
        vararg checks: Pair<String, (cause: Throwable) -> RuntimeException>,
    ): E {
        try {
            val saved = saveEntity(entity, idProvider)
            em.flush()
            return saved
        } catch (ex: Exception) {
            for ((uniqueConstraintName, onViolation) in checks) {
                if (findConstraintViolation(ex, uniqueConstraintName) != null) {
                    // [onViolation] must chain [ex] as
                    // the built exception's cause via its own constructor, not a later `initCause`:
                    // `InvoiceDomainException`'s constructor already passes `cause` (even `null`) to
                    // `RuntimeException`, which locks the cause field; `initCause` afterward throws
                    // `IllegalStateException: Can't overwrite cause`. This is purely for callers that
                    // inspect the cause (logging, another findConstraintViolation walk further up) —
                    // no caller reachable through this method still needs it for retry-decision logic
                    // (that now happens one layer up, against [saveEntityFlushing]'s own untranslated
                    // exception; see [de.littletrotter.infrastructure.persistence
                    // .PanacheRecipientRepository.saveAndFlush]'s own KDoc).
                    throw onViolation(ex)
                }
            }
            throw ex
        }
    }

    /**
     * Delete counterpart to [saveEntityFlushingOrThrow]: flushes the removal immediately in the
     * caller's own ambient transaction and maps one named FK-constraint violation
     * ([ConstraintViolationException.getConstraintName]) to the typed domain exception [checks]
     * name — the row being deleted here was still referenced by a write (e.g. an invoice create/
     * header-update/item-add naming this recipient/sender profile/article) that committed
     * concurrently, AFTER the caller's own service-level "still referenced" pre-check (e.g.
     * [de.littletrotter.application.masterdata.CustomerManagementService.delete]'s
     * `invoiceRepository.findByRecipientId` lookup) had already passed. Without the immediate
     * flush here, the violation would only surface at this transaction's own commit, as a raw,
     * untranslated exception. Any other failure (a different constraint, or none found in the
     * cause chain) is rethrown unchanged.
     */
    // Sole catch-all of this flow: mirrors [saveEntityFlushingOrThrow]'s own KDoc ("The broad
    // catch (ex: Exception) is deliberate") — Panache/Hibernate wraps the underlying
    // ConstraintViolationException in different outer exception types depending on the call path.
    protected fun <E : Any> deleteByIdFlushingOrThrow(
        id: Long,
        entityClass: Class<E>,
        vararg checks: Pair<String, (cause: Throwable) -> RuntimeException>,
    ) {
        try {
            em.find(entityClass, id)?.let {
                masterdataChanges.deleting(it)
                em.remove(it)
            }
            em.flush()
        } catch (ex: Exception) {
            for ((constraintName, onViolation) in checks) {
                if (findConstraintViolation(ex, constraintName) != null) {
                    throw onViolation(ex)
                }
            }
            throw ex
        }
    }

    /** Walks [ex]'s cause chain for a [ConstraintViolationException] naming [name] — Panache/
     *  Hibernate wraps the underlying violation in different outer exception types depending on
     *  the call path (mirrors [de.littletrotter.application.masterdata.MasterDataNumberService
     *  .findConstraintViolation], the pre-existing instance of this same lookup). */
    protected fun findConstraintViolation(ex: Throwable?, name: String): ConstraintViolationException? {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is ConstraintViolationException && cause.constraintName == name) return cause
            cause = cause.cause
        }
        return null
    }

    /**
     * Every caller
     * ([de.littletrotter.infrastructure.persistence.PanacheSenderProfileRepository],
     * [de.littletrotter.infrastructure.persistence.PanacheTaxRateRepository],
     * [de.littletrotter.infrastructure.persistence.PanachePerDiemRateRepository],
     * [de.littletrotter.infrastructure.persistence.PanachePaymentTermRepository]) used to pass in
     * an identical, always-literal `"SELECT x FROM Entity x WHERE x.isDefault = true"` JPQL
     * string — a genuinely static query the former `@Suppress`-guarded taint check could not see
     * was static, since the string arrived through a `String` parameter. Criteria makes the
     * staticness structural instead of asserted: [entityClass] is the only caller-supplied input
     * left, and it can only ever select the fixed `isDefault` boolean column, never compose SQL.
     */
    protected fun <E : Any> findDefaultInternal(entityClass: Class<E>): E? {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(entityClass)
        val root = cq.from(entityClass)
        cq.select(root).where(cb.isTrue(root.get("isDefault")))
        return em.createQuery(cq).setMaxResults(1).resultList.firstOrNull()
    }

    /** As [findDefaultInternal], for the (rare) case of more than one row with `isDefault = true`. */
    protected fun <E : Any> findDefaultsInternal(entityClass: Class<E>): List<E> {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(entityClass)
        val root = cq.from(entityClass)
        cq.select(root).where(cb.isTrue(root.get("isDefault")))
        return em.createQuery(cq).resultList
    }

    /** Resolves a sort field from an allowlist; the direction is already typed ([SortDirection]),
     *  so only its JPQL literal (`.name`) is needed here. */
    protected fun resolveSortExpr(
        sortField: String,
        sortDir: SortDirection,
        allowed: Map<String, String>,
        default: String,
    ): Pair<String, String> {
        val expr = allowed[sortField] ?: default
        return expr to sortDir.name
    }

    /** Binds the standard LIKE filter parameter `:f` if filterText is not blank. */
    protected fun <T> jakarta.persistence.TypedQuery<T>.applyLikeParam(filterText: String?): jakarta.persistence.TypedQuery<T> =
        applyLikeParam(FILTER_PARAM, filterText)

    /** Like [applyLikeParam], but for an explicitly named parameter — the counterpart of
     *  [de.littletrotter.domain.shared.likeFilterNamed] for queries with more than one
     *  independently-bound LIKE filter. */
    protected fun <T> jakarta.persistence.TypedQuery<T>.applyLikeParam(param: String, filterText: String?): jakarta.persistence.TypedQuery<T> {
        if (!filterText.isNullOrBlank()) setParameter(param, likePattern(filterText))
        return this
    }
}
