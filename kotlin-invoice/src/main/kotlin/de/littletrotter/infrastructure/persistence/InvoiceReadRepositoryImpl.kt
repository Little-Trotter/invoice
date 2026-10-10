package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.JobSummaryDto
import de.littletrotter.application.dto.documentGenerationFailureOf
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.InvoiceReadRepository
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.slf4j.LoggerFactory
import java.time.Duration

@ApplicationScoped
class InvoiceReadRepositoryImpl @Inject constructor(
    private val em: EntityManager,
    private val metrics: InvoiceReadMetrics,
    private val time: TimeSource,
) : InvoiceReadRepository {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun findInvoiceDetailDto(id: Long): InvoiceDetailDto? {
        val start = time.instant()
        try {
            val header = fetchHeaderTuple(id) ?: return null
            val parties = DetailParties(sender = fetchSender(header), recipient = InvoiceDetailPartyFieldMapper.mapRecipient(header))
            val items = fetchItems(id)
            val latestJob = fetchLatestJob(id)
            val dto = buildDetailDto(header, parties, items, latestJob)

            val durationMs = Duration.between(start, time.instant()).toMillis()
            metrics.recordSuccess(durationMs)
            logger.info("findInvoiceDetailDto id={} built in {} ms", id, durationMs)
            return dto
        } catch (e: Exception) {
            // AP-EXC-1: kept broad on purpose — this is an observability wrapper, not error
            // handling: it records the metrics counter and logs with the invoice id as business
            // context, then always rethrows the exact same exception unchanged. Narrowing would
            // serve no purpose since nothing here is actually handled or swallowed.
            metrics.recordFailure()
            logger.warn("failed to build InvoiceDetailDto for id={}: {}", id, e.message)
            throw e
        }
    }

    // Header / parties
    // Every column carries an explicit alias and is read back via tuple.get("alias")
    // instead of a positional index. A `SELECT NEW` constructor expression was not viable here (50
    // arguments plus the converter types this projection relies on — see InvoiceDetailRowSupport's
    // parse* helpers, used by this class's per-group field mappers); aliases are the structural fix
    // for the positional projection that made an insert-in-the-middle shift every following column
    // and sink the fix/mustang-kmu-leitweg branch (/README.md) — inserting a new column here is now
    // harmless as long as it gets its own alias.
    private fun fetchHeaderTuple(id: Long): Tuple? = em.createQuery(
        "SELECT i.id AS id, i.invoiceNumber AS invoiceNumber, i.status AS status, i.invoiceDate AS invoiceDate, " +
        "i.totals.netAmount AS netAmount, i.totals.vatAmount AS vatAmount, i.totals.grossAmount AS grossAmount, " +
        "sp.id AS senderId, sp.name AS senderName, sp.taxNumber AS senderTaxNumber, sp.vatId AS senderVatId, " +
        "sp.email AS senderEmail, sp.phone AS senderPhone, sp.commercialRegisterNumber AS senderCommercialRegisterNumber, " +
        "sp.courtOfJurisdiction AS senderCourtOfJurisdiction, sp.registerCourt AS senderRegisterCourt, sp.iban AS senderIban, " +
        "r.id AS recipientId, r.name AS recipientName, r.customerNumber AS recipientCustomerNumber, r.vatId AS recipientVatId, " +
        "r.email AS recipientEmail, r.phone AS recipientPhone, r.contactPerson AS recipientContactPerson, r.routingId AS recipientRoutingId, " +
        "i.version AS version, i.dueDate AS dueDate, i.servicePeriodFrom AS servicePeriodFrom, i.servicePeriodTo AS servicePeriodTo, " +
        // W7 — COALESCE prefers the frozen payment-term snapshot over the live association's
        // resolved name for an already-SENT invoice (same rule as the overview list's own query,
        // InvoiceOverviewQueryServiceImpl). PaymentTerm.name is
        // no longer a plain column; ptExact/ptFallback (joined below) reproduce PaymentTerm.nameFor's
        // own resolution rule: the exact match for this invoice's own documentLanguage wins, falling
        // back to the fallback-language entry.
        "i.freeText AS freeText, i.invoiceNote AS invoiceNote, i.invoiceNoteOrigin AS invoiceNoteOrigin, " +
        "i.consumerNote AS consumerNote, i.consumerNoteOrigin AS consumerNoteOrigin, " +
        "i.reverseCharge AS reverseCharge, " +
        "COALESCE(i.paymentTermSnapshot.name, ptExact.value, ptFallback.value) AS paymentTermName, " +
        // Skonto — the discount pair from both possible sources plus the presence flag
        // that decides which one wins, so InvoiceDetailCashDiscountFieldMapper can reproduce
        // Invoice.cashDiscount()'s snapshot-wins-over-live rule without loading a domain Invoice.
        "i.paymentTermSnapshot.discountPercent AS snapshotDiscountPercent, i.paymentTermSnapshot.discountDays AS snapshotDiscountDays, " +
        "pt.discountPercent AS termDiscountPercent, pt.discountDays AS termDiscountDays, " +
        "CASE WHEN i.paymentTermSnapshot IS NOT NULL THEN TRUE ELSE FALSE END AS hasPaymentTermSnapshot, " +
        "ba.id AS billingAddressId, ba.street AS billingStreet, ba.postalCode AS billingPostalCode, ba.city AS billingCity, ba.country AS billingCountry, " +
        "da.id AS deliveryAddressId, da.street AS deliveryStreet, da.postalCode AS deliveryPostalCode, da.city AS deliveryCity, da.country AS deliveryCountry, " +
        "i.referenceFields.buyerReference AS buyerReference, i.referenceFields.purchaseOrderReference AS purchaseOrderReference, " +
        "i.referenceFields.orderReference AS orderReference, i.referenceFields.orderReferenceDate AS orderReferenceDate, i.currency AS currency, " +
        "i.referenceFields.projectNumber AS projectNumber, " +
        "sp.managingDirector AS senderManagingDirector, sp.bankName AS senderBankName, sp.bic AS senderBic, " +
        // Fields the UI needs so it never has to reconstruct a domain Invoice
        // (dtoToInvoice, removed) to answer these questions itself.
        "i.documentEngine AS documentEngine, i.validationStatus AS validationStatus, i.invoiceFormat AS invoiceFormat, " +
        "i.sellerVatRegistrationCountry AS sellerVatRegistrationCountry, pt.id AS paymentTermId, rb2b.id AS recipientB2bProfileId, " +
        "r.invoiceFormat AS recipientInvoiceFormat, " +
        "i.typeCode AS typeCode, " +
        // Needed so the storno dialog can decide whether to offer the English reason field
        // without reconstructing a domain Invoice.
        "i.documentLanguage AS documentLanguage, " +
        // The mail send read model, so the detail view's send history needs no call
        // into the mail component on its render path.
        "i.mailSent AS mailSent, i.mailSentAt AS mailSentAt " +
        "FROM Invoice i " +
        "LEFT JOIN i.senderProfile sp " +
        "LEFT JOIN i.recipient r " +
        "LEFT JOIN r.b2bProfile rb2b " +
        "LEFT JOIN i.paymentTerm pt " +
        "LEFT JOIN pt.nameGroup ptng " +
        "LEFT JOIN ptng.entries ptExact ON ptExact.language.code = i.documentLanguage " +
        "LEFT JOIN ptng.entries ptFallback ON ptFallback.language.isFallback = TRUE " +
        "LEFT JOIN i.billingAddress ba " +
        "LEFT JOIN i.deliveryAddress da " +
        "WHERE i.id = :id",
        Tuple::class.java,
    ).setParameter("id", id).resultList.firstOrNull()

    private fun fetchSender(header: Tuple): PartyDto? {
        val senderId = (header.get("senderId") as Number?)?.toLong() ?: return null
        // The seller's additional (non-German) VAT registrations, so the UI can
        // decide FacturXEligibility/the sellerVatRegistrationCountry ComboBox options without ever
        // loading (or constructing) a domain SenderProfile itself.
        val additionalVatCountries = em.createQuery(
            "SELECT svr.country FROM SellerVatRegistration svr WHERE svr.senderProfile.id = :senderId",
        ).setParameter("senderId", senderId).resultList
            // seller_vat_registration.country is a non-nullable, converter-backed Country
            // column, so a JPQL path select of it always comes back as a Country instance with a
            // non-null alpha2 — the `as?`/`?:` fallback was unreachable.
            .map { c -> (c as Country).alpha2 }
        return InvoiceDetailPartyFieldMapper.mapSender(header, senderId, additionalVatCountries)
    }

    private fun fetchItems(id: Long): List<ItemDto> = InvoiceDetailItemFieldMapper.mapAll(
        itemRows = fetchItemRows(id),
        receiptRows = fetchReceiptRows(id),
        travelRows = fetchTravelRows(id),
        expenseRows = fetchExpenseRows(id),
    )

    private fun fetchItemRows(id: Long): List<Tuple> = em.createQuery(
        "SELECT item.id AS id, item.positionId AS positionId, item.description AS description, item.quantity AS quantity, " +
        "item.unit AS unit, item.unitPriceNet AS unitPriceNet, COALESCE(tr.rate, 0) AS taxRate, tr.id AS taxRateRefId, " +
        "item.netAmount AS netAmount, item.vatAmount AS vatAmount, item.grossAmount AS grossAmount, item.version AS version, " +
        "item.category AS category, item.sortOrder AS sortOrder, item.deliveryNoteReference AS deliveryNoteReference, " +
        "item.itemDescription AS itemDescription, ar.id AS articleRefId, item.exemptionReasonText AS exemptionReasonText, " +
        "item.taxLegalBasis AS taxLegalBasis, item.taxCategory AS taxCategory, item.tripDetailedOnInvoice AS tripDetailedOnInvoice, " +
        "item.tripFixedNetPrice AS tripFixedNetPrice, item.servicePeriodFrom AS servicePeriodFrom, item.servicePeriodTo AS servicePeriodTo, " +
        "item.buyerItemDescription AS buyerItemDescription " +
        "FROM Invoice i JOIN i.items item LEFT JOIN item.taxRateRef tr LEFT JOIN item.articleRef ar WHERE i.id = :id ORDER BY item.sortOrder",
        Tuple::class.java,
    ).setParameter("id", id).resultList

    private fun fetchReceiptRows(id: Long): List<Array<*>> = em.createQuery(
        "SELECT item.id, r.id FROM InvoiceItem item LEFT JOIN item.receipts r WHERE item.invoice.id = :id",
    ).setParameter("id", id).resultList.map { it as Array<*> }

    private fun fetchTravelRows(id: Long): List<Array<*>> = em.createQuery(
        "SELECT te.id, te.invoiceItem.id, te.travelDate, te.country, te.isArrivalDeparture, te.breakfastDeduction, te.lunchDeduction, te.dinnerDeduction, te.calculatedAmount, " +
        "te.version, te.fullDayRateSnapshot, te.arrivalDepartureRateSnapshot, te.employerContribution, te.description, te.participant, pr.id, te.shortName " +
        "FROM TravelExpenseEntry te LEFT JOIN te.perDiemRate pr WHERE te.invoiceItem.invoice.id = :id",
    ).setParameter("id", id).resultList.map { it as Array<*> }

    private fun fetchExpenseRows(id: Long): List<Array<*>> = em.createQuery(
        "SELECT te.id, te.invoiceItem.id, te.kind, te.description, te.travelDate, te.quantity, te.unit, te.unitPriceNet, te.netAmount, rcpt.id, te.sortOrder, te.version, te.rateProvided " +
        "FROM TripExpense te LEFT JOIN te.receipt rcpt WHERE te.invoiceItem.invoice.id = :id ORDER BY te.sortOrder",
    ).setParameter("id", id).resultList.map { it as Array<*> }

    // latest generation job
    // S6619 — Tuple with explicit aliases, same reasoning as fetchItemRows above: a keyed
    // Tuple.get(alias) is a genuine Any? read, so resultRefs/kositReport keep an honest safe-call
    // instead of turning an actual null into the literal string "null".
    private fun fetchLatestJob(id: Long): JobSummaryDto? {
        val jobRaw = em.createQuery(
            "SELECT j.id AS id, j.status AS status, j.createdAt AS createdAt, j.resultRefs AS resultRefs, j.kositReport AS kositReport, j.lastError AS lastError " +
            "FROM GenerationJob j WHERE j.invoice.id = :id ORDER BY j.createdAt DESC",
            Tuple::class.java,
        ).setParameter("id", id).setMaxResults(1).resultList.firstOrNull()
        return jobRaw?.let { j ->
            JobSummaryDto(
                id = (j.get("id") as Number).toLong(),
                status = j.get("status").toString(),
                createdAt = j.get("createdAt") as java.time.OffsetDateTime?,
                resultRefs = j.get("resultRefs")?.toString(),
                kositReport = j.get("kositReport")?.toString(),
                lastError = j.get("lastError")?.toString(),
            )
        }
    }

    private fun buildDetailDto(
        header: Tuple,
        parties: DetailParties,
        items: List<ItemDto>,
        latestJob: JobSummaryDto?,
    ): InvoiceDetailDto {
        val (sender, recipient) = parties
        val headerFields = InvoiceDetailHeaderFieldMapper.map(header)
        val totals = InvoiceDetailTotalsFieldMapper.map(header)
        val tax = InvoiceDetailTaxFieldMapper.map(header)
        val texts = InvoiceDetailTextFieldMapper.map(header)
        val billingAddress = InvoiceDetailPartyFieldMapper.mapBillingAddress(header)
        val deliveryAddress = InvoiceDetailPartyFieldMapper.mapDeliveryAddress(header)
        val cashDiscount = InvoiceDetailCashDiscountFieldMapper.map(
            header = header,
            typeCode = headerFields.typeCode,
            invoiceDate = headerFields.invoiceDate,
            grossAmount = totals.grossAmount,
        )
        return InvoiceDetailDto(
            id = headerFields.id,
            invoiceNumber = headerFields.invoiceNumber,
            status = headerFields.status,
            invoiceDate = headerFields.invoiceDate,
            netAmount = totals.netAmount,
            vatAmount = totals.vatAmount,
            grossAmount = totals.grossAmount,
            sender = sender,
            recipient = recipient,
            items = items,
            latestGenerationJob = latestJob,
            version = headerFields.version,
            dueDate = headerFields.dueDate,
            servicePeriodFrom = headerFields.servicePeriodFrom,
            servicePeriodTo = headerFields.servicePeriodTo,
            freeText = texts.freeText,
            invoiceNote = texts.invoiceNote,
            invoiceNoteOrigin = texts.invoiceNoteOrigin,
            consumerNote = texts.consumerNote,
            consumerNoteOrigin = texts.consumerNoteOrigin,
            buyerReference = texts.buyerReference,
            purchaseOrderReference = texts.purchaseOrderReference,
            orderReference = texts.orderReference,
            orderReferenceDate = texts.orderReferenceDate,
            projectNumber = texts.projectNumber,
            currency = totals.currency,
            reverseCharge = tax.reverseCharge,
            paymentTermName = tax.paymentTermName,
            billingAddress = billingAddress,
            deliveryAddress = deliveryAddress,
            documentEngine = headerFields.documentEngine,
            validationStatus = headerFields.validationStatus,
            invoiceFormat = headerFields.invoiceFormat,
            sellerVatRegistrationCountry = tax.sellerVatRegistrationCountry,
            paymentTermId = tax.paymentTermId,
            typeCode = headerFields.typeCode,
            documentLanguage = headerFields.documentLanguage,
            mailSent = headerFields.mailSent,
            mailSentAt = headerFields.mailSentAt,
            cashDiscount = cashDiscount,
            documentGenerationFailure = documentGenerationFailureOf(headerFields.status, latestJob),
        )
    }
}

/** Sender and recipient party of one invoice detail read. */
private data class DetailParties(val sender: PartyDto?, val recipient: PartyDto?)
