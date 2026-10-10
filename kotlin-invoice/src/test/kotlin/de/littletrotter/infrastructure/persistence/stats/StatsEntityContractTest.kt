package de.littletrotter.infrastructure.persistence.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * -Folge — contract pinning for the read-only statistics view mappings. Two things no
 * query-level test can cover: (1) every entity must be no-arg constructible with sane defaults
 * (the JPA instantiation contract — the aggregate queries only ever read scalar projections, so
 * hydration never runs these initializers in production); (2) the seven `@IdClass` types carry
 * generated `equals`/`hashCode` that Hibernate's identity map relies on — equal keys must be
 * equal, and every single field must participate in the comparison.
 */
class StatsEntityContractTest {

    /** Every mapped attribute must be a working read/write property (the JPA mapping contract);
     *  production never calls the accessors — the aggregate queries read scalar projections and
     *  Hibernate/Criteria work on fields and metamodel paths — so this roundtrip is the one
     *  place the accessor pairs are exercised at all. */
    @Test
    fun `every stats entity is no-arg constructible and all mapped properties roundtrip`() {
        val samples = mapOf<kotlin.reflect.KClass<*>, Any>(
            Int::class to 7, Long::class to 7L, Boolean::class to true,
            String::class to "probe", BigDecimal::class to BigDecimal("42.42"),
            java.time.LocalDate::class to java.time.LocalDate.of(2026, 8, 29),
        )
        val entities = listOf(
            StatYearly(), StatQuarterly(), StatMonthly(), StatDaily(),
            StatItemYearly(), StatItemQuarterly(), StatItemMonthly(),
            StatInvoiceBase(), StatItemBase(), StatAvailableYear(),
        )
        for (entity in entities) {
            for (prop in entity::class.members.filterIsInstance<kotlin.reflect.KMutableProperty1<Any, Any?>>()) {
                val sample = samples[prop.returnType.classifier as? kotlin.reflect.KClass<*>]
                    ?: error("no sample for ${'$'}{prop.returnType} on ${'$'}{entity::class.simpleName}.${'$'}{prop.name}")
                prop.set(entity, sample)
                assertEquals(sample, prop.get(entity), "${'$'}{entity::class.simpleName}.${'$'}{prop.name} must roundtrip")
            }
        }
    }

    private fun <T> assertKeyContract(base: T, same: T, variants: List<T>) {
        assertEquals(base, same)
        assertEquals(base.hashCode(), same.hashCode())
        variants.forEachIndexed { i, v ->
            assertNotEquals(base, v, "field #$i must participate in equals")
        }
    }

    @Test
    fun `StatYearlyId equality covers every field`() {
        val a = StatYearlyId(2026, 1L, "S", 2L, "R", 3, true, 1)
        assertKeyContract(
            a, StatYearlyId(2026, 1L, "S", 2L, "R", 3, true, 1),
            listOf(
                a.copy(year = 2025), a.copy(senderId = 9L), a.copy(senderName = "X"),
                a.copy(recipientId = 9L), a.copy(recipientName = "X"), a.copy(status = 9),
                a.copy(reverseCharge = false), a.copy(currency = 2),
            ),
        )
        assertEquals(StatYearlyId(), StatYearlyId())
    }

    @Test
    fun `StatQuarterlyId equality covers every field`() {
        val a = StatQuarterlyId(2026, 2, 1L, "S", 2L, "R", 3, true, 1)
        assertKeyContract(
            a, StatQuarterlyId(2026, 2, 1L, "S", 2L, "R", 3, true, 1),
            listOf(
                a.copy(year = 2025), a.copy(quarter = 4), a.copy(senderId = 9L),
                a.copy(senderName = "X"), a.copy(recipientId = 9L), a.copy(recipientName = "X"),
                a.copy(status = 9), a.copy(reverseCharge = false), a.copy(currency = 2),
            ),
        )
        assertEquals(StatQuarterlyId(), StatQuarterlyId())
    }

    @Test
    fun `StatMonthlyId equality covers every field`() {
        val a = StatMonthlyId(2026, 8, 1L, "S", 2L, "R", 3, true, 1)
        assertKeyContract(
            a, StatMonthlyId(2026, 8, 1L, "S", 2L, "R", 3, true, 1),
            listOf(
                a.copy(year = 2025), a.copy(month = 1), a.copy(senderId = 9L),
                a.copy(senderName = "X"), a.copy(recipientId = 9L), a.copy(recipientName = "X"),
                a.copy(status = 9), a.copy(reverseCharge = false), a.copy(currency = 2),
            ),
        )
        assertEquals(StatMonthlyId(), StatMonthlyId())
    }

    @Test
    fun `StatDailyId equality covers every field`() {
        val a = StatDailyId(2026, 8, 29, 1L, "S", 2L, "R", 3, true, 1)
        assertKeyContract(
            a, StatDailyId(2026, 8, 29, 1L, "S", 2L, "R", 3, true, 1),
            listOf(
                a.copy(year = 2025), a.copy(month = 1), a.copy(day = 1), a.copy(senderId = 9L),
                a.copy(senderName = "X"), a.copy(recipientId = 9L), a.copy(recipientName = "X"),
                a.copy(status = 9), a.copy(reverseCharge = false), a.copy(currency = 2),
            ),
        )
        assertEquals(StatDailyId(), StatDailyId())
    }

    @Test
    fun `StatItemYearlyId equality covers every field`() {
        val a = StatItemYearlyId("Beratung", 2026, 1L, "S", 2L, "R", 3, true, 1)
        assertKeyContract(
            a, StatItemYearlyId("Beratung", 2026, 1L, "S", 2L, "R", 3, true, 1),
            listOf(
                a.copy(description = "X"), a.copy(year = 2025), a.copy(senderId = 9L),
                a.copy(senderName = "X"), a.copy(recipientId = 9L), a.copy(recipientName = "X"),
                a.copy(status = 9), a.copy(reverseCharge = false), a.copy(currency = 2),
            ),
        )
        assertEquals(StatItemYearlyId(), StatItemYearlyId())
    }

    @Test
    fun `StatItemQuarterlyId equality covers every field`() {
        val a = StatItemQuarterlyId("Beratung", 2026, 2, 1L, "S", 2L, "R", 3, true, 1)
        assertKeyContract(
            a, StatItemQuarterlyId("Beratung", 2026, 2, 1L, "S", 2L, "R", 3, true, 1),
            listOf(
                a.copy(description = "X"), a.copy(year = 2025), a.copy(quarter = 4),
                a.copy(senderId = 9L), a.copy(senderName = "X"), a.copy(recipientId = 9L),
                a.copy(recipientName = "X"), a.copy(status = 9), a.copy(reverseCharge = false),
                a.copy(currency = 2),
            ),
        )
        assertEquals(StatItemQuarterlyId(), StatItemQuarterlyId())
    }

    @Test
    fun `StatItemMonthlyId equality covers every field`() {
        val a = StatItemMonthlyId("Beratung", 2026, 8, 1L, "S", 2L, "R", 3, true, 1)
        assertKeyContract(
            a, StatItemMonthlyId("Beratung", 2026, 8, 1L, "S", 2L, "R", 3, true, 1),
            listOf(
                a.copy(description = "X"), a.copy(year = 2025), a.copy(month = 1),
                a.copy(senderId = 9L), a.copy(senderName = "X"), a.copy(recipientId = 9L),
                a.copy(recipientName = "X"), a.copy(status = 9), a.copy(reverseCharge = false),
                a.copy(currency = 2),
            ),
        )
        assertEquals(StatItemMonthlyId(), StatItemMonthlyId())
    }
}
