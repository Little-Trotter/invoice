package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.inbound.QuoteSendGateState
import de.littletrotter.shared.quote.QuoteSendGate

/** Contract value to local value; both enums carry the same constants. */
internal fun QuoteSendGate.toState(): QuoteSendGateState = when (this) {
    QuoteSendGate.READY -> QuoteSendGateState.READY
    QuoteSendGate.NOT_IN_REVIEW -> QuoteSendGateState.NOT_IN_REVIEW
    QuoteSendGate.COPY_IN_FLIGHT -> QuoteSendGateState.COPY_IN_FLIGHT
    QuoteSendGate.COPY_STALE -> QuoteSendGateState.COPY_STALE
    QuoteSendGate.COPY_MISSING -> QuoteSendGateState.COPY_MISSING
    QuoteSendGate.NO_RECIPIENT_EMAIL -> QuoteSendGateState.NO_RECIPIENT_EMAIL
}

/** The local value named by a wire text; `null` for an unknown name. */
internal fun sendGateStateOf(name: String): QuoteSendGateState? =
    QuoteSendGate.entries.firstOrNull { it.name == name }?.toState()
