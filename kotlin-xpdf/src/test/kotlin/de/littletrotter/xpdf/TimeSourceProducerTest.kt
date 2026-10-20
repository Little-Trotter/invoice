package de.littletrotter.xpdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class TimeSourceProducerTest {

    @Test
    fun `the produced time source reads the system clock in UTC`() {
        val before = Instant.now()

        val timeSource = TimeSourceProducer().timeSource()

        val reading = timeSource.instant()
        val after = Instant.now()
        assertFalse(reading.isBefore(before))
        assertFalse(reading.isAfter(after))
        assertEquals(ZoneOffset.UTC, timeSource.nowUtc().offset)
    }
}
