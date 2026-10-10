package de.littletrotter.ui.navigation

import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.security.AppPermission
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.navigation.NavItem.Companion.drawerParent
import de.littletrotter.ui.view.referencedata.ReferenceDataTab
import de.littletrotter.ui.view.referencedata.hiddenReferenceDataTabs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SFS — the navigation-model half of the settings feature: the SETTINGS item exists, is rendered
 * as a child of SYSTEM (not as a section leaf), keeps the longest-prefix route rules intact, and
 * the breadcrumb gains the "System › Einstellungen" level.
 *
 * Pure functions only (the model companion + MainLayout's companion predicates + the palette
 * builder) — the actual drawer `SideNavItem` tree construction is covered by
 * `MainLayoutDrawerKaribuTest`'s idiom if it ever needs extending.
 */
class SettingsNavigationTest {

    // ── Model ──────────────────────────────────────────────────────────────────

    @Test
    fun `SETTINGS is an administration-section item rendered under SYSTEM`() {
        assertEquals(NavSection.ADMINISTRATION, NavItem.SETTINGS.section)
        assertEquals("system/einstellungen", NavItem.SETTINGS.routePath)
        assertEquals(NavItem.SYSTEM, NavItem.SETTINGS.drawerParent())
        assertEquals(listOf(NavItem.SETTINGS), NavItem.childItemsOf(NavItem.SYSTEM, NavItem.itemsFor(NavSection.ADMINISTRATION)))
    }

    @Test
    fun `top-level section rendering does not duplicate children`() {
        val topLevel = NavItem.topLevelItemsFor(NavSection.ADMINISTRATION)
        assertEquals(listOf(NavItem.REFERENCE_DATA, NavItem.SYSTEM), topLevel)
    }

    @Test
    fun `route matching prefers the deeper child entry`() {
        // longest-prefix: exactly what keeps the breadcrumb/drawer highlight on "Einstellungen"
        // while the same nav section also owns the shorter "system" prefix.
        assertEquals(NavItem.SETTINGS, NavItem.activeItemFor("system/einstellungen"))
        assertEquals(NavItem.SETTINGS, NavItem.activeItemFor("system/einstellungen/"))
        assertEquals(NavItem.SYSTEM, NavItem.activeItemFor("system"))
        assertEquals(NavItem.SYSTEM, NavItem.activeItemFor("system/sonstiges"), "unknown path keeps the parent prefix match")
    }

    // ── Permission + availability axes compose the same for the new entry ─────

    @Test
    fun `the settings entry follows exactly the SYSTEM_VIEW permission`() {
        val administration = NavSection.ADMINISTRATION
        val adminPerms = setOf(AppPermission.SYSTEM_VIEW, AppPermission.REFERENCE_DATA_EDIT)
        val viewerPerms = setOf(AppPermission.REFERENCE_DATA_EDIT)
        val visibleForAdmin = MainLayout.visibleNavItems(administration, adminPerms)
        assertTrue(NavItem.SETTINGS in visibleForAdmin)
        assertFalse(NavItem.SETTINGS in MainLayout.visibleNavItems(administration, viewerPerms))
    }

    @Test
    fun `system node expands while the settings child is active`() {
        assertTrue(MainLayout.systemExpandedFor("system/einstellungen"))
        assertFalse(MainLayout.systemExpandedFor("system"))
        assertFalse(MainLayout.systemExpandedFor("referenzdaten/reisekosten"))
    }

    // ── E-Mail: deployment AND flag, orthogonal axes ──────────────────────────

    @Test
    fun `mail entry availability is the AND of deployment state and the feature flag`() {
        val installed = MailAvailability(true)
        val missing = MailAvailability(false)
        assertTrue(MainLayout.mailAvailabilityPredicate(installed, emailFlagEnabled = true)(NavItem.MAIL_JOURNAL))
        assertFalse(MainLayout.mailAvailabilityPredicate(installed, emailFlagEnabled = false)(NavItem.MAIL_JOURNAL))
        assertFalse(MainLayout.mailAvailabilityPredicate(missing, emailFlagEnabled = true)(NavItem.MAIL_JOURNAL))
        assertFalse(MainLayout.mailAvailabilityPredicate(missing, emailFlagEnabled = false)(NavItem.MAIL_JOURNAL))
        // every other entry: untouched by the mail axis.
        assertTrue(MainLayout.mailAvailabilityPredicate(missing, emailFlagEnabled = false)(NavItem.INVOICES))
        assertTrue(MainLayout.mailAvailabilityPredicate(missing, emailFlagEnabled = false)(NavItem.SETTINGS))
    }

    @Test
    fun `the pre-SFS single-argument predicate keeps pure deployment semantics`() {
        assertFalse(MainLayout.mailAvailabilityPredicate(MailAvailability(false))(NavItem.MAIL_JOURNAL))
        assertTrue(MainLayout.mailAvailabilityPredicate(MailAvailability(true))(NavItem.MAIL_JOURNAL))
    }

    // ── Command palette ────────────────────────────────────────────────────────

    @Test
    fun `the palette indexes the settings entry as a top-level nav target`() {
        val index = buildCommandPaletteIndex()
        val entry = index.single { it.navItem == NavItem.SETTINGS }
        assertEquals("system/einstellungen", entry.routePath)
        assertEquals(NavSection.ADMINISTRATION, entry.section)
    }

    @Test
    fun `a hidden reference-data tab is absent from the palette too`() {
        val hidden = hiddenReferenceDataTabs(perDiemUiEnabled = false)
        val index = buildCommandPaletteIndex(hiddenReferenceDataTabs = hidden)
        assertFalse(index.any { it.routePath == "referenzdaten/reisekosten/verpflegungspauschalen" })
        assertTrue(index.any { it.routePath == "referenzdaten/reisekosten/uebernachtungspauschalen" })
        assertNotNull(FeatureFlag.fromKey("feature.perdiem.enabled"))
    }

    // ── Breadcrumb ─────────────────────────────────────────────────────────────

    @Test
    fun `the settings route gains the system level in the breadcrumb`() {
        val segments = NavBreadcrumb.pathToSegments("system/einstellungen")
        assertEquals(3, segments.size, "Administration › System › Einstellungen: $segments")
        assertTrue(segments.last() is BreadcrumbSegment.Current)
    }

    @Test
    fun `the plain system route keeps its two-level breadcrumb`() {
        val segments = NavBreadcrumb.pathToSegments("system")
        assertEquals(2, segments.size)
        assertTrue(segments.last() is BreadcrumbSegment.Current)
    }

    // ── hiddenTabs mapping ─────────────────────────────────────────────────────

    @Test
    fun `only the per-diem rates tab is feature bound`() {
        assertEquals(
            setOf(ReferenceDataTab.PER_DIEM_RATES),
            hiddenReferenceDataTabs(perDiemUiEnabled = false),
        )
        assertTrue(hiddenReferenceDataTabs(perDiemUiEnabled = true).isEmpty())
    }

    // ── RouteAccess mapping ────────────────────────────────────────────────────

    @Test
    fun `settings route is governed with SYSTEM_VIEW and never a landing target`() {
        assertEquals(
            AppPermission.SYSTEM_VIEW,
            de.littletrotter.ui.security.RouteAccess.permissionFor(
                de.littletrotter.ui.view.settings.SettingsView::class.java,
            ),
        )
        assertTrue(de.littletrotter.ui.security.RouteAccess.isGoverned(de.littletrotter.ui.view.settings.SettingsView::class.java))
        val landingRoutes = de.littletrotter.ui.security.RouteAccess.LANDING_CANDIDATES.map { it.first }
        assertFalse(landingRoutes.contains(de.littletrotter.ui.view.settings.SettingsView::class.java))
    }

    // ── null availability predicate (no component wired) ───────────────────────

    @Test
    fun `a null mail availability hides the entry however the flag reads`() {
        assertFalse(MainLayout.mailAvailabilityPredicate(null, emailFlagEnabled = true)(NavItem.MAIL_JOURNAL))
        assertTrue(MainLayout.mailAvailabilityPredicate(null, emailFlagEnabled = false)(NavItem.INVOICES))
    }
}
