package de.littletrotter.shared.mail

/**
 * Whether a sent mail was handed over to the operator's mail archive — a second axis next to
 * [MailArchiveStatus], which only tells whether the internal archive package exists.
 * [NOT_CONFIGURED] means no archive address is set up; [DISABLED] means an address exists but the
 * handover is switched off for this document family.
 */
enum class MailArchiveHandoverStatus {
    NOT_CONFIGURED,
    DISABLED,
    PENDING,
    HANDED_OVER,
    FAILED,
}
