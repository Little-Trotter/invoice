package de.littletrotter.ui.navigation

import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import de.littletrotter.ui.view.referencedata.ReferenceDataTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Exhaustive coverage of [resolveLabel] across every [LabelKey] subtype and every
 * [NavItem]/[NavSection]/[ReferenceDataGroup]/[ReferenceDataTab] arm, including
 * [NavItem.REFERENCE_DATA] which every other caller (breadcrumb, command palette) filters out.
 */
class LabelKeyTest {

    @Test
    fun `happy path - resolves the reference data root label`() {
        assertEquals(UiTranslations.tr("nav.referenceData"), resolveLabel(LabelKey.ReferenceDataRoot))
    }

    @Test
    fun `happy path - resolves the reference data tab context label`() {
        val expected = UiTranslations.tr("nav.referenceData") + " · " + UiTranslations.tr("referenceData.group.abrechnung")
        assertEquals(expected, resolveLabel(LabelKey.RefDataTabContextKey(ReferenceDataGroup.ABRECHNUNG)))
    }

    @Test
    fun `boundary - resolves every NavItem including REFERENCE_DATA which other callers filter out`() {
        NavItem.entries.forEach { item ->
            val expected = when (item) {
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
                NavItem.REFERENCE_DATA -> UiTranslations.tr("nav.referenceData")
                NavItem.SYSTEM -> UiTranslations.tr("nav.system")
                NavItem.SETTINGS -> UiTranslations.tr("nav.settings")
                NavItem.INFO -> UiTranslations.tr("nav.disclaimer")
            }
            assertEquals(expected, resolveLabel(LabelKey.NavItemKey(item)), "item=$item")
        }
    }

    @Test
    fun `boundary - resolves every NavSection`() {
        val expectedBySection = mapOf(
            NavSection.INVOICING to "nav.section.invoicing",
            NavSection.QUOTING to "nav.section.quoting",
            NavSection.MAIL to "nav.section.mail",
            NavSection.MASTER_DATA to "nav.section.masterData",
            NavSection.ADMINISTRATION to "nav.section.administration",
            NavSection.HELP to "nav.section.help",
        )
        NavSection.entries.forEach { section ->
            assertEquals(
                UiTranslations.tr(expectedBySection.getValue(section)),
                resolveLabel(LabelKey.NavSectionKey(section)),
                "section=$section",
            )
        }
    }

    @Test
    fun `boundary - resolves every ReferenceDataGroup`() {
        val expectedByGroup = mapOf(
            ReferenceDataGroup.ABRECHNUNG to "referenceData.group.abrechnung",
            ReferenceDataGroup.REISEKOSTEN to "referenceData.group.reisekosten",
            ReferenceDataGroup.E_RECHNUNG to "referenceData.group.eRechnung",
        )
        ReferenceDataGroup.entries.forEach { group ->
            assertEquals(
                UiTranslations.tr(expectedByGroup.getValue(group)),
                resolveLabel(LabelKey.RefDataGroupKey(group)),
                "group=$group",
            )
        }
    }

    @Test
    fun `boundary - resolves every ReferenceDataTab`() {
        val expectedByTab = mapOf(
            ReferenceDataTab.PAYMENT_TERMS to "referenceData.paymentTerms",
            ReferenceDataTab.TAX_RATES to "referenceData.taxRates",
            ReferenceDataTab.UNITS to "referenceData.units",
            ReferenceDataTab.NUMBER_RANGES to "referenceData.numberRange.title",
            ReferenceDataTab.PER_DIEM_RATES to "referenceData.perDiemRates",
            ReferenceDataTab.HOTEL_RATES to "referenceData.hotelRates",
            ReferenceDataTab.MILEAGE_RATES to "referenceData.mileageRates",
            ReferenceDataTab.B2B_PROFILES to "referenceData.b2b.tabTitle",
            ReferenceDataTab.VALIDATION to "referenceData.validations",
        )
        ReferenceDataTab.entries.forEach { tab ->
            assertEquals(
                UiTranslations.tr(expectedByTab.getValue(tab)),
                resolveLabel(LabelKey.RefDataTabKey(tab)),
                "tab=$tab",
            )
        }
    }
}
