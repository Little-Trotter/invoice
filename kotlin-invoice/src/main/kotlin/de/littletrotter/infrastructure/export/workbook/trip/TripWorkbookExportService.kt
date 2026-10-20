package de.littletrotter.infrastructure.export.workbook.trip

import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.export.port.outbound.TripExportNotFoundException
import de.littletrotter.application.export.port.outbound.TripWorkbookExportPort
import de.littletrotter.application.export.TripWorkbookView
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.infrastructure.export.workbook.ExportMetadata
import de.littletrotter.infrastructure.export.workbook.ExportSheetSummary
import de.littletrotter.infrastructure.export.workbook.ExportSheetWriter
import de.littletrotter.infrastructure.export.workbook.WorkbookWriter
import de.littletrotter.shared.InvoiceItemCategoryDetail
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Bulkhead
import java.io.OutputStream
import java.text.MessageFormat

/**
 * The [TripWorkbookExportPort] implementation: reads the `TRIP` position's export detail
 * through [InvoiceDtoMapper] (never the UI-facing `ItemDto`/`TripDto` — that read-model side,
 * `InvoiceReadRepositoryImpl`, is a different path for a different consumer, the UI grid), then
 * writes the four sheets — `Dienstreise` ([TripDetailSheetWriter]), `Posten`
 * ([TripExpenseSheetWriter]), `Verpflegung` ([PerDiemSheetWriter]) and `Export`
 * ([ExportSheetWriter], the cover sheet, reused verbatim)
 * — in that creation order, with `Export` moved to the front exactly like
 * [de.littletrotter.infrastructure.export.workbook.InvoiceWorkbookExportService] already does for
 * the per-invoice workbook.
 *
 * **Worker seam:** see [TripWorkbookExportPort]'s own KDoc — this class only ever writes
 * synchronously to the [OutputStream] it is handed; nothing here publishes to NATS or Valkey (that
 * happens in [InvoiceDtoMapper], the caller of the [TripWorkbookView]-based overload).
 */
@ApplicationScoped
class TripWorkbookExportService @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val invoiceDtoMapper: InvoiceDtoMapper,
    private val auditActorProvider: AuditActorQueryPort,
    private val applicationIdentity: ApplicationIdentity,
    private val time: TimeSource,
) : TripWorkbookExportPort {

    override fun fileName(invoiceNumber: String, invoiceId: Long, positionId: String): String =
        tripWorkbookFileName(invoiceNumber, invoiceId, positionId)

    /** Reloads+remaps the invoice by id, then delegates to the [TripWorkbookView]-based overload —
     *  the live download button's own path. Never called from [InvoiceDtoMapper] itself;
     *  see this class's own KDoc and [TripWorkbookExportPort]'s.
     *
     *  `@Bulkhead` on both overloads (see the other one's own KDoc for the shared
     *  config property and rejection story): a same-instance call from this overload into the
     *  other one does not go through *its* interceptor (CDI self-invocation is not proxied), so
     *  each externally reachable overload needs its own annotation to actually be protected. */
    @Bulkhead(20)
    override fun writeTripWorkbook(invoiceId: Long, positionId: String, translate: (String) -> String, output: OutputStream) {
        writeTripWorkbook(loadTripWorkbookView(invoiceId, positionId), translate, output)
    }

    /** `@Bulkhead`, sized via `invoice.generation.bulkhead.max-concurrent-calls` (same
     *  property as InvoiceWorkbookExportService and DocumentJobPublisher.publish): building
     *  the POI workbook is the per-call memory cost this bounds. No persisted job backs a trip
     *  workbook download, so a rejection propagates as a plain `BulkheadException` to the caller
     *  (the UI download action, or [InvoiceDtoMapper] when it renders one inline) — the same
     *  "no durable job-intent for this synchronous feature" trade-off as the invoice workbook
     *  export; see the implementation-log entry. */
    @Bulkhead(20)
    override fun writeTripWorkbook(view: TripWorkbookView, translate: (String) -> String, output: OutputStream) {
        val labels = buildTripWorkbookExportLabels(translate)
        val productLine = applicationIdentity.productLine()
        val appliedSelection = MessageFormat.format(labels.subtitlePattern, view.title, view.invoiceNumber)

        WorkbookWriter().use { writer ->
            writer.setCoreProperties(title = "$productLine — $appliedSelection", creator = productLine, application = productLine)
            val detailSheet = TripDetailSheetWriter.write(writer, labels.detailSheet, view)
            val expenseSheet = TripExpenseSheetWriter.write(writer, labels.expenseSheet, view)
            val perDiemSheet = PerDiemSheetWriter.write(writer, labels.perDiemSheet, view)

            val metadata = ExportMetadata(
                productLine = productLine,
                generatedAt = time.nowUtc(),
                actingUser = auditActorProvider.currentActor(),
                appliedSelection = appliedSelection,
                applicationVersion = applicationIdentity.version(),
            )
            val summaries = listOf(
                ExportSheetSummary(detailSheet.name, detailSheet.recordCount),
                ExportSheetSummary(expenseSheet.name, expenseSheet.recordCount),
                ExportSheetSummary(perDiemSheet.name, perDiemSheet.recordCount),
            )
            ExportSheetWriter.write(writer, labels.exportSheet, metadata, summaries)
            writer.write(output)
        }
    }

    /**
     * Reloads [invoiceId] with its items (the same [InvoiceRepository.findByIdWithItems] call
     * every other trip-aware service uses — [de.littletrotter.application.invoice.TripService],
     * [de.littletrotter.application.invoice.InvoiceCancellationService]), maps it through
     * [InvoiceDtoMapper.mapToDto] (which is `@Transactional` itself, so this call needs no
     * transaction boundary of its own), and picks out exactly the one `TRIP` position asked for.
     */
    private fun loadTripWorkbookView(invoiceId: Long, positionId: String): TripWorkbookView {
        val invoice = invoiceRepository.findByIdWithItems(invoiceId)
            ?: throw TripExportNotFoundException(invoiceId, positionId)
        val invoiceDto = invoiceDtoMapper.mapToDto(invoice)
        val item = invoiceDto.items.find { it.positionId == positionId }
            ?: throw TripExportNotFoundException(invoiceId, positionId)
        // item.categoryDetail (InvoiceItemCategoryDetail) replaces the old
        // `category != TRIP || trip == null` pair check: a TRIP item's export detail is now
        // guaranteed non-null by InvoiceItemDto's own construction invariant, so there is no
        // longer a reachable "TRIP but trip == null" arm to guard against here.
        val trip = when (val detail = item.categoryDetail) {
            is InvoiceItemCategoryDetail.Trip -> detail.export
            InvoiceItemCategoryDetail.NonTrip -> throw TripExportNotFoundException(invoiceId, positionId)
        }
        val currency = invoiceDto.totals.currency

        return TripWorkbookView(
            positionId = item.positionId,
            title = item.description,
            purpose = item.itemDescription,
            periodFrom = TripExportDates.parse(item.servicePeriodFrom),
            periodTo = TripExportDates.parse(item.servicePeriodTo),
            invoiceNumber = invoiceDto.invoiceNumber.value,
            invoiceDate = TripExportDates.parse(invoiceDto.dates.invoiceDate),
            customerName = invoiceDto.recipient?.name,
            costCenter = item.costCenter,
            taxRatePercent = item.taxRatePercent,
            calculatedNet = trip.calculatedNet,
            fixedNetPrice = trip.fixedNetPrice,
            receiptsCount = item.receipts.size,
            currency = currency,
            expenses = trip.expenses,
            perDiemDays = trip.perDiemDays,
        )
    }
}
