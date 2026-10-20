package de.littletrotter.application.invoice

import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.invoice.veraPdfCheckStateOf
import de.littletrotter.domain.reference.ValidationCapabilityId
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Holds a send back while the veraPDF verdict of its document is outstanding. Reads the current
 * switch on every call, so switching the check off releases waiting documents the next time their
 * send is looked at. Findings and an impossible check never hold anything back.
 */
@ApplicationScoped
class VeraPdfSendGuard @Inject constructor(
    private val settings: ValidationSettingsGate,
) {
    fun mustWait(kositReport: String?): Boolean =
        veraPdfCheckStateOf(kositReport).holdsBackSending(settings.isEnabled(ValidationCapabilityId.VERAPDF_CHECK))
}
