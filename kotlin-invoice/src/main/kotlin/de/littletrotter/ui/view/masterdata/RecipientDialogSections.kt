package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.component.HeroCard
import de.littletrotter.ui.component.PartyExtensionsSection
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.component.VatIdField

private const val FONT_WEIGHT_SEMIBOLD = "font-weight-semibold"
private const val TEXT_MUTED = "text-muted"
private const val FS_08 = "fs-08"
private const val FORM_RESPONSIVE_WIDTH = "520px"
private const val RECIPIENT_DIALOG_NEW_KEY = "recipient.dialog.new"

/** The "identity" tab's fields — grouped so [RecipientDialogSections]/[RecipientFormPopulator]/
 *  [RecipientFormValidator] each take one parameter instead of four. */
class RecipientIdentityFields(
    val customerNumberField: TextField,
    val nameField: TextField,
    val contactPersonField: TextField,
    val shortHashField: TextField,
)

/** Billing + delivery address, plus the contact fields that share the "address" tab. */
class RecipientAddressFields(
    val addressFormSection: AddressFormSection,
    val deliveryAddressFormSection: AddressFormSection,
    val emailField: TextField,
    val phoneField: TextField,
) {
    /** The delivery country combo defaults to DE, so its value alone must never count as
     *  "delivery address entered" — shared by save-time validation (delivery PLZ gating) and
     *  draft-building, so both agree on what counts as a delivery address. */
    fun deliveryAddressEntered(): Boolean =
        deliveryAddressFormSection.street.value.isNotBlank() ||
            deliveryAddressFormSection.postalCode.value.isNotBlank() ||
            deliveryAddressFormSection.city.value.isNotBlank()
}

/** The "tax" tab's fields (customer type, invoice format, reverse-charge preference, VAT id, IBAN)
 *  — the widgets [RecipientFormPopulator]/[RecipientFormValidator] read and write. */
class RecipientTaxFields(
    val customerTypeField: ComboBox<CustomerType>,
    val invoiceFormatField: ComboBox<InvoiceFormat>,
    val documentLanguageField: ComboBox<DocumentLanguage>,
    val reverseChargePreferenceField: RadioButtonGroup<ReverseChargePreference>,
    val vatIdField: VatIdField,
    val vatIdValidationNoteField: TextField,
    val ibanField: TextField,
)

/** LongParameterList — the VAT-id tab widgets that only [RecipientDialogSections]'s layout needs
 *  (never read/written by the populator or validator), split out of [RecipientTaxFields] to stay
 *  under the constructor-parameter threshold. */
class RecipientVatIdLayoutFields(
    val vatIdVerifyButton: Button,
    val vatIdValidatedRow: HorizontalLayout,
)

/** The e-invoice identity tab's fields (Leitweg-ID, BT-46/47 pairs, extension pairs). */
class RecipientEinvoiceIdentityFields(
    val routingIdField: TextField,
    val buyerIdentifierValueField: TextField,
    val buyerIdentifierSchemeField: TextField,
    val legalRegistrationValueField: TextField,
    val legalRegistrationSchemeField: TextField,
    val partySection: PartyExtensionsSection,
)

/** The standing B2B profile default and its per-customer field values. */
class RecipientB2bFields(
    val b2bProfileCombo: ComboBox<B2bProfileDto>,
    val b2bFieldsContainer: Div,
    val b2bFieldsSectionTitle: Span,
)

/**
 * [RecipientView]'s dialog layout, extracted out so the view's own body stays about
 * wiring behaviour, not laying out six tabs. Same split as [SenderProfileDialogSections]/
 * [SenderProfileFormBinder]: this class owns tab content and field labels, never validation or
 * persistence.
 */
class RecipientDialogSections(
    private val tr: (String) -> String,
    private val identity: RecipientIdentityFields,
    private val address: RecipientAddressFields,
    private val tax: RecipientTaxFields,
    private val vatIdLayout: RecipientVatIdLayoutFields,
    private val einvoiceIdentity: RecipientEinvoiceIdentityFields,
    private val b2b: RecipientB2bFields,
) {

    fun configureLabels() {
        identity.customerNumberField.label = tr("recipient.customerNumber")
        identity.shortHashField.label = tr("recipient.shortHash")
        identity.shortHashField.helperText = tr("recipient.shortHash.helper")
        identity.shortHashField.isReadOnly = true
        identity.nameField.label = tr("recipient.name")
        tax.vatIdField.label = tr("recipient.vatId")
        tax.ibanField.label = tr("recipient.iban")
        tax.ibanField.helperText = tr("shared.iban.format")
        einvoiceIdentity.routingIdField.label = tr("recipient.routingId")
        einvoiceIdentity.routingIdField.helperText = tr("recipient.routingId.helper")
        address.emailField.label = tr("recipient.email")
        address.phoneField.label = tr("recipient.phone")
        identity.contactPersonField.label = tr("recipient.contactPerson")

        tax.invoiceFormatField.label = tr("recipient.invoiceFormat")
        tax.invoiceFormatField.helperText = tr("recipient.invoiceFormat.helper")
        tax.invoiceFormatField.setItemLabelGenerator {
            when (it) {
                InvoiceFormat.GERMAN -> tr("recipient.invoiceFormat.german")
                InvoiceFormat.INTERNATIONAL -> tr("recipient.invoiceFormat.international")
            }
        }

        configureDocumentLanguageLabels()

        tax.reverseChargePreferenceField.label = tr("recipient.reverseChargePreference.label")
        tax.reverseChargePreferenceField.helperText = tr("recipient.reverseChargePreference.helper")
        tax.reverseChargePreferenceField.setItemLabelGenerator {
            when (it) {
                ReverseChargePreference.NEVER -> tr("recipient.reverseChargePreference.never")
                ReverseChargePreference.ALWAYS -> tr("recipient.reverseChargePreference.always")
                ReverseChargePreference.ASK -> tr("recipient.reverseChargePreference.ask")
            }
        }

        tax.customerTypeField.label = tr("recipient.customerType")
        tax.customerTypeField.setItemLabelGenerator {
            when (it) {
                CustomerType.BUSINESS -> tr("recipient.customerType.business")
                CustomerType.CONSUMER -> tr("recipient.customerType.consumer")
            }
        }
        einvoiceIdentity.buyerIdentifierValueField.label = tr("recipient.buyerIdentifier.value")
        einvoiceIdentity.buyerIdentifierValueField.helperText = tr("recipient.buyerIdentifier.helper")
        einvoiceIdentity.buyerIdentifierSchemeField.label = tr("recipient.buyerIdentifier.scheme")
        einvoiceIdentity.buyerIdentifierSchemeField.placeholder = "0088"
        einvoiceIdentity.legalRegistrationValueField.label = tr("recipient.legalRegistrationIdentifier.value")
        einvoiceIdentity.legalRegistrationValueField.helperText = tr("recipient.legalRegistrationIdentifier.helper")
        einvoiceIdentity.legalRegistrationSchemeField.label = tr("recipient.legalRegistrationIdentifier.scheme")
        einvoiceIdentity.legalRegistrationSchemeField.placeholder = "0002"
        tax.vatIdValidationNoteField.label = tr("recipient.vatIdValidationNote")
        tax.vatIdValidationNoteField.helperText = tr("recipient.vatIdValidationNote.helper")

        b2b.b2bProfileCombo.label = tr("recipient.b2bProfile")
        b2b.b2bProfileCombo.helperText = tr("recipient.b2bProfile.helper")
        b2b.b2bFieldsSectionTitle.text = tr("recipient.section.b2bFields")
    }

    /** The dialog's hero card; [onNameChange] refreshes the proposed customer number as the name
     *  is typed (view-owned state). */
    fun buildDialogHero(onNameChange: (String) -> Unit): HeroCard {
        val hero = HeroCard(tr(RECIPIENT_DIALOG_NEW_KEY), tr("nav.recipients"), "+")
        identity.nameField.addValueChangeListener { e ->
            val v = e.value.trim()
            hero.setName(v.ifBlank { tr(RECIPIENT_DIALOG_NEW_KEY) })
            hero.setAvatar(v.take(1).uppercase().ifEmpty { "+" })
            onNameChange(v)
        }
        return hero
    }

    /** Builds the six-tab [TabSheet] and populates [fieldTabs] (backs `RecipientView.revealField`;
     *  see that method's KDoc for why the map exists at all — PO report 04.09.2026, "Speichern-
     *  Knopf tut sichtbar nichts"). */
    fun buildTabs(fieldTabs: MutableMap<Component, Tab>): TabSheet {
        val vatIdWithButton = HorizontalLayout(tax.vatIdField, vatIdLayout.vatIdVerifyButton).apply {
            alignItems = FlexComponent.Alignment.END
            isSpacing = true
            setFlexGrow(1.0, tax.vatIdField)
        }
        val identityForm = FormLayout(
            identity.customerNumberField, identity.nameField, identity.contactPersonField, identity.shortHashField,
        ).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
            setColspan(identity.contactPersonField, 2)
        }
        val taxForm = buildTaxForm(vatIdWithButton)
        val identityForm2 = buildIdentityForm2()
        val addressContent = buildAddressContent()
        val deliveryAddressContent = buildDeliveryAddressContent()

        val identityTab: Tab
        val addressTab: Tab
        val taxTab: Tab
        val deliveryAddressTab: Tab
        val built = TabSheet().apply {
            setWidthFull()
            identityTab = add(tr("recipient.tab.identity"), Section(tr("recipient.section.identity"), "sp-section-bar--red", identityForm))
            addressTab = add(tr("recipient.tab.address"), Section(tr("recipient.section.address"), "sp-section-bar--blue", addressContent))
            taxTab = add(tr("recipient.tab.tax"), Section(tr("recipient.section.tax"), "sp-section-bar--amber", taxForm))
            add(tr("recipient.tab.einvoiceIdentity"), Section(tr("recipient.section.einvoiceIdentity"), "sp-section-bar--amber", identityForm2))
            // The standing B2B profile default for this customer, followed by the
            // "B2B-Felder" section below it — hidden (b2bFieldsContainer's own isVisible) until the
            // assigned profile actually carries a RECIPIENT-placed field.
            add(
                tr("recipient.tab.b2b"),
                Section(
                    tr("recipient.section.b2b"), "sp-section-bar--red",
                    VerticalLayout(FormLayout(b2b.b2bProfileCombo), b2b.b2bFieldsContainer).apply {
                        isPadding = false; isSpacing = true; setWidthFull()
                    },
                ),
            )
            deliveryAddressTab = add(
                tr("recipient.tab.deliveryAddress"),
                Section(tr("recipient.tab.deliveryAddress"), "sp-section-bar--blue", deliveryAddressContent),
            )
        }
        listOf(identity.nameField, identity.customerNumberField).forEach { fieldTabs[it] = identityTab }
        listOf(
            address.addressFormSection.street, address.addressFormSection.postalCode, address.addressFormSection.city,
            address.phoneField, address.emailField,
        ).forEach { fieldTabs[it] = addressTab }
        listOf(tax.vatIdField, tax.ibanField).forEach { fieldTabs[it] = taxTab }
        fieldTabs[address.deliveryAddressFormSection.postalCode] = deliveryAddressTab
        return built
    }

    private fun configureDocumentLanguageLabels() {
        tax.documentLanguageField.label = tr("recipient.documentLanguage")
        tax.documentLanguageField.helperText = tr("recipient.documentLanguage.helper")
        tax.documentLanguageField.setItemLabelGenerator {
            when (it) {
                DocumentLanguage.DE -> tr("recipient.documentLanguage.de")
                DocumentLanguage.EN -> tr("recipient.documentLanguage.en")
            }
        }
    }

    private fun buildTaxForm(vatIdWithButton: HorizontalLayout): FormLayout =
        FormLayout(
            tax.customerTypeField, tax.invoiceFormatField, tax.documentLanguageField, tax.reverseChargePreferenceField,
            vatIdWithButton,
            vatIdLayout.vatIdValidatedRow, tax.vatIdValidationNoteField, tax.ibanField,
        ).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
            setColspan(tax.reverseChargePreferenceField, 2) // segmented control needs the full row width
            setColspan(vatIdLayout.vatIdValidatedRow, 2)
            setColspan(tax.ibanField, 2)
            setColspan(tax.vatIdValidationNoteField, 2)
        }

    // BT-46/47 — each is one value+scheme *pair*, not two independent fields; grouped so that's
    // visible at a glance instead of four same-weight TextFields in a row.
    private fun btPairGroup(heading: String, valueField: TextField, schemeField: TextField): Div = Div().apply {
        addClassName("recipient-bt-pair-group")
        add(Span(heading).apply { addClassNames(FS_08, FONT_WEIGHT_SEMIBOLD, TEXT_MUTED) })
        add(
            FormLayout(valueField, schemeField).apply {
                setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("360px", 2))
            },
        )
    }

    private fun buildIdentityForm2(): VerticalLayout {
        val routingIdForm = FormLayout(einvoiceIdentity.routingIdField).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
        }
        return VerticalLayout(
            routingIdForm,
            btPairGroup(tr("recipient.section.einvoiceIdentity.bt46"), einvoiceIdentity.buyerIdentifierValueField, einvoiceIdentity.buyerIdentifierSchemeField),
            btPairGroup(tr("recipient.section.einvoiceIdentity.bt47"), einvoiceIdentity.legalRegistrationValueField, einvoiceIdentity.legalRegistrationSchemeField),
        ).apply {
            isPadding = false; isSpacing = true; setWidthFull()
            // Extension pairs (electronic address BT-49) dock below BT-47. With no extension
            // registered the section is empty and adds nothing.
            if (einvoiceIdentity.partySection.hasContent) add(einvoiceIdentity.partySection)
        }
    }

    private fun groupHeading(text: String): Span = Span(text).apply { addClassNames(FS_08, FONT_WEIGHT_SEMIBOLD, TEXT_MUTED) }

    // Contact moved into address section; same pattern as SenderProfileView.
    private fun buildAddressContent(): VerticalLayout {
        val contactForm = FormLayout(address.emailField, address.phoneField).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
        }
        return VerticalLayout(
            address.addressFormSection,
            groupHeading(tr("recipient.section.contact")), contactForm,
        ).apply { isPadding = false; isSpacing = true; setWidthFull() }
    }

    // Customer has only billing address + optional delivery address (redundant field removed).
    private fun buildDeliveryAddressContent(): VerticalLayout =
        VerticalLayout(
            Span(tr("recipient.deliveryAddress.helper")).apply { addClassNames(FS_08, TEXT_MUTED) },
            address.deliveryAddressFormSection,
        ).apply { isPadding = false; isSpacing = true; setWidthFull() }
}
