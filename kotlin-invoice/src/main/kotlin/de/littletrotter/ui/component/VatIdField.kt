package de.littletrotter.ui.component

import com.vaadin.flow.component.Key
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.value.VatIdValidator
import java.text.MessageFormat

/**
 * Shared "one field, understood" VAT-id text field (`vat-id-field-ergonomics`, PO report
 * 14.09.2026): the country prefix and number stay one value (PO: "ein Feld ist eher Standard"),
 * but the field visibly separates them — auto-prefixing digit-only input from [countrySupplier],
 * showing the recognised country as a muted suffix, and validating the format live on blur.
 *
 * Used identically in `RecipientView` (billing-address country, VIES button alongside),
 * `SenderProfileView` (profile address country, no VIES) and `SellerVatRegistrationsSection`
 * (each row's own country combo, [mismatchIsError] = `true` since the row's country is
 * definitive there).
 *
 * @param tr resolves an i18n key to its (possibly `{0}`/`{1}`-parameterised) pattern string; a
 *   parameterised one is filled in via [MessageFormat], the same idiom
 *   `ScopedB2bProfileFieldsSection` already uses for a plain `(String) -> String` lambda.
 * @param countrySupplier the country this field is linked to — the billing/profile/row address
 *   country whose VAT prefix a bare digit-only input is completed with, and whose own prefix a
 *   recognised, mismatching VAT-id prefix is compared against.
 * @param mismatchIsError `false` (customer, sender): a prefix that differs from the linked
 *   country is legitimate (a German company invoicing from an Austrian registration) and only
 *   shown as an informational helper. `true` (seller VAT registration row): the row's own country
 *   is definitive, so a mismatch is an error.
 */
class VatIdField(
    private val tr: (String) -> String,
    private val countrySupplier: () -> Country?,
    private val mismatchIsError: Boolean = false,
) : TextField() {

    private val suffixSpan = Span().apply { addClassNames("fs-08", "text-muted") }

    /** The VAT prefix [onLinkedCountryChanged]/[applyCountryPrefill] last saw for the linked
     *  country — lets a later call tell "the field still holds nothing but that earlier
     *  auto-inserted prefix" apart from "the user typed something real", the rule
     *  `SellerVatRegistrationsSection` used to implement for itself. */
    private var lastLinkedPrefix: String? = null

    /** Set by [applyNormalizationAndPrefix] when that call itself added the prefix — drives the
     *  one-shot `shared.vatId.prefixAdded` helper text; cleared on the next client-driven edit so
     *  the hint does not linger past the edit that made it stale. */
    private var justAutoPrefixed = false

    init {
        suffixComponent = suffixSpan
        suffixSpan.isVisible = false
        addBlurListener { applyNormalizationAndPrefix() }
        addKeyDownListener(Key.ENTER, { applyNormalizationAndPrefix() })
        addValueChangeListener { event -> if (event.isFromClient) justAutoPrefixed = false }
        applyPlaceholder()
    }

    /**
     * Runs the full blur-time pass: normalise (whitespace stripped, upper-cased), auto-prefix a
     * digit-only value from [countrySupplier] (P1), write the result back so the user sees
     * exactly what will be stored/queried, then re-derive validity/suffix/mismatch/placeholder.
     *
     * Also what [validateNow] and the customer VIES button (`RecipientView.verifyVatId`) use to
     * force this pass before acting on the field's current value.
     */
    private fun applyNormalizationAndPrefix() {
        val normalised = VatIdValidator.normalize(value)
        var target = normalised
        var autoPrefixed = false
        if (target.isNotEmpty() && target.first().isDigit()) {
            val prefix = countrySupplier()?.let { VatIdValidator.vatPrefixFor(it) }
            if (prefix != null) {
                target = prefix + target
                autoPrefixed = true
            }
        }
        justAutoPrefixed = autoPrefixed
        if (target != value) value = target
        updateDerivedState()
    }

    /**
     * Called by the owning view whenever the linked country changes (`AddressFormSection
     * .onCountryChange`): re-derives the placeholder and the prefix-mismatch helper/error for the
     * field's CURRENT value, without touching it — a passive country switch (e.g. the customer's
     * billing country) must never silently rewrite a VAT id the user already typed.
     */
    fun onLinkedCountryChanged() {
        lastLinkedPrefix = countrySupplier()?.let { VatIdValidator.vatPrefixFor(it) }
        updateDerivedState()
    }

    /**
     * `SellerVatRegistrationsSection`'s own former prefill rule, now the one shared
     * implementation: picking a country seeds a BLANK field with that country's VAT prefix, and
     * REPLACES a value that still holds nothing but the row's previous auto-inserted prefix — but
     * never touches real, user-typed content. Called from the row's country combo's own
     * client-driven value-change listener.
     */
    fun applyCountryPrefill() {
        val newPrefix = countrySupplier()?.let { VatIdValidator.vatPrefixFor(it) }
        val current = value.orEmpty()
        if (newPrefix != null && (current.isBlank() || current == lastLinkedPrefix)) {
            value = newPrefix
        }
        lastLinkedPrefix = newPrefix
        updateDerivedState()
    }

    /**
     * Forces the full blur-time pass and reports whether the field is valid afterwards — the
     * small API the owning view's save-time validation calls (`RecipientView.validateForm`,
     * `SenderProfileView`'s binder validator, the customer VIES button).
     */
    fun validateNow(): Boolean {
        applyNormalizationAndPrefix()
        return !isInvalid
    }

    private fun updateDerivedState() {
        val normalised = VatIdValidator.normalize(value)
        val violation = VatIdValidator.violationOf(normalised)
        applySuffix(normalised)
        applyPlaceholder()

        val linkedPrefix = countrySupplier()?.let { VatIdValidator.vatPrefixFor(it) }
        val ownPrefix = VatIdValidator.prefixOf(normalised).takeIf { it in VatIdValidator.supportedCountryPrefixes() }
        val mismatch = linkedPrefix != null && ownPrefix != null && linkedPrefix != ownPrefix

        when {
            violation != null -> {
                isInvalid = true
                errorMessage = errorMessageFor(violation, normalised)
                helperText = ""
            }
            mismatchIsError && mismatch -> {
                isInvalid = true
                errorMessage = mismatchMessage(ownPrefix, linkedPrefix)
                helperText = ""
            }
            else -> {
                isInvalid = false
                errorMessage = ""
                helperText = when {
                    normalised.isBlank() -> ""
                    justAutoPrefixed -> MessageFormat.format(tr("shared.vatId.prefixAdded"), ownPrefix ?: "")
                    mismatch -> mismatchMessage(ownPrefix, linkedPrefix)
                    else -> tr("shared.vatId.formatOk")
                }
            }
        }
    }

    /** `{1}` is the linked country's own VAT prefix (e.g. `"AT"`, `"DE"`) — matches the spec's
     *  literal example verbatim: "Kennung AT, Adresse DE — zulässig, bitte prüfen." */
    private fun mismatchMessage(ownPrefix: String, linkedPrefix: String): String =
        MessageFormat.format(tr("shared.vatId.countryMismatch"), ownPrefix, linkedPrefix)

    private fun errorMessageFor(violation: VatIdValidator.Violation, normalised: String): String {
        val prefix = normalised.take(2)
        return when (violation) {
            VatIdValidator.Violation.TOO_LONG -> MessageFormat.format(tr("validation.vatId.tooLong"), VatIdValidator.MAX_LENGTH.toString())
            VatIdValidator.Violation.NO_PREFIX, VatIdValidator.Violation.UNKNOWN_PREFIX ->
                MessageFormat.format(tr("validation.vatId.unknownPrefix"), prefix)
            VatIdValidator.Violation.FORMAT ->
                MessageFormat.format(tr("validation.vatId.format"), prefix, VatIdValidator.exampleFor(prefix))
        }
    }

    private fun applySuffix(normalised: String) {
        val prefix = VatIdValidator.prefixOf(normalised)
        val known = prefix.isNotEmpty() && prefix in VatIdValidator.supportedCountryPrefixes()
        suffixSpan.isVisible = known
        if (known) suffixSpan.text = countryNameForPrefix(prefix)
    }

    private fun applyPlaceholder() {
        val prefix = countrySupplier()?.let { VatIdValidator.vatPrefixFor(it) }
        placeholder = VatIdValidator.exampleFor(prefix.orEmpty())
    }

    /** The VAT prefix and the ISO alpha-2 code agree for every supported prefix except Greece
     *  ("EL" vs. "GR") — see [VatIdValidator.vatPrefixFor]'s own KDoc for why. */
    private fun countryNameForPrefix(prefix: String): String =
        countryDisplayName(if (prefix == "EL") "GR" else prefix)
}
