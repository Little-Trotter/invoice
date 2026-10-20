package de.littletrotter.application.export.port.inbound

import java.io.OutputStream

/**
 * Input port for the workbook of one business trip. Labels are resolved by the caller through
 * [translate]; the caller owns the output stream and closes it.
 */
interface TripWorkbookExportQueryPort {
    /** Writes the three-sheet workbook of the trip position [positionId] of invoice [invoiceId]. */
    fun writeTripWorkbook(invoiceId: Long, positionId: String, translate: (String) -> String, output: OutputStream)

    /** The download file name of that workbook. */
    fun fileName(invoiceNumber: String, invoiceId: Long, positionId: String): String
}
