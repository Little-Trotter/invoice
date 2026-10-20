package de.littletrotter.ui.component

import com.vaadin.flow.component.ItemLabelGenerator
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.AddressDto
import de.littletrotter.shared.country.Country

/**
 * The street/postal-code/city/country field group, previously hand-built five times
 * (SenderProfileView's single address; RecipientView's main, billing and delivery addresses).
 *
 * Labels come from [labelPrefix] + ".street"/".postalCode"/".city"/".country" — the existing
 * i18n keys (e.g. "senderProfile.street", "recipient.postalCode") are unchanged, so callers
 * only need to pass the prefix already used at each call site.
 *
 * The four fields are exposed directly so a caller can bind them to a `Binder` (SenderProfileView)
 * or read/validate them by hand (RecipientView) exactly as before — this component only removes
 * the duplicated construction, not the differing validation wiring each view already has.
 */
class AddressFormSection(
    labelPrefix: String,
    tr: (String) -> String,
    countryItemLabelGenerator: (Country) -> String = { it.alpha2 },
    required: Boolean = false,
) : FormLayout() {

    val street: TextField = TextField().apply {
        label = tr("$labelPrefix.street")
        isRequired = required
    }
    val postalCode: TextField = TextField().apply {
        label = tr("$labelPrefix.postalCode")
        isRequired = required
    }
    val city: TextField = TextField().apply {
        label = tr("$labelPrefix.city")
        isRequired = required
    }
    val country: ComboBox<Country> = ComboBox<Country>().apply {
        label = tr("$labelPrefix.country")
        setItems(*Country.entries.toTypedArray())
        itemLabelGenerator = ItemLabelGenerator(countryItemLabelGenerator)
        isClearButtonVisible = true
        width = "6rem"
        value = Country.DE
        isRequired = required
    }

    init {
        add(street, postalCode, city, country)
        setResponsiveSteps(ResponsiveStep("0", 1), ResponsiveStep("520px", 2))
    }

    /** Registers [listener] on the country combo's value-change event — used by the owning view
     *  to recompute country-scoped visibility (e.g. the party-extension pairs). */
    fun onCountryChange(listener: (Country?) -> Unit) {
        country.addValueChangeListener { event -> listener(event.value) }
    }

    fun populate(streetValue: String?, postalCodeValue: String?, cityValue: String?, countryValue: Country?) {
        street.value = streetValue.orEmpty()
        postalCode.value = postalCodeValue.orEmpty()
        city.value = cityValue.orEmpty()
        country.value = countryValue ?: Country.DE
    }

    /** Fills the fields from an application-layer [AddressDto] read model
     *  (e.g. [de.littletrotter.application.dto.RecipientDetailDto.billingAddress]/`deliveryAddress`). */
    fun populate(address: AddressDto?) {
        populate(address?.street, address?.postalCode, address?.city, Country.fromAlpha2(address?.countryAlpha2))
    }

    fun clear() {
        street.clear()
        postalCode.clear()
        city.clear()
        country.value = Country.DE
    }
}
