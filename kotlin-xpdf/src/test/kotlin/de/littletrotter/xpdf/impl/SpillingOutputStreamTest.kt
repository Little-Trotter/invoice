package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpillingOutputStreamTest {

    @TempDir
    lateinit var dir: Path

    private val payload = ByteArray(100) { it.toByte() }

    @Test
    fun `content within the threshold stays in memory`() {
        SpillingOutputStream(100, dir).use { out ->
            out.write(payload)

            assertFalse(out.spilled)
            assertArrayEquals(payload, out.toByteArray())
        }
        assertEquals(0, Files.list(dir).use { it.count() })
    }

    @Test
    fun `content beyond the threshold moves to a file and comes back whole`() {
        SpillingOutputStream(10, dir).use { out ->
            out.write(payload, 0, 5)
            out.write(payload[5].toInt())
            out.write(payload, 6, payload.size - 6)

            assertTrue(out.spilled)
            assertEquals(1, Files.list(dir).use { it.count() })
            assertArrayEquals(payload, out.toByteArray())
        }
    }

    @Test
    fun `close removes the spill file and invalidates the stream`() {
        val out = SpillingOutputStream(10, dir)
        out.write(payload)

        out.close()

        assertEquals(0, Files.list(dir).use { it.count() })
        assertThrows(IllegalStateException::class.java) { out.write(1) }
    }
}
