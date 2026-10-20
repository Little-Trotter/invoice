package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.ui.component.ScopedB2bProfileFieldsSection
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * [SenderProfileView] should not receive every reference-data port just to
 * build/persist its own SENDER_PROFILE-scoped B2B section (the PO's "views should not receive
 * every port" ruling); this narrow CDI bean owns the B2B ports that section actually
 * needs and [SenderProfileView] injects it directly instead.
 *
 * The seller's own SELLER/BOTH-placed B2B
 * fields: the union across every active profile (the seller has no own profile
 * selector, see [B2bFieldDefinitionQueryPort.findB2bSellerFieldsUnion]).
 */
@Dependent
class SenderB2bSectionComposer @Inject constructor(
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val b2bFieldValueQueries: B2bFieldValueQueryPort,
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase,
) {
    /** Builds the section, pre-populated with the seller-wide union of SELLER/BOTH-placed fields.
     *  [tr] is the caller's own [de.littletrotter.ui.i18n.TranslationProvider.tr] — locale
     *  resolution needs a component context this bean does not have, so it stays the caller's job. */
    fun buildSection(tr: (String) -> String): ScopedB2bProfileFieldsSection =
        ScopedB2bProfileFieldsSection(b2bFieldValueQueries, B2bFieldOwnerType.SENDER_PROFILE, tr).apply {
            setFields(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion())
        }

    /** Persists [section]'s current values for [senderId]; unconditional on
     *  current group visibility (a hidden country group's typed value is written back exactly
     *  like a visible one). */
    fun persist(senderId: Long, section: ScopedB2bProfileFieldsSection) {
        manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.SENDER_PROFILE, senderId, section.currentValues())
    }
}
