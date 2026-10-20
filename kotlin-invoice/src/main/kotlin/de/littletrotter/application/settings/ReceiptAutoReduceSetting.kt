package de.littletrotter.application.settings

import de.littletrotter.application.invoice.port.inbound.ReceiptAutoReduceQueryPort
import de.littletrotter.domain.settings.FeatureFlag
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Reads the receipt reduction switch from the feature settings (default on, fail-open). */
@ApplicationScoped
class ReceiptAutoReduceSetting @Inject constructor(
    private val gate: FeatureSettingsGate,
) : ReceiptAutoReduceQueryPort {
    override fun isAutoReduceEnabled(): Boolean = gate.isEnabled(FeatureFlag.RECEIPT_AUTO_REDUCE)
}
