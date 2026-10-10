package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.domain.repository.SystemSettingRepository
import de.littletrotter.domain.settings.SystemSetting
import de.littletrotter.shared.wording.WordingProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The real-Postgres proof the `system_setting` table (new in the V1 baseline) is
 * applied correctly and round-trips through [PanacheSystemSettingRepository] and, at the gate
 * level, [WordingSettingsGate]: the table has no trigger of its own (a plain key/value row, JPA
 * `@Version` optimistic locking only), so this is deliberately a thin, single-class proof rather
 * than a dedicated trigger test — the same scope `PanacheHotelRateRepositoryIT` set for a table
 * with no interesting DB-side behaviour either.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheSystemSettingRepositoryIT {

    @Inject
    lateinit var repository: SystemSettingRepository

    @Inject
    lateinit var wordingSettingsGate: WordingSettingsGate

    private val key = "ZZ-RK-7-IT-wording.profile"

    @Test
    fun `a row is missing until saved, then round-trips through the real table`() {
        assertNull(repository.findById(key))

        val saved = repository.save(SystemSetting.of(key, "BERATUNG"))
        assertEquals(key, saved.key)
        assertEquals("BERATUNG", saved.value)

        val reloaded = repository.findById(key)
        assertEquals("BERATUNG", reloaded?.value)
    }

    @Test
    fun `a family holds the row itself and its dotted children in key order, nothing else`() {
        val family = "ZZ-RK-7-IT-family"
        repository.save(SystemSetting.of("$family.2", "b"))
        repository.save(SystemSetting.of(family, "a"))
        repository.save(SystemSetting.of("${family}x", "not a child"))

        assertEquals(listOf("a", "b"), repository.findFamily(family).map { it.value })
    }

    @Test
    fun `updating an existing row upserts on the string PK instead of duplicating`() {
        repository.save(SystemSetting.of(key, "HANDWERK"))
        val existing = requireNotNull(repository.findById(key))
        existing.value = "BERATUNG"
        repository.save(existing)

        assertEquals("BERATUNG", repository.findById(key)?.value)
    }

    @Test
    fun `WordingSettingsGate setProfile persists through the real repository and cache invalidation`() {
        wordingSettingsGate.setProfile(WordingProfile.BERATUNG, "rk7-it")
        assertEquals(WordingProfile.BERATUNG, wordingSettingsGate.currentProfile())

        wordingSettingsGate.setProfile(WordingProfile.HANDWERK, "rk7-it")
        assertEquals(WordingProfile.HANDWERK, wordingSettingsGate.currentProfile())
    }
}
