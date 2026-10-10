package de.littletrotter.application.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * TS-8 — pins [AppRole]'s permission bundles to the binding permission table so an accidental
 * edit to the static mapping is caught here rather than surfacing as a silent access-control
 * regression.
 */
class AppRoleTest {

    @Test
    fun `invoicing bundles exactly the invoicing-column permissions`() {
        assertEquals(
            setOf(
                AppPermission.INVOICE_FULL_EDIT,
                AppPermission.INVOICE_JOBS,
                AppPermission.RECEIPTS_AND_STATISTICS,
                AppPermission.MASTER_DATA_EDIT,
                AppPermission.ARTICLES_EDIT,
                AppPermission.DOCUMENT_REPRODUCE,
                AppPermission.MAIL_COMPOSE_AND_SEND,
                AppPermission.MAIL_JOURNAL_VIEW,
            ),
            AppRole.INVOICING.permissions,
        )
    }

    @Test
    fun `admin bundles exactly the admin-column permissions`() {
        assertEquals(
            setOf(
                AppPermission.REFERENCE_DATA_EDIT,
                AppPermission.SYSTEM_VIEW,
                AppPermission.DATABASE_MAINTENANCE,
                AppPermission.DOCUMENT_REPRODUCE,
                AppPermission.MAIL_TEMPLATE_EDIT,
                AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN,
                AppPermission.MAIL_JOURNAL_VIEW,
                AppPermission.QUOTE_VIEW,
                AppPermission.QUOTE_SETTINGS_EDIT,
                AppPermission.QUOTE_LIBRARY_ARCHIVE,
            ),
            AppRole.ADMIN.permissions,
        )
    }

    @Test
    fun `quoting bundles view, edit and send of offers and the receipt pool maintenance`() {
        assertEquals(
            setOf(
                AppPermission.QUOTE_VIEW,
                AppPermission.QUOTE_EDIT,
                AppPermission.QUOTE_SEND,
                AppPermission.QUOTE_LIBRARY_EDIT,
                AppPermission.QUOTE_LIBRARY_ARCHIVE,
            ),
            AppRole.QUOTING.permissions,
        )
    }

    @Test
    fun `only admin may edit the offer settings`() {
        assertEquals(
            listOf(AppRole.ADMIN),
            AppRole.entries.filter { AppPermission.QUOTE_SETTINGS_EDIT in it.permissions },
        )
    }

    @Test
    fun `DOCUMENT_REPRODUCE and MAIL_JOURNAL_VIEW are shared between invoicing and admin roles`() {
        assertEquals(setOf(AppPermission.DOCUMENT_REPRODUCE, AppPermission.MAIL_JOURNAL_VIEW), AppRole.INVOICING.permissions intersect AppRole.ADMIN.permissions)
    }

    @Test
    fun `all roles together cover all AppPermissions`() {
        val allBundled = AppRole.entries.flatMap { it.permissions }
        assertEquals(AppPermission.entries.toSet(), allBundled.toSet())
    }

    @Test
    fun `fromClaim matches the Authentik group names case-insensitively`() {
        assertEquals(AppRole.INVOICING, AppRole.fromClaim("invoicing"))
        assertEquals(AppRole.ADMIN, AppRole.fromClaim("admin"))
        assertEquals(AppRole.QUOTING, AppRole.fromClaim("Quoting"))
        assertEquals(AppRole.ADMIN, AppRole.fromClaim("Admin"))
        assertEquals(AppRole.ADMIN, AppRole.fromClaim("ADMIN"))
    }

    @Test
    fun `fromClaim ignores unrelated groups`() {
        assertNull(AppRole.fromClaim("authentik Admins"))
        assertNull(AppRole.fromClaim(""))
        assertNull(AppRole.fromClaim("invoicing-lead"))
    }

    @Test
    fun `permissions() is additive across all roles`() {
        val all = AppRole.entries.toSet().permissions()
        assertEquals(AppPermission.entries.toSet(), all)
    }

    @Test
    fun `permissions() of an empty role set is empty`() {
        assertEquals(emptySet<AppPermission>(), emptySet<AppRole>().permissions())
    }

    @Test
    fun `permissions() of a single role is exactly that role's own set`() {
        assertEquals(AppRole.INVOICING.permissions, setOf(AppRole.INVOICING).permissions())
        assertEquals(AppRole.ADMIN.permissions, setOf(AppRole.ADMIN).permissions())
    }
}
