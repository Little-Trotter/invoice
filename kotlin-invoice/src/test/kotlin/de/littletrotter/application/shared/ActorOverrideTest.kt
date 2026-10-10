package de.littletrotter.application.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ActorOverrideTest {

    private val override = ActorOverride()

    @Test
    fun `no override is active by default`() {
        assertNull(override.current())
    }

    @Test
    fun `the actor is visible inside the block only and the block result is returned`() {
        val result = override.runAs("clerk") { override.current() }

        assertEquals("clerk", result)
        assertNull(override.current())
    }

    @Test
    fun `nested blocks restore the outer actor`() {
        override.runAs("outer") {
            override.runAs("inner") { assertEquals("inner", override.current()) }
            assertEquals("outer", override.current())
        }
    }

    @Test
    fun `a failing block still clears the override`() {
        assertThrows(IllegalStateException::class.java) {
            override.runAs("clerk") { throw IllegalStateException("boom") }
        }

        assertNull(override.current())
    }

    @Test
    fun `the override does not leak to another thread`() {
        var seen: String? = "unset"
        override.runAs("clerk") {
            val t = Thread { seen = override.current() }
            t.start()
            t.join()
        }

        assertNull(seen)
    }
}
