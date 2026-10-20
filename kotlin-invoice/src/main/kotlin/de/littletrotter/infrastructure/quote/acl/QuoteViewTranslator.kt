package de.littletrotter.infrastructure.quote.acl

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.QuoteUnknownValueException
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryVersion
import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteB2bValue
import de.littletrotter.application.quote.port.inbound.QuoteCustomerOption
import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteHeaderField
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrder
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderStatus
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteKpis
import de.littletrotter.application.quote.port.inbound.QuoteLogEntry
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuotePage
import de.littletrotter.application.quote.port.inbound.QuoteParty
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteQuarterTotals
import de.littletrotter.application.quote.port.inbound.QuoteRankingRow
import de.littletrotter.application.quote.port.inbound.QuoteSettings
import de.littletrotter.application.quote.port.inbound.QuoteStatistics
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteStatusCounts
import de.littletrotter.application.quote.port.inbound.QuoteSummary
import de.littletrotter.application.quote.port.inbound.QuoteTotals
import de.littletrotter.application.quote.port.inbound.QuoteTrendPoint
import de.littletrotter.application.quote.port.inbound.QuoteValidationReport
import de.littletrotter.application.quote.port.inbound.QuoteVatGroup
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile
import de.littletrotter.application.quote.port.inbound.StoredQuoteReduction
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.quote.AttachmentLibraryEntryView
import de.littletrotter.shared.quote.AttachmentLibraryVersionView
import de.littletrotter.shared.quote.QuoteArticleOptionView
import de.littletrotter.shared.quote.QuoteAttachmentView
import de.littletrotter.shared.quote.QuoteB2bValueView
import de.littletrotter.shared.quote.QuoteCustomerOptionView
import de.littletrotter.shared.quote.QuoteDashboardView
import de.littletrotter.shared.quote.QuoteDetailView
import de.littletrotter.shared.quote.QuoteDispatchHandoverView
import de.littletrotter.shared.quote.QuoteDocumentView
import de.littletrotter.shared.quote.QuoteIdAnswer
import de.littletrotter.shared.quote.QuoteHeaderFieldView
import de.littletrotter.shared.quote.QuoteInvoiceOrderView
import de.littletrotter.shared.quote.QuoteItemView
import de.littletrotter.shared.quote.QuoteKpisView
import de.littletrotter.shared.quote.QuoteLogEntryView
import de.littletrotter.shared.quote.QuotePageView
import de.littletrotter.shared.quote.QuotePartyView
import de.littletrotter.shared.quote.QuoteRankingRowView
import de.littletrotter.shared.quote.QuoteReductionAnswer
import de.littletrotter.shared.quote.QuoteSettingsView
import de.littletrotter.shared.quote.QuoteStatisticsView
import de.littletrotter.shared.quote.QuoteSummaryView
import de.littletrotter.shared.quote.QuoteTotalsView
import de.littletrotter.shared.quote.QuoteVatGroupView
import de.littletrotter.shared.tax.VatCategory

/**
 * Read side of the anti-corruption layer: contract views become local models. Enums travel as
 * their name on the wire; a name the local enum does not know means the component runs a newer
 * contract than this build, which is reported instead of guessed.
 */
object QuoteViewTranslator {

    /** An upload answer; a missing or unknown reduction stage reads as "not reported" rather than failing the upload. */
    fun toStoredFile(answer: QuoteIdAnswer): StoredQuoteFile =
        StoredQuoteFile(answer.id, answer.reduction?.let(::toStoredReduction))

    private fun toStoredReduction(answer: QuoteReductionAnswer): StoredQuoteReduction? {
        val stage = ReceiptNormalisationStage.entries.firstOrNull { it.name == answer.stage } ?: return null
        return StoredQuoteReduction(stage, answer.storedBytes)
    }

    fun toPage(view: QuotePageView): QuotePage = QuotePage(view.rows.map(::toSummary), view.total)

    fun toSummary(view: QuoteSummaryView): QuoteSummary =
        QuoteSummary(
            id = view.id,
            customerName = view.customerName,
            status = statusOf(view.status),
            quoteDate = view.quoteDate,
            validUntil = view.validUntil,
            netTotal = view.netTotal,
            number = view.number,
            subject = view.subject,
        )

    fun toDetail(view: QuoteDetailView): QuoteDetail =
        QuoteDetail(
            id = view.id,
            version = view.version,
            status = statusOf(view.status),
            party = toParty(view.party),
            priceDisplay = enumOf<QuotePriceDisplay>(view.priceDisplay, "priceDisplay"),
            quoteDate = view.quoteDate,
            validUntil = view.validUntil,
            totals = toTotals(view.totals),
            number = view.number,
            subject = view.subject,
            introText = view.introText,
            closingText = view.closingText,
            noteText = view.noteText,
            showsGrossPrices = view.showsGrossPrices,
            acceptedOn = view.acceptedOn,
            customerOrderReference = view.customerOrderReference,
            rejectionReason = view.rejectionReason,
            invoiceNumber = view.invoiceNumber,
            items = view.items.map(::toItem),
            b2bValues = view.b2bValues.map(::toB2bValue),
            invoiceOrder = view.invoiceOrder?.let(::toInvoiceOrder),
        )

    fun toLogEntry(view: QuoteLogEntryView): QuoteLogEntry =
        QuoteLogEntry(view.occurredAt, view.actor, enumOf<QuoteLogKind>(view.kind, "log kind"), view.text, view.reference)

    fun toAttachment(view: QuoteAttachmentView): QuoteAttachment =
        QuoteAttachment(
            id = view.id,
            kind = enumOf<QuoteAttachmentKind>(view.kind, "attachment kind"),
            fileName = view.fileName,
            mimeType = view.mimeType,
            sizeBytes = view.sizeBytes,
            sha256 = view.sha256,
            uploadedAt = view.uploadedAt,
            itemId = view.itemId,
            itemPosition = view.itemPosition,
            libraryVersionId = view.libraryVersionId,
            libraryTitle = view.libraryTitle,
            versionNo = view.versionNo,
            newerVersionAvailable = view.newerVersionAvailable,
        )

    fun toLibraryEntry(view: AttachmentLibraryEntryView): AttachmentLibraryEntry =
        AttachmentLibraryEntry(
            id = view.id,
            title = view.title,
            description = view.description,
            currentVersion = toLibraryVersion(view.currentVersion),
            versions = view.versions.map(::toLibraryVersion),
            usedByQuotes = view.usedByQuotes,
            archived = view.archived,
        )

    private fun toLibraryVersion(view: AttachmentLibraryVersionView): AttachmentLibraryVersion =
        AttachmentLibraryVersion(
            id = view.id,
            versionNo = view.versionNo,
            fileName = view.fileName,
            mimeType = view.mimeType,
            sizeBytes = view.sizeBytes,
            sha256 = view.sha256,
            validFrom = view.validFrom,
            uploadedAt = view.uploadedAt,
            uploadedBy = view.uploadedBy,
        )

    /** Every status is present in the result, so a view can render a chip per status without null checks. */
    fun toStatistics(view: QuoteStatisticsView): QuoteStatistics {
        val counts = view.countsByStatus.entries.groupBy({ statusOf(it.key) }, { it.value }).mapValues { it.value.sum() }
        return QuoteStatistics(QuoteStatus.entries.associateWith { counts[it] ?: 0L })
    }

    fun toDashboard(view: QuoteDashboardView): QuoteDashboard =
        QuoteDashboard(
            availableYears = view.availableYears,
            kpis = toKpis(view.kpis),
            kpisPreviousYear = toKpis(view.kpisPreviousYear),
            statusCounts = QuoteStatusCounts(
                view.statusCounts.draft,
                view.statusCounts.review,
                view.statusCounts.sent,
                view.statusCounts.cancelled,
            ),
            quarters = view.quarters.sortedBy { it.quarter }.map { QuoteQuarterTotals(it.quarter, it.net, it.count) },
            trend = view.trend.sortedBy { it.day }.map { QuoteTrendPoint(it.day, it.net, it.count) },
        )

    fun toRankingRow(view: QuoteRankingRowView): QuoteRankingRow =
        QuoteRankingRow(view.key, view.name, view.count, view.net)

    private fun toKpis(view: QuoteKpisView): QuoteKpis =
        QuoteKpis(
            sentVolume = view.sentVolume,
            sentCount = view.sentCount,
            averageSent = view.averageSent,
            inProgressAmount = view.inProgressAmount,
            inProgressCount = view.inProgressCount,
            avgDaysToSend = view.avgDaysToSend,
        )

    fun toSettings(view: QuoteSettingsView): QuoteSettings =
        QuoteSettings(view.validityDays, enumOf<QuoteXmlFormat>(view.xmlSyntax, "xmlSyntax"), view.updatedAt)

    fun toDocument(view: QuoteDocumentView): QuoteDocument =
        QuoteDocument(
            id = view.id,
            mark = enumOf<QuoteDocumentMark>(view.mark, "document mark"),
            status = enumOf<QuoteDocumentStatus>(view.status, "document status"),
            requestedAt = view.requestedAt,
            completedAt = view.completedAt,
            sha256 = view.sha256,
            hasValidationReport = view.hasValidationReport,
            error = view.error,
            hasXml = view.hasXml,
        )

    /**
     * Reads a stored check report. Two shapes exist: the full validation payload (status plus
     * detailed findings) and the short form of the machine-readable data check (`valid` flag plus
     * optional `errors`). Anything else is reported instead of guessed.
     */
    fun toValidationReport(raw: String): QuoteValidationReport {
        val payload = KositReportPayload.decode(raw)
        if (payload != null) {
            val valid = payload.status.equals(STATUS_VALID, ignoreCase = true)
            val errors = payload.details
                .filter { it.severity.uppercase() in ERROR_SEVERITIES }
                .map { listOfNotNull(it.ruleId, it.message).joinToString(": ") }
            return QuoteValidationReport(valid, if (valid) emptyList() else errors.ifEmpty { listOfNotNull(payload.summary) })
        }
        val tree = try {
            REPORT_MAPPER.readTree(raw)
        } catch (e: JsonProcessingException) {
            throw QuoteServiceException("The offer component's validation report is not readable: ${e.originalMessage}", e)
        }
        val valid = tree?.get("valid")?.takeIf { it.isBoolean }?.asBoolean()
            ?: throw QuoteServiceException("The offer component's validation report has no result")
        val errors = tree.get("errors")?.map { it.asText() }.orEmpty()
        return QuoteValidationReport(valid, errors)
    }

    fun toCustomerOption(view: QuoteCustomerOptionView): QuoteCustomerOption =
        QuoteCustomerOption(view.id, view.name, view.customerNumber, view.city)

    fun toArticleOption(view: QuoteArticleOptionView): QuoteArticleOption =
        QuoteArticleOption(
            id = view.id,
            articleNumber = view.articleNumber,
            description = view.description,
            category = enumOf<InvoiceItemCategory>(view.category, FIELD_CATEGORY),
            unit = enumOf<InvoiceItemUnit>(view.defaultUnit, FIELD_UNIT),
            unitPrice = view.unitPrice,
            vatCategory = enumOf<VatCategory>(view.vatCategory, FIELD_VAT_CATEGORY),
            vatRate = view.vatRate,
            customerReference = view.customerReference,
            customerArticleName = view.customerArticleName,
        )

    fun toHeaderField(view: QuoteHeaderFieldView): QuoteHeaderField =
        QuoteHeaderField(view.btCode, view.mandatory, view.defaultValue)

    private fun toParty(view: QuotePartyView): QuoteParty =
        QuoteParty(
            customerId = view.customerId,
            name = view.name,
            countryCode = view.countryCode,
            customerType = view.customerType,
            documentLanguage = view.documentLanguage,
            customerNumber = view.customerNumber,
            street = view.street,
            postalCode = view.postalCode,
            city = view.city,
            vatId = view.vatId,
            email = view.email,
        )

    private fun toItem(view: QuoteItemView): QuoteItem =
        QuoteItem(
            id = view.id,
            position = view.position,
            description = view.description,
            quantity = view.quantity,
            category = enumOf<InvoiceItemCategory>(view.category, FIELD_CATEGORY),
            unit = enumOf<InvoiceItemUnit>(view.unit, FIELD_UNIT),
            unitPrice = view.unitPrice,
            vatCategory = enumOf<VatCategory>(view.vatCategory, FIELD_VAT_CATEGORY),
            vatRate = view.vatRate,
            net = view.net,
            articleId = view.articleId,
            articleNumber = view.articleNumber,
            customerReference = view.customerReference,
            customerArticleName = view.customerArticleName,
        )

    /** A hand-over answer that lacks a field is a contract break, reported instead of guessed. */
    fun toDispatchHandover(view: QuoteDispatchHandoverView): QuoteDispatchHandover =
        QuoteDispatchHandover(
            required(view.draftId, "draftId"),
            required(view.composeUrl, "composeUrl"),
        )

    private fun required(value: String?, field: String): String =
        value?.takeIf { it.isNotBlank() }
            ?: throw QuoteServiceException("The offer component's dispatch answer lacks $field")

    private fun toTotals(view: QuoteTotalsView): QuoteTotals =
        QuoteTotals(view.net, view.vat, view.gross, view.groups.map(::toVatGroup))

    private fun toVatGroup(view: QuoteVatGroupView): QuoteVatGroup =
        QuoteVatGroup(enumOf<VatCategory>(view.category, FIELD_VAT_CATEGORY), view.rate, view.base, view.vat)

    private fun toInvoiceOrder(view: QuoteInvoiceOrderView): QuoteInvoiceOrder =
        QuoteInvoiceOrder(
            orderId = view.orderId,
            status = enumOf<QuoteInvoiceOrderStatus>(view.status, "invoice order status"),
            requestedAt = view.requestedAt,
            deliveryDate = view.deliveryDate,
            servicePeriodFrom = view.servicePeriodFrom,
            servicePeriodTo = view.servicePeriodTo,
            invoiceId = view.invoiceId,
            invoiceNumber = view.invoiceNumber,
            rejectionReason = view.rejectionReason,
        )

    private fun toB2bValue(view: QuoteB2bValueView): QuoteB2bValue = QuoteB2bValue(view.btCode, view.value)

    /** An unrecognised status degrades to [QuoteStatus.UNKNOWN]: one row from a newer component must not break the list. */
    private fun statusOf(raw: String): QuoteStatus = QuoteStatus.known.firstOrNull { it.name == raw } ?: QuoteStatus.UNKNOWN

    private inline fun <reified E : Enum<E>> enumOf(raw: String, field: String): E =
        enumValues<E>().firstOrNull { it.name == raw }
            ?: throw QuoteUnknownValueException(field, raw)

    private const val STATUS_VALID = "VALID"
    private val ERROR_SEVERITIES = setOf("ERROR", "FATAL")
    private val REPORT_MAPPER = ObjectMapper()
    private const val FIELD_VAT_CATEGORY = "vatCategory"
    private const val FIELD_CATEGORY = "category"
    private const val FIELD_UNIT = "unit"
}
