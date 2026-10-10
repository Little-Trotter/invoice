package de.littletrotter.ui.navigation

import de.littletrotter.ui.view.referencedata.ReferenceDataTab
import de.littletrotter.ui.view.referencedata.referenceDataRoute
import java.text.Normalizer

/**
 * MEN-5 — pure logic behind [CommandPaletteDialog], kept separate from the dialog component
 * itself: index building, diacritic-tolerant filtering, and recents ordering, following the
 * established "test the companion, not the CDI-wired component" idiom
 * ([de.littletrotter.ui.MainLayout.bellBadgeText], [NavBreadcrumb.pathToSegments]).
 */

/**
 * One entry in the command palette's index: a navigable target with its [section] (used to group
 * the empty-query result list), display [labelKey], an optional secondary [contextKey] line, and
 * the [routePath] Enter navigates to. Labels/context are resolved to i18n text lazily via
 * [resolveLabel], not cached here, so they always reflect the current locale. [navItem] is the
 * [NavItem] this entry belongs to for permission filtering ([de.littletrotter.ui.MainLayout.visibleNavItems]/TS-8):
 * itself for a top-level entry, [NavItem.REFERENCE_DATA] for every reference-data tab entry (they
 * have no `NavItem` of their own, but are gated by the same permission as the REFERENCE_DATA
 * drawer node).
 */
internal data class CommandPaletteEntry(
    val section: NavSection,
    val labelKey: LabelKey,
    val contextKey: LabelKey?,
    val routePath: String,
    val navItem: NavItem,
)

/**
 * The palette's full index: one entry per [NavItem] leaf (every top-level nav target except
 * [NavItem.REFERENCE_DATA], which has no route of its own) plus one entry per [ReferenceDataTab] —
 * the ten reference-data sub-tabs, each deep-linking to `referenzdaten/<gruppe>/<tab>`
 * ([referenceDataRoute]). Declaration order of [NavItem]/[ReferenceDataTab] flows through, so a
 * stable sort by [CommandPaletteEntry.section] (see [groupBySection]) reproduces the drawer's
 * section order without re-encoding it here.
 *
 * TS-8/MEN-5 — [itemFilter] applies the same "hidden, not disabled" permission rule the drawer
 * uses ([de.littletrotter.ui.MainLayout.visibleNavItems]): an entry is included iff `itemFilter(entry.navItem)` holds,
 * checked against [CommandPaletteEntry.navItem] (the owning [NavItem], [NavItem.REFERENCE_DATA]
 * for every reference-data tab entry). Defaults to "everything passes" so existing callers/tests
 * that don't care about permissions are unaffected.
 */
internal fun buildCommandPaletteIndex(
    itemFilter: (NavItem) -> Boolean = { true },
    // SFS — reference-data tabs hidden by a switched-off feature; they vanish from the palette
    // exactly like they vanish from the sub-tab rail. Defaults to "nothing hidden" so every
    // pre-SFS caller/test keeps its behaviour.
    hiddenReferenceDataTabs: Set<ReferenceDataTab> = emptySet(),
): List<CommandPaletteEntry> {
    val topLevelEntries = NavItem.entries
        .filter { it != NavItem.REFERENCE_DATA && itemFilter(it) }
        .map { item ->
            CommandPaletteEntry(
                item.section,
                LabelKey.NavItemKey(item),
                LabelKey.NavSectionKey(item.section),
                item.routePath,
                item,
            )
        }

    val referenceDataEntries = if (itemFilter(NavItem.REFERENCE_DATA)) {
        ReferenceDataTab.entries.filterNot { it in hiddenReferenceDataTabs }.map { tab ->
            CommandPaletteEntry(
                section = NavItem.REFERENCE_DATA.section,
                labelKey = LabelKey.RefDataTabKey(tab),
                contextKey = LabelKey.RefDataTabContextKey(tab.group),
                routePath = referenceDataRoute(tab.group, tab),
                navItem = NavItem.REFERENCE_DATA,
            )
        }
    } else {
        emptyList()
    }

    return topLevelEntries + referenceDataEntries
}

/** Diacritic marks left behind by [Normalizer.Form.NFD] decomposition (e.g. the combining
 *  diaeresis split off "ä"), stripped so folded text compares "a" to "a" regardless of accents. */
private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")

/**
 * Case-insensitive, diacritic-tolerant fold for search matching: NFD-decomposes [text] (splitting
 * e.g. "ä" into "a" + a combining diaeresis), strips the combining marks, then lowercases — so
 * "Kilometersätze" and "kilometersatze" fold to the same string.
 */
internal fun foldForSearch(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    return decomposed.replace(COMBINING_MARKS, "").lowercase()
}

/**
 * Case-insensitive, diacritic-tolerant substring match over [CommandPaletteEntry.labelKey] and
 * [CommandPaletteEntry.contextKey] (label OR context containing [query] is a match). A blank
 * [query] matches everything — callers show the empty-query view ([buildDisplayList]) instead of
 * calling this in that case, but returning everything keeps the function total. No fuzzy-ranking
 * library — the index is ~20 entries (per the MEN-5 work package), a simple substring scan is
 * plenty.
 */
internal fun filterCommandPaletteEntries(
    entries: List<CommandPaletteEntry>,
    query: String,
    resolve: (LabelKey) -> String = ::resolveLabel,
): List<CommandPaletteEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return entries
    val needle = foldForSearch(trimmed)
    return entries.filter { entry ->
        foldForSearch(resolve(entry.labelKey)).contains(needle) ||
            entry.contextKey?.let { foldForSearch(resolve(it)).contains(needle) } == true
    }
}

/** Stable-sorts [entries] by [NavSection] declaration order, preserving each section's internal
 *  order (the drawer's section order, reused instead of re-declared). */
internal fun groupBySection(entries: List<CommandPaletteEntry>): List<CommandPaletteEntry> =
    entries.sortedBy { it.section.ordinal }

/**
 * The empty-query display order: up to [MAX_RECENTS] most-recently-visited targets first (in
 * [recentRoutePaths] order, which is already most-recent-first — see [addRecent]), then every
 * remaining entry grouped by section ([groupBySection]). A route path that isn't in the current
 * index (e.g. a stale recents entry across an index change) is silently dropped rather than
 * blowing up.
 */
internal fun buildDisplayList(entries: List<CommandPaletteEntry>, recentRoutePaths: List<String>): List<CommandPaletteEntry> {
    val recentEntries = recentRoutePaths.mapNotNull { path -> entries.find { it.routePath == path } }
    val remaining = entries.filterNot { it.routePath in recentRoutePaths }
    return recentEntries + groupBySection(remaining)
}

/** The empty-query view shows at most this many recently-visited targets. */
internal const val MAX_RECENTS = 5

/**
 * Records a visit to [routePath] in [recents] (most-recent-first): moves [routePath] to the
 * front, drops any earlier occurrence (a route is never listed twice), and caps the result at
 * [maxRecents].
 */
internal fun addRecent(recents: List<String>, routePath: String, maxRecents: Int = MAX_RECENTS): List<String> =
    (listOf(routePath) + recents.filterNot { it == routePath }).take(maxRecents)

/**
 * MEN-5 — session-scoped memory of recently-visited command-palette targets, "UI/session scope
 * only" per the work package: no persistence, no backend, resets on a new session. Same
 * session-attribute idiom as
 * [de.littletrotter.ui.view.referencedata.ReferenceDataLastTabState]: a small serializable value
 * stashed on [com.vaadin.flow.server.VaadinSession].
 */
internal data class CommandPaletteRecents(private val routePaths: List<String> = emptyList()) : java.io.Serializable {
    fun asList(): List<String> = routePaths

    fun withVisit(routePath: String): CommandPaletteRecents = CommandPaletteRecents(addRecent(routePaths, routePath))
}
