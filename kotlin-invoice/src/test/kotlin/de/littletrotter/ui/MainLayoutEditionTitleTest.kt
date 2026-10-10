package de.littletrotter.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Browser-tab title composition. The application display name always leads, so it is never
 * hidden behind a hardcoded page title; the current view follows as an optional suffix.
 */
class MainLayoutEditionTitleTest {

    @Test
    fun `display name alone when no view title`() {
        assertEquals("Little Trotter", MainLayout.tabTitle("Little Trotter", null))
        assertEquals("Little Trotter", MainLayout.tabTitle("Little Trotter", ""))
        assertEquals("Little Trotter", MainLayout.tabTitle("Little Trotter", "   "))
    }

    @Test
    fun `view title is appended behind the display name`() {
        assertEquals("Little Trotter – System", MainLayout.tabTitle("Little Trotter", "System"))
        assertEquals("Little Trotter – Kunden", MainLayout.tabTitle("Little Trotter", "Kunden"))
    }

    // ── Bell badge text: hidden at 0, visible (and capped) above 0 ─────────────────────────────

    @Test
    fun `badge is hidden (null) at zero unread messages`() {
        assertEquals(null, MainLayout.bellBadgeText(0))
    }

    @Test
    fun `badge shows the exact count for a small backlog`() {
        assertEquals("1", MainLayout.bellBadgeText(1))
        assertEquals("42", MainLayout.bellBadgeText(42))
        assertEquals("99", MainLayout.bellBadgeText(99))
    }

    @Test
    fun `badge caps at 99+ for a large backlog`() {
        assertEquals("99+", MainLayout.bellBadgeText(100))
        assertEquals("99+", MainLayout.bellBadgeText(1000))
    }
}
