package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.ValidationStatus
import jakarta.enterprise.context.ApplicationScoped

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

    // The items ARE fetched here — the name promises it, and all 11 production
    // callers (lifecycle, cancellation, generation) read them. Single collection fetch, so no
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
     *  .domain.invoice.Invoice.beginEdit]. `OPTIMISTIC_FORCE_INCREMENT` makes Hibernate issue
     *  that `UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?` unconditionally at
     *  the next flush, so the second writer's own version check fails instead. Mirrors
     *  [PanacheSenderProfileRepository]/[PanacheRecipientRepository]/[PanacheArticleRepository]'s
     *  own `forceVersionIncrement`. */
    override fun forceVersionIncrement(invoice: Invoice) {
        em.lock(invoice, jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    }

    /** IMP-6 — unlike [BasePanacheRepository.saveEntityFlushing], deliberately **without**
     *  `REQUIRES_NEW`: the credit note must be created inside the cancellation call's transaction so
     *  that a later failure rolls it back too. Only the visibility of the index violation is
     *  needed here, not a transaction of its own — the loser of the race throws anyway, rolling
     *  back everything it created. */
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
