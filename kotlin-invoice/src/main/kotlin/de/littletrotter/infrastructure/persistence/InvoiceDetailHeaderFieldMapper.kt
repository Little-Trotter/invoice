package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceStatus
import jakarta.persistence.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The "header" cohesive group of the header-tuple projection
 * [InvoiceReadRepositoryImpl.findInvoiceDetailDto] reads: identification, document-classification
 * ordinals and the invoice's own dates. Every field defaults to the same value
 * [de.littletrotter.application.dto.InvoiceDetailDto]'s own field would (detekt's
 * `LongParameterList.ignoreDefaultParameters` exemption for a defaulted constructor, same
 * convention [de.littletrotter.application.export.InvoiceHeaderExportRow] already uses) — every
 * call site below still sets every field explicitly, the defaults are never actually relied upon.
 */
internal data class InvoiceDetailHeaderFields(
    val id: Long = 0L,
    val invoiceNumber: String = "",
    val status: Int = InvoiceStatus.DRAFT.ordinal,
    val invoiceDate: LocalDate? = null,
    val version: Long? = null,
    val dueDate: LocalDate? = null,
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
    val validationStatus: Int? = null,
    val invoiceFormat: Int? = null,
    val typeCode: Int? = null,
    val documentLanguage: String = "de",
    val mailSent: Boolean? = null,
    val mailSentAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime? = null,
)

internal object InvoiceDetailHeaderFieldMapper {

    fun map(header: Tuple): InvoiceDetailHeaderFields = InvoiceDetailHeaderFields(
        id = (header.get("id") as Number).toLong(),
        invoiceNumber = InvoiceDetailRowSupport.parseInvoiceNumber(header.get("invoiceNumber")),
        status = InvoiceDetailRowSupport.parseInvoiceStatusOrdinal(header.get("status")),
        invoiceDate = header.get("invoiceDate") as LocalDate?,
        version = (header.get("version") as Number?)?.toLong(),
        dueDate = header.get("dueDate") as LocalDate?,
        servicePeriodFrom = header.get("servicePeriodFrom") as LocalDate?,
        servicePeriodTo = header.get("servicePeriodTo") as LocalDate?,
        validationStatus = InvoiceDetailRowSupport.parseOrdinal(header.get("validationStatus")),
        invoiceFormat = InvoiceDetailRowSupport.parseOrdinal(header.get("invoiceFormat")),
        // The cancellation dialog decides whether to offer the English reason field from this
        // value, so it has to survive the projection.
        typeCode = InvoiceDetailRowSupport.parseOrdinal(header.get("typeCode")),
        // documentLanguage is a non-nullable column on Invoice, so its fallback default
        // was unreachable for any persisted row.
        documentLanguage = InvoiceDetailRowSupport.codeOf(header.get("documentLanguage")) ?: "de",
        // The mail send read model, so the detail view's send history needs no call
        // into the mail component on its render path.
        mailSent = header.get("mailSent") as Boolean?,
        mailSentAt = header.get("mailSentAt") as OffsetDateTime?,
        createdAt = header.get("createdAt") as OffsetDateTime?,
    )
}
