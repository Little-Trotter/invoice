package de.littletrotter.application.settings.port.inbound

import de.littletrotter.shared.wording.WordingProfile

/** Read-only view of the active wording profile, for callers that only need to branch on it
 *  (e.g. which tabs a sender form shows). */
fun interface WordingProfileQueryPort {
    fun currentProfile(): WordingProfile
}
