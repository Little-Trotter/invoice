package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.shared.country.Country
import de.littletrotter.spi.party.SellerVatRegistrationRow
import de.littletrotter.spi.party.SellerVatRegistrationsExtension

/**
 * Generic rendering for the seller's additional VAT registrations (0..n rows of
 * country + local VAT id), mirroring [PartyExtensionsSection]'s pattern for a *repeatable* shape:
 * the section only knows *that* the seller may have such rows; the owning [extension] does the
 * actual read/write against the domain object, so its storage model never leaks into the view.
 *
 * Whether [extension] is present decides everything: `null` renders nothing — the case where no
 * such extension bean is on the classpath.
 */
class SellerVatRegistrationsSection(
    private val extension: SellerVatRegistrationsExtension?,
    private val tr: (String) -> String,
) : VerticalLayout() {

    val hasContent: Boolean get() = extension != null

    // internal, not private: Karibu tests drive a row's country ComboBox<String> directly by its
    // real type; the row bar only exposes it through its Vaadin Component children, which would
    // need an unchecked cast back to ComboBox<String> to get the type erasure otherwise loses.
    internal data class Row(val country: ComboBox<String>, val vatId: VatIdField, val bar: HorizontalLayout)

    internal val rows: MutableList<Row> = mutableListOf()
    private val rowsContainer = VerticalLayout().apply { isPadding = false; isSpacing = true; setWidthFull() }
    private val addButton = Button(tr("senderProfile.euVat.add"), ActionIcons.ADD.create())

    /** anbieter-einvoice-ergonomie §3 — listeners notified whenever a row is added, removed, or
     *  has its country changed: the set [currentCountries] returns may have changed, and the
     *  owning view uses this to recompute country-scoped B2B field visibility reactively (same
     *  wiring [de.littletrotter.ui.view.masterdata.SenderProfileView.updatePartySectionVisibility]
     *  already uses for [PartyExtensionsSection]). Not fired by [readFrom] itself — the caller
     *  recomputes visibility explicitly once, after every section has finished loading. */
    private val changeListeners = mutableListOf<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    private fun notifyChange() = changeListeners.forEach { it() }

    /** The countries of every row currently holding a selected country — used together with the
     *  seller's own [de.littletrotter.domain.masterdata.SenderProfile.vatId] to resolve
     *  [de.littletrotter.domain.masterdata.sellerRegistrationCountries]. Rows with no country
     *  chosen yet (a freshly added, still-blank row) contribute nothing. */
    fun currentCountries(): Set<Country> = rows.mapNotNull { it.country.value }.mapNotNull { Country.fromAlpha2(it) }.toSet()

    init {
        isPadding = false
        isSpacing = true
        setWidthFull()
        if (extension != null) {
            addButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            // New rows default to Luxembourg (PO decision) — the most common non-domestic
            // registration in practice, so it's a sensible starting point rather than blank.
            addButton.addClickListener { addRow("LU", ""); notifyChange() }
            add(rowsContainer, addButton)
        }
    }

    /** Loads the current rows off the domain [senderProfile] via the owning extension. */
    fun readFrom(senderProfile: Any) {
        rowsContainer.removeAll()
        rows.clear()
        extension?.read(senderProfile)?.forEach { addRow(it.country, it.vatId) }
    }

    /** Writes the current, in-flight row state onto the domain [senderProfile] via the owning
     *  extension. Blank rows (no country chosen and no VAT id typed) are dropped silently — they
     *  represent an add-then-abandon row, not a validation error. */
    fun writeTo(senderProfile: Any) {
        val extension = this.extension ?: return
        val submitted = rows.mapNotNull { row ->
            val country = row.country.value
            // VatIdField extends TextField, whose `value` never surfaces as null
            // (AbstractSinglePropertyField's reader falls back to the registered default, ""),
            // only Kotlin's Java-interop platform type left it looking nullable here.
            val vatId = row.vatId.value.trim()
            if (country.isNullOrBlank() && vatId.isBlank()) null
            else SellerVatRegistrationRow(country.orEmpty(), vatId)
        }
        extension.write(senderProfile, submitted)
    }

    private fun addRow(country: String, vatId: String) {
        // Both call sites (the add button's listener, registered only when extension != null; and
        // readFrom's extension?.read(...)?.forEach, which never invokes this lambda when extension
        // is null) already gate on a present extension — the null case is structurally
        // unreachable here, unlike the public writeTo's own `?: return` guard above.
        val extension = this.extension!!
        val countryField = ComboBox<String>().apply {
            label = tr("senderProfile.euVat.country")
            setItems(extension.supportedCountries)
            value = country.blankToNull()
            width = "8rem"
        }
        // The row's own country is definitive (mismatchIsError = true) — SellerVatRegistration
        // .validate requires the vat-id's prefix to match this row's chosen country, unlike the
        // customer/sender VatIdField instances where a mismatch is merely informational.
        val vatIdField = VatIdField(tr, { Country.fromAlpha2(countryField.value) }, mismatchIsError = true).apply {
            label = tr("senderProfile.euVat.vatId")
            value = vatId
            setWidthFull()
        }
        // Picking/changing the row's country seeds a BLANK vat-id field with that country's VAT
        // prefix, and REPLACES a value that still holds nothing but the row's own previous
        // auto-inserted prefix — never real, user-typed content (VatIdField.applyCountryPrefill's
        // own rule, the shared implementation of what this section used to do by hand).
        // `isFromClient` tells a user-driven change apart from a programmatic one (readFrom sets
        // the value programmatically and must not trigger this).
        countryField.addValueChangeListener { event ->
            if (!event.isFromClient) return@addValueChangeListener
            vatIdField.applyCountryPrefill()
            // §3 — a country change on an existing row may change currentCountries(); a bulk load
            // (readFrom) never reaches here since it sets the value programmatically.
            notifyChange()
        }
        // The same prefill-if-blank rule applies the moment a row gets its (possibly default)
        // initial country, not only on a later manual re-selection — a brand-new row (e.g. the
        // "add" button's LU default) shows its country's prefix in the VAT-id field right away.
        // Persisted rows loaded via [readFrom] are unaffected: [SellerVatRegistration.validate]
        // never allows a blank `vatId`, so an existing row's `vatId` is never blank here.
        vatIdField.applyCountryPrefill()
        val removeButton = Button(ActionIcons.DELETE.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR)
            element.setAttribute("aria-label", tr("senderProfile.euVat.remove"))
        }
        val bar = HorizontalLayout(countryField, vatIdField, removeButton).apply {
            setWidthFull()
            isSpacing = true
            expand(vatIdField)
            alignItems = com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.BASELINE
        }
        val row = Row(countryField, vatIdField, bar)
        removeButton.addClickListener {
            rows.remove(row)
            rowsContainer.remove(bar)
            notifyChange()
        }
        rows.add(row)
        rowsContainer.add(bar)
    }
}
