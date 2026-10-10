package de.littletrotter.domain.compliance

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** One journal's chain head as it goes into `anchor.txt` — see [ChainAnchorFormat]. [chainHead] is
 *  the journal's newest `entry_hash` (empty string for a journal with zero rows — there is no
 *  entry yet to anchor). */
data class JournalAnchor(val journalName: String, val chainHead: String, val rowCount: Long)

/**
 * The canonical, unit-tested definition of
 * `anchor.txt`'s text format: what every backup archive carries out of the host so the chain head
 * of both journals survives independently of the database it was read from (see
 * `konzept-aenderungsjournal.md`'s "Ehrliche Grenze" section — a copy that leaves the host is the
 * only thing a host-local superuser cannot also rewrite).
 *
 * Production writes and reads this format entirely in shell — `admin-tools/scripts/backup
 * /backup.sh` builds it via `psql` against the read-only `lt_app` role with no dependency
 * on the application being up during a backup, and `backup-list.sh`/`restore.sh` parse it back
 * out the same way — never through this function at runtime. It is kept here anyway as the single
 * place the format is written down and tested: `backup.sh`'s own heredoc mirrors [format]'s output
 * byte-for-byte (see that script's own comment on the anchor step), and any change to one must be
 * carried over to the other by hand.
 */
object ChainAnchorFormat {

    fun format(generatedAt: OffsetDateTime, runId: String, journals: List<JournalAnchor>): String {
        val header = "# little-trotter-invoice chain-head anchor (RS-12, M6)\n" +
            "# generated=${generatedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} run=$runId\n"
        val lines = journals.joinToString("\n") { j ->
            "journal=${j.journalName} chain_head=${j.chainHead} row_count=${j.rowCount}"
        }
        return header + lines + "\n"
    }
}
