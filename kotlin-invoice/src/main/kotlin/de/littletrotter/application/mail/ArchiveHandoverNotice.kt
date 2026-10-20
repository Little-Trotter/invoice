package de.littletrotter.application.mail

import java.time.Instant

/** Why administrators are told about the archive hand-over. */
enum class ArchiveHandoverNoticeReason { ADDRESS_MISSING, HANDOVER_FAILED }

/** The mail component's report that an administrator has to act on the archive hand-over of one sent mail. */
data class ArchiveHandoverNotice(
    val journalId: Long,
    val referenceKey: String,
    val reason: ArchiveHandoverNoticeReason,
    val error: String?,
    val occurredAt: Instant,
)
