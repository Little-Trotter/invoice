package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.masterdata.port.inbound.DeleteSenderProfileUseCase
import de.littletrotter.application.masterdata.port.inbound.SaveSenderProfileUseCase
import de.littletrotter.application.masterdata.port.inbound.SenderProfileQueryPort
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * The three input ports [SenderProfileView] works through, bundled so the view's constructor
 * stays within the parameter limit (same reason as [SenderProfileDefaultsComposer]). Holds the
 * ports only; every use case stays its own role interface.
 */
@Dependent
class SenderProfilePorts @Inject constructor(
    val queries: SenderProfileQueryPort,
    val save: SaveSenderProfileUseCase,
    val delete: DeleteSenderProfileUseCase,
)
