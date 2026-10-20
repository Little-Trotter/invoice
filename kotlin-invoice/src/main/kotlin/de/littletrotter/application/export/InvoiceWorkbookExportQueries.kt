package de.littletrotter.application.export

import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.port.inbound.InvoiceWorkbookExportQueryPort
import de.littletrotter.application.export.port.outbound.InvoiceWorkbookExportPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.OutputStream

/** Implements [InvoiceWorkbookExportQueryPort] on top of the workbook adapter. */
@ApplicationScoped
class InvoiceWorkbookExportQueries @Inject constructor(
    private val export: InvoiceWorkbookExportPort,
) : InvoiceWorkbookExportQueryPort {
    override fun writeWorkbook(
        invoiceId: Long,
        appliedSelection: String,
        translate: (String) -> String,
        output: OutputStream,
    ) = export.writeWorkbook(invoiceId, appliedSelection, translate, output)

    override fun writeWorkbook(
        filter: InvoiceExportFilter,
        appliedSelection: String,
        translate: (String) -> String,
        output: OutputStream,
    ) = export.writeWorkbook(filter, appliedSelection, translate, output)

    override fun writePositionsCsv(filter: InvoiceExportFilter, translate: (String) -> String, output: OutputStream) =
        export.writePositionsCsv(filter, translate, output)
}
