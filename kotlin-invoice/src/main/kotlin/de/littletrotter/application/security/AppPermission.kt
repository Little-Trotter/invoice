package de.littletrotter.application.security

/**
 * TS-8 — the application's fine-grained permissions. One value per row of the binding permission
 * table: product design lives in code, not data — [AppRole] bundles these
 * statically into the two Authentik-sourced roles (`invoicing`, `admin`). Both the Vaadin
 * navigation guard ([de.littletrotter.ui.security.NavigationAccessGuard]) and the service-layer
 * interceptor ([RequiresPermission]/[PermissionInterceptor]) check against this enum, never
 * against raw role strings, so a future permission split only touches [AppRole]'s mapping.
 */
enum class AppPermission {
    /** Invoices: full edit — create, edit, generate/deliver, publish, cancel. */
    INVOICE_FULL_EDIT,

    /** Invoice jobs: e-invoice/print job list, job actions. */
    INVOICE_JOBS,

    /** Receipt pool and invoice statistics. */
    RECEIPTS_AND_STATISTICS,

    /** Master data — sender profiles (providers) and recipients (customers): read, edit, create. */
    MASTER_DATA_EDIT,

    /** Articles: read, edit, create. */
    ARTICLES_EDIT,

    /** Reference data: read, edit. */
    REFERENCE_DATA_EDIT,

    /** System view (incl. SBOM) and operational monitoring (message center). */
    SYSTEM_VIEW,

    /** Database maintenance. */
    DATABASE_MAINTENANCE,

    /** Restore or reproduce missing invoice documents. */
    DOCUMENT_REPRODUCE,

    /** Mail: compose and send a mail from an invoice. Mirrors the mail component's compose permission. */
    MAIL_COMPOSE_AND_SEND,

    /** Mail: read the send journal. */
    MAIL_JOURNAL_VIEW,

    /** Mail: edit the mail templates. */
    MAIL_TEMPLATE_EDIT,

    /** Mail: set the archive address and repeat a failed hand-over to the mail archive. Mirrors the mail component's admin permission. */
    MAIL_ARCHIVE_HANDOVER_ADMIN,

    /** Offers: read the list, details, log and attachments. Mirrors the offer component's view permission. */
    QUOTE_VIEW,

    /** Offers: create and change offers and drive their life cycle. */
    QUOTE_EDIT,

    /** Offers: hand a reviewed offer to the mail component. */
    QUOTE_SEND,

    /** Offers: change the offer settings (default validity). */
    QUOTE_SETTINGS_EDIT,

    /** Offers: add entries and versions to the offer receipt pool. Mirrors the offer component's library edit permission. */
    QUOTE_LIBRARY_EDIT,

    /** Offers: archive an entry of the offer receipt pool. Mirrors the offer component's library archive permission. */
    QUOTE_LIBRARY_ARCHIVE,
}
