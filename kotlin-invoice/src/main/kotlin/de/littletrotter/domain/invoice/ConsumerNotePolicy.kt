package de.littletrotter.domain.invoice

import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.wording.WordingProfile

/**
 * Decides whether an invoice carries the private-customer notice. The notice is a craft-trade
 * convention: it applies only to private recipients, only under the craft-trade wording profile,
 * and only while the sender has not switched it off.
 */
object ConsumerNotePolicy {
    fun applies(profile: WordingProfile, customerType: CustomerType?, enabled: Boolean): Boolean =
        enabled && profile == WordingProfile.HANDWERK && customerType == CustomerType.CONSUMER
}
