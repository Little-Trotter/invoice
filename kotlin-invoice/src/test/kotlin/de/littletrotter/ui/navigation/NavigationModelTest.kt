package de.littletrotter.ui.navigation

import com.vaadin.flow.component.Component
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@DisplayName("Navigation Model Tests")
class NavigationModelTest {

    @Test
    @DisplayName("Should have sections in PO-approved order")
    fun testSectionOrder() {
        val sections = NavSection.entries
        assertEquals(
            listOf(
                NavSection.INVOICING,
                NavSection.QUOTING,
                NavSection.MAIL,
                NavSection.MASTER_DATA,
                NavSection.ADMINISTRATION,
                NavSection.HELP,
            ),
            sections,
        )
    }

    @Test
    @DisplayName("Should return items for INVOICING section in display order")
    fun testInvoicingSectionItems() {
        val items = NavItem.itemsFor(NavSection.INVOICING)
        assertEquals(4, items.size)
        assertEquals(NavItem.INVOICES, items[0])
        assertEquals(NavItem.RECEIPTS, items[1])
        assertEquals(NavItem.PRINT_JOBS, items[2])
        assertEquals(NavItem.STATISTICS, items[3])
    }

    @Test
    @DisplayName("Should return overview then statistics for the QUOTING section")
    fun testQuotingSectionItems() {
        assertEquals(listOf(NavItem.QUOTES, NavItem.QUOTE_RECEIPT_POOL, NavItem.QUOTE_STATISTICS), NavItem.itemsFor(NavSection.QUOTING))
    }

    @Test
    @DisplayName("Should return journal then templates for the MAIL section")
    fun testMailSectionItems() {
        assertEquals(listOf(NavItem.MAIL_JOURNAL, NavItem.MAIL_TEMPLATES), NavItem.itemsFor(NavSection.MAIL))
    }

    @Test
    @DisplayName("Should return items for MASTER_DATA section in display order")
    fun testMasterDataSectionItems() {
        val items = NavItem.itemsFor(NavSection.MASTER_DATA)
        assertEquals(3, items.size)
        assertEquals(NavItem.SENDER_PROFILES, items[0])
        assertEquals(NavItem.RECIPIENTS, items[1])
        assertEquals(NavItem.ARTICLES, items[2])
    }

    @Test
    @DisplayName("Should return items for ADMINISTRATION section in display order")
    fun testAdministrationSectionItems() {
        val items = NavItem.itemsFor(NavSection.ADMINISTRATION)
        // SFS — third ADMINISTRATION entry, declared as System's drawer child
        // (NavItem.drawerParent); still a section-ordered model item.
        assertEquals(3, items.size)
        assertEquals(NavItem.REFERENCE_DATA, items[0])
        assertEquals(NavItem.SYSTEM, items[1])
        assertEquals(NavItem.SETTINGS, items[2])
    }

    @Test
    @DisplayName("Should return items for HELP section in display order")
    fun testHelpSectionItems() {
        val items = NavItem.itemsFor(NavSection.HELP)
        assertEquals(1, items.size)
        assertEquals(NavItem.INFO, items[0])
    }

    @Test
    @DisplayName("Should resolve root path to INVOICES")
    fun testActiveItemForRootPath() {
        val item = NavItem.activeItemFor("")
        assertEquals(NavItem.INVOICES, item)
    }

    @Test
    @DisplayName("Should resolve exact paths to corresponding items")
    fun testActiveItemForExactPaths() {
        assertEquals(NavItem.INVOICES, NavItem.activeItemFor(""))
        assertEquals(NavItem.RECEIPTS, NavItem.activeItemFor("receipt-pool"))
        assertEquals(NavItem.PRINT_JOBS, NavItem.activeItemFor("e-invoice-jobs"))
        assertEquals(NavItem.STATISTICS, NavItem.activeItemFor("statistics"))
        assertEquals(NavItem.SENDER_PROFILES, NavItem.activeItemFor("sender-profiles"))
        assertEquals(NavItem.RECIPIENTS, NavItem.activeItemFor("recipients"))
        assertEquals(NavItem.ARTICLES, NavItem.activeItemFor("articles"))
        assertEquals(NavItem.REFERENCE_DATA, NavItem.activeItemFor("referenzdaten"))
        assertEquals(NavItem.SYSTEM, NavItem.activeItemFor("system"))
        assertEquals(NavItem.INFO, NavItem.activeItemFor("info"))
    }

    @Test
    @DisplayName("Should use longest-prefix match for deep links")
    fun testActiveItemForLongestPrefixMatch() {
        // Referenzdaten deep links should resolve to REFERENCE_DATA
        assertEquals(NavItem.REFERENCE_DATA, NavItem.activeItemFor("referenzdaten/abrechnung"))
        assertEquals(NavItem.REFERENCE_DATA, NavItem.activeItemFor("referenzdaten/e-rechnung"))
        assertEquals(NavItem.REFERENCE_DATA, NavItem.activeItemFor("referenzdaten/e-rechnung/b2b-profile"))
        assertEquals(NavItem.REFERENCE_DATA, NavItem.activeItemFor("referenzdaten/reisekosten/verpflegungspauschalen"))
    }

    @Test
    @DisplayName("Should tolerate trailing slashes")
    fun testActiveItemForTrailingSlashes() {
        assertEquals(NavItem.INVOICES, NavItem.activeItemFor("/"))
        assertEquals(NavItem.RECEIPTS, NavItem.activeItemFor("receipt-pool/"))
        assertEquals(NavItem.STATISTICS, NavItem.activeItemFor("statistics/"))
        assertEquals(NavItem.REFERENCE_DATA, NavItem.activeItemFor("referenzdaten/"))
        assertEquals(NavItem.REFERENCE_DATA, NavItem.activeItemFor("referenzdaten/e-rechnung/"))
        assertEquals(NavItem.REFERENCE_DATA, NavItem.activeItemFor("referenzdaten/e-rechnung/b2b-profile/"))
    }

    @Test
    @DisplayName("Should return null for unknown paths")
    fun testActiveItemForUnknownPath() {
        assertNull(NavItem.activeItemFor("unknown"))
        assertNull(NavItem.activeItemFor("unknown-path"))
        assertNull(NavItem.activeItemFor("nonexistent/path"))
    }

    @Test
    @DisplayName("Should resolve reference-data groups for deep links")
    fun testActiveGroupForDeepLinks() {
        assertEquals(ReferenceDataGroup.ABRECHNUNG, NavItem.activeGroupFor("referenzdaten/abrechnung"))
        assertEquals(ReferenceDataGroup.ABRECHNUNG, NavItem.activeGroupFor("referenzdaten/abrechnung/zahlungsziele"))
        assertEquals(ReferenceDataGroup.REISEKOSTEN, NavItem.activeGroupFor("referenzdaten/reisekosten"))
        assertEquals(ReferenceDataGroup.REISEKOSTEN, NavItem.activeGroupFor("referenzdaten/reisekosten/verpflegungspauschalen"))
        assertEquals(ReferenceDataGroup.E_RECHNUNG, NavItem.activeGroupFor("referenzdaten/e-rechnung"))
        assertEquals(ReferenceDataGroup.E_RECHNUNG, NavItem.activeGroupFor("referenzdaten/e-rechnung/b2b-profile"))
    }

    @Test
    @DisplayName("Should tolerate trailing slashes in reference-data deep links")
    fun testActiveGroupForTrailingSlashes() {
        assertEquals(ReferenceDataGroup.ABRECHNUNG, NavItem.activeGroupFor("referenzdaten/abrechnung/"))
        assertEquals(ReferenceDataGroup.REISEKOSTEN, NavItem.activeGroupFor("referenzdaten/reisekosten/verpflegungspauschalen/"))
        assertEquals(ReferenceDataGroup.E_RECHNUNG, NavItem.activeGroupFor("referenzdaten/e-rechnung/"))
    }

    @Test
    @DisplayName("Should return null for activeGroupFor when not on reference-data path")
    fun testActiveGroupForNonReferenceDataPath() {
        assertNull(NavItem.activeGroupFor(""))
        assertNull(NavItem.activeGroupFor("statistics"))
        assertNull(NavItem.activeGroupFor("system"))
        assertNull(NavItem.activeGroupFor("unknown"))
    }

    @Test
    @DisplayName("Should return null for activeGroupFor with unknown group slug")
    fun testActiveGroupForUnknownGroupSlug() {
        assertNull(NavItem.activeGroupFor("referenzdaten/unknown"))
        assertNull(NavItem.activeGroupFor("referenzdaten/nonexistent/zahlungsziele"))
    }

    @Test
    @DisplayName("Route paths should match @Route annotations on view classes")
    fun testAnnotationDrift() {
        NavItem.entries.forEach { item ->
            val annotatedPath = item.annotatedRoutePath()
            val expectedPath = item.routePath

            // For REFERENCE_DATA, we extract just the base path from "referenzdaten/:gruppe?/:tab?"
            val normalizedAnnotatedPath = if (annotatedPath.contains("/:")) {
                annotatedPath.substringBefore("/:").takeIf { it.isNotEmpty() } ?: ""
            } else {
                annotatedPath
            }

            assertEquals(
                expectedPath,
                normalizedAnnotatedPath,
                "NavItem.${item.name}'s routePath '$expectedPath' doesn't match @Route value '$annotatedPath' on ${item.viewClass.simpleName}"
            )
        }
    }

    @Test
    @DisplayName("All nav items should have unique route paths within their section")
    fun testUniqueRoutePaths() {
        NavSection.entries.forEach { section ->
            val items = NavItem.itemsFor(section)
            val paths = items.map { it.routePath }
            assertEquals(items.size, paths.distinct().size, "Section $section has duplicate route paths")
        }
    }

    @Test
    @DisplayName("All nav items should have non-null icons")
    fun testAllItemsHaveIcons() {
        NavItem.entries.forEach { item ->
            assertNotNull(item.icon, "NavItem.${item.name} has null icon")
        }
    }

    @Test
    @DisplayName("Every nav item carries a routed view class")
    fun testAllItemsHaveViewClasses() {
        NavItem.entries.forEach { item ->
            assertTrue(
                Component::class.java.isAssignableFrom(item.viewClass),
                "NavItem.${item.name} viewClass ${item.viewClass.simpleName} must extend Component",
            )
        }
    }

    @Test
    @DisplayName("The mail journal item is a mail-gated entry in the mail section")
    fun testMailJournalItem() {
        assertEquals("mail/journal", NavItem.MAIL_JOURNAL.routePath)
        assertEquals(NavSection.MAIL, NavItem.MAIL_JOURNAL.section)
        assertEquals(NavItem.MAIL_JOURNAL, NavItem.activeItemFor("mail/journal"))
    }

    @Test
    @DisplayName("All nav items should have correct sections assigned")
    fun testAllItemsHaveCorrectSections() {
        NavItem.entries.forEach { item ->
            val section = NavItem.itemsFor(item.section)
            assertTrue(section.contains(item),
                "NavItem.${item.name} claims section ${item.section} but is not in itemsFor(${item.section})")
        }
    }

    @Test
    fun `boundary - topLevelItemsFor excludes a child item reachable only through its drawerParent`() {
        // SFS — NavItem.SETTINGS is a drawer child of NavItem.SYSTEM within ADMINISTRATION.
        val all = NavItem.itemsFor(NavSection.ADMINISTRATION)
        val topLevel = NavItem.topLevelItemsFor(NavSection.ADMINISTRATION)
        assertTrue(all.contains(NavItem.SETTINGS))
        assertTrue(topLevel.contains(NavItem.REFERENCE_DATA))
        assertTrue(topLevel.contains(NavItem.SYSTEM))
        assertTrue(topLevel.none { it == NavItem.SETTINGS })
    }

    @Test
    fun `boundary - routePathFromAnnotation throws for a view class with no Route annotation`() {
        // Every real NavItem.viewClass carries @Route (testAnnotationDrift asserts as much for
        // every non-external item), so this defensive branch is otherwise never reachable through
        // the enum itself — drive the extracted pure function directly with a plain Component.
        val exception = kotlin.test.assertFailsWith<IllegalStateException> {
            NavItem.routePathFromAnnotation(com.vaadin.flow.component.html.Div::class.java)
        }
        assertTrue(exception.message!!.contains("Div"))
    }

    @Test
    fun `boundary - activeGroupFor returns null when the reference-data slug segment is empty`() {
        // A double trailing slash normalizes to a path ending exactly on the reference-data base
        // ("referenzdaten/"), so the slug-extraction remainder is empty rather than absent.
        assertNull(NavItem.activeGroupFor("referenzdaten//"))
    }
}
