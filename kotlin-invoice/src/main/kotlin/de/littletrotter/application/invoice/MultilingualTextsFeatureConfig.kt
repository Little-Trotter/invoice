package de.littletrotter.application.invoice

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Deployment-level switch for capturing a second document language in the UI.
 *
 * **This flag gates UI capture only.** Nothing in the domain, the mapper, the exporter or datagen
 * may ever branch on it. A German invoice never moves regardless of this flag's value, and once a
 * [de.littletrotter.domain.reference.LocalizedTextGroup] carries an English entry, resolution
 * ([de.littletrotter.shared.reference.resolveLocalizedText]) uses it independent of this switch —
 * the flag only decides whether a user can *type* that entry in the first place. This is the
 * invariant a later change is most likely to break.
 */
@ApplicationScoped
class MultilingualTextsFeatureConfig(
    @param:ConfigProperty(name = "littletrotter.features.multilingual-texts.enabled", defaultValue = "false")
    val enabled: Boolean,
)
