package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.shared.Money
import de.littletrotter.ui.format.MoneyFormatter

/**
 * The "trip" family:
 * [TRIP_SAVED][de.littletrotter.domain.compliance.InvoiceAuditAction.TRIP_SAVED], set by
 * `TripService` for every write to `trip_expense` (hotel/transport/mileage/per-diem/travel-time
 * lines). Names the trip line's own description and net amount rather than trying to distinguish
 * "added" from "changed" — `TripService` may touch several `trip_expense` rows in one save
 * (a whole day re-priced at once), so this composer summarizes every row rather than picking one.
 */
object TripFamilyComposer : JournalWhatComposer {

    private const val DESCRIPTION_COLUMN = "description"
    private const val NET_AMOUNT_COLUMN = "net_amount"

    override fun compose(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        val tr = ctx.tr
        val tripRows = line.rows.filter {
            it.event == InvoiceAuditEvent.TRIP_EXPENSE_CREATED ||
                it.event == InvoiceAuditEvent.TRIP_EXPENSE_UPDATED ||
                it.event == InvoiceAuditEvent.TRIP_EXPENSE_DELETED
        }
        if (tripRows.isEmpty()) return null

        val phrases = tripRows.map { row ->
            val description = JournalComposerSupport.firstSnapshot(listOf(row), DESCRIPTION_COLUMN)
                ?: JournalComposerSupport.firstDiff(listOf(row), DESCRIPTION_COLUMN)?.second
                ?: tr("journal.what.tripLineUnknown", emptyList())
            val amount = (
                JournalComposerSupport.firstSnapshot(listOf(row), NET_AMOUNT_COLUMN)
                    ?: JournalComposerSupport.firstDiff(listOf(row), NET_AMOUNT_COLUMN)?.second
                )?.toBigDecimalOrNull()?.let { MoneyFormatter.format(Money.of(it)) }
            if (amount != null) tr("journal.what.tripLineWithAmount", listOf(description, amount)) else description
        }
        return phrases.distinct().joinToString("; ")
    }
}
