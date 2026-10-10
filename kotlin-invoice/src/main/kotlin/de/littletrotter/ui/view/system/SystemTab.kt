package de.littletrotter.ui.view.system

/**
 * The tabs of [SystemView], in display order.
 *
 * Same shape as [de.littletrotter.ui.view.referencedata.ReferenceDataTab]: the enum is the single
 * source of truth for which tabs exist and in what order, so adding or removing one is a one-line
 * change here plus its panel, not an edit spread over the view, its i18n keys and every test that
 * counts tabs. Tests derive their expectations from [entries] instead of hard-coding a number.
 */
enum class SystemTab(val labelKey: String) {
    STATUS("system.tab.status"),
    NATS("system.tab.nats"),
    CACHE("system.tab.cache"),
    SBOM("system.tab.sbom"),
    // Global, read-only "invoice_audit_journal" view (JournalPanel).
    JOURNAL("system.tab.journal"),
}
