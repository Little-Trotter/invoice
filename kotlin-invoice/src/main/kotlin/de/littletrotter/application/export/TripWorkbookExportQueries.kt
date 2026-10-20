package de.littletrotter.application.export

import de.littletrotter.application.export.port.inbound.TripWorkbookExportQueryPort
import de.littletrotter.application.export.port.outbound.TripWorkbookExportPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.OutputStream

/** Implements [TripWorkbookExportQueryPort] on top of the workbook adapter. */
@ApplicationScoped
class TripWorkbookExportQueries @Inject constructor(
    private val export: TripWorkbookExportPort,
) : TripWorkbookExportQueryPort {
    override fun writeTripWorkbook(
        invoiceId: Long,
        positionId: String,
        translate: (String) -> String,
        output: OutputStream,
    ) = export.writeTripWorkbook(invoiceId, positionId, translate, output)

    override fun fileName(invoiceNumber: String, invoiceId: Long, positionId: String): String =
        export.fileName(invoiceNumber, invoiceId, positionId)
}
