package de.littletrotter.application.reference

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.application.cache.port.outbound.ReferenceListCachePort
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * tax-rate slice of the former `ReferenceDataServiceCacheAndGuardsTest` (split by
 * Referenzdaten domain alongside [TaxRateService]).
 * A real (non-mocked) [ObjectMapper] drives the actual JSON round trip for the happy paths; the
 * two [JsonProcessingException] fallbacks (a corrupt cached blob, a serialization failure) are the
 * one place a mocked [ObjectMapper] is warranted.
 *
 * CACHE-FIX: the "cache hit" tests use a plain `ObjectMapper()` (no module registered at all) and
 * only prove the JSON shape round-trips at all — they would NOT have caught the regression where
 * [TaxRate.rate] ([de.littletrotter.domain.value.TaxRatePercent], a `@JvmInline` value class)
 * failed to deserialize specifically under the mapper Quarkus actually wires in production.
 * [productionEquivalentObjectMapper] reproduces that via [ObjectMapper.findAndRegisterModules].
 */
class TaxRateServiceTest {

    /** Minimal in-memory double for the one cache key this suite exercises — real enough to
     *  drive [TaxRateService]'s hit/miss branches without a live Valkey. */
    private class FakeCache : ReferenceListCachePort {
        val overview = mutableMapOf<String, String>()
        override fun getCachedReferenceList(key: String): String? = overview[key]
        override fun cacheReferenceList(key: String, json: String, ttlSeconds: Long) {
            overview[key] = json
        }
        override fun evictReferenceList(key: String) {
            overview.remove(key)
        }
    }

    private val taxRateRepository = mock<TaxRateRepository>()
    private val cache = FakeCache()

    private fun productionEquivalentObjectMapper(): ObjectMapper =
        ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun serviceWith(objectMapper: ObjectMapper = ObjectMapper()) =
        TaxRateService(taxRateRepository, cache, objectMapper)

    private fun taxRate(id: Long = 1L) = TaxRate().edit { this.id = id; name = "Regelsatz"; rate = TaxRatePercent(BigDecimal("19.00")) }

    @Test
    fun `findAllTaxRates liest bei leerem Cache aus dem Repository und befuellt den Cache`() {
        whenever(taxRateRepository.findAll(0, 60, null)).thenReturn(listOf(taxRate()))
        val service = serviceWith()

        val result = service.findAllTaxRates()

        assertEquals(1, result.size)
        assertTrue(cache.overview.containsKey("tax-rates"), "a cache miss must populate the cache")
    }

    @Test
    fun `findAllTaxRates liest bei Treffer aus dem Cache ohne das Repository zu befragen`() {
        val mapper = ObjectMapper()
        cache.overview["tax-rates"] = mapper.writeValueAsString(listOf(TaxRateCacheEntry.from(taxRate(id = 9L))))
        val service = serviceWith(mapper)

        val result = service.findAllTaxRates()

        assertEquals(1, result.size)
        assertEquals(9L, result[0].id)
        verify(taxRateRepository, never()).findAll(any(), any(), any(), any(), any())
    }

    @Test
    fun `findAllTaxRates faellt bei einem korrupten Cache-Eintrag auf einen frischen DB-Read zurueck`() {
        cache.overview["tax-rates"] = "not-valid-json{{"
        whenever(taxRateRepository.findAll(0, 60, null)).thenReturn(listOf(taxRate(id = 3L)))
        val service = serviceWith()

        val result = service.findAllTaxRates()

        assertEquals(1, result.size)
        assertEquals(3L, result[0].id)
    }

    @Test
    fun `findAllTaxRates umgeht den Cache wenn eine Seite oder ein Filter angefragt wird`() {
        whenever(taxRateRepository.findAll(1, 60, null)).thenReturn(emptyList())
        val service = serviceWith()

        service.findAllTaxRates(pageIndex = 1)

        verify(taxRateRepository).findAll(1, 60, null)
        assertTrue(cache.overview.isEmpty(), "a paged/filtered read must not touch the overview cache at all")
    }

    @Test
    fun `findAllTaxRates umgeht den Cache bei gesetztem Filtertext`() {
        whenever(taxRateRepository.findAll(0, 60, "19")).thenReturn(emptyList())
        val service = serviceWith()

        service.findAllTaxRates(filterText = "19")

        verify(taxRateRepository).findAll(0, 60, "19")
        assertTrue(cache.overview.isEmpty())
    }

    @Test
    fun `findAllTaxRates faengt einen Serialisierungsfehler beim Befuellen des Cache ab`() {
        val mapper = mock<ObjectMapper>()
        whenever(mapper.writeValueAsString(any())).thenThrow(object : JsonProcessingException("boom") {})
        whenever(taxRateRepository.findAll(0, 60, null)).thenReturn(listOf(taxRate()))
        val service = serviceWith(mapper)

        val result = service.findAllTaxRates()

        assertEquals(1, result.size, "a cache-write failure must not fail the read itself")
        assertTrue(cache.overview.isEmpty())
    }

    @Test
    fun `TaxRateCacheEntry roundtrips through den produktionsnahen Kotlin-ObjectMapper`() {
        val mapper = productionEquivalentObjectMapper()
        val entry = TaxRateCacheEntry.from(
            taxRate(id = 9L).edit {
                code = "DE_STANDARD_19"
                category = "S"
                validFrom = LocalDate.of(2026, 1, 1)
            },
        )

        val json = mapper.writeValueAsString(listOf(entry))
        val roundtripped = mapper.readValue<List<TaxRateCacheEntry>>(json)

        assertEquals(listOf(entry), roundtripped, "a broken round trip must fail this assertion directly, not be swallowed")
        assertEquals(BigDecimal("19.00"), roundtripped[0].rate)
    }

    @Test
    fun `findAllTaxRates liest einen Cache-Treffer unter dem produktionsnahen Kotlin-ObjectMapper ohne Repository-Zugriff`() {
        val mapper = productionEquivalentObjectMapper()
        cache.overview["tax-rates"] = mapper.writeValueAsString(listOf(TaxRateCacheEntry.from(taxRate(id = 9L))))
        val service = serviceWith(mapper)

        val result = service.findAllTaxRates()

        assertEquals(1, result.size)
        assertEquals(9L, result[0].id)
        assertEquals(0, BigDecimal("19.00").compareTo(result[0].rate.value))
        verify(taxRateRepository, never()).findAll(any(), any(), any(), any(), any())
    }

    @Test
    fun `saveTaxRate entzieht anderen Saetzen den Standard und leert den Cache`() {
        cache.overview["tax-rates"] = "stale"
        val other = taxRate(id = 2L).edit { isDefault = true }
        whenever(taxRateRepository.findDefaults()).thenReturn(listOf(other))
        val newDefault = taxRate(id = 1L).edit { isDefault = true }
        whenever(taxRateRepository.save(newDefault)).thenReturn(newDefault)

        serviceWith().saveTaxRate(newDefault)

        assertFalse(cache.overview.containsKey("tax-rates"), "a save must evict the stale cache entry")
        verify(taxRateRepository).save(other)
        assertFalse(other.isDefault)
    }

    @Test
    fun `deleteTaxRate leert den Cache`() {
        cache.overview["tax-rates"] = "stale"

        serviceWith().deleteTaxRate(1L)

        verify(taxRateRepository).delete(1L)
        assertTrue(cache.overview.isEmpty())
    }

    // --- moved from ReferenceDataServiceRatesTest, --

    @Test
    fun `boundary - unknown id resolves to null, never to a fallback rate`() {
        assertEquals(null, serviceWith().findTaxRateById(404L))
        assertFalse(serviceWith().isTaxRateInUse(404L))
    }

    @Test
    fun `boundary - an explicitly sorted tax-rate lookup reads through and never touches the cache`() {
        whenever(taxRateRepository.findAll(0, 60, null, "name", de.littletrotter.domain.shared.SortDirection.DESC))
            .thenReturn(listOf(taxRate()))

        assertEquals(1, serviceWith().findAllTaxRates(sortField = "name", sortDir = de.littletrotter.domain.shared.SortDirection.DESC).size)

        assertTrue(cache.overview.isEmpty())
    }
}
