package de.littletrotter.ui.navigation

import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import de.littletrotter.ui.view.referencedata.ReferenceDataTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MEN-4 — unit tests for the pure path→segments mapping in [NavBreadcrumb.pathToSegments].
 * Tests the segment computation logic without any Vaadin/Karibu scaffolding or i18n resolution.
 */
class NavBreadcrumbTest {

    @Test
    fun `top-level invoice route produces section and item segments`() {
        val segments = NavBreadcrumb.pathToSegments("")

        assertEquals(2, segments.size)
        assertEquals(LabelKey.NavSectionKey(NavSection.INVOICING), (segments[0] as BreadcrumbSegment.Text).labelKey)
        assertEquals(LabelKey.NavItemKey(NavItem.INVOICES), (segments[1] as BreadcrumbSegment.Current).labelKey)
    }

    @Test
    fun `invoicing section items produce correct segments`() {
        listOf(
            "" to NavItem.INVOICES,
            "receipt-pool" to NavItem.RECEIPTS,
            "e-invoice-jobs" to NavItem.PRINT_JOBS,
            "statistics" to NavItem.STATISTICS,
        ).forEach { (path, expectedItem) ->
            val segments = NavBreadcrumb.pathToSegments(path)
            assertEquals(2, segments.size)
            assertEquals(
                LabelKey.NavSectionKey(NavSection.INVOICING),
                (segments[0] as BreadcrumbSegment.Text).labelKey,
                "path=$path"
            )
            assertEquals(
                LabelKey.NavItemKey(expectedItem),
                (segments[1] as BreadcrumbSegment.Current).labelKey,
                "path=$path"
            )
        }
    }

    @Test
    fun `quoting section routes produce Angebote section segments`() {
        listOf(
            "quotes" to NavItem.QUOTES,
            "quotes/4711" to NavItem.QUOTES,
            "quotes/statistics" to NavItem.QUOTE_STATISTICS,
        ).forEach { (path, expectedItem) ->
            val segments = NavBreadcrumb.pathToSegments(path)
            assertEquals(LabelKey.NavSectionKey(NavSection.QUOTING), (segments[0] as BreadcrumbSegment.Text).labelKey, path)
            assertEquals(LabelKey.NavItemKey(expectedItem), (segments[1] as BreadcrumbSegment.Current).labelKey, path)
        }
    }

    @Test
    fun `mail section routes produce E-Mail section segments`() {
        listOf(
            "mail/journal" to NavItem.MAIL_JOURNAL,
            "mail/templates" to NavItem.MAIL_TEMPLATES,
        ).forEach { (path, expectedItem) ->
            val segments = NavBreadcrumb.pathToSegments(path)
            assertEquals(LabelKey.NavSectionKey(NavSection.MAIL), (segments[0] as BreadcrumbSegment.Text).labelKey, path)
            assertEquals(LabelKey.NavItemKey(expectedItem), (segments[1] as BreadcrumbSegment.Current).labelKey, path)
        }
    }

    @Test
    fun `master data section items produce correct segments`() {
        listOf(
            "sender-profiles" to NavItem.SENDER_PROFILES,
            "recipients" to NavItem.RECIPIENTS,
            "articles" to NavItem.ARTICLES,
        ).forEach { (path, expectedItem) ->
            val segments = NavBreadcrumb.pathToSegments(path)
            assertEquals(2, segments.size)
            assertEquals(NavSection.MASTER_DATA, (segments[0] as BreadcrumbSegment.Text).labelKey.let { it as LabelKey.NavSectionKey }.section)
            assertEquals(expectedItem, (segments[1] as BreadcrumbSegment.Current).labelKey.let { it as LabelKey.NavItemKey }.item)
        }
    }

    @Test
    fun `system and info routes produce correct segments`() {
        listOf(
            "system" to NavItem.SYSTEM,
            "info" to NavItem.INFO,
        ).forEach { (path, expectedItem) ->
            val segments = NavBreadcrumb.pathToSegments(path)
            assertEquals(2, segments.size)
            val lastSegment = segments[1] as BreadcrumbSegment.Current
            assertEquals(expectedItem, (lastSegment.labelKey as LabelKey.NavItemKey).item)
        }
    }

    @Test
    fun `reference-data bare route produces three-level segments`() {
        val segments = NavBreadcrumb.pathToSegments(
            "referenzdaten",
            ReferenceDataGroup.ABRECHNUNG,
            ReferenceDataTab.PAYMENT_TERMS
        )

        assertEquals(3, segments.size)
        val seg0 = segments[0] as BreadcrumbSegment.Link
        assertTrue(seg0.labelKey is LabelKey.ReferenceDataRoot)
        assertEquals("referenzdaten", seg0.routePath)

        val seg1 = segments[1] as BreadcrumbSegment.Link
        assertEquals(ReferenceDataGroup.ABRECHNUNG, (seg1.labelKey as LabelKey.RefDataGroupKey).group)
        assertEquals("referenzdaten/abrechnung", seg1.routePath)

        val seg2 = segments[2] as BreadcrumbSegment.Current
        assertEquals(ReferenceDataTab.PAYMENT_TERMS, (seg2.labelKey as LabelKey.RefDataTabKey).tab)
    }

    @Test
    fun `reference-data deep link with all three groups produces correct group path`() {
        listOf(
            ReferenceDataGroup.ABRECHNUNG to "referenzdaten/abrechnung",
            ReferenceDataGroup.REISEKOSTEN to "referenzdaten/reisekosten",
            ReferenceDataGroup.E_RECHNUNG to "referenzdaten/e-rechnung",
        ).forEach { (group, expectedPath) ->
            val segments = NavBreadcrumb.pathToSegments(
                "referenzdaten/${group.slug}/zahlungsziele",
                group,
                ReferenceDataTab.PAYMENT_TERMS
            )
            val groupSegment = segments[1] as BreadcrumbSegment.Link
            assertEquals(expectedPath, groupSegment.routePath, "group=$group")
        }
    }

    @Test
    fun `reference-data deep link produces all three segment types`() {
        val segments = NavBreadcrumb.pathToSegments(
            "referenzdaten/e-rechnung/b2b-profile",
            ReferenceDataGroup.E_RECHNUNG,
            ReferenceDataTab.B2B_PROFILES
        )

        assertEquals(3, segments.size)
        assertTrue(segments[0] is BreadcrumbSegment.Link)
        assertTrue(segments[1] is BreadcrumbSegment.Link)
        assertTrue(segments[2] is BreadcrumbSegment.Current)
    }

    @Test
    fun `reference-data with group only (no tab) produces two segments`() {
        val segments = NavBreadcrumb.pathToSegments(
            "referenzdaten/reisekosten",
            ReferenceDataGroup.REISEKOSTEN,
            null
        )

        assertEquals(2, segments.size)
        assertTrue(segments[0] is BreadcrumbSegment.Link)
        assertTrue(segments[1] is BreadcrumbSegment.Link) // group, still a link
    }

    @Test
    fun `reference-data with neither group nor tab produces only root link`() {
        val segments = NavBreadcrumb.pathToSegments(
            "referenzdaten",
            null,
            null
        )

        assertEquals(1, segments.size)
        val rootSegment = segments[0] as BreadcrumbSegment.Link
        assertTrue(rootSegment.labelKey is LabelKey.ReferenceDataRoot)
    }

    @Test
    fun `unknown route produces empty segments`() {
        val segments = NavBreadcrumb.pathToSegments("unknown-route")
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `unknown route with trailing slash produces empty segments`() {
        val segments = NavBreadcrumb.pathToSegments("unknown-route/")
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `path with trailing slash is handled correctly for top-level views`() {
        val segments = NavBreadcrumb.pathToSegments("statistics/")

        assertEquals(2, segments.size)
        assertEquals(NavItem.STATISTICS, (segments[1] as BreadcrumbSegment.Current).labelKey.let { it as LabelKey.NavItemKey }.item)
    }

    @Test
    fun `path with trailing slash is handled correctly for reference data`() {
        val segments = NavBreadcrumb.pathToSegments(
            "referenzdaten/e-rechnung/",
            ReferenceDataGroup.E_RECHNUNG,
            null
        )

        assertEquals(2, segments.size)
        assertEquals(ReferenceDataGroup.E_RECHNUNG, (segments[1] as BreadcrumbSegment.Link).labelKey.let { it as LabelKey.RefDataGroupKey }.group)
    }
}
