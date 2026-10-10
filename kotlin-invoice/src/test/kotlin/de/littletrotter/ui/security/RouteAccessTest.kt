package de.littletrotter.ui.security

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRole
import de.littletrotter.ui.view.info.InfoView
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.invoice.InvoiceStatisticsView
import de.littletrotter.ui.view.invoice.PrintJobListView
import de.littletrotter.ui.view.invoice.ReceiptPoolView
import de.littletrotter.ui.view.masterdata.ArticleView
import de.littletrotter.ui.view.masterdata.RecipientView
import de.littletrotter.ui.view.masterdata.SenderProfileView
import de.littletrotter.ui.view.message.MessageCenterView
import de.littletrotter.ui.view.referencedata.ReferenceDataView
import de.littletrotter.ui.view.system.SbomView
import de.littletrotter.ui.view.system.SystemView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TS-8 — pins [RouteAccess]'s matrix, using the REAL `@Route` view classes, to the binding route
 * matrix table. A change here that doesn't also update the doc's table is exactly the drift this
 * test exists to catch.
 */
class RouteAccessTest {

    @Test
    fun `route matrix matches the binding table exactly`() {
        assertEquals(AppPermission.INVOICE_FULL_EDIT, RouteAccess.permissionFor(InvoiceListView::class.java))
        assertEquals(AppPermission.INVOICE_FULL_EDIT, RouteAccess.permissionFor(InvoiceDetailView::class.java))
        assertEquals(AppPermission.INVOICE_JOBS, RouteAccess.permissionFor(PrintJobListView::class.java))
        assertEquals(AppPermission.RECEIPTS_AND_STATISTICS, RouteAccess.permissionFor(ReceiptPoolView::class.java))
        assertEquals(AppPermission.RECEIPTS_AND_STATISTICS, RouteAccess.permissionFor(InvoiceStatisticsView::class.java))
        assertEquals(AppPermission.MASTER_DATA_EDIT, RouteAccess.permissionFor(RecipientView::class.java))
        assertEquals(AppPermission.MASTER_DATA_EDIT, RouteAccess.permissionFor(SenderProfileView::class.java))
        assertEquals(AppPermission.ARTICLES_EDIT, RouteAccess.permissionFor(ArticleView::class.java))
        assertEquals(AppPermission.REFERENCE_DATA_EDIT, RouteAccess.permissionFor(ReferenceDataView::class.java))
        assertEquals(AppPermission.SYSTEM_VIEW, RouteAccess.permissionFor(SystemView::class.java))
        assertEquals(AppPermission.SYSTEM_VIEW, RouteAccess.permissionFor(SbomView::class.java))
        assertEquals(AppPermission.SYSTEM_VIEW, RouteAccess.permissionFor(MessageCenterView::class.java))
        assertNull(RouteAccess.permissionFor(InfoView::class.java))
    }

    @Test
    fun `isGoverned is true for every matrix entry and false for an unrelated class`() {
        RouteAccess.ENTRIES.forEach { (viewClass, _) -> assertTrue(RouteAccess.isGoverned(viewClass), viewClass.simpleName) }
        assertFalse(RouteAccess.isGoverned(String::class.java))
    }

    @Test
    fun `permissionFor a class outside the matrix is null - same as any-authenticated`() {
        assertNull(RouteAccess.permissionFor(String::class.java))
    }

    @Test
    fun `InvoiceDetailView is excluded from landing candidates - it needs a path parameter`() {
        assertFalse(RouteAccess.LANDING_CANDIDATES.any { it.first == InvoiceDetailView::class.java })
        assertTrue(RouteAccess.ENTRIES.any { it.first == InvoiceDetailView::class.java })
    }

    @Test
    fun `first permitted landing view for invoicing-only roles is InvoiceListView itself`() {
        assertEquals(InvoiceListView::class.java, RouteAccess.firstPermittedLandingView(setOf(AppRole.INVOICING)))
    }

    @Test
    fun `first permitted landing view for admin-only roles is SystemView - the acceptance example`() {
        assertEquals(SystemView::class.java, RouteAccess.firstPermittedLandingView(setOf(AppRole.ADMIN)))
    }

    @Test
    fun `first permitted landing view for no roles at all falls back to InfoView`() {
        assertEquals(InfoView::class.java, RouteAccess.firstPermittedLandingView(emptySet()))
    }

    @Test
    fun `first permitted landing view for both roles is still InvoiceListView - declared first`() {
        assertEquals(InvoiceListView::class.java, RouteAccess.firstPermittedLandingView(setOf(AppRole.INVOICING, AppRole.ADMIN)))
    }
}
