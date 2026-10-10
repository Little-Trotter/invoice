package de.littletrotter.application.export

/**
 * Pure mapping from the raw scalar projection [de.littletrotter.infrastructure.persistence
 * .InvoiceExportReadRepositoryImpl] reads into a fully-typed [InvoiceHeaderExportRow]. No database
 * access, no i18n resolution (that is [de.littletrotter.infrastructure.export.workbook
 * .InvoiceHeaderSheetWriter]'s job) — deliberately a plain function over an `Array<Any?>`, the
 * same shape [de.littletrotter.application.dto.InvoiceOverviewMapper.fromRow] already uses, so it
 * is unit-testable without a Testcontainers Postgres.
 *
 * row carries every column EXCEPT the invoice number itself — that one is read through a
 * separate native-SQL lookup ([de.littletrotter.domain.invoice
 * .InvoiceNumberConverter] throws on a malformed stored value, so it must never sit on a Criteria
 * path that a bulk projection selects) and passed in as rawInvoiceNumber/invoiceNumberMalformed.
 *
 * The 75-column projection's mapping logic is split into cohesive per-group mappers, each
 * over its own column range (see [InvoiceHeaderRowIndex] for the full layout table):
 * [InvoiceHeaderCoreRowMapper] (header: identification/dates/payment terms),
 * [InvoicePartyRowMapper] (parties), [InvoiceTaxRowMapper] (tax), [InvoiceTotalsRowMapper]
 * (totals) and [InvoiceTextRowMapper] (texts). [fromRow] itself only composes them: the core
 * mapper seeds an [InvoiceHeaderExportRow] with its own group's fields (every other field at its
 * own class default), and every other mapper `.copy()`s its group's columns onto that in turn —
 * [InvoiceHeaderExportRow]'s public shape and [fromRow]'s own signature are unchanged by the split.
 */
object InvoiceHeaderRowMapper {

    fun fromRow(
        row: Array<Any?>,
        rawInvoiceNumber: String?,
        invoiceNumberMalformed: Boolean,
    ): InvoiceHeaderExportRow {
        val seed = InvoiceHeaderCoreRowMapper.map(row, rawInvoiceNumber, invoiceNumberMalformed)
        return GROUP_MAPPERS.fold(seed) { acc, mapper -> mapper(acc, row) }
    }

    private val GROUP_MAPPERS: List<(InvoiceHeaderExportRow, Array<Any?>) -> InvoiceHeaderExportRow> = listOf(
        InvoicePartyRowMapper::apply,
        InvoiceTaxRowMapper::apply,
        InvoiceTotalsRowMapper::apply,
        InvoiceTextRowMapper::apply,
    )
}
