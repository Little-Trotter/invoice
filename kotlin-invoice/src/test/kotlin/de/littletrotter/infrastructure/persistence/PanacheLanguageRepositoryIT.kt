package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.repository.LanguageRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [PanacheLanguageRepository] against the real V2 seed (`de` sort_order 10, `en` sort_order
 * 20, both active).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheLanguageRepositoryIT {

    @Inject
    lateinit var repository: LanguageRepository

    @Test
    fun `findActiveOrdered returns the seeded languages in sort_order`() {
        val codes = repository.findActiveOrdered().map { it.code }
        assertEquals(listOf("de", "en"), codes)
    }

    @Test
    fun `findByCode finds the seeded de row`() {
        val language = repository.findByCode("de")
        assertEquals("de", language?.code)
        assertEquals(true, language?.isFallback)
    }

    @Test
    fun `findByCode returns null for an unknown code`() {
        assertNull(repository.findByCode("xx"))
    }
}
