package de.littletrotter.application.dto

import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Read model for displaying/assigning a receipt: the receipts tab
 * ([de.littletrotter.ui.view.invoice.InvoiceDetailViewReceiptsTab]), the receipt dialog
 * ([de.littletrotter.ui.view.invoice.ReceiptDialog]), the target gateway
 * ([de.littletrotter.ui.view.invoice.ReceiptTargetGateway]), the pool picker
 * ([de.littletrotter.ui.view.invoice.ReceiptPoolPane]) and the trip expense rows
 * ([de.littletrotter.ui.view.invoice.TripExpenseRowsEditor]).
 *
 * `data class` equality replaces the object identity a `Receipt` entity used to carry; grids and
 * combos that show a receipt more than once key their own row type by id (plus target, where the
 * same receipt can appear once per assignment target) instead of comparing instances.
 */
data class ReceiptRowDto(
    val id: Long,
    val fileName: String,
    val description: String,
    val documentDate: LocalDate?,
    val sizeBytes: Long?,
    val uploadedAt: OffsetDateTime?,
    val objectKey: String,
    /** Defaults to [ReceiptNormalisationStage.NONE] so the many existing call sites that predate
     *  receipt-size-normalisation (test fixtures especially) keep compiling unchanged; the two
     *  production mappers ([de.littletrotter.application.invoice.AttachReceipts] and
     *  `InvoiceReceiptQueries.toRowDto`) always pass the persisted entity's real value. */
    val normalisationStage: ReceiptNormalisationStage = ReceiptNormalisationStage.NONE,
    val originalSizeBytes: Long? = null,
)
