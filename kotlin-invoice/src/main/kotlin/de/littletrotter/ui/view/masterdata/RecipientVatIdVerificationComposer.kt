package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.PendingVatIdVerification
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.masterdata.VatIdVerificationPort
import de.littletrotter.application.masterdata.VatIdVerificationResult
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.value.VatIdValidator
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.component.VatIdField
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.notification.UiNotifications
import java.time.OffsetDateTime

private const val VAT_ID_VERIFY_ACTION_KEY = "recipient.vatId.verify.action"
private const val RECIPIENT_VAT_ID_INVALID_FORMAT_KEY = "recipient.vatId.verify.invalidFormat"

/** Provenance of the currently displayed VERIFIED state — tracked only in memory for the open
 *  dialog; there is no persisted column distinguishing "VIES said yes" from "the user manually
 *  attested it" (both write the same `vat_id_verification_status`/`vat_id_verified_at` columns,
 *  see [de.littletrotter.application.masterdata.ManageRecipient]'s reconciliation). */
private enum class VatIdProvenance { VIES, MANUAL }

/** The five Vaadin fields this composer operates on, bundled into one constructor parameter
 *  (LongParameterList) — all still declared and constructed by [RecipientView] itself, so the
 *  existing field-name reflection in the Karibu tests keeps working. */
internal data class VatIdFormFields(
    val vatIdField: VatIdField,
    val verifyButton: Button,
    val validatedCheckbox: Checkbox,
    val failedBadge: Span,
    val validationNoteField: TextField,
)

/**
 * [RecipientView]'s VAT-ID verification section, extracted as a composer: the manual
 * attestation checkbox/badge/note and the on-demand VIES check ([verify]). [pendingVerification]
 * exposes whatever this session's [verify] recorded, and [validatedByUser] the checkbox's current
 * state, so [RecipientView] can carry both onto its
 * [de.littletrotter.application.dto.RecipientDraft] — reconciling either onto the recipient
 * actually persisted is [de.littletrotter.application.masterdata.ManageRecipient]'s job now, not
 * this composer's. Operates on Vaadin components [RecipientView] still constructs and owns (so the
 * existing field-name reflection in the Karibu tests keeps working); this class owns only the
 * behaviour around them.
 */
internal class RecipientVatIdVerificationComposer(
    private val fields: VatIdFormFields,
    private val vatIdVerificationPort: VatIdVerificationPort,
    private val time: TimeSource,
    private val trRaw: (String, Array<out Any>) -> String,
) {
    private val vatIdField get() = fields.vatIdField
    private val vatIdVerifyButton get() = fields.verifyButton
    private val vatIdValidatedCheckbox get() = fields.validatedCheckbox
    private val vatIdFailedBadge get() = fields.failedBadge
    private val vatIdValidationNoteField get() = fields.validationNoteField

    private fun tr(key: String, vararg params: Any): String = trRaw(key, params)

    /** `null` until [verify] records an outcome for the currently open dialog; reset on every
     *  dialog open ([populate]/[clear]) so a verification from a previous recipient never leaks
     *  into a different one. */
    private var pendingVerification: PendingVatIdVerification? = null

    private var vatIdProvenance: VatIdProvenance? = null

    /** The status/timestamp currently shown by [vatIdValidatedCheckbox]/[vatIdFailedBadge] —
     *  mirrors the loaded recipient's status right after [populate]/[clear], then follows [verify]
     *  and manual checkbox toggles for the rest of the dialog session as a live preview of what a
     *  save would persist. */
    private var displayedVatIdStatus: VatIdVerificationStatus? = null
    private var displayedVatIdVerifiedAt: OffsetDateTime? = null

    /** The trader name/address VIES returned for the current VERIFIED-via-VIES outcome, joined by
     *  `", "` (only the parts VIES actually delivered). `null`/blank whenever the current VERIFIED
     *  status is not a fresh VIES result in this dialog session. */
    private var displayedVatIdViesDetail: String? = null

    init {
        vatIdVerifyButton.addClickListener { verify() }
        vatIdValidatedCheckbox.addValueChangeListener {
            // Any value change from here on — a real click or a Karibu test simulating one —
            // supersedes whatever provenance a prior VIES call or dialog load established for the
            // status currently on screen.
            vatIdProvenance = VatIdProvenance.MANUAL
            displayedVatIdViesDetail = null
            displayedVatIdStatus = if (vatIdValidatedCheckbox.value) VatIdVerificationStatus.VERIFIED else VatIdVerificationStatus.UNVERIFIED
            displayedVatIdVerifiedAt = if (vatIdValidatedCheckbox.value) time.nowUtc() else null
            updateVatIdHelperText()
        }
    }

    private fun formatVatIdDate(at: OffsetDateTime): String =
        DateFormatter.format(at.atZoneSameInstant(GermanTime.ZONE).toLocalDate())

    /**
     * Refreshes [vatIdValidatedCheckbox]'s helper text and [vatIdFailedBadge]'s visibility.
     * FAILED always shows the badge; VERIFIED shows the timestamp, with provenance wording when
     * it is actually known for this session ("via VIES"/"manuell") — a VERIFIED status loaded from
     * a previous session (no in-session verify/checkbox action yet) shows the timestamp alone,
     * never a guessed provenance. Anything else (UNVERIFIED/null) shows the plain explanation.
     */
    private fun updateVatIdHelperText() {
        val status = displayedVatIdStatus
        val at = displayedVatIdVerifiedAt
        vatIdFailedBadge.isVisible = status == VatIdVerificationStatus.FAILED
        if (status == VatIdVerificationStatus.FAILED && at != null) {
            vatIdFailedBadge.text = tr("recipient.vatIdValidated.failedBadge", formatVatIdDate(at))
        }
        vatIdValidatedCheckbox.helperText = if (status == VatIdVerificationStatus.VERIFIED && at != null) {
            when (vatIdProvenance) {
                VatIdProvenance.VIES -> {
                    val detail = displayedVatIdViesDetail
                    // displayedVatIdViesDetail's only writer (handleVerified) already
                    // applies `.ifBlank { null }`, so it is always either null or non-blank —
                    // never a non-null blank string; `detail != null` is the whole check.
                    if (detail != null) {
                        tr("recipient.vatIdValidated.helper.verifiedViesDetail", formatVatIdDate(at), detail)
                    } else {
                        tr("recipient.vatIdValidated.helper.verifiedVies", formatVatIdDate(at))
                    }
                }
                VatIdProvenance.MANUAL -> tr("recipient.vatIdValidated.helper.verifiedManual", formatVatIdDate(at))
                null -> tr("recipient.vatIdValidated.helper.verifiedTimestamp", formatVatIdDate(at))
            }
        } else {
            tr("recipient.vatIdValidated.helper")
        }
    }

    /**
     * The VIES button is only enabled while [vatIdField] is non-blank, format-valid and
     * [VatIdValidator.isViesCountry] — asking VIES about a formally invalid or non-EU id is a
     * guaranteed, confusing "unreachable". Disabled, the button's tooltip names the reason
     * instead of leaving the user to guess why nothing happens on click.
     */
    fun updateVatIdVerifyButtonState() {
        val normalised = VatIdValidator.normalize(vatIdField.value)
        val formatValid = normalised.isNotBlank() && VatIdValidator.violationOf(normalised) == null
        val viesCountry = VatIdValidator.isViesCountry(normalised)
        val enabled = formatValid && viesCountry
        vatIdVerifyButton.isEnabled = enabled
        val reason = when {
            !formatValid -> tr("shared.vatId.vies.disabled.format")
            !viesCountry -> tr("shared.vatId.vies.disabled.nonEu")
            else -> tr(VAT_ID_VERIFY_ACTION_KEY)
        }
        vatIdVerifyButton.setAriaLabel(reason)
        vatIdVerifyButton.element.setAttribute("title", reason)
    }

    /**
     * Loads [recipient]'s VAT-ID verification state into the checkbox/badge/note, resetting any
     * verification recorded for a previously open dialog. Called from [RecipientView.populateForm]
     * after every other relevant field is at its final loaded value.
     */
    fun populate(recipient: RecipientDetailDto) =
        applyLoadedState(recipient.vatIdVerificationStatus, recipient.vatIdVerifiedAt, recipient.vatIdVerificationNote)

    /** The manual attestation checkbox's current state, for
     *  [de.littletrotter.application.dto.RecipientDraft.vatIdValidatedByUser]. */
    fun validatedByUser(): Boolean = vatIdValidatedCheckbox.value

    /** Whatever [verify] recorded for the currently open dialog, or `null` — for
     *  [de.littletrotter.application.dto.RecipientDraft.pendingVatIdVerification]. */
    fun pendingVerification(): PendingVatIdVerification? = pendingVerification

    private fun applyLoadedState(status: VatIdVerificationStatus?, verifiedAt: OffsetDateTime?, note: String?) {
        pendingVerification = null
        vatIdValidatedCheckbox.value = status == VatIdVerificationStatus.VERIFIED
        displayedVatIdStatus = status
        displayedVatIdVerifiedAt = verifiedAt
        vatIdProvenance = null
        displayedVatIdViesDetail = null
        updateVatIdHelperText()
        vatIdValidationNoteField.value = UiNotifications.orEmpty(note)
    }

    /** Resets the checkbox/badge back to a fresh, never-verified state. Called from
     *  [RecipientView.clearForm]. */
    fun clear() {
        pendingVerification = null
        vatIdValidatedCheckbox.clear()
        displayedVatIdStatus = null
        displayedVatIdVerifiedAt = null
        vatIdProvenance = null
        displayedVatIdViesDetail = null
        updateVatIdHelperText()
    }

    /**
     * VIES verification on user request.
     *
     * Only triggered by the verify button; format validation ([VatIdValidator]) runs first.
     * Unreachability is not treated as a validation error — a failed API call does not change the
     * status (it remains unchanged).
     */
    fun verify() {
        // The button is disabled while this would fail (updateVatIdVerifyButtonState), but
        // validateNow() is the one place that normalises/auto-prefixes the field — running it
        // here as well keeps this method correct even when called directly, as Karibu tests do,
        // bypassing the button's own enabled state.
        if (!vatIdField.validateNow()) {
            UiNotifications.errorOn(vatIdField, vatIdField.errorMessage.orEmpty().ifBlank { tr(RECIPIENT_VAT_ID_INVALID_FORMAT_KEY) })
            return
        }
        val normalisedVatId = vatIdField.value
        if (normalisedVatId.isBlank()) {
            UiNotifications.errorOn(vatIdField, tr(RECIPIENT_VAT_ID_INVALID_FORMAT_KEY))
            return
        }

        // Plausibility: VIES only answers for EU member states. GB/NO/US/CA/AU/NZ pass the format
        // check but have no VIES entry, and asking anyway yields UNREACHABLE — indistinguishable
        // from a real outage. Say what is actually the case instead.
        if (!VatIdValidator.isViesCountry(normalisedVatId)) {
            UiNotifications.info(tr("recipient.vatId.verify.nonEuCountry", VatIdValidator.prefixOf(normalisedVatId)))
            return
        }

        val countryCode = VatIdValidator.prefixOf(normalisedVatId)
        val vatIdNumber = VatIdValidator.numberOf(normalisedVatId)
        // Every VatIdValidator.COUNTRY_PATTERNS entry requires at least two trailing
        // characters after its two-letter prefix (RO's `\d{2,10}` is the shortest); validateNow()
        // above already requires a full pattern match before this point, so `vatIdNumber` can
        // never be blank once isViesCountry has confirmed a VIES-recognised prefix. The
        // blank-guard this replaced was dead.

        // Call VIES; unreachability leaves the status unchanged.
        when (val result = vatIdVerificationPort.verify(countryCode, vatIdNumber)) {
            is VatIdVerificationResult.VERIFIED -> handleVerified(normalisedVatId, result)
            VatIdVerificationResult.FAILED -> handleFailed(normalisedVatId)
            is VatIdVerificationResult.UNREACHABLE -> handleUnreachable(countryCode, result)
        }
    }

    /**
     * Records the VERIFIED outcome (checkbox, provenance, [pendingVerification]), plus the trader
     * name/address VIES returned: shown in the checkbox helper and, when [vatIdValidationNoteField]
     * is still blank, used to prefill it. A non-blank note is never overwritten.
     */
    private fun handleVerified(normalisedVatId: String, result: VatIdVerificationResult.VERIFIED) {
        val verifiedAt = time.nowUtc()
        pendingVerification = PendingVatIdVerification(normalisedVatId, VatIdVerificationStatus.VERIFIED, verifiedAt)
        displayedVatIdStatus = VatIdVerificationStatus.VERIFIED
        displayedVatIdVerifiedAt = verifiedAt
        val detail = listOfNotNull(result.name?.trim()?.ifBlank { null }, result.address?.trim()?.ifBlank { null }).joinToString(", ")
        // The checkbox mirrors whatever the automated check just found — a manual attestation
        // must not silently disagree with a fresh VIES result. Provenance/detail are stamped AFTER
        // the assignment below: the checkbox's own listener fires on it and would otherwise flag
        // this as a MANUAL toggle and clear the detail again.
        vatIdValidatedCheckbox.value = true
        vatIdProvenance = VatIdProvenance.VIES
        displayedVatIdViesDetail = detail.ifBlank { null }
        updateVatIdHelperText()
        if (vatIdValidationNoteField.value.isBlank()) {
            vatIdValidationNoteField.value = if (detail.isBlank()) {
                tr("recipient.vatId.verify.notePrefill.plain", formatVatIdDate(verifiedAt))
            } else {
                tr("recipient.vatId.verify.notePrefill", formatVatIdDate(verifiedAt), detail)
            }
        }
        UiNotifications.confirmOn(vatIdVerifyButton)
    }

    private fun handleFailed(normalisedVatId: String) {
        val verifiedAt = time.nowUtc()
        pendingVerification = PendingVatIdVerification(normalisedVatId, VatIdVerificationStatus.FAILED, verifiedAt)
        displayedVatIdStatus = VatIdVerificationStatus.FAILED
        displayedVatIdVerifiedAt = verifiedAt
        // FAILED is not VERIFIED — the checkbox (which can only mean VERIFIED) must reflect that,
        // even if it happened to be checked before this run.
        vatIdValidatedCheckbox.value = false
        vatIdProvenance = VatIdProvenance.VIES
        updateVatIdHelperText()
        // Warning, not error: VIES saying "not valid" is information about the id, never a reason
        // to refuse the record — the customer stays saveable with FAILED and the red badge showing.
        UiNotifications.warning(tr("recipient.vatId.verify.failed"))
    }

    /** Names the VIES `userError` reason instead of a generic "unreachable" for every code the
     *  spec table lists. Status stays unchanged in every case. */
    private fun handleUnreachable(countryCode: String, result: VatIdVerificationResult.UNREACHABLE) {
        val message = when (result.reason) {
            "MS_UNAVAILABLE" -> tr("recipient.vatId.verify.unreachable.msUnavailable", countryCode)
            "MS_MAX_CONCURRENT_REQ", "GLOBAL_MAX_CONCURRENT_REQ" -> tr("recipient.vatId.verify.unreachable.busy")
            "TIMEOUT", "SERVICE_UNAVAILABLE" -> tr("recipient.vatId.verify.unreachable.timeout")
            "IP_BLOCKED", "VAT_BLOCKED", "INVALID_REQUESTER_INFO" -> tr("recipient.vatId.verify.unreachable.blocked")
            "INVALID_INPUT" -> tr("recipient.vatId.verify.unreachable.badRequest")
            else -> tr("recipient.vatId.verify.unreachable")
        }
        // Status unchanged; unreachability is not a statement about the VAT ID. Warning, not
        // error, for the same reason as the FAILED branch.
        UiNotifications.warning(message)
    }
}
