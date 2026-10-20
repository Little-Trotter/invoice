package de.littletrotter.domain.compliance

import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ValidationProfileTest {

    @Test
    fun `stateEnum resolves the matching ValidationProfileState by name`() {
        val profile = ValidationProfile().edit {
            profileId = "XRechnung"
            specVersion = "3.0.2"
            state = ValidationProfileState.ACTIVE.name
        }

        assertEquals(ValidationProfileState.ACTIVE, profile.stateEnum)
    }

    @Test
    fun `stateEnum returns null for a state string that matches no known enum value`() {
        val profile = ValidationProfile().edit {
            state = "SOME_FUTURE_STATE_NOT_YET_KNOWN"
        }

        assertNull(profile.stateEnum)
    }

    @Test
    fun `defaults to EXPERIMENTAL and carries the plain field values it was given`() {
        val profile = ValidationProfile()

        assertEquals(ValidationProfileState.EXPERIMENTAL.name, profile.state)
        assertEquals("", profile.profileId)
        assertEquals("", profile.specVersion)
        assertNull(profile.syntax)
        assertNull(profile.validFrom)
        assertNull(profile.validTo)
    }

    @Test
    fun `boundary - stateEnum resolves every declared ValidationProfileState by name`() {
        ValidationProfileState.entries.forEach { state ->
            val profile = ValidationProfile().edit { this.state = state.name }
            assertEquals(state, profile.stateEnum)
        }
    }

    @Test
    fun `boundary - every mutable field can be set and read back, including id, syntax, createdAt and version`() {
        val validFrom = LocalDate.of(2026, 1, 1)
        val validTo = LocalDate.of(2026, 12, 31)
        val createdAt = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        val profile = ValidationProfile().edit {
            id = 42L
            profileId = "EN16931"
            specVersion = "1.0"
            syntax = "UBL"
            state = ValidationProfileState.BLOCKED.name
            this.validFrom = validFrom
            this.validTo = validTo
            this.createdAt = createdAt
            version = 3L
        }

        assertEquals(42L, profile.id)
        assertEquals("EN16931", profile.profileId)
        assertEquals("1.0", profile.specVersion)
        assertEquals("UBL", profile.syntax)
        assertEquals(ValidationProfileState.BLOCKED, profile.stateEnum)
        assertEquals(validFrom, profile.validFrom)
        assertEquals(validTo, profile.validTo)
        assertEquals(createdAt, profile.createdAt)
        assertEquals(3L, profile.version)
    }
}
