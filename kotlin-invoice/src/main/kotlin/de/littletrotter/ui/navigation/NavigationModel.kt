package de.littletrotter.ui.navigation

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.router.Route
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.invoice.ReceiptPoolView
import de.littletrotter.ui.view.invoice.PrintJobListView
import de.littletrotter.ui.view.invoice.InvoiceStatisticsView
import de.littletrotter.ui.view.masterdata.SenderProfileView
import de.littletrotter.ui.view.masterdata.RecipientView
import de.littletrotter.ui.view.masterdata.ArticleView
import de.littletrotter.ui.view.referencedata.ReferenceDataView
import de.littletrotter.ui.view.settings.SettingsView
import de.littletrotter.ui.view.system.SystemView
import de.littletrotter.ui.view.info.InfoView
import de.littletrotter.ui.view.mail.MailJournalView
import de.littletrotter.ui.view.mail.MailTemplatesView
import de.littletrotter.ui.view.quote.QuoteListView
import de.littletrotter.ui.view.quote.QuoteReceiptPoolView
import de.littletrotter.ui.view.quote.QuoteStatisticsView
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup

/**
 * MEN-1 — navigation sections that group menu items.
 */
enum class NavSection {
    INVOICING,
    QUOTING,
    MAIL,
    MASTER_DATA,
    ADMINISTRATION,
    HELP,
}

/**
 * MEN-1 — a navigation item in the drawer, with its section, icon, view class, and route path.
 * Route paths are extracted from @Route annotations on view classes and hardcoded here to prevent drift.
 * Declaration order is display order within the section.
 */
enum class NavItem(
    val section: NavSection,
    val icon: VaadinIcon,
    val viewClass: Class<out Component>,
    val routePath: String,
) {
    // INVOICING section
    INVOICES(NavSection.INVOICING, VaadinIcon.FILE_TEXT, InvoiceListView::class.java, ""),
    RECEIPTS(NavSection.INVOICING, VaadinIcon.ARCHIVES, ReceiptPoolView::class.java, "receipt-pool"),
    PRINT_JOBS(NavSection.INVOICING, VaadinIcon.FILE_PROCESS, PrintJobListView::class.java, "e-invoice-jobs"),
    STATISTICS(NavSection.INVOICING, VaadinIcon.CHART, InvoiceStatisticsView::class.java, "statistics"),

    // QUOTING section
    /**
     * The offer list. Hidden entirely (drawer *and* command palette) unless
     * [de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort.enabled]; see
     * [de.littletrotter.ui.MainLayout.quoteAvailabilityPredicate].
     */
    QUOTES(NavSection.QUOTING, VaadinIcon.FILE_TEXT_O, QuoteListView::class.java, "quotes"),

    /** The offer receipt pool (reusable documents); hidden together with [QUOTES]. */
    QUOTE_RECEIPT_POOL(NavSection.QUOTING, VaadinIcon.ARCHIVES, QuoteReceiptPoolView::class.java, "quote-receipt-pool"),

    /** The offer statistics; hidden together with [QUOTES]. */
    QUOTE_STATISTICS(NavSection.QUOTING, VaadinIcon.CHART, QuoteStatisticsView::class.java, "quotes/statistics"),

    // MAIL section
    /**
     * The mail journal and, after it, the mail templates. Both hidden entirely (drawer *and* command palette) unless
     * [de.littletrotter.application.mail.port.inbound.MailAvailabilityQueryPort.enabled]; see
     * [de.littletrotter.ui.MainLayout.visibleNavItems]'s availability predicate.
     */
    MAIL_JOURNAL(NavSection.MAIL, VaadinIcon.ENVELOPE, MailJournalView::class.java, "mail/journal"),
    MAIL_TEMPLATES(NavSection.MAIL, VaadinIcon.FILE_FONT, MailTemplatesView::class.java, "mail/templates"),

    // MASTER_DATA section
    SENDER_PROFILES(NavSection.MASTER_DATA, VaadinIcon.USER, SenderProfileView::class.java, "sender-profiles"),
    RECIPIENTS(NavSection.MASTER_DATA, VaadinIcon.FAMILY, RecipientView::class.java, "recipients"),
    ARTICLES(NavSection.MASTER_DATA, VaadinIcon.CLIPBOARD_TEXT, ArticleView::class.java, "articles"),

    // ADMINISTRATION section
    REFERENCE_DATA(NavSection.ADMINISTRATION, VaadinIcon.BOOK, ReferenceDataView::class.java, "referenzdaten"),
    SYSTEM(NavSection.ADMINISTRATION, VaadinIcon.COG, SystemView::class.java, "system"),

    /**
     * SFS — the feature-switches view. Lives UNDER [SYSTEM] in the drawer (see
     * [de.littletrotter.ui.MainLayout]'s system nav item): the model entry itself is what the
     * command palette and the route guard address, the parent-child grouping is a rendering rule
     * that [de.littletrotter.ui.MainLayout.visibleNavItems] keeps inside the ADMINISTRATION
     * section — the drift protection (a navigation model test) only knows "one item, one @Route
     * value", and this entry's routePath is checked against
     * [SettingsView]'s own `@Route` like every other.
     */
    SETTINGS(NavSection.ADMINISTRATION, VaadinIcon.WRENCH, SettingsView::class.java, "system/einstellungen"),

    // HELP section
    INFO(NavSection.HELP, VaadinIcon.INFO_CIRCLE, InfoView::class.java, "info"),
    ;

    /**
     * Get the route path from the view class's @Route annotation, ensuring consistency with
     * this item's hardcoded [routePath].
     */
    fun annotatedRoutePath(): String = routePathFromAnnotation(viewClass)

    companion object {
        /**
         * [annotatedRoutePath]'s actual annotation lookup, split out so the defensive
         * "no @Route" throw — never reachable through any of today's [NavItem.viewClass] entries,
         * every one of which does carry `@Route` — stays unit-testable against a plain
         * [Class]&lt;out [Component]&gt; instead of needing a 13th enum entry with a broken invariant.
         */
        internal fun routePathFromAnnotation(target: Class<out Component>): String =
            target.getAnnotation(Route::class.java)?.value
                ?: error("View class ${target.simpleName} has no @Route annotation")

        /**
         * Get all NavItems in a section, in display order.
         */
        fun itemsFor(section: NavSection): List<NavItem> =
            entries.filter { it.section == section }

        /**
         * SFS — the drawer parent of [this]: an item with a parent is rendered as an expandable
         * child below it instead of as a top-level leaf of its [section]. A function, not a
         * constructor field, because Kotlin forbids one enum entry referencing another in a
         * constructor call — the rule still lives in the model so drawer, palette and breadcrumb
         * read one truth the same way they read [itemsFor].
         */
        fun NavItem.drawerParent(): NavItem? = when (this) {
            SETTINGS -> SYSTEM
            else -> null
        }

        /** SFS — section leaves for drawer rendering: [itemsFor] minus the [drawerParent]ed child
         *  entries (a child is reachable through its parent's expanded list, never twice). */
        fun topLevelItemsFor(section: NavSection): List<NavItem> =
            itemsFor(section).filter { it.drawerParent() == null }

        /** SFS — visible children of [parent], [drawerParent]-wise; empty for a plain leaf. */
        fun childItemsOf(parent: NavItem, visibleSectionItems: List<NavItem>): List<NavItem> =
            visibleSectionItems.filter { it.drawerParent() == parent }

        /**
         * Find the NavItem for a given route path, using longest-prefix match.
         * For example, "referenzdaten/e-rechnung/b2b-profile" matches REFERENCE_DATA (path "referenzdaten").
         * Trailing slashes are tolerated and stripped before matching.
         * Returns null if no match is found.
         */
        fun activeItemFor(path: String): NavItem? {
            val normalizedPath = path.removeSuffix("/")
            return entries
                .filter { it.routePath.isNotEmpty() }
                .sortedByDescending { it.routePath.length }
                .firstOrNull { normalizedPath.startsWith(it.routePath) }
                ?: entries.firstOrNull { it.routePath.isEmpty() && normalizedPath.isEmpty() }
        }

        /**
         * Find the reference-data group for a given route path, resolving deep links like
         * "referenzdaten/e-rechnung/b2b-profile" to the E_RECHNUNG group.
         * Returns null if the path doesn't resolve to REFERENCE_DATA or if the group is unknown.
         */
        fun activeGroupFor(path: String): ReferenceDataGroup? {
            if (activeItemFor(path) != REFERENCE_DATA) return null

            val normalizedPath = path.removeSuffix("/")
            val referenceDataBase = "referenzdaten"
            if (!normalizedPath.startsWith(referenceDataBase)) return null

            // Extract the group slug from paths like "referenzdaten/e-rechnung" or "referenzdaten/e-rechnung/b2b-profile"
            val remainder = normalizedPath.removePrefix("$referenceDataBase/").takeWhile { it != '/' }
            return if (remainder.isNotEmpty()) ReferenceDataGroup.fromSlug(remainder) else null
        }
    }
}
