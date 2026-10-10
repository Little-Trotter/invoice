package de.littletrotter.ui.view.journal

import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.shared.Money

/**
 * The "Δ Brutto" column: the signed change to `invoice.gross_amount` (the real column
 * name, `V1__baseline_schema.sql` section 3 — NOT a guess) an [InvoiceAuditActionLine] made, or
 * `null` when none of the action's technical rows touched it (an `INSERT` snapshot carries no
 * old/new pair at all — [JournalDetailRenderer] only ever renders a [JournalDetailRendering.Diff]
 * for an `_UPDATED` event — so a fresh invoice's own creation naturally yields no delta here,
 * without this object special-casing [de.littletrotter.domain.compliance.InvoiceAuditAction
 * .INVOICE_CREATED]).
 *
 * Sums across every row that carries a `gross_amount` change (a `STORNO` transaction may touch the
 * column on more than one of its two invoices) — [Money], never raw `BigDecimal` (project
 * convention).
 */
object JournalGrossDelta {

    private const val GROSS_AMOUNT_COLUMN = "gross_amount"

    fun compute(line: InvoiceAuditActionLine): Money? = compute(line.rows)

    fun compute(rows: List<InvoiceAuditJournalEntry>): Money? {
        val deltas = rows.mapNotNull { row ->
            val rendering = JournalDetailRenderer.render(row.event, row.detail)
            val change = (rendering as? JournalDetailRendering.Diff)?.changes
                ?.firstOrNull { it.field == GROSS_AMOUNT_COLUMN }
                ?: return@mapNotNull null
            val old = change.oldValue?.toBigDecimalOrNull() ?: return@mapNotNull null
            val new = change.newValue?.toBigDecimalOrNull() ?: return@mapNotNull null
            Money.of(new) - Money.of(old)
        }
        if (deltas.isEmpty()) return null
        return deltas.fold(Money.ZERO) { acc, delta -> acc + delta }
    }
}
