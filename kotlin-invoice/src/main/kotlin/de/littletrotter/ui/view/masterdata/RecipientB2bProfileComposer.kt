package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.masterdata.port.inbound.RecipientB2bFieldsQueryPort
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.ui.component.B2bProfileFieldsSection

/**
 * [RecipientView]'s "B2B-Felder" section, extracted as a composer:
 * [b2bProfileCombo] is the customer's standing B2B profile default, and [b2bFieldsSection] the
 * per-customer values for that profile's `RECIPIENT`-placed fields (`recipient_b2b_field_value`).
 * Operates on Vaadin components [RecipientView] still constructs and owns (so the existing
 * field-name reflection in the Karibu tests keeps working); this class owns only the behaviour
 * around them.
 */
internal class RecipientB2bProfileComposer(
    private val b2bProfileCombo: ComboBox<B2bProfileDto>,
    private val b2bFieldsSection: B2bProfileFieldsSection,
    private val b2bFieldsContainer: Div,
    private val b2bFields: RecipientB2bFieldsQueryPort,
    /** The recipient the surrounding dialog is currently editing (`null` for a not-yet-persisted
     *  one) — a live accessor, since it can change while this composer is in use. */
    private val currentRecipientId: () -> Long?,
) {

    init {
        // The assigned profile can gain/lose RECIPIENT-placed fields at any time, so the
        // "B2B-Felder" section below has to react to every change of this ComboBox, not just the
        // one made when the dialog first opens (prepare/populate both go through `.value =`/
        // `.clear()`, which fire this listener the same way an interactive change does).
        b2bProfileCombo.addValueChangeListener { refresh() }
    }

    /** Rebuilds [b2bProfileCombo]'s item list for [current] — a profile no longer active still
     *  has to appear so an already-assigned recipient keeps showing it. */
    fun prepare(current: B2bProfileDto?) {
        val activeProfiles = b2bFields.findAllActiveB2bProfiles()
        val items = if (current != null && activeProfiles.none { it.id?.let { id -> id == current.id } == true }) {
            activeProfiles + current
        } else {
            activeProfiles
        }
        b2bProfileCombo.setItems(items)
    }

    /** Rebuilds [b2bFieldsSection] for [b2bProfileCombo]'s current value: field list first (a
     *  profile switch changes which terms even apply), then this recipient's own stored values —
     *  only for an already-persisted recipient. [b2bFieldsContainer] is only shown once there is
     *  actually something to maintain. */
    fun refresh() {
        val profileId = b2bProfileCombo.value?.id
        b2bFieldsSection.setFields(profileId?.let { b2bFields.findB2bRecipientFields(it) }.orEmpty())
        val recipientId = currentRecipientId()
        b2bFieldsSection.readFrom(
            if (recipientId != null && profileId != null) b2bFields.findB2bRecipientFieldValues(recipientId, profileId) else emptyMap(),
        )
        b2bFieldsContainer.isVisible = b2bFieldsSection.hasContent
    }

    /** Persists the per-customer B2B field values after the recipient itself was saved (rows
     *  reference `recipientId`). No-op without both a persisted recipient and an assigned profile,
     *  or when the section renders nothing for this profile. */
    fun persist(recipientId: Long?) {
        val profileId = b2bProfileCombo.value?.id
        if (recipientId != null && profileId != null && b2bFieldsSection.hasContent) {
            b2bFields.saveB2bRecipientFieldValues(recipientId, profileId, b2bFieldsSection.currentValues())
        }
    }
}
