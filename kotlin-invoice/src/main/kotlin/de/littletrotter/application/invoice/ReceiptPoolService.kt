package de.littletrotter.application.invoice

import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.application.cache.NoopInvoiceCachePort
import de.littletrotter.application.error.ApplicationExceptionTranslator
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.domain.exception.ReceiptLockedByIssuedInvoiceException
import de.littletrotter.domain.exception.ReceiptNotFoundException
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.ReceiptUsage
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.shared.DocumentStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.LocalDate
import java.util.logging.Logger

/**
 * Application service for the receipt pool's CRUD/usage concerns: listing, reading the
 * PDF bytes, editing the mandatory fields, and deleting (guarded by usage).
 */
@ApplicationScoped
class ReceiptPoolService @Inject constructor(
    val receiptRepository: ReceiptRepository,
    val documentStore: DocumentStore,
    private val cache: InvoiceCachePort,
) {

    /** Backwards-compatible constructor for tests that don't provide a cache. */
    constructor(
        receiptRepository: ReceiptRepository,
        documentStore: DocumentStore,
    ) : this(receiptRepository, documentStore, NoopInvoiceCachePort())

    companion object {
        /** [findAll]'s own page size. */
        private const val DEFAULT_LIST_PAGE_SIZE = 60

        private val logger = Logger.getLogger(ReceiptPoolService::class.java.name)
    }

    fun findAll(): List<Receipt> = receiptRepository.findAll(0, DEFAULT_LIST_PAGE_SIZE, null)
    fun findAllSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: GridSort): List<Receipt> =
        receiptRepository.findAllSorted(pageIndex, pageSize, filterText, sort)

    fun findAll(pageIndex: Int, pageSize: Int, filterText: String? = null): List<Receipt> = receiptRepository.findAll(pageIndex, pageSize, filterText)
    fun countAll(filterText: String? = null): Long = receiptRepository.count(filterText)

    fun findById(id: Long): Receipt =
        receiptRepository.findById(id) ?: throw ApplicationExceptionTranslator.from(ReceiptNotFoundException(id))

    /**
     * ST-B2 — a receipt row can outlive its object (deleted directly in RustFS, a failed/partial
     * upload cleanup, a store outage): [documentStore] has no shared checked-exception contract
     * across backends (S3 SDK, local filesystem — see [runDocumentStoreOp]), so whatever that
     * backend throws for a missing key or an unreachable store is translated here to the same
     * domain "not found" [findById] already throws for a missing receipt row (chained as
     * [ReceiptNotFoundException.cause]), instead of letting an infrastructure-specific exception
     * escape to callers such as [de.littletrotter.ui.view.invoice.ReceiptDialog]'s download
     * handlers.
     */
    fun getBytes(receiptId: Long): ByteArray {
        val receipt = findById(receiptId)
        return runDocumentStoreOp(onFailure = { failure ->
            logger.warning("Could not read receipt object from store: receiptId=$receiptId, objectKey=${receipt.objectKey} – ${failure.message}")
            throw ReceiptNotFoundException(receiptId, failure)
        }) { documentStore.get(receipt.objectKey) }
    }

    /**
     * L9 — updates a pool receipt's editable details (description and Belegdatum), both required.
     * fix-receipt-editable-after-invoice-issued: locked once any attached invoice is issued
     * (SENT/CANCELLED) — the receipt is then part of a finalized accounting record. A draft/
     * in-review link does not lock it. receipt-mandatory-fields refuses a blank/missing value
     * unconditionally on every write path.
     */
    @Transactional
    fun updateDetails(receiptId: Long, description: String?, documentDate: LocalDate?) {
        val requiredDescription = requireReceiptDescription(description)
        val requiredDocumentDate = documentDate
            ?: throw ApplicationValidationException(receiptBundleMessage("receipt.error.documentDateRequired"))
        requireEditable(receiptId)
        val receipt = findById(receiptId)
        receipt.description = requiredDescription
        receipt.documentDate = requiredDocumentDate
        receipt.validate()
        receiptRepository.save(receipt)
    }

    /** fix-receipt-editable-after-invoice-issued — refuses once any linked invoice is issued. */
    private fun requireEditable(receiptId: Long) {
        val issuedNumbers = receiptRepository.findReceiptUsage(receiptId)
            .filter { it.invoiceIssued }
            .map { it.invoiceNumber }
            .distinct()
        if (issuedNumbers.isNotEmpty()) {
            throw ReceiptLockedByIssuedInvoiceException(issuedNumbers)
        }
    }

    /** fix-receipt-editable-after-invoice-issued — receipt ids in [receiptIds] whose edit is
     *  currently locked, for the pool's edit-button gate (one batched lookup per page). */
    @Transactional
    fun findLockedReceiptIds(receiptIds: List<Long>): Set<Long> =
        receiptRepository.findReceiptUsageBatch(receiptIds)
            .filterValues { usages -> usages.any { it.invoiceIssued } }
            .keys

    /**
     * V4 — where a receipt is currently used (which invoice numbers, on how many items). Drives
     * both the usage column in the pool / ordering dialog and the delete guard below.
     */
    @Transactional
    fun findReceiptUsage(receiptId: Long): List<ReceiptUsage> =
        receiptRepository.findReceiptUsage(receiptId)

    /** V4 — readable usage summary ("RE-xxxx Position 1", ...) for the UI, so the UI never has to
     *  touch the domain-layer [ReceiptUsage] type directly (architecture rule: ui !-> domain.repository).
     *  belege-improve A4: invoice-level usage reads "RE-xxxx <invoiceLevelLabel>". Both display
     *  texts come **from** the caller — this layer builds the shape, not the wording. */
    @Transactional
    fun findReceiptUsageSummary(receiptId: Long, unusedText: String, invoiceLevelLabel: String): String {
        val usage = receiptRepository.findReceiptUsage(receiptId)
        if (usage.isEmpty()) return unusedText
        return usage.joinToString(", ") { it.summaryLabel(invoiceLevelLabel) }
    }

    /** V4 — whether a receipt may be deleted at all right now, plus the invoice numbers it is
     *  currently assigned to. `usageCount` counts the links across those invoices (n:m), item- AND
     *  invoice-level; any link at all blocks the delete (receipt-delete-guard). A draft carries no
     *  invoice number yet, so [ReceiptDeleteCheck.assignedInvoiceNumbers] holds only the numbers
     *  that actually exist — it names what it can, it is not the count. */
    @Transactional
    fun checkDeleteEligibility(receiptId: Long): ReceiptDeleteCheck {
        val usage = receiptRepository.findReceiptUsage(receiptId)
        return ReceiptDeleteCheck(
            assignedInvoiceNumbers = usage.map { it.invoiceNumber }.filter { it.isNotBlank() }.distinct(),
            usageCount = usage.size,
        )
    }

    /**
     * V4/receipt-delete-guard — deletes a receipt from the pool. Only an unassigned receipt can be
     * deleted: as long as any invoice references it — draft or finalized — the receipt is that
     * invoice's proof and deleting it would take the proof with it, so the delete is refused
     * outright ([ReceiptAssignedToInvoiceException]) instead of unlinking on the way out. Releasing
     * an assignment happens on the invoice itself, which only DRAFT/IN_REVIEW invoices allow.
     */
    // Row and object go in one short transaction under the checksum lock that uploads of the same
    // bytes also take: object keys are content-addressed, so without the lock a concurrent upload
    // could reuse the object this delete is about to remove and end up with a row without object.
    // A store failure is logged as an orphaned-object warning instead of failing the delete; a
    // failed commit after the object delete is healed by the next upload of the same bytes
    // (it rewrites a missing object) and a download meanwhile answers "not available".
    @Transactional
    fun delete(receiptId: Long) {
        val check = checkDeleteEligibility(receiptId)
        if (!check.deletable) {
            throw ReceiptAssignedToInvoiceException(check.assignedInvoiceNumbers, check.usageCount)
        }

        val receipt = findById(receiptId)
        val objectKey = receipt.objectKey
        val checksum = receipt.sha256Checksum
        if (!checksum.isNullOrBlank()) {
            receiptRepository.lockChecksum(checksum)
        }
        receiptRepository.delete(receiptId)
        runDocumentStoreOp(onFailure = { failure ->
            logger.warning("Could not delete object from store: $objectKey – ${failure.message}")
        }) { documentStore.delete(objectKey) }
        if (!checksum.isNullOrBlank()) {
            cache.evictChecksum(checksum)
        }
    }

    /**
     * belege-improve — usage summaries for a whole page of receipts in one query. The pool's
     * usage column called [findReceiptUsageSummary] per rendered row, i.e. one statement per row
     * on every repaint.
     */
    @Transactional
    fun findUsageSummaries(
        receiptIds: List<Long>,
        unusedText: String,
        invoiceLevelLabel: String,
    ): Map<Long, String> {
        val byReceipt = receiptRepository.findReceiptUsageBatch(receiptIds)
        return receiptIds.associateWith { id ->
            val usage = byReceipt[id].orEmpty()
            if (usage.isEmpty()) unusedText
            else usage.joinToString(", ") { it.summaryLabel(invoiceLevelLabel) }
        }
    }
}

/** V4/receipt-delete-guard — outcome of [ReceiptPoolService.checkDeleteEligibility]: the total
 *  number of links a receipt carries (item- AND invoice-level) and the invoice numbers among them
 *  that are already assigned (a draft has none yet). Any link at all makes the receipt undeletable,
 *  so the UI hides its delete affordance on [deletable] and never on the number list. */
data class ReceiptDeleteCheck(
    val assignedInvoiceNumbers: List<String>,
    val usageCount: Int,
) {
    val deletable: Boolean get() = usageCount == 0
}

/** Human-readable label for one [ReceiptUsage] row; used by the pool / picker usage columns.
 *  belege-improve A4: invoice-level usage (no position) reads "RE-xxxx <invoiceLevelLabel>", where
 *  the label is supplied by the caller from `messages.properties` — no display wording in code. */
internal fun ReceiptUsage.summaryLabel(invoiceLevelLabel: String): String =
    if (positionId != null) "$invoiceNumber $positionId" else "$invoiceNumber $invoiceLevelLabel"

/**
 * V4/receipt-delete-guard — a receipt that is assigned to an invoice is that invoice's proof;
 * deleting it would take the proof with it, so the pool refuses the delete for every assignment,
 * draft and finalized alike. The assignment has to be released on the invoice first, which only
 * DRAFT/IN_REVIEW invoices allow.
 *
 * Carries [invoiceNumbers] (empty when every referencing invoice is still a draft, which draws its
 * number only at send time) and [usageCount] as data so the UI can phrase the message itself.
 */
class ReceiptAssignedToInvoiceException(val invoiceNumbers: List<String>, val usageCount: Int) :
    RuntimeException(
        "Receipt is assigned to $usageCount invoice reference(s) and cannot be deleted" +
            invoiceNumbers.joinToString(", ", prefix = ": ") { "#$it" }.takeIf { invoiceNumbers.isNotEmpty() }.orEmpty(),
    )
