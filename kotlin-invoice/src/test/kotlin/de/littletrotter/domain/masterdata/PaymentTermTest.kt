package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextEntry
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.reference.LocalizedTextPurpose
import de.littletrotter.domain.value.DiscountPercent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PaymentTermTest {

    private fun german() = Language().apply { id = 1; code = "de"; isFallback = true }
    private fun english() = Language().apply { id = 2; code = "en"; isFallback = false }

    /** a [PaymentTerm] with only a German (fallback) name, the shape most of this file's
     *  cases need; [PaymentTerm.name] no longer exists (it is now [PaymentTerm.nameGroup]). */
    private fun named(name: String): PaymentTerm = PaymentTerm().apply { setName(german(), name) }

    @Test
    fun `validate accepts good term`() {
        val t = named("Net 30").apply { dueInDays = 30; discountPercent = DiscountPercent(BigDecimal.ZERO) }
        t.validate() // no exception
    }

    @Test
    fun `validate rejects blank name`() {
        val t = named("")
        assertThrows(InvalidParameterException::class.java) { t.validate() }
    }

    @Test
    fun `validate rejects a term with no name group at all`() {
        val t = PaymentTerm()
        assertThrows(InvalidParameterException::class.java) { t.validate() }
    }

    @Test
    fun `validate rejects negative due days`() {
        val t = named("X").apply { dueInDays = -1 }
        assertThrows(InvalidParameterException::class.java) { t.validate() }
    }

    @Test
    fun `validate accepts zero due days, meaning immediate payment`() {
        val t = named("Sofort").apply { dueInDays = 0; discountPercent = DiscountPercent(BigDecimal.ZERO) }
        t.validate() // must not throw
    }

    @Test
    fun `validate rejects negative discount days`() {
        val t = named("X").apply { dueInDays = 30; discountDays = -1; discountPercent = DiscountPercent(BigDecimal.ZERO) }
        assertThrows(InvalidParameterException::class.java) { t.validate() }
    }

    @Test
    fun `validate accepts zero discount days`() {
        val t = named("X").apply { dueInDays = 30; discountDays = 0; discountPercent = DiscountPercent(BigDecimal("2.00")) }
        t.validate() // must not throw
    }

    // the 0..100 range is now DiscountPercent's own constructor invariant: it throws
    // immediately when the value is built, before it ever reaches PaymentTerm.validate().
    @Test
    fun `discount percent below zero is rejected at construction`() {
        assertThrows(InvalidParameterException::class.java) { DiscountPercent(BigDecimal("-0.01")) }
    }

    @Test
    fun `discount percent above 100 is rejected at construction`() {
        assertThrows(InvalidParameterException::class.java) { DiscountPercent(BigDecimal("100.01")) }
    }

    @Test
    fun `validate accepts boundary discount percent 0 and 100`() {
        named("A").apply { dueInDays = 30; discountPercent = DiscountPercent(BigDecimal.ZERO) }.validate()
        named("B").apply { dueInDays = 30; discountPercent = DiscountPercent(BigDecimal("100")); discountDays = 7 }.validate()
    }

    // ── Skonto is only meaningful as a pair (percent + days) ───────────

    @Test
    fun `validate rejects a discount percent without discount days`() {
        val t = named("X").apply { dueInDays = 30; discountPercent = DiscountPercent(BigDecimal("2.00")) }
        assertThrows(InvalidParameterException::class.java) { t.validate() }
    }

    @Test
    fun `validate rejects discount days without a discount percent`() {
        val t = named("X").apply { dueInDays = 30; discountDays = 7; discountPercent = DiscountPercent(BigDecimal.ZERO) }
        assertThrows(InvalidParameterException::class.java) { t.validate() }
    }

    @Test
    fun `validate accepts a fully paired discount`() {
        val t = named("X").apply { dueInDays = 30; discountPercent = DiscountPercent(BigDecimal("2.00")); discountDays = 7 }
        t.validate() // must not throw
    }

    // ── "Sofort fällig" is modeled as 0 days, not 14 ────────────────────

    @Test
    fun `Sofort faellig is zero days, not fourteen`() {
        val t = named("Zahlbar sofort").apply { dueInDays = 0 }
        assertEquals(0, t.dueInDays)
    }

    // ── nameFor/setName (formerly name/englishName) / isActive ────────────

    @Test
    fun `nameFor returns null when no name group exists yet`() {
        val t = PaymentTerm()
        assertNull(t.nameFor("de"))
        assertNull(t.rawNameFor("de"))
    }

    @Test
    fun `setName then nameFor round-trips per language`() {
        val t = PaymentTerm()
        t.setName(german(), "Netto 30")
        t.setName(english(), "Net 30 days")
        assertEquals("Netto 30", t.nameFor("de"))
        assertEquals("Net 30 days", t.nameFor("en"))
    }

    @Test
    fun `nameFor falls back to the fallback language when the requested one has no entry`() {
        val t = named("Netto 30")
        assertEquals("Netto 30", t.nameFor("en"), "no English entry captured — the German fallback wins")
    }

    @Test
    fun `rawNameFor never falls back, unlike nameFor`() {
        val t = named("Netto 30")
        assertNull(t.rawNameFor("en"), "no English entry was ever captured for this term")
    }

    @Test
    fun `boundary - createdAt is settable and defaults to null on a new instance`() {
        val t = PaymentTerm()
        assertNull(t.createdAt)

        val stamp = java.time.OffsetDateTime.parse("2026-03-14T10:00:00Z")
        t.createdAt = stamp

        assertEquals(stamp, t.createdAt)
    }

    @Test
    fun `isActive defaults to true on a new instance`() {
        assertTrue(PaymentTerm().isActive)
    }

    @Test
    fun `a deactivated term without an English name still validates`() {
        // Legacy V901 rows deactivated by V906 have no English name; validate() must not force
        // one on them (the same defense-in-depth pitfall R1 avoided for TaxRate.isSelectable).
        val t = named("Sofort fällig (14 Tage netto)").apply { dueInDays = 14; isActive = false }
        t.validate() // must not throw
    }

    @Test
    fun `isDefault defaults to false on new instance`() {
        assertFalse(PaymentTerm().isDefault)
    }

    @Test
    fun `isDefault can be set to true`() {
        val t = named("Standard").apply { dueInDays = 30; isDefault = true }
        assertTrue(t.isDefault)
        t.validate() // isDefault is not a validation concern
    }

    // ── setName() — branch coverage for localized text management ─────────────

    @Test
    fun `setName with whitespace-only value removes entry like null`() {
        val t = named("Netto 30")

        t.setName(german(), "   ")

        assertNull(t.rawNameFor("de"), "whitespace should be trimmed and treated as removal")
    }

    @Test
    fun `setName with empty string removes entry`() {
        val t = named("Netto 30")

        t.setName(german(), "")

        assertNull(t.rawNameFor("de"), "empty string should be treated as removal")
    }

    @Test
    fun `setName updates existing entry in place`() {
        val t = named("Old Name")
        val groupBefore = t.nameGroup

        t.setName(german(), "New Name")

        assertSame(groupBefore, t.nameGroup, "should reuse same group")
        assertEquals("New Name", t.rawNameFor("de"))
    }

    @Test
    fun `setName with null value removes entry`() {
        val t = named("Initial Name")

        t.setName(german(), null)

        assertNull(t.rawNameFor("de"))
    }

    @Test
    fun `setName preserves other language entries when removing one`() {
        val t = PaymentTerm()
        t.setName(german(), "Deutsch")
        t.setName(english(), "English")

        t.setName(german(), null)

        assertNotNull(t.nameGroup, "group should still exist because English entry remains")
        assertNull(t.rawNameFor("de"))
        assertEquals("English", t.rawNameFor("en"))
    }

    @Test
    fun `rawNameFor with no matching language returns null`() {
        val t = named("Only German")

        assertNull(t.rawNameFor("en"), "English entry was never set")
    }

    @Test
    fun `rawNameFor returns the exact value without fallback`() {
        val t = PaymentTerm()
        t.setName(german(), "Netto 30")
        t.setName(english(), "Net 30")

        assertEquals("Net 30", t.rawNameFor("en"))
        assertEquals("Netto 30", t.rawNameFor("de"))
    }

    // ── equals() — branch coverage for identity comparison ────────────────────

    @Test
    fun `two payment terms with both ids null are not equal`() {
        val a = named("Term A")
        val b = named("Term B")

        assertNotEquals(a, b, "two transient terms must not be equal")
    }

    @Test
    fun `two payment terms with same non-null id are equal`() {
        val a = named("Term A").apply { id = 42L }
        val b = PaymentTerm().apply { id = 42L; setName(german(), "Term B") }

        assertEquals(a, b, "same id means same entity")
    }

    @Test
    fun `a payment term with id is not equal to same term without id`() {
        val persisted = named("Term").apply { id = 1L }
        val transient = named("Term")

        assertNotEquals(persisted, transient)
    }

    @Test
    fun `validate rejects a fallback entry that carries a blank value directly (bypassing setName)`() {
        // every other blank-name fixture goes through setName(), which removes a blank
        // entry outright (nameGroup ends up with no fallback entry at all, entries.any{} = false
        // on an empty list). Constructing the entry directly proves the `it.value.isNotBlank()`
        // conjunct itself is enforced, not merely the emptier "no fallback entry exists" case.
        val t = PaymentTerm().apply {
            nameGroup = LocalizedTextGroup().apply {
                purpose = LocalizedTextPurpose.PAYMENT_TERM_NAME
                entries.add(LocalizedTextEntry().apply { language = german(); value = "   " })
            }
        }

        assertThrows(InvalidParameterException::class.java) { t.validate() }
    }

    @Test
    fun `hashCode is constant and does not derive from id`() {
        val withId = named("Term").apply { id = 100L }
        val withDifferentId = named("Term").apply { id = 999L }

        assertEquals(withId.hashCode(), withDifferentId.hashCode(), "hashCode must not change with id")
    }
}
