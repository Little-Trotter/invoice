package de.littletrotter.ui.navigation

import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import de.littletrotter.ui.view.referencedata.ReferenceDataTab

private const val NAV_REFERENCE_DATA_KEY = "nav.referenceData"

/**
 * MEN-4/MEN-5 — encodes a navigation label to be resolved to i18n text at render time via
 * [resolveLabel]. Originally introduced in [NavBreadcrumb] (MEN-4) so its pure `pathToSegments`
 * function could stay translation-context-free and unit-testable; extracted here (MEN-5) so
 * [CommandPaletteDialog]'s index can reuse the exact same item/section/reference-data label
 * mapping instead of a third copy of the `tr()` key table.
 */
sealed class LabelKey {
    data class NavItemKey(val item: NavItem) : LabelKey()
    data class NavSectionKey(val section: NavSection) : LabelKey()
    data class RefDataGroupKey(val group: ReferenceDataGroup) : LabelKey()
    data class RefDataTabKey(val tab: ReferenceDataTab) : LabelKey()
    object ReferenceDataRoot : LabelKey()

    /** MEN-5 — the command palette's secondary "context" line for a reference-data tab entry:
     *  "Referenzdaten · <Gruppe>". */
    data class RefDataTabContextKey(val group: ReferenceDataGroup) : LabelKey()
}

/**
 * Resolve a [LabelKey] to its German display text. The app serves a single locale
 * ([de.littletrotter.ui.i18n.TranslationService.GERMAN]), so a plain, component-free bundle
 * lookup ([UiTranslations.tr]) resolves identically to a component-scoped one and keeps this
 * function pure/unit-testable without any Vaadin/CDI scaffolding. Shared by [NavBreadcrumb] and
 * [CommandPaletteDialog] so the label-key-to-i18n-key mapping is defined exactly once.
 *
 * Split into one lookup per [LabelKey] subtype (same idiom as
 * [de.littletrotter.ui.MainLayout]'s `navItemLabel`/`sectionLabel`/`referenceDataGroupLabel`)
 * rather than one large `when`, which keeps each function's cyclomatic complexity well under the
 * detekt gate instead of summing every enum's branch count into a single function.
 */
fun resolveLabel(labelKey: LabelKey): String = when (labelKey) {
    is LabelKey.NavItemKey -> resolveNavItemLabel(labelKey.item)
    is LabelKey.NavSectionKey -> resolveNavSectionLabel(labelKey.section)
    is LabelKey.RefDataGroupKey -> resolveRefDataGroupLabel(labelKey.group)
    is LabelKey.RefDataTabKey -> resolveRefDataTabLabel(labelKey.tab)
    is LabelKey.ReferenceDataRoot -> UiTranslations.tr(NAV_REFERENCE_DATA_KEY)
    is LabelKey.RefDataTabContextKey ->
        UiTranslations.tr(NAV_REFERENCE_DATA_KEY) + " · " + resolveRefDataGroupLabel(labelKey.group)
}

private fun resolveNavItemLabel(item: NavItem): String = when (item) {
    NavItem.INVOICES -> UiTranslations.tr("nav.invoices")
    NavItem.RECEIPTS -> UiTranslations.tr("nav.receipts")
    NavItem.PRINT_JOBS -> UiTranslations.tr("nav.printJobs")
    NavItem.STATISTICS -> UiTranslations.tr("nav.statistics")
    NavItem.MAIL_JOURNAL -> UiTranslations.tr("nav.mailJournal")
    NavItem.MAIL_TEMPLATES -> UiTranslations.tr("nav.mailTemplates")
    NavItem.QUOTES -> UiTranslations.tr("nav.quotes")
    NavItem.QUOTE_RECEIPT_POOL -> UiTranslations.tr("nav.quoteReceiptPool")
    NavItem.QUOTE_STATISTICS -> UiTranslations.tr("nav.quoteStatistics")
    NavItem.SENDER_PROFILES -> UiTranslations.tr("nav.senderProfiles")
    NavItem.RECIPIENTS -> UiTranslations.tr("nav.recipients")
    NavItem.ARTICLES -> UiTranslations.tr("nav.articles")
    NavItem.REFERENCE_DATA -> UiTranslations.tr(NAV_REFERENCE_DATA_KEY)
    NavItem.SYSTEM -> UiTranslations.tr("nav.system")
    // SFS — the feature-switches child entry (drawer renders it under System; palette/breadcrumb resolve here).
    NavItem.SETTINGS -> UiTranslations.tr("nav.settings")
    NavItem.INFO -> UiTranslations.tr("nav.disclaimer")
}

private fun resolveNavSectionLabel(section: NavSection): String = when (section) {
    NavSection.INVOICING -> UiTranslations.tr("nav.section.invoicing")
    NavSection.QUOTING -> UiTranslations.tr("nav.section.quoting")
    NavSection.MAIL -> UiTranslations.tr("nav.section.mail")
    NavSection.MASTER_DATA -> UiTranslations.tr("nav.section.masterData")
    NavSection.ADMINISTRATION -> UiTranslations.tr("nav.section.administration")
    NavSection.HELP -> UiTranslations.tr("nav.section.help")
}

private fun resolveRefDataGroupLabel(group: ReferenceDataGroup): String = when (group) {
    ReferenceDataGroup.ABRECHNUNG -> UiTranslations.tr("referenceData.group.abrechnung")
    ReferenceDataGroup.REISEKOSTEN -> UiTranslations.tr("referenceData.group.reisekosten")
    ReferenceDataGroup.E_RECHNUNG -> UiTranslations.tr("referenceData.group.eRechnung")
}

private fun resolveRefDataTabLabel(tab: ReferenceDataTab): String = when (tab) {
    ReferenceDataTab.PAYMENT_TERMS -> UiTranslations.tr("referenceData.paymentTerms")
    ReferenceDataTab.TAX_RATES -> UiTranslations.tr("referenceData.taxRates")
    ReferenceDataTab.UNITS -> UiTranslations.tr("referenceData.units")
    ReferenceDataTab.NUMBER_RANGES -> UiTranslations.tr("referenceData.numberRange.title")
    ReferenceDataTab.PER_DIEM_RATES -> UiTranslations.tr("referenceData.perDiemRates")
    ReferenceDataTab.HOTEL_RATES -> UiTranslations.tr("referenceData.hotelRates")
    ReferenceDataTab.MILEAGE_RATES -> UiTranslations.tr("referenceData.mileageRates")
    ReferenceDataTab.B2B_PROFILES -> UiTranslations.tr("referenceData.b2b.tabTitle")
    ReferenceDataTab.VALIDATION -> UiTranslations.tr("referenceData.validations")
}
