package de.littletrotter.application.invoice

import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.shared.ReceiptDto

/** The receipt as the export worker gets it: location plus the page facts recorded at upload,
 *  so the worker knows without opening the file whether the pages can be copied as images. */
internal fun Receipt.toJobReceiptDto(): ReceiptDto = ReceiptDto(
    fileName = fileName,
    objectKey = objectKey,
    description = description,
    rasterKind = rasterKind.name,
    pageCount = pageCount,
    a4Factor = a4Factor,
    annexBytes = annexBytes,
)
