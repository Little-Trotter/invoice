package de.littletrotter.domain.reference

import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * anbieter-einvoice-ergonomie §3/§4/§7 — the pure profile-visibility predicate
 * ([isB2bProfileVisibleForSeller]), the country-scoped seller field union's dedupe/grouping rule
 * ([dedupeB2bSellerFieldGroups]) and its companion visibility check
 * ([isB2bSellerFieldGroupVisible]). No Vaadin/JPA types anywhere in this file.
 */
class B2bProfileVisibilityTest {

    // ── isB2bProfileVisibleForSeller — unscoped, scoped-match, scoped-no-match ──────────────

    @Test
    fun `an unscoped profile (empty countries) is always visible`() {
        assertTrue(isB2bProfileVisibleForSeller(emptySet(), emptySet()))
        assertTrue(isB2bProfileVisibleForSeller(emptySet(), setOf(Country.DE)))
    }

    @Test
    fun `a scoped profile is visible when the seller matches`() {
        assertTrue(isB2bProfileVisibleForSeller(setOf(Country.FR, Country.LU), setOf(Country.FR)))
    }

    @Test
    fun `a scoped profile is hidden when the seller does not match`() {
        assertFalse(isB2bProfileVisibleForSeller(setOf(Country.FR, Country.LU), setOf(Country.DE)))
    }

    @Test
    fun `a scoped profile is hidden when the seller has no registrations at all`() {
        assertFalse(isB2bProfileVisibleForSeller(setOf(Country.FR), emptySet()))
    }

    // ── dedupeB2bSellerFieldGroups — the §4 ordering rule ────────────────────────────────────

    private fun field(term: B2bTerm) = B2bProfileField().edit { this.term = term; businessDefinition = term.officialCode }

    @Test
    fun `a field contributed only by unscoped profiles resolves to the unscoped group`() {
        val result = dedupeB2bSellerFieldGroups(listOf(field(B2bTerm.BT_19) to emptySet()))
        assertEquals(1, result.size)
        assertEquals(null, result.single().group)
        assertEquals(emptySet<Country>(), result.single().triggerCountries)
    }

    @Test
    fun `a field contributed only by one FR-scoped profile resolves to the FR group`() {
        val result = dedupeB2bSellerFieldGroups(listOf(field(B2bTerm.BT_84) to setOf(Country.FR)))
        assertEquals(Country.FR, result.single().group)
        assertEquals(setOf(Country.FR), result.single().triggerCountries)
    }

    @Test
    fun `duplicate bt_code across two differently-scoped profiles dedupes to one entry`() {
        val result = dedupeB2bSellerFieldGroups(
            listOf(field(B2bTerm.BT_84) to setOf(Country.FR), field(B2bTerm.BT_84) to setOf(Country.LU)),
        )
        assertEquals(1, result.size, "must dedupe by term, same as the pre-existing distinctBy union")
    }

    @Test
    fun `duplicate bt_code from FR- and LU-scoped profiles resolves to FR (alpha2-first) with both as trigger countries`() {
        val result = dedupeB2bSellerFieldGroups(
            listOf(field(B2bTerm.BT_84) to setOf(Country.LU), field(B2bTerm.BT_84) to setOf(Country.FR)),
        )
        val entry = result.single()
        assertEquals(Country.FR, entry.group, "FR sorts before LU alphabetically")
        assertEquals(setOf(Country.FR, Country.LU), entry.triggerCountries, "OR of every contributor's scope")
    }

    @Test
    fun `duplicate bt_code from an unscoped and a scoped profile always resolves to the unscoped group`() {
        val result = dedupeB2bSellerFieldGroups(
            listOf(field(B2bTerm.BT_84) to setOf(Country.FR), field(B2bTerm.BT_84) to emptySet()),
        )
        assertEquals(null, result.single().group, "unscoped sorts first in the group order, regardless of contribution order")
    }

    @Test
    fun `the kept field instance is the first one encountered, same stable-order convention as the pre-existing union`() {
        val fromA = field(B2bTerm.BT_84).edit { businessDefinition = "von A" }
        val fromB = field(B2bTerm.BT_84).edit { businessDefinition = "von B" }
        val result = dedupeB2bSellerFieldGroups(listOf(fromA to emptySet(), fromB to emptySet()))
        assertEquals("von A", result.single().field.businessDefinition)
    }

    @Test
    fun `distinct bt_codes each get their own entry`() {
        val result = dedupeB2bSellerFieldGroups(
            listOf(field(B2bTerm.BT_19) to emptySet(), field(B2bTerm.BT_84) to setOf(Country.FR)),
        )
        assertEquals(setOf(B2bTerm.BT_19, B2bTerm.BT_84), result.map { it.field.term }.toSet())
    }

    // ── isB2bSellerFieldGroupVisible ──────────────────────────────────────────────────────────

    @Test
    fun `the unscoped group (group = null) is always visible`() {
        assertTrue(isB2bSellerFieldGroupVisible(null, emptySet(), emptySet()))
        assertTrue(isB2bSellerFieldGroupVisible(null, setOf(Country.FR), emptySet()))
    }

    @Test
    fun `a country group is visible when the seller's countries intersect the trigger countries`() {
        assertTrue(isB2bSellerFieldGroupVisible(Country.FR, setOf(Country.FR), setOf(Country.FR, Country.DE)))
    }

    @Test
    fun `a country group is hidden when the seller has no matching registration`() {
        assertFalse(isB2bSellerFieldGroupVisible(Country.FR, setOf(Country.FR), setOf(Country.DE)))
    }

    @Test
    fun `a country group is visible via a trigger country other than its own group (the multi-scope edge case)`() {
        // FR/LU dedupe example above: group == FR but triggerCountries == {FR, LU}; a seller
        // registered only in LU must still see it.
        assertTrue(isB2bSellerFieldGroupVisible(Country.FR, setOf(Country.FR, Country.LU), setOf(Country.LU)))
    }
}
