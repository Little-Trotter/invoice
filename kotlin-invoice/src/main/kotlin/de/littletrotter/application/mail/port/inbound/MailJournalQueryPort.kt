package de.littletrotter.application.mail.port.inbound

/**
 * Reads the mail journal kept by the mail component: one filtered, sorted page, the size of the
 * whole filtered set for the pager, and the per-status counts for the status chips.
 *
 * Reads only; the journal is owned by the component, so the host never caches it and always shows
 * the component's current state.
 */
interface MailJournalQueryPort {

    /** The page [criteria] describes. */
    fun findPage(criteria: MailJournalCriteria): List<MailJournalEntry>

    /** How many rows [criteria] matches in total, ignoring its page window. */
    fun count(criteria: MailJournalCriteria): Long

    /** Rows per status under [criteria] without its own status filter; absent statuses count zero. */
    fun countByStatus(criteria: MailJournalCriteria): Map<MailJournalStatus, Long>
}
