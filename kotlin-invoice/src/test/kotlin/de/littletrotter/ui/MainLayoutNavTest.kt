package de.littletrotter.ui

import de.littletrotter.application.security.AppPermission
import de.littletrotter.ui.navigation.NavItem
import de.littletrotter.ui.navigation.NavSection
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MEN-2 — the drawer is now built from [NavItem] entries (one `SideNav` per [NavSection])
 * rather than a hand-rolled `RouterLink` list,
 * so this class no longer scans [MainLayout]'s source for a literal `navLink(...)` line (the
 * original approach — see git history). What it protects instead:
 * - the e-invoice-jobs entry's placement, now expressed against the navigation model
 *   itself (the drawer has no other source of item order) — MEN-1's PO-approved order slots
 *   RECEIPTS between INVOICES and PRINT_JOBS, so the original "directly below" wording became
 *   "third in the INVOICING section, right after RECEIPTS";
 * - [MainLayout.referenceDataExpandedFor], the pure expand-decision function extracted per the
 *   MEN-2 spec (same "test the companion, not the CDI-wired component" idiom as
 *   [MainLayout.bellBadgeText] / [MainLayoutEditionTitleTest]);
 * - [MainLayout.referenceDataGroupPath], the pure path-derivation used for the reference-data
 *   node's three children.
 *
 * [MainLayoutDrawerKaribuTest] covers the actual drawer construction (SideNav/SideNavItem tree).
 */
class MainLayoutNavTest {

    @Test
    fun `the e-invoice-jobs nav entry is wired up in the invoicing section, right after receipts`() {
        val invoicingItems = NavItem.itemsFor(NavSection.INVOICING)

        assertEquals(
            listOf(NavItem.INVOICES, NavItem.RECEIPTS, NavItem.PRINT_JOBS, NavItem.STATISTICS),
            invoicingItems,
        )
    }

    // ── MEN-2 — expand decision logic ───────────────────────────────────────────

    @Test
    fun `reference-data node expands on the bare referenzdaten route`() {
        assertTrue(MainLayout.referenceDataExpandedFor("referenzdaten"))
    }

    @Test
    fun `reference-data node expands on any referenzdaten deep link`() {
        assertTrue(MainLayout.referenceDataExpandedFor("referenzdaten/abrechnung"))
        assertTrue(MainLayout.referenceDataExpandedFor("referenzdaten/e-rechnung/b2b-profile"))
        assertTrue(MainLayout.referenceDataExpandedFor("referenzdaten/reisekosten/verpflegungspauschalen/"))
    }

    @Test
    fun `reference-data node collapses outside the referenzdaten tree`() {
        assertFalse(MainLayout.referenceDataExpandedFor(""))
        assertFalse(MainLayout.referenceDataExpandedFor("statistics"))
        assertFalse(MainLayout.referenceDataExpandedFor("system"))
        assertFalse(MainLayout.referenceDataExpandedFor("unknown"))
    }

    // ── MEN-2 — reference-data group child paths ────────────────────────────────

    @Test
    fun `each reference-data group child links to its group prefix, not a specific tab`() {
        assertEquals("referenzdaten/abrechnung", MainLayout.referenceDataGroupPath(ReferenceDataGroup.ABRECHNUNG))
        assertEquals("referenzdaten/reisekosten", MainLayout.referenceDataGroupPath(ReferenceDataGroup.REISEKOSTEN))
        assertEquals("referenzdaten/e-rechnung", MainLayout.referenceDataGroupPath(ReferenceDataGroup.E_RECHNUNG))
    }

    // ── TS-8/MEN-2 — visibleNavItems permission filter ──────────────────────────

    @Test
    fun `visibleNavItems with every permission granted returns the section's full, unfiltered item list`() {
        val everyPermission = AppPermission.entries.toSet()
        NavSection.entries.forEach { section ->
            assertEquals(NavItem.itemsFor(section), MainLayout.visibleNavItems(section, everyPermission))
        }
    }

    @Test
    fun `visibleNavItems with no permissions at all leaves only the InfoView-backed HELP item`() {
        NavSection.entries.forEach { section ->
            val visible = MainLayout.visibleNavItems(section, emptySet())
            if (section == NavSection.HELP) {
                assertEquals(listOf(NavItem.INFO), visible, "InfoView requires no specific permission, so HELP must survive")
            } else {
                assertTrue(visible.isEmpty(), "section $section must be fully hidden with no permissions granted")
            }
        }
    }

    @Test
    fun `visibleNavItems with only SYSTEM_VIEW shows System but hides Referenzdaten in ADMINISTRATION`() {
        val visible = MainLayout.visibleNavItems(NavSection.ADMINISTRATION, setOf(AppPermission.SYSTEM_VIEW))
        // SFS — SETTINGS shares SYSTEM_VIEW and follows SYSTEM in declaration order; the DRAWER
        // renders it as System's child (SettingsNavigationTest/children rendering), the permission
        // axis itself only sees both as plain section entries.
        assertEquals(listOf(NavItem.SYSTEM, NavItem.SETTINGS), visible)
    }

    @Test
    fun `visibleNavItems with only REFERENCE_DATA_EDIT shows Referenzdaten but hides System in ADMINISTRATION`() {
        val visible = MainLayout.visibleNavItems(NavSection.ADMINISTRATION, setOf(AppPermission.REFERENCE_DATA_EDIT))
        assertEquals(listOf(NavItem.REFERENCE_DATA), visible)
    }

    /** TS-2 — same source-scan idiom as the drawer-order test above (see the class KDoc for why
     *  a plain unit test can't instantiate [MainLayout] directly): asserts the RP-initiated
     *  logout button ([MainLayout.createLogoutLink]) is wired into the header and points at
     *  Quarkus OIDC's logout path. */
    @Test
    fun `the header carries a logout button pointing at the OIDC logout path`() {
        val source = File("src/main/kotlin/de/littletrotter/ui/MainLayout.kt").readText()

        assertTrue(
            source.contains("setLocation(\"/logout\")"),
            "MainLayout must send the browser to Quarkus OIDC's configured logout path (/logout)",
        )
        assertTrue(
            source.contains("if (appRoleProvider.isAuthenticated()) {") &&
                source.contains("header.add(createLogoutLink())"),
            "the logout link must be added to the header row for authenticated users",
        )
    }
}
