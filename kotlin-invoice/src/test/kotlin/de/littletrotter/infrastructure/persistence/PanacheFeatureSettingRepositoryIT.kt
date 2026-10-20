package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.repository.FeatureSettingRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.settings.FeatureSetting
import de.littletrotter.fixture.edit
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PanacheFeatureSettingRepository] against a real Testcontainers Postgres. V2 seeds a
 * row for every [FeatureFlag] (`feature.perdiem.enabled`, `feature.email.enabled`,
 * `feature.extended-profile.enabled`), so the "row is missing" boundary is exercised by deleting
 * the row inside this test's own `@TestTransaction` — undone again on rollback, never a permanent
 * change to the shared fixture.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheFeatureSettingRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: FeatureSettingRepository

    @Test
    @TestTransaction
    fun `happy path findAll and findById resolve the seeded rows ordered by key`() {
        val all = repository.findAll()

        assertTrue(all.map { it.key }.let { it == it.sorted() }, "findAll is ordered by key")
        assertEquals(true, repository.findById(FeatureFlag.PER_DIEM_UI)?.enabled)
        assertEquals(true, repository.findById(FeatureFlag.EMAIL_SEND)?.enabled)
        assertEquals(false, repository.findById(FeatureFlag.EXTENDED_PROFILE)?.enabled)
    }

    @Test
    @TestTransaction
    fun `boundary findById returns null when the row is missing`() {
        em.createNativeQuery("DELETE FROM feature_setting WHERE key = :key")
            .setParameter("key", FeatureFlag.EMAIL_SEND.key)
            .executeUpdate()

        assertNull(repository.findById(FeatureFlag.EMAIL_SEND))
    }

    @Test
    @TestTransaction
    fun `boundary save upserts on the string key instead of duplicating`() {
        val existing = requireNotNull(repository.findById(FeatureFlag.PER_DIEM_UI))
        existing.edit { enabled = false }
        repository.save(existing)

        assertEquals(false, repository.findById(FeatureFlag.PER_DIEM_UI)?.enabled)

        val fresh = FeatureSetting.of(FeatureFlag.PER_DIEM_UI, enabled = true)
        repository.save(fresh)

        assertEquals(true, repository.findById(FeatureFlag.PER_DIEM_UI)?.enabled)
        assertEquals(1L, repository.findAll().count { it.key == FeatureFlag.PER_DIEM_UI.key }.toLong())
    }

    @Test
    @TestTransaction
    fun `boundary a key outside the FeatureFlag enum still saves and round-trips, but findById cannot address it`() {
        val outside = FeatureSetting().edit { key = "zzcov9.custom.flag"; enabled = true }

        repository.save(outside)

        val direct = em.find(FeatureSetting::class.java, "zzcov9.custom.flag")
        assertEquals(true, direct?.enabled)
        assertTrue(repository.findAll().any { it.key == "zzcov9.custom.flag" })
    }
}
