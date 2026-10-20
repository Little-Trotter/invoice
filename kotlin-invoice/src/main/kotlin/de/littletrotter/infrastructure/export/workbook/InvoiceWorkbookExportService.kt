package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.port.outbound.InvoiceExportNotFoundException
import de.littletrotter.application.export.InvoiceHeaderExportRow
import de.littletrotter.application.export.InvoiceJournalExportRow
import de.littletrotter.application.export.InvoicePositionExportRow
import de.littletrotter.application.export.port.outbound.InvoiceWorkbookExportPort
import de.littletrotter.application.export.port.outbound.InvoiceExportReadRepository
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.infrastructure.export.csv.InvoicePositionCsvWriter
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Bulkhead
import java.io.OutputStream

/**
 * The [InvoiceWorkbookExportPort] implementation: assembles all four sheets for exactly
 * one invoice from [InvoiceExportReadRepository]'s mode-A methods, in R1's fixed order (`Export`,
 * `Rechnungen`, `Positionen`, `Journal`), streaming straight into the caller's [OutputStream]
 * rather than holding the workbook in memory. Lives in this package (not `application`) because it
 * assembles POI writer calls directly — this onion architecture lock-in means only this
 * `infrastructure.export` adapter may depend on those types; [InvoiceWorkbookExportPort] is the
 * pure boundary `application`/`ui` code depends on instead.
 *
 * Mode B adds the filtered-list export over [InvoiceExportReadRepository
 * .findHeaderRows] as an overload of both methods below, no `Journal` sheet — see that
 * overload's own KDoc.
 */
@ApplicationScoped
class InvoiceWorkbookExportService @Inject constructor(
    private val readRepository: InvoiceExportReadRepository,
    private val auditActorProvider: AuditActorQueryPort,
    private val applicationIdentity: ApplicationIdentity,
    private val time: TimeSource,
) : InvoiceWorkbookExportPort {

    /**
     * Sheets are created in R1's fixed final order (`Rechnungen`, `Positionen`, `Journal`, in that
     * creation order) with `Export` written last — [ExportSheetWriter.write] moves it to the front
     * itself ([WorkbookWriter.moveToFront]), so the workbook's final tab order comes out as
     * `Export`, `Rechnungen`, `Positionen`, `Journal` without this method reordering anything
     * itself.
     *
     * `@Bulkhead`, sized via `invoice.generation.bulkhead.max-concurrent-calls`
     * (same property as the bulk overload below and DocumentJobPublisher.publish): building a
     * POI workbook in memory is the per-call cost this bounds. A rejection here has no persisted
     * job to fall back on (this is a synchronous download, not a `GenerationJob`) — it propagates
     * as a plain `BulkheadException` that the calling UI action reports as an ordinary error, and
     * the user's own retry (clicking export again) is the recovery path. See the
     * implementation-log entry for why this differs from the durable-job-intent composition
     * DocumentJobPublisher.publish gets.
     */
    @Bulkhead(20)
    override fun writeWorkbook(invoiceId: Long, appliedSelection: String, translate: (String) -> String, output: OutputStream) {
        val header = readRepository.findHeaderRow(invoiceId) ?: throw InvoiceExportNotFoundException(invoiceId)
        val positions = readRepository.findPositionRows(invoiceId)
        val journal = readRepository.findJournalRows(invoiceId)
        val labels = buildInvoiceWorkbookExportLabels(translate, ExportSheetMode.SINGLE_INVOICE)
        writeWorkbookCore(labels, appliedSelection, WorkbookSheetData(listOf(header), positions, journal), output)
    }

    /**
     * Mode B: three sheets, no `Journal` (see this method's own port KDoc for why).
     * Streams the filtered header rows in [InvoiceExportReadRepository.DEFAULT_BATCH_SIZE]
     * batches so a large filter never materialises the whole result in memory at once; for each
     * batch, the positions of all its invoices are fetched with one
     * [InvoiceExportReadRepository.findPositionRows] call, so the query count grows with the
     * number of batches rather than the number of invoices.
     *
     * `@Bulkhead`, same property and reasoning as the single-invoice overload above.
     * This overload is the heavier of the two (an unbounded number of matched invoices held in
     * `headers`/`positions` before anything is written out), so it is the one most likely to
     * actually hit the limit under a concurrent-export burst.
     */
    @Bulkhead(20)
    override fun writeWorkbook(filter: InvoiceExportFilter, appliedSelection: String, translate: (String) -> String, output: OutputStream) {
        val headers = mutableListOf<InvoiceHeaderExportRow>()
        val positions = mutableListOf<InvoicePositionExportRow>()
        collectHeaderBatches(filter) { batch ->
            headers += batch
            val positionsByInvoice = readRepository.findPositionRows(batch.map { it.invoiceId })
            batch.forEach { positions += positionsByInvoice[it.invoiceId].orEmpty() }
        }
        val labels = buildInvoiceWorkbookExportLabels(translate, ExportSheetMode.FILTERED_LIST)

        writeWorkbookCore(labels, appliedSelection, WorkbookSheetData(headers, positions), output)
    }

    /** [writeWorkbook]/[writePositionsCsv]'s shared batched header scan — streams
     *  [InvoiceExportReadRepository.DEFAULT_BATCH_SIZE] batches to [onBatch] instead of
     *  materialising the whole filtered result at once. Extracted so neither caller's loop needs
     *  more than one jump statement (LoopWithTooManyJumpStatements): "no more rows" and
     *  "short batch" both just clear [hasMore] instead of `break`-ing. */
    private fun collectHeaderBatches(filter: InvoiceExportFilter, onBatch: (List<InvoiceHeaderExportRow>) -> Unit) {
        var afterId: Long? = null
        var hasMore = true
        while (hasMore) {
            val batch = readRepository.findHeaderRows(filter, afterId)
            if (batch.isEmpty()) {
                hasMore = false
            } else {
                onBatch(batch)
                afterId = batch.last().invoiceId
                hasMore = batch.size >= InvoiceExportReadRepository.DEFAULT_BATCH_SIZE
            }
        }
    }

    /** Shared workbook assembly for mode A (single invoice + journal sheet) and mode B
     *  (filtered-list, no journal sheet). Writes header, position and optionally journal
     *  sheets via [InvoiceHeaderSheetWriter]/[InvoicePositionSheetWriter]/[InvoiceJournalSheetWriter]
     *  followed by [ExportSheetWriter] and the final stream. */
    /** The sheet data [writeWorkbookCore] writes — [WorkbookSheetData.journal] `null` for mode B
     *  (filtered-list, no journal sheet). Bundled so that function takes one parameter for it
     *  instead of three (LongParameterList). */
    private data class WorkbookSheetData(
        val headers: List<InvoiceHeaderExportRow>,
        val positions: List<InvoicePositionExportRow>,
        val journal: List<InvoiceJournalExportRow>? = null,
    )

    private fun writeWorkbookCore(
        labels: InvoiceWorkbookExportLabels,
        appliedSelection: String,
        data: WorkbookSheetData,
        output: OutputStream,
    ) {
        val (headers, positions, journal) = data
        val productLine = applicationIdentity.productLine()
        WorkbookWriter().use { writer ->
            writer.setCoreProperties(title = "$productLine — $appliedSelection", creator = productLine, application = productLine)
            val headerSheet = InvoiceHeaderSheetWriter.write(
                writer, labels.headerSheet, headers,
                positionSheetLastDataRow = FIRST_DATA_ROW - 1 + positions.size,
            )
            val positionSheet = InvoicePositionSheetWriter.write(writer, labels.positionSheet, positions)
            val journalSheet = journal?.let { InvoiceJournalSheetWriter.write(writer, labels.journalSheet, it) }

            val metadata = ExportMetadata(
                productLine = productLine,
                generatedAt = time.nowUtc(),
                actingUser = auditActorProvider.currentActor(),
                appliedSelection = appliedSelection,
                applicationVersion = applicationIdentity.version(),
            )
            val summaries = buildList {
                add(ExportSheetSummary(headerSheet.name, headerSheet.recordCount))
                add(ExportSheetSummary(positionSheet.name, positionSheet.recordCount))
                journalSheet?.let { add(ExportSheetSummary(it.name, it.recordCount)) }
            }
            ExportSheetWriter.write(writer, labels.exportSheet, metadata, summaries)
            writer.write(output)
        }
    }

    /** The positions-only CSV (R5), mode B's own: every position of every
     *  invoice [filter] matches, batched so the whole result is never materialised at once, the
     *  same shape [writeWorkbook]'s mode-B overload uses. Mode A's single-invoice counterpart was
     *  deleted — see [de.littletrotter.application.export.port.outbound.InvoiceWorkbookExportPort]'s
     *  own KDoc for why. */
    override fun writePositionsCsv(filter: InvoiceExportFilter, translate: (String) -> String, output: OutputStream) {
        val positions = mutableListOf<InvoicePositionExportRow>()
        collectHeaderBatches(filter) { batch ->
            val positionsByInvoice = readRepository.findPositionRows(batch.map { it.invoiceId })
            batch.forEach { positions += positionsByInvoice[it.invoiceId].orEmpty() }
        }
        val labels = buildInvoicePositionSheetLabels(translate)
        InvoicePositionCsvWriter.write(output, labels, positions)
    }

    private companion object {
        /** Excel is 1-based and row 1 is the header, so the first data row is row 2 — mirrors
         *  [InvoiceHeaderSheetWriter]'s own private constant, needed here only to compute the
         *  empty-positions edge case's `positionSheetLastDataRow` (row 1, an empty-but-valid
         *  range) the same way the writer's own tests already establish that convention. */
        const val FIRST_DATA_ROW = 2
    }
}
