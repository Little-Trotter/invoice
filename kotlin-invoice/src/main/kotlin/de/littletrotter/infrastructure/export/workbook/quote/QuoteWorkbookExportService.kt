package de.littletrotter.infrastructure.export.workbook.quote

import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteListWorkbookExportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteWorkbookExportQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteDetailRemote
import de.littletrotter.application.quote.port.outbound.QuoteListRemote
import de.littletrotter.infrastructure.export.allRows
import de.littletrotter.infrastructure.export.workbook.ColumnWidths
import de.littletrotter.infrastructure.export.workbook.RowWriter
import de.littletrotter.infrastructure.export.workbook.WorkbookWriter
import de.littletrotter.infrastructure.export.workbook.itemCategoryKey
import de.littletrotter.infrastructure.export.workbook.vatCategoryKey
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.OutputStream

private val HEADER_COLUMN_KEYS = listOf(
    "quoteExport.column.number",
    "quoteExport.column.status",
    "quoteExport.column.quoteDate",
    "quoteExport.column.validUntil",
    "quoteExport.column.customerNumber",
    "quoteExport.column.customer",
    "quoteExport.column.street",
    "quoteExport.column.postalCode",
    "quoteExport.column.city",
    "quoteExport.column.country",
    "quoteExport.column.vatId",
    "quoteExport.column.subject",
    "quoteExport.column.net",
    "quoteExport.column.vat",
    "quoteExport.column.gross",
    "quoteExport.column.acceptedOn",
    "quoteExport.column.customerOrderReference",
    "quoteExport.column.rejectionReason",
    "quoteExport.column.invoiceNumber",
    "quoteExport.column.introText",
    "quoteExport.column.closingText",
    "quoteExport.column.noteText",
)

private val HEADER_COLUMN_WIDTHS = listOf(
    ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.DATE, ColumnWidths.DATE,
    ColumnWidths.CODE, ColumnWidths.NAME, ColumnWidths.NAME, ColumnWidths.CODE,
    ColumnWidths.NAME, ColumnWidths.FLAG, ColumnWidths.CODE, ColumnWidths.TEXT,
    ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.DATE,
    ColumnWidths.CODE, ColumnWidths.TEXT, ColumnWidths.CODE, ColumnWidths.TEXT,
    ColumnWidths.TEXT, ColumnWidths.TEXT,
)

private val ITEM_COLUMN_KEYS = listOf(
    "quoteExport.column.number",
    "quoteExport.column.position",
    "quoteExport.column.articleNumber",
    "quoteExport.column.description",
    "quoteExport.column.category",
    "quoteExport.column.quantity",
    "quoteExport.column.unit",
    "quoteExport.column.unitPrice",
    "quoteExport.column.vatCategory",
    "quoteExport.column.vatRate",
    "quoteExport.column.net",
    "quoteExport.column.customerReference",
    "quoteExport.column.customerArticleName",
)

private val ITEM_COLUMN_WIDTHS = listOf(
    ColumnWidths.CODE, ColumnWidths.FLAG, ColumnWidths.CODE, ColumnWidths.TEXT,
    ColumnWidths.CODE, ColumnWidths.QUANTITY, ColumnWidths.FLAG, ColumnWidths.MONEY,
    ColumnWidths.CODE, ColumnWidths.PERCENT, ColumnWidths.MONEY, ColumnWidths.CODE,
    ColumnWidths.NAME,
)

/**
 * Writes offers as a two-sheet workbook: header data (one row per offer) and items (one row per
 * item, each carrying its offer number so the two sheets can be joined). The list export reads the
 * filtered list page by page and loads each offer's detail for its header and items, one call per
 * offer — the offer component offers no bulk detail call, and the export is an occasional action.
 */
@ApplicationScoped
class QuoteWorkbookExportService @Inject constructor(
    private val listRemote: QuoteListRemote,
    private val detailRemote: QuoteDetailRemote,
) : QuoteWorkbookExportQueryPort, QuoteListWorkbookExportQueryPort {

    override fun writeWorkbook(quoteId: String, translate: (String) -> String, output: OutputStream) {
        write(sequenceOf(detailRemote.find(quoteId)), "quoteExport.sheet.header", translate, output)
    }

    override fun writeWorkbook(criteria: QuoteListCriteria, translate: (String) -> String, output: OutputStream) {
        val details = listRemote.allRows(criteria).map { detailRemote.find(it.id) }
        write(details, "quoteExport.sheet.quotes", translate, output)
    }

    private fun write(details: Sequence<QuoteDetail>, headerSheetKey: String, translate: (String) -> String, output: OutputStream) {
        WorkbookWriter().use { writer ->
            val headerSheet = writer.sheet(translate(headerSheetKey)).header(HEADER_COLUMN_KEYS.map(translate), HEADER_COLUMN_WIDTHS)
            val itemSheet = writer.sheet(translate("quoteExport.sheet.items")).header(ITEM_COLUMN_KEYS.map(translate), ITEM_COLUMN_WIDTHS)
            details.forEach { detail ->
                val number = checkNotNull(detail.number) { "offer ${detail.id} has no number" }
                headerSheet.row().writeHeader(detail, number, translate)
                detail.items.sortedBy { it.position }.forEach { itemSheet.row().writeItem(it, number, translate) }
            }
            writer.write(output)
        }
    }

    private fun RowWriter.writeHeader(detail: QuoteDetail, number: String, translate: (String) -> String): RowWriter =
        text(number)
            .text(translate("quote.status.${detail.status.name}"))
            .date(detail.quoteDate)
            .date(detail.validUntil)
            .text(detail.party.customerNumber)
            .text(detail.party.name)
            .text(detail.party.street)
            .text(detail.party.postalCode)
            .text(detail.party.city)
            .text(detail.party.countryCode)
            .text(detail.party.vatId)
            .text(detail.subject)
            .money(detail.totals.net)
            .money(detail.totals.vat)
            .money(detail.totals.gross)
            .date(detail.acceptedOn)
            .text(detail.customerOrderReference)
            .text(detail.rejectionReason, wrap = true)
            .text(detail.invoiceNumber)
            .text(detail.introText, wrap = true)
            .text(detail.closingText, wrap = true)
            .text(detail.noteText, wrap = true)

    private fun RowWriter.writeItem(item: QuoteItem, number: String, translate: (String) -> String): RowWriter =
        text(number)
            .integer(item.position.toLong())
            .text(item.articleNumber)
            .text(item.description, wrap = true)
            .text(translate(itemCategoryKey(item.category)))
            .quantity(item.quantity)
            .text(item.unit.symbol)
            .money(item.unitPrice)
            .text(translate(vatCategoryKey(item.vatCategory)))
            .percent(item.vatRate)
            .money(item.net)
            .text(item.customerReference)
            .text(item.customerArticleName)
}
