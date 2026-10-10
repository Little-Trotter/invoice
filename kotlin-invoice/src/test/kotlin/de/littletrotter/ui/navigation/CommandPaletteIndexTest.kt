package de.littletrotter.ui.navigation

import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import de.littletrotter.ui.view.referencedata.ReferenceDataTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MEN-5 — unit tests for the command palette's pure logic (`CommandPaletteIndex.kt`): index
 * building, diacritic-tolerant filtering, and recents ordering. No Vaadin/Karibu scaffolding —
 * [CommandPaletteDialogKaribuTest] covers the actual dialog wiring.
 */
class CommandPaletteIndexTest {

    // ── Index building ────────────────────────────────────────────────────────

    @Test
    fun `index has one entry per NavItem leaf except REFERENCE_DATA, plus one per reference-data tab`() {
        val expectedTopLevel = NavItem.entries.size - 1 // every NavItem except REFERENCE_DATA itself
        val expectedTabs = ReferenceDataTab.entries.size
        val index = buildCommandPaletteIndex()

        assertEquals(expectedTopLevel + expectedTabs, index.size)
        // NavItem.MAIL_JOURNAL and NavItem.MAIL_TEMPLATES are part of the unfiltered index. The palette
        // *filters* them out again whenever the mail component is not installed — see MainLayoutMailEntryKaribuTest.
        // SFS added NavItem.SETTINGS (the only new always-top-level entry; its drawer child role
        // does not take it out of the palette, and the settings view is never conditionally hidden).
        // The offer receipt pool and statistics entries are the second and third QUOTING leaves.
        assertEquals(24, index.size, "pin the actual count so an index change is a deliberate edit here too")
    }

    @Test
    fun `index has no entry for REFERENCE_DATA itself`() {
        val index = buildCommandPaletteIndex()
        assertTrue(index.none { it.labelKey == LabelKey.NavItemKey(NavItem.REFERENCE_DATA) })
    }

    @Test
    fun `every non-reference-data NavItem is present with its section as context`() {
        val index = buildCommandPaletteIndex()
        NavItem.entries.filter { it != NavItem.REFERENCE_DATA }.forEach { item ->
            val entry = index.firstOrNull { it.labelKey == LabelKey.NavItemKey(item) }
            assertTrue(entry != null, "missing index entry for $item")
            assertEquals(item.routePath, entry!!.routePath)
            assertEquals(item.section, entry.section)
            assertEquals(LabelKey.NavSectionKey(item.section), entry.contextKey)
        }
    }

    @Test
    fun `every reference-data tab is present with its group context and deep-link route`() {
        val index = buildCommandPaletteIndex()
        ReferenceDataTab.entries.forEach { tab ->
            val entry = index.firstOrNull { it.labelKey == LabelKey.RefDataTabKey(tab) }
            assertTrue(entry != null, "missing index entry for $tab")
            assertEquals(NavItem.REFERENCE_DATA.section, entry!!.section)
            assertEquals(LabelKey.RefDataTabContextKey(tab.group), entry.contextKey)
            assertEquals("referenzdaten/${tab.group.slug}/${tab.slug}", entry.routePath)
        }
    }

    // ── TS-8/MEN-5 — itemFilter permission gate ─────────────────────────────

    @Test
    fun `buildCommandPaletteIndex with the default filter includes everything`() {
        val index = buildCommandPaletteIndex()
        assertEquals(NavItem.entries.size - 1 + ReferenceDataTab.entries.size, index.size)
    }

    @Test
    fun `every entry's navItem is itself for a top-level entry, REFERENCE_DATA for every reference-data tab entry`() {
        val index = buildCommandPaletteIndex()
        index.forEach { entry ->
            when (entry.labelKey) {
                is LabelKey.RefDataTabKey -> assertEquals(NavItem.REFERENCE_DATA, entry.navItem)
                is LabelKey.NavItemKey -> assertEquals((entry.labelKey as LabelKey.NavItemKey).item, entry.navItem)
                else -> throw AssertionError("unexpected labelKey ${entry.labelKey} in the index")
            }
        }
    }

    @Test
    fun `filtering out REFERENCE_DATA drops the ten reference-data tab entries too`() {
        val index = buildCommandPaletteIndex(itemFilter = { item -> item != NavItem.REFERENCE_DATA })

        assertEquals(NavItem.entries.size - 1, index.size)
        assertTrue(index.none { it.navItem == NavItem.REFERENCE_DATA })
        assertTrue(index.none { it.labelKey is LabelKey.RefDataTabKey })
    }

    @Test
    fun `filtering to a single NavItem leaves only that item's entry (or entries)`() {
        val index = buildCommandPaletteIndex(itemFilter = { item -> item == NavItem.ARTICLES })

        assertEquals(listOf(LabelKey.NavItemKey(NavItem.ARTICLES)), index.map { it.labelKey })
    }

    @Test
    fun `an itemFilter that rejects everything yields an empty index`() {
        val index = buildCommandPaletteIndex(itemFilter = { false })
        assertTrue(index.isEmpty())
    }

    @Test
    fun `a stale recent that itemFilter now excludes is silently dropped, same as an unknown route`() {
        val filteredIndex = buildCommandPaletteIndex(itemFilter = { item -> item != NavItem.REFERENCE_DATA })
        val recentRoutePaths = listOf("referenzdaten/reisekosten/kilometersaetze", NavItem.ARTICLES.routePath)

        val display = buildDisplayList(filteredIndex, recentRoutePaths)

        assertTrue(display.none { it.routePath == "referenzdaten/reisekosten/kilometersaetze" })
        assertEquals(LabelKey.NavItemKey(NavItem.ARTICLES), display.first().labelKey)
        assertEquals(filteredIndex.size, display.size)
    }

    // ── Diacritic folding ────────────────────────────────────────────────────

    @Test
    fun `folding strips umlauts and lowercases`() {
        assertEquals("kilometersatze", foldForSearch("Kilometersätze"))
        assertEquals(foldForSearch("kilometersatze"), foldForSearch("Kilometersätze"))
        assertEquals("strasse", foldForSearch("Strasse"))
    }

    // ── Filtering ────────────────────────────────────────────────────────────

    @Test
    fun `filtering by label substring finds the expected entries`() {
        val index = buildCommandPaletteIndex()

        val kiloMatches = filterCommandPaletteEntries(index, "kilo")
        assertTrue(kiloMatches.any { it.labelKey == LabelKey.RefDataTabKey(ReferenceDataTab.MILEAGE_RATES) })

        // "nav.articles" was `{term:product.plural}`, resolved against the live WordingSettingsGate;
        // commit c6c11f0e4 changed it to the literal "Leistungsverzeichnis". This plain unit test
        // runs with no CDI container, so resolveLabel's fallback (WordingProfile.DEFAULT) applies.
        // The test ensures substring matching still works against the current label.
        val articlesMatches = filterCommandPaletteEntries(index, "verzeichnis")
        assertTrue(articlesMatches.any { it.labelKey == LabelKey.NavItemKey(NavItem.ARTICLES) })
    }

    @Test
    fun `filtering is diacritic-tolerant using a real umlaut label`() {
        val index = buildCommandPaletteIndex()

        // "Kilometersätze" (real German label, MILEAGE_RATES) must be found by the un-umlauted query.
        val matches = filterCommandPaletteEntries(index, "kilometersatze")

        assertTrue(matches.any { it.labelKey == LabelKey.RefDataTabKey(ReferenceDataTab.MILEAGE_RATES) })
    }

    @Test
    fun `filtering also matches on the context line`() {
        val index = buildCommandPaletteIndex()

        // "Abrechnung" is a group name, only present in reference-data tab entries' context, not
        // in any top-level label.
        val matches = filterCommandPaletteEntries(index, "abrechnung")

        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { it.contextKey != null })
    }

    @Test
    fun `filtering is case-insensitive`() {
        val index = buildCommandPaletteIndex()
        val lower = filterCommandPaletteEntries(index, "kilo")
        val upper = filterCommandPaletteEntries(index, "KILO")
        assertEquals(lower, upper)
    }

    @Test
    fun `a query matching nothing returns an empty list`() {
        val index = buildCommandPaletteIndex()
        assertTrue(filterCommandPaletteEntries(index, "xyzzy-no-such-entry").isEmpty())
    }

    @Test
    fun `a blank query matches every entry`() {
        val index = buildCommandPaletteIndex()
        assertEquals(index.size, filterCommandPaletteEntries(index, "   ").size)
    }

    // ── Section grouping ─────────────────────────────────────────────────────

    @Test
    fun `grouping by section preserves each section's internal order and groups administration together`() {
        val index = buildCommandPaletteIndex()
        val grouped = groupBySection(index)

        // Non-decreasing section ordinal across the whole list.
        for (i in 1 until grouped.size) {
            assertTrue(grouped[i - 1].section.ordinal <= grouped[i].section.ordinal)
        }
        // SYSTEM and SETTINGS (plain NavItems; SETTINGS is a drawer child but a palette leaf —
        // see SettingsNavigationTest) plus the ten reference-data tabs are all ADMINISTRATION —
        // grouping must not scatter them.
        val administrationEntries = grouped.filter { it.section == NavSection.ADMINISTRATION }
        assertEquals(2 + ReferenceDataTab.entries.size, administrationEntries.size)
    }

    @Test
    fun `grouping follows the drawer section order`() {
        val sections = groupBySection(buildCommandPaletteIndex()).map { it.section }.distinct()
        assertEquals(NavSection.entries.toList(), sections)
    }

    @Test
    fun `searching the section name finds the quoting entries through their context`() {
        val index = buildCommandPaletteIndex()
        val items = filterCommandPaletteEntries(index, "angebot").map { it.navItem }
        assertTrue(NavItem.QUOTES in items)
        assertTrue(NavItem.itemsFor(NavSection.QUOTING).all { it in items })
    }

    @Test
    fun `searching the section name finds the mail entries through their context`() {
        val index = buildCommandPaletteIndex()
        val items = filterCommandPaletteEntries(index, "e-mail").map { it.navItem }
        assertTrue(items.containsAll(listOf(NavItem.MAIL_JOURNAL, NavItem.MAIL_TEMPLATES)))
    }

    @Test
    fun `navigation entries carry their section as context`() {
        buildCommandPaletteIndex()
            .filter { it.navItem != NavItem.REFERENCE_DATA }
            .forEach { assertEquals(LabelKey.NavSectionKey(it.navItem.section), it.contextKey, it.navItem.name) }
    }

    // ── Recents ordering ─────────────────────────────────────────────────────

    @Test
    fun `addRecent puts the newly visited route first`() {
        val recents = addRecent(emptyList(), "receipt-pool")
        assertEquals(listOf("receipt-pool"), recents)
    }

    @Test
    fun `addRecent moves a revisited route to the front instead of duplicating it`() {
        val recents = addRecent(listOf("a", "b", "c"), "b")
        assertEquals(listOf("b", "a", "c"), recents)
    }

    @Test
    fun `addRecent caps at maxRecents, dropping the oldest`() {
        val recents = addRecent(listOf("a", "b", "c", "d", "e"), "f", maxRecents = 5)
        assertEquals(listOf("f", "a", "b", "c", "d"), recents)
        assertEquals(5, recents.size)
    }

    @Test
    fun `CommandPaletteRecents withVisit chains most-recent-first without duplicates`() {
        val recents = CommandPaletteRecents()
            .withVisit("a")
            .withVisit("b")
            .withVisit("c")
            .withVisit("b")
            .withVisit("d")

        assertEquals(listOf("d", "b", "c", "a"), recents.asList())
    }

    @Test
    fun `buildDisplayList shows recents first, then the remaining entries grouped by section`() {
        val index = buildCommandPaletteIndex()
        val recentRoutePaths = listOf(NavItem.ARTICLES.routePath, "referenzdaten/reisekosten/kilometersaetze")

        val display = buildDisplayList(index, recentRoutePaths)

        assertEquals(LabelKey.NavItemKey(NavItem.ARTICLES), display[0].labelKey)
        assertEquals(LabelKey.RefDataTabKey(ReferenceDataTab.MILEAGE_RATES), display[1].labelKey)
        // Recent entries appear exactly once, not again in the grouped remainder.
        assertEquals(1, display.count { it.labelKey == LabelKey.NavItemKey(NavItem.ARTICLES) })
        assertEquals(index.size, display.size)
    }

    @Test
    fun `buildDisplayList silently drops a stale recent route no longer in the index`() {
        val index = buildCommandPaletteIndex()
        val display = buildDisplayList(index, listOf("no-longer-a-route"))

        assertEquals(index.size, display.size)
        assertFalse(display.any { it.routePath == "no-longer-a-route" })
    }

    @Test
    fun `buildDisplayList with no recents is just the section-grouped index`() {
        val index = buildCommandPaletteIndex()
        assertEquals(groupBySection(index), buildDisplayList(index, emptyList()))
    }

    // ── Label resolution reuse (MEN-4 extraction) ───────────────────────────

    @Test
    fun `RefDataTabContextKey resolves to Referenzdaten dot Gruppe`() {
        val text = resolveLabel(LabelKey.RefDataTabContextKey(ReferenceDataGroup.ABRECHNUNG))
        assertTrue(text.contains(resolveLabel(LabelKey.ReferenceDataRoot)))
        assertTrue(text.contains(resolveLabel(LabelKey.RefDataGroupKey(ReferenceDataGroup.ABRECHNUNG))))
    }
}
