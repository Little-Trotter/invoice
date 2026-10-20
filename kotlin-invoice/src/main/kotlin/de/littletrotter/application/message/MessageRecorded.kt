package de.littletrotter.application.message

/**
 * Fired by [MessageCenterService.record] for every stored message. Observed after the recording
 * transaction commits, so live views (the bell badge) can re-read the unread count without racing
 * the insert. Carries no payload: visibility depends on the viewer, so each view asks for its own
 * count.
 */
data object MessageRecorded
