package de.littletrotter.ui.view.referencedata

/** The `referenzdaten` route's base path, shared between [ReferenceDataView]'s
 *  `@Route` annotation and [referenceDataRoute] so the two can't drift apart. */
internal const val REFERENCE_DATA_ROUTE_BASE = "referenzdaten"

/**
 * The three-group structure of the reference-data view: the ten
 * existing tabs are unchanged, only how they're grouped and navigated changes. [slug] is the
 * German route segment used in `referenzdaten/:gruppe/:tab` deep links — lowercase, ASCII
 * (umlauts transliterated) so it's safe as a URL path segment. Display labels live in
 * [ReferenceDataView] (`groupLabel`) next to their i18n keys, not here — this type only carries
 * routing/grouping data so it stays trivially unit-testable without a translation bundle.
 */
enum class ReferenceDataGroup(val slug: String) {
    ABRECHNUNG("abrechnung"),
    REISEKOSTEN("reisekosten"),
    E_RECHNUNG("e-rechnung"),
    ;

    companion object {
        fun fromSlug(slug: String?): ReferenceDataGroup? = entries.find { it.slug == slug }
    }
}

/**
 * One reference-data tab: the [ReferenceDataGroup] it belongs to and its route [slug]. Display
 * label and header description live in [ReferenceDataView] (`tabLabel` / `tabDescription`) next
 * to their i18n keys, not here — see [ReferenceDataGroup]'s kdoc for why. Declaration order is
 * display order within the group.
 */
enum class ReferenceDataTab(val group: ReferenceDataGroup, val slug: String) {
    PAYMENT_TERMS(ReferenceDataGroup.ABRECHNUNG, "zahlungsziele"),
    TAX_RATES(ReferenceDataGroup.ABRECHNUNG, "steuersaetze"),
    UNITS(ReferenceDataGroup.ABRECHNUNG, "einheiten"),
    NUMBER_RANGES(ReferenceDataGroup.ABRECHNUNG, "nummernkreise"),

    PER_DIEM_RATES(ReferenceDataGroup.REISEKOSTEN, "verpflegungspauschalen"),
    HOTEL_RATES(ReferenceDataGroup.REISEKOSTEN, "uebernachtungspauschalen"),
    MILEAGE_RATES(ReferenceDataGroup.REISEKOSTEN, "kilometersaetze"),

    B2B_PROFILES(ReferenceDataGroup.E_RECHNUNG, "b2b-profile"),
    VALIDATION(ReferenceDataGroup.E_RECHNUNG, "validierung"),
    ;

    companion object {
        fun forGroup(group: ReferenceDataGroup): List<ReferenceDataTab> = entries.filter { it.group == group }
        fun fromSlug(group: ReferenceDataGroup, slug: String?): ReferenceDataTab? =
            forGroup(group).find { it.slug == slug }
    }
}

/**
 * Pure deep-link resolution for `referenzdaten/:gruppe?/:tab?` (bare route, group only, or
 * group+tab). An unknown or missing group falls back to the first group; an unknown tab falls back
 * to the first tab of the resolved group. This is also how the bare route behaves
 * ("alte Route leitet auf die erste Gruppe") — there's no separate bare-route special
 * case, it's just the fallback path with `groupSlug == null`.
 *
 * MEN-3 — a *missing* tab segment (`groupSlug` present, `tabSlug == null`, i.e. the drawer's
 * stateless `referenzdaten/<gruppe>` link) no longer always falls back to the group's first tab:
 * the memory of last-visited tabs is consulted first so returning to a group restores the sub-tab
 * last visited there. An explicit but *unknown* tab segment still falls back to the first tab — a
 * bad deep link shouldn't silently resurrect an unrelated previous session. The last-tab-for-group
 * memory defaults to "no memory" so every pre-existing call site (tests included) keeps today's
 * behavior unchanged.
 */
/**
 * SFS — the sub-tabs of a group a viewer may actually see: every tab of the group except the
 * hidden ones (an empty set is the pre-SFS "nothing is switched off" state — the default of
 * every new parameter here, so all existing call sites keep their behaviour). The `ifEmpty`
 * fallback keeps a group whose tabs are ALL hidden from throwing: hiding is per feature, and a
 * future feature that would empty a group entirely should show the group's first tab rather
 * than crash the view.
 */
internal fun visibleTabsFor(group: ReferenceDataGroup, hiddenTabs: Set<ReferenceDataTab> = emptySet()): List<ReferenceDataTab> {
    val visible = ReferenceDataTab.forGroup(group).filterNot { it in hiddenTabs }
    return visible.ifEmpty { ReferenceDataTab.forGroup(group) }
}

/**
 * SFS — hidden tabs extend the original resolution rules with one principle: a hidden tab is
 * addressed exactly like an unknown one — an explicit deep link to `verpflegungspauschalen` while
 * the feature is switched off resolves to the group's first VISIBLE tab (a hidden tab is a
 * deliberate absence, not a bad link, but must not resurrect a session either), and the
 * last-tab-for-group memory may only restore a tab that is visible.
 */
/**
 * The one mapping from feature state to "which reference-data tabs are hidden", shared by the
 * Settings-aware view and the command-palette index so neither of them can flip the rule
 * independently. New feature-bound tabs extend this list, not a copy elsewhere.
 *
 * One axis today: the per-diem UI (a user-toggled flag) hides the per-diem rates.
 */
internal fun hiddenReferenceDataTabs(perDiemUiEnabled: Boolean): Set<ReferenceDataTab> =
    buildSet {
        if (!perDiemUiEnabled) add(ReferenceDataTab.PER_DIEM_RATES)
    }

internal fun resolveReferenceDataDeepLink(
    groupSlug: String?,
    tabSlug: String?,
    hiddenTabs: Set<ReferenceDataTab> = emptySet(),
    lastTabForGroup: (ReferenceDataGroup) -> ReferenceDataTab? = { null },
): Pair<ReferenceDataGroup, ReferenceDataTab> {
    val group = ReferenceDataGroup.fromSlug(groupSlug) ?: ReferenceDataGroup.entries.first()
    val visible = visibleTabsFor(group, hiddenTabs)
    val tab = if (tabSlug == null) {
        lastTabForGroup(group)?.takeIf { it in visible } ?: visible.first()
    } else {
        ReferenceDataTab.fromSlug(group, tabSlug)?.takeIf { it in visible } ?: visible.first()
    }
    return group to tab
}

/** The `referenzdaten/:gruppe/:tab` path for a given selection; used to update the
 *  browser URL (history replace) without triggering a full navigation/reload. */
internal fun referenceDataRoute(group: ReferenceDataGroup, tab: ReferenceDataTab): String =
    "$REFERENCE_DATA_ROUTE_BASE/${group.slug}/${tab.slug}"

/**
 * MEN-3 — session-held memory of the last sub-tab visited within each [ReferenceDataGroup].
 * Vaadin instantiates a fresh [ReferenceDataView] on every navigation under the `referenzdaten`
 * route tree (routed views aren't preserved across navigation, only across a browser refresh), so this
 * can't live as a plain instance field the way `lastTabForGroup` did before MEN-3 removed the
 * group tab bar — same session-attribute idiom as
 * [de.littletrotter.ui.view.invoice.PrintJobListFilterState]: a small typed, serializable value
 * stashed on [com.vaadin.flow.server.VaadinSession] and read back in `beforeEnter`.
 *
 * Keyed by slug rather than by the enums themselves so the stored value stays a plain
 * serializable data class independent of enum identity across redeploys.
 */
internal data class ReferenceDataLastTabState(
    private val tabSlugByGroupSlug: Map<String, String> = emptyMap(),
) : java.io.Serializable {

    fun tabFor(group: ReferenceDataGroup): ReferenceDataTab? =
        tabSlugByGroupSlug[group.slug]?.let { slug -> ReferenceDataTab.fromSlug(group, slug) }

    fun with(group: ReferenceDataGroup, tab: ReferenceDataTab): ReferenceDataLastTabState =
        copy(tabSlugByGroupSlug = tabSlugByGroupSlug + (group.slug to tab.slug))
}
