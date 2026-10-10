package de.littletrotter.application.invoice

import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.application.dto.PrintJobDaySummary
import de.littletrotter.application.dto.PrintJobOverviewRow

/**
 * Read path for the global "Druckaufträge" view: every [de.littletrotter.domain.invoice
 * .GenerationJob] across every invoice, paged and filtered — mirrors [InvoiceOverviewQueryService]'s
 * shape (offset-based paging plus a matching count) for print jobs instead of invoices. Never
 * returns a managed entity, only the flat [PrintJobOverviewRow] projection.
 */
interface PrintJobOverviewQueryService {
    /** Paged listing, newest first (`createdAt DESC, id DESC`). To load one day's jobs (the
     *  TreeGrid's child rows), pass [filter] with `createdFrom`/`createdTo` narrowed to that day's
     *  own bounds — see [de.littletrotter.ui.view.invoice.PrintJobListView]'s own `dayFilter`. */
    fun findPaged(filter: GenerationJobFilter = GenerationJobFilter(), offset: Int = 0, limit: Int = 60): List<PrintJobOverviewRow>

    /** Row count matching [filter] — drives the callback data provider's total. */
    fun count(filter: GenerationJobFilter = GenerationJobFilter()): Long

    /** Per-day aggregate counts for [filter], newest day first; drives the TreeGrid's root
     *  (day) rows. The day list is derived from the same filtered set [findPaged]/[count] page
     *  through, so it always matches what a caller sees once a day row is expanded. */
    fun findDaySummaries(filter: GenerationJobFilter = GenerationJobFilter()): List<PrintJobDaySummary>
}
