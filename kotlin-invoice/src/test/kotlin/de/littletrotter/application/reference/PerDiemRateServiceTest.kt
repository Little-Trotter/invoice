package de.littletrotter.application.reference

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.PerDiemRateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * per-diem-rate slice of the former `ReferenceDataServiceCacheAndGuardsTest` (split by
 * Referenzdaten domain alongside [PerDiemRateService]).
 */
class PerDiemRateServiceTest {

    /** Minimal in-memory double for the one cache key this suite exercises — real enough to
     *  drive [PerDiemRateService]'s hit/miss branches without a live Valkey. */
    private class FakeCache : InvoiceCachePort {
        val overview = mutableMapOf<String, String>()
        override fun getCachedOverview(cacheKey: String): String? = overview[cacheKey]
        override fun cacheOverview(cacheKey: String, json: String, ttlSeconds: Long) {
            overview[cacheKey] = json
        }
        override fun evictOverview(cacheKey: String) {
            overview.remove(cacheKey)
        }
        override fun evictAllOverview() = overview.clear()
        override fun getCachedPdf(invoiceId: Long): ByteArray? = null
        override fun cachePdf(invoiceId: Long, pdf: ByteArray, ttlSeconds: Long) {}
        override fun evictPdf(invoiceId: Long) {}
        override fun getJobStatus(correlationId: String): String? = null
        override fun cacheJobStatus(correlationId: String, statusJson: String, ttlSeconds: Long) {}
        override fun evictJobStatus(correlationId: String) {}
        override fun isChecksumKnown(sha256: String): Boolean = false
        override fun markChecksumKnown(sha256: String) {}
        override fun evictChecksum(sha256: String) {}
    }

    private val perDiemRateRepository = mock<PerDiemRateRepository>()
    private val cache = FakeCache()

    private fun productionEquivalentObjectMapper(): ObjectMapper =
        ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun serviceWith(objectMapper: ObjectMapper = ObjectMapper()) =
        PerDiemRateService(perDiemRateRepository, cache, objectMapper)

    private fun perDiemRate(id: Long = 1L) = PerDiemRate().apply { this.id = id; country = Country.DE }

    @Test
    fun `findAllPerDiemRates liest einen Cache-Treffer unter dem produktionsnahen Kotlin-ObjectMapper ohne Repository-Zugriff`() {
        val mapper = productionEquivalentObjectMapper()
        cache.overview["ref:per-diem-rates"] = mapper.writeValueAsString(listOf(perDiemRate(id = 4L)))
        val service = serviceWith(mapper)

        val result = service.findAllPerDiemRates()

        assertEquals(1, result.size)
        assertEquals(4L, result[0].id)
        verify(perDiemRateRepository, never()).findAll(any(), any(), any(), any(), any())
    }

    @Test
    fun `findAllPerDiemRates liest bei leerem Cache aus dem Repository und befuellt den Cache`() {
        whenever(perDiemRateRepository.findAll(0, 60, null)).thenReturn(listOf(perDiemRate()))

        val result = serviceWith().findAllPerDiemRates()

        assertEquals(1, result.size)
        assertTrue(cache.overview.containsKey("ref:per-diem-rates"))
    }

    @Test
    fun `findAllPerDiemRates liest bei Treffer aus dem Cache`() {
        val mapper = ObjectMapper()
        cache.overview["ref:per-diem-rates"] = mapper.writeValueAsString(listOf(perDiemRate(id = 4L)))

        val result = serviceWith(mapper).findAllPerDiemRates()

        assertEquals(4L, result[0].id)
        verify(perDiemRateRepository, never()).findAll(any(), any(), any(), any(), any())
    }

    @Test
    fun `findAllPerDiemRates faellt bei korruptem Cache-Eintrag auf die DB zurueck`() {
        cache.overview["ref:per-diem-rates"] = "{{broken"
        whenever(perDiemRateRepository.findAll(0, 60, null)).thenReturn(listOf(perDiemRate(id = 7L)))

        val result = serviceWith().findAllPerDiemRates()

        assertEquals(7L, result[0].id)
    }

    @Test
    fun `findAllPerDiemRates umgeht den Cache wenn eine Seite angefragt wird`() {
        whenever(perDiemRateRepository.findAll(2, 60, null)).thenReturn(emptyList())

        serviceWith().findAllPerDiemRates(pageIndex = 2)

        verify(perDiemRateRepository).findAll(2, 60, null)
        assertTrue(cache.overview.isEmpty())
    }

    @Test
    fun `savePerDiemRate raeumt bei neuem Standard alle bisherigen Standards und leert den Cache`() {
        cache.overview["ref:per-diem-rates"] = "stale"
        val rate = perDiemRate().apply { isDefault = true }
        whenever(perDiemRateRepository.save(rate)).thenReturn(rate)

        serviceWith().savePerDiemRate(rate)

        verify(perDiemRateRepository).clearAllDefaults()
        assertTrue(cache.overview.isEmpty())
    }

    @Test
    fun `savePerDiemRate raeumt bei einem Nicht-Standard-Satz keine bestehenden Standards`() {
        val rate = perDiemRate().apply { isDefault = false }
        whenever(perDiemRateRepository.save(rate)).thenReturn(rate)

        serviceWith().savePerDiemRate(rate)

        verify(perDiemRateRepository, never()).clearAllDefaults()
    }

    @Test
    fun `deletePerDiemRate leert den Cache`() {
        cache.overview["ref:per-diem-rates"] = "stale"

        serviceWith().deletePerDiemRate(3L)

        verify(perDiemRateRepository).delete(3L)
        assertTrue(cache.overview.isEmpty())
    }

    // --- moved from ReferenceDataServiceRatesTest, --

    @Test
    fun `boundary - unknown id resolves to null, never to a fallback rate`() {
        assertEquals(null, serviceWith().findPerDiemRateById(404L))
        assertEquals(null, serviceWith().findDefaultPerDiemRate())
    }

    @Test
    fun `boundary - a non-default per-diem sort reads through and never touches the cache`() {
        whenever(perDiemRateRepository.findAll(0, 60, null, "country", de.littletrotter.domain.shared.SortDirection.DESC)).thenReturn(emptyList())
        whenever(perDiemRateRepository.findAll(0, 60, null, "amount", de.littletrotter.domain.shared.SortDirection.ASC)).thenReturn(emptyList())

        assertTrue(serviceWith().findAllPerDiemRates(sortDir = de.littletrotter.domain.shared.SortDirection.DESC).isEmpty())
        assertTrue(serviceWith().findAllPerDiemRates(sortField = "amount").isEmpty())

        assertTrue(cache.overview.isEmpty())
    }

    @Test
    fun `boundary - a per-diem cache write failure still returns the freshly read rates`() {
        val objectMapper = mock<ObjectMapper>()
        val rates = listOf(PerDiemRate())
        whenever(perDiemRateRepository.findAll(0, 60, null)).thenReturn(rates)
        whenever(objectMapper.writeValueAsString(any())).thenThrow(
            com.fasterxml.jackson.core.JsonParseException(null as com.fasterxml.jackson.core.JsonParser?, "boom"),
        )

        assertEquals(rates, serviceWith(objectMapper).findAllPerDiemRates())

        assertTrue(cache.overview.isEmpty())
    }
}
