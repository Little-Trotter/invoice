package de.littletrotter.domain.shared

import de.littletrotter.domain.compliance.ValidationFinding
import de.littletrotter.domain.compliance.ValidationProfile
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.InvoicePartySnapshot
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.OffsetDateTime
import de.littletrotter.shared.time.ClockTimeSource

class CreationStampListenerTest {

    private val insertMoment = OffsetDateTime.parse("2026-12-31T23:00:30Z")
    private val listener = CreationStampListener(ClockTimeSource.fixedAt(insertMoment.toInstant()))

    @Test
    fun `a job whose creator set no creation moment is stamped with the insert moment, never 1970`() {
        val job = GenerationJob()

        listener.beforeInsert(job)

        assertEquals(insertMoment, job.createdAt)
    }

    @Test
    fun `a creation moment the creator set explicitly is kept`() {
        val explicit = OffsetDateTime.parse("2026-12-31T22:59:59Z")
        val event = DatabaseMaintenanceEvent(createdAt = explicit)

        listener.beforeInsert(event)

        assertEquals(explicit, event.createdAt)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createdAtEntities")
    fun `an entity whose createdAt nobody set is stamped with the insert moment`(entity: CreatedAtEntity) {
        val instance = entity.create()

        listener.beforeInsert(instance)

        assertEquals(insertMoment, entity.createdAt(instance))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createdAtEntities")
    fun `an entity whose createdAt was set keeps it`(entity: CreatedAtEntity) {
        val explicit = OffsetDateTime.parse("2026-01-02T03:04:05Z")
        val instance = entity.create()
        instance.stampCreationIfUnset(explicit)

        listener.beforeInsert(instance)

        assertEquals(explicit, entity.createdAt(instance))
    }

    @Test
    fun `entities without a creation moment are left alone`() {
        assertDoesNotThrow { listener.beforeInsert("not an entity") }
    }

    /** One entity whose `createdAt` used to be a Hibernate-generated stamp. */
    class CreatedAtEntity(
        private val name: String,
        val create: () -> StampedOnCreation,
        val createdAt: (StampedOnCreation) -> OffsetDateTime?,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private inline fun <reified T : StampedOnCreation> entity(
            noinline create: () -> T,
            crossinline createdAt: (T) -> OffsetDateTime?,
        ) = CreatedAtEntity(T::class.java.simpleName, create) { createdAt(it as T) }

        @JvmStatic
        fun createdAtEntities(): List<CreatedAtEntity> = listOf(
            entity(::ValidationProfile) { it.createdAt },
            entity(::ValidationFinding) { it.createdAt },
            entity(::PaymentTerm) { it.createdAt },
            entity(::PerDiemRate) { it.createdAt },
            entity(::MileageRate) { it.createdAt },
            entity(::HotelRate) { it.createdAt },
            entity(::TaxRate) { it.createdAt },
            entity(::NumberRange) { it.createdAt },
            entity(::InvoicePartySnapshot) { it.createdAt },
        )
    }
}
