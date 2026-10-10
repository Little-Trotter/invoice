package de.littletrotter.ui.view.masterdata

import de.littletrotter.domain.masterdata.LegalRegistrationIdentifier
import de.littletrotter.domain.masterdata.PartyIdentifier

/**
 * P2 — shared value+scheme→embeddable conversion for [SenderProfileView] and [RecipientView]'s
 * BT-29/30/34/46/47/49 form fields: both blank means "not set" (`null`); exactly one filled is a
 * validation error the embeddable's own `.of()` factory raises, surfaced to the user via the
 * existing generic `writeValidatedForm`/`persistEntity` exception handling rather than silently
 * dropped or silently accepted with a missing scheme.
 */
internal fun toPartyIdentifier(value: String, schemeId: String): PartyIdentifier? =
    if (value.isBlank() && schemeId.isBlank()) null else PartyIdentifier.of(value.trim(), schemeId.trim())

internal fun toLegalRegistrationIdentifier(value: String, schemeId: String): LegalRegistrationIdentifier? =
    if (value.isBlank() && schemeId.isBlank()) null else LegalRegistrationIdentifier.of(value.trim(), schemeId.trim())
