package de.littletrotter.ui.security

import com.vaadin.flow.component.Component
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.permissions
import de.littletrotter.ui.view.info.InfoView
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.invoice.InvoiceStatisticsView
import de.littletrotter.ui.view.invoice.PrintJobListView
import de.littletrotter.ui.view.invoice.ReceiptPoolView
import de.littletrotter.ui.view.mail.MailComposeView
import de.littletrotter.ui.view.mail.MailJournalView
import de.littletrotter.ui.view.mail.MailTemplatesView
import de.littletrotter.ui.view.masterdata.ArticleView
import de.littletrotter.ui.view.masterdata.RecipientView
import de.littletrotter.ui.view.masterdata.SenderProfileView
import de.littletrotter.ui.view.message.MessageCenterView
import de.littletrotter.ui.view.quote.QuoteDetailView
import de.littletrotter.ui.view.quote.QuoteEditorView
import de.littletrotter.ui.view.quote.QuoteListView
import de.littletrotter.ui.view.quote.QuoteReceiptPoolView
import de.littletrotter.ui.view.quote.QuoteStatisticsView
import de.littletrotter.ui.view.referencedata.ReferenceDataView
import de.littletrotter.ui.view.settings.SettingsView
import de.littletrotter.ui.view.system.SbomView
import de.littletrotter.ui.view.system.SystemView

/**
 * TS-8 — the binding route matrix, mapped from the doc's logical route names to the actual
 * `@Route` view classes:
 *
 * | Doc route | View | Permission |
 * |---|---|---|
 * | `` (root) | [InvoiceListView] | [AppPermission.INVOICE_FULL_EDIT] |
 * | `invoice/:id` | [InvoiceDetailView] | [AppPermission.INVOICE_FULL_EDIT] |
 * | `mail/compose/:draftId` | [MailComposeView] | [AppPermission.INVOICE_FULL_EDIT] |
 * | `mail/journal` | [MailJournalView] | [AppPermission.MAIL_JOURNAL_VIEW] |
 * | `mail/templates` | [MailTemplatesView] | [AppPermission.MAIL_TEMPLATE_EDIT] |
 * | `quotes` | [QuoteListView] | [AppPermission.QUOTE_VIEW] |
 * | `quote-receipt-pool` | [QuoteReceiptPoolView] | [AppPermission.QUOTE_VIEW] |
 * | `quotes/statistics` | [QuoteStatisticsView] | [AppPermission.QUOTE_VIEW] |
 * | `quotes/:id` | [QuoteDetailView] | [AppPermission.QUOTE_VIEW] |
 * | `quotes/:id/edit` | [QuoteEditorView] | [AppPermission.QUOTE_EDIT] |
 * | `e-invoice-jobs` | [PrintJobListView] | [AppPermission.INVOICE_JOBS] |
 * | `receipt-pool` | [ReceiptPoolView] | [AppPermission.RECEIPTS_AND_STATISTICS] |
 * | `statistics` | [InvoiceStatisticsView] | [AppPermission.RECEIPTS_AND_STATISTICS] |
 * | `recipients` | [RecipientView] | [AppPermission.MASTER_DATA_EDIT] |
 * | `sender-profiles` | [SenderProfileView] | [AppPermission.MASTER_DATA_EDIT] |
 * | `articles` | [ArticleView] | [AppPermission.ARTICLES_EDIT] |
 * | `referenzdaten/:gruppe?/:tab?` | [ReferenceDataView] | [AppPermission.REFERENCE_DATA_EDIT] |
 * | `system` | [SystemView] | [AppPermission.SYSTEM_VIEW] |
 * | `system/einstellungen` | [SettingsView] | [AppPermission.SYSTEM_VIEW] |
 * | `sbom` | [SbomView] | [AppPermission.SYSTEM_VIEW] |
 * | `messages` | [MessageCenterView] | [AppPermission.SYSTEM_VIEW] |
 * | `info` | [InfoView] | `null` — any authenticated user |
 *
 * `null` in [ENTRIES] means "any authenticated user, no specific [AppPermission]" — the guard
 * still requires a governed view to be reached at all (HTTP-level `authenticated` policy,
 * application.properties/TS-2), it just doesn't gate on role beyond that.
 *
 * **Non-Vaadin protected paths** (documented here for completeness, not part of [ENTRIES] since
 * they are plain JAX-RS routes, not [Component] views — [isGoverned]/[permissionFor] never see
 * them): [de.littletrotter.ui.documents.DocumentDownloadResource] serves
 * `GET /documents/jobs/{jobId}/pdf|xml|archive` (any of [AppPermission.INVOICE_JOBS],
 * [AppPermission.INVOICE_FULL_EDIT]) and `GET /documents/receipts/{receiptId}` (any of
 * [AppPermission.RECEIPTS_AND_STATISTICS], [AppPermission.INVOICE_FULL_EDIT]) — mirroring
 * [PrintJobListView]'s/[ReceiptPoolView]'s and [InvoiceDetailView]'s own permissions above, since
 * both views render links into these routes.
 * Enforced in the resource itself via [de.littletrotter.application.security.AppRoleProvider
 * .hasPermission], not this navigation guard, which only runs for Vaadin navigation. Every request
 * still passes the deployment's global `authenticated` HTTP policy first
 * (application.properties/TS-2), exactly like every [ENTRIES] view.
 */
object RouteAccess {

    /** Every routed view this navigation guard governs, in menu/landing-priority order (used by
     *  [firstPermittedLandingView] for the root-redirect fallback and by the drawer menu for
     *  "hide, don't disable" visibility). */
    val ENTRIES: List<Pair<Class<out Component>, AppPermission?>> = listOf(
        InvoiceListView::class.java to AppPermission.INVOICE_FULL_EDIT,
        SystemView::class.java to AppPermission.SYSTEM_VIEW,
        // SFS — the feature-switches view renders as a child of SYSTEM in the drawer, but it is
        // its own governed route with its own guard. Never a landing target (see below).
        SettingsView::class.java to AppPermission.SYSTEM_VIEW,
        PrintJobListView::class.java to AppPermission.INVOICE_JOBS,
        ReceiptPoolView::class.java to AppPermission.RECEIPTS_AND_STATISTICS,
        InvoiceStatisticsView::class.java to AppPermission.RECEIPTS_AND_STATISTICS,
        SenderProfileView::class.java to AppPermission.MASTER_DATA_EDIT,
        RecipientView::class.java to AppPermission.MASTER_DATA_EDIT,
        ArticleView::class.java to AppPermission.ARTICLES_EDIT,
        ReferenceDataView::class.java to AppPermission.REFERENCE_DATA_EDIT,
        SbomView::class.java to AppPermission.SYSTEM_VIEW,
        MessageCenterView::class.java to AppPermission.SYSTEM_VIEW,
        MailJournalView::class.java to AppPermission.MAIL_JOURNAL_VIEW,
        MailTemplatesView::class.java to AppPermission.MAIL_TEMPLATE_EDIT,
        QuoteListView::class.java to AppPermission.QUOTE_VIEW,
        QuoteStatisticsView::class.java to AppPermission.QUOTE_VIEW,
        QuoteReceiptPoolView::class.java to AppPermission.QUOTE_VIEW,
        InfoView::class.java to null,
        // Needs a path parameter (`invoice/:id`) — excluded from LANDING_CANDIDATES below, it can
        // never be a root-redirect target.
        InvoiceDetailView::class.java to AppPermission.INVOICE_FULL_EDIT,
        // Needs a path parameter (`mail/compose/:draftId`) — likewise never a landing target.
        MailComposeView::class.java to AppPermission.INVOICE_FULL_EDIT,
        QuoteDetailView::class.java to AppPermission.QUOTE_VIEW,
        QuoteEditorView::class.java to AppPermission.QUOTE_EDIT,
    )

    // The offer views exist only while the offer component is deployed, which the guard does not
    // know; landing on one of them could dead-end, so none is ever a root-redirect target.
    private val LANDING_EXCLUDED = setOf(
        InvoiceDetailView::class.java,
        MailComposeView::class.java,
        SettingsView::class.java,
        QuoteListView::class.java,
        QuoteStatisticsView::class.java,
        QuoteReceiptPoolView::class.java,
        QuoteDetailView::class.java,
        QuoteEditorView::class.java,
    )

    /** [ENTRIES] minus views that need a path parameter — candidates for the root-redirect
     *  fallback (TS-8 acceptance: an admin-only user landing on `` is redirected, never 403'd). */
    val LANDING_CANDIDATES: List<Pair<Class<out Component>, AppPermission?>> =
        ENTRIES.filterNot { (viewClass, _) -> viewClass in LANDING_EXCLUDED }

    /** `null` both for "not governed by this matrix at all" and for "governed, any authenticated
     *  user" — use [isGoverned] to tell those apart. */
    fun permissionFor(viewClass: Class<*>): AppPermission? = ENTRIES.firstOrNull { it.first == viewClass }?.second

    /** Whether [viewClass] is one of the routes this guard governs — unmapped classes (Vaadin's
     *  own error/internal views) are never touched by [de.littletrotter.ui.security.NavigationAccessGuard]. */
    fun isGoverned(viewClass: Class<*>): Boolean = ENTRIES.any { it.first == viewClass }

    /** The first [LANDING_CANDIDATES] entry [roles] has [AppPermission] for (or that requires no
     *  specific permission) — used to redirect an admin-only user landing on `` (bound to
     *  [InvoiceListView], which they lack [AppPermission.INVOICE_FULL_EDIT] for) to a view they
     *  can actually use, e.g. [SystemView]. [InfoView] (permission `null`) is last in
     *  [LANDING_CANDIDATES] and always matches, so [first] is guaranteed to find an entry — no
     *  defensive fallback needed (or wanted: a future edit that ever removed [InfoView] from
     *  [LANDING_CANDIDATES] should fail loudly here, not silently mask the mistake). */
    fun firstPermittedLandingView(roles: Set<AppRole>): Class<out Component> {
        val granted = roles.permissions()
        return LANDING_CANDIDATES.first { (_, permission) -> permission == null || permission in granted }.first
    }
}
