package de.littletrotter.arch.specimen.ui

import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.settings.FeatureSetting

/**
 * Test-only specimens for [de.littletrotter.arch.UiEntityBoundaryArchitectureTest]: screens that
 * reach past the read models into persistent domain types, proving the rule rejects them. The
 * last class is the accepted way and must stay unreported.
 */
class ViolatingEntityReader {
    fun label(setting: FeatureSetting): String = setting.toString()
}

class ViolatingEmbeddableReader {
    fun label(identifier: PartyIdentifier): String = identifier.toString()
}

class AcceptedReadModelReader {
    fun label(view: AcceptedReadModel): String = view.label
}

data class AcceptedReadModel(val label: String)
