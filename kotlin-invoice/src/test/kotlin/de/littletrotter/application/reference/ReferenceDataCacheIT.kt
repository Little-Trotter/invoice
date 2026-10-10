package de.littletrotter.application.reference

import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.infrastructure.cache.ValkeyInvoiceCacheClient
import de.littletrotter.test.ValkeyTestContainersExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import redis.clients.jedis.Jedis
import java.math.BigDecimal

/**
 * Integration test for CACHE-003: reference data (TaxRate, PerDiemRate) caching.
 *
 * Uses a real Valkey Testcontainer and Mockito-mocked repositories to count DB calls.
 * Verifies:
 *  - Cache-hit: repository called only once for repeated reads
 *  - Cache-miss on first call: repository called exactly once
 *  - Eviction on save: next read hits repository again
 *  - Eviction on delete: next read hits repository again
 *  - Filtered reads bypass cache (pageIndex > 0 or filterText != null)
 */
@ExtendWith(ValkeyTestContainersExtension::class)
class ReferenceDataCacheIT {

    private lateinit var taxRateRepo: TaxRateRepository
    private lateinit var perDiemRepo: PerDiemRateRepository
    private lateinit var cache: ValkeyInvoiceCacheClient
    private lateinit var taxRateService: TaxRateService
    private lateinit var perDiemRateService: PerDiemRateService

    @BeforeEach
    fun setup() {
        val host = ValkeyTestContainersExtension.host()
        val port = ValkeyTestContainersExtension.port()

        Jedis(host, port).use { j -> j.select(2); j.flushDB() }

        cache = ValkeyInvoiceCacheClient(host, port, "", invoiceDb = 1, queryDb = 2)
        cache.init()

        taxRateRepo = mock()
        perDiemRepo = mock()

        // split ReferenceDataService by Referenzdaten domain; both services still share
        // the same Valkey-backed cache instance, so the "caches are independent" test below still
        // proves the two domains' keys don't interfere with each other.
        taxRateService = TaxRateService(taxRateRepo, cache, ObjectMapper())
        perDiemRateService = PerDiemRateService(perDiemRepo, cache, ObjectMapper())
    }

    // ── TaxRate cache ─────────────────────────────────────────────────────────

    @Test
    fun `findAllTaxRates - first call hits DB, second call hits cache`() {
        val rate = TaxRate().apply { id = 1L; name = "USt 19%"; rate = TaxRatePercent(BigDecimal("19.00")) }
        whenever(taxRateRepo.findAll(0, 60, null)).thenReturn(listOf(rate))

        val first  = taxRateService.findAllTaxRates()
        val second = taxRateService.findAllTaxRates()

        verify(taxRateRepo, times(1)).findAll(0, 60, null)
        assertThat(first).hasSize(1)
        assertThat(second).hasSize(1)
        assertThat(second.first().name).isEqualTo("USt 19%")
    }

    @Test
    fun `saveTaxRate evicts cache so next read hits DB`() {
        val rate = TaxRate().apply { id = 1L; name = "USt 19%"; rate = TaxRatePercent(BigDecimal("19.00")) }
        whenever(taxRateRepo.findAll(0, 60, null)).thenReturn(listOf(rate))
        whenever(taxRateRepo.findDefaults()).thenReturn(emptyList())
        whenever(taxRateRepo.save(rate)).thenReturn(rate)

        taxRateService.findAllTaxRates()            // populate cache
        taxRateService.saveTaxRate(rate)            // must evict
        taxRateService.findAllTaxRates()            // must hit DB again

        verify(taxRateRepo, times(2)).findAll(0, 60, null)
    }

    @Test
    fun `deleteTaxRate evicts cache so next read hits DB`() {
        val rate = TaxRate().apply { id = 7L; name = "USt 7%"; rate = TaxRatePercent(BigDecimal("7.00")) }
        whenever(taxRateRepo.findAll(0, 60, null)).thenReturn(listOf(rate))

        taxRateService.findAllTaxRates()   // populate cache
        taxRateService.deleteTaxRate(7L)   // must evict
        taxRateService.findAllTaxRates()   // must hit DB again

        verify(taxRateRepo, times(2)).findAll(0, 60, null)
    }

    @Test
    fun `findAllTaxRates with filter bypasses cache`() {
        whenever(taxRateRepo.findAll(0, 60, "ust")).thenReturn(emptyList())

        taxRateService.findAllTaxRates(filterText = "ust")
        taxRateService.findAllTaxRates(filterText = "ust")

        // Each filtered call must hit the repo
        verify(taxRateRepo, times(2)).findAll(0, 60, "ust")
    }

    // ── PerDiemRate cache ─────────────────────────────────────────────────────

    @Test
    fun `findAllPerDiemRates - first call hits DB, second call hits cache`() {
        val rate = PerDiemRate().apply {
            id = 1L
            country = de.littletrotter.shared.country.Country.DE
            region = "DE"
        }
        whenever(perDiemRepo.findAll(0, 60, null)).thenReturn(listOf(rate))

        val first  = perDiemRateService.findAllPerDiemRates()
        val second = perDiemRateService.findAllPerDiemRates()

        verify(perDiemRepo, times(1)).findAll(0, 60, null)
        assertThat(first).hasSize(1)
        assertThat(second.first().region).isEqualTo("DE")
    }

    @Test
    fun `savePerDiemRate evicts cache`() {
        val rate = PerDiemRate().apply {
            id = 1L
            country = de.littletrotter.shared.country.Country.DE
            region = "DE"
        }
        whenever(perDiemRepo.findAll(0, 60, null)).thenReturn(listOf(rate))
        whenever(perDiemRepo.clearAllDefaults()).then { }
        whenever(perDiemRepo.save(rate)).thenReturn(rate)

        perDiemRateService.findAllPerDiemRates()
        perDiemRateService.savePerDiemRate(rate)
        perDiemRateService.findAllPerDiemRates()

        verify(perDiemRepo, times(2)).findAll(0, 60, null)
    }

    @Test
    fun `deletePerDiemRate evicts cache`() {
        val rate = PerDiemRate().apply {
            id = 5L
            country = de.littletrotter.shared.country.Country.AT
            region = "AT"
        }
        whenever(perDiemRepo.findAll(0, 60, null)).thenReturn(listOf(rate))

        perDiemRateService.findAllPerDiemRates()
        perDiemRateService.deletePerDiemRate(5L)
        perDiemRateService.findAllPerDiemRates()

        verify(perDiemRepo, times(2)).findAll(0, 60, null)
    }

    @Test
    fun `tax rate and per diem rate caches are independent`() {
        val taxRate    = TaxRate().apply { id = 1L; name = "USt 19%"; rate = TaxRatePercent(BigDecimal("19.00")) }
        val perDiemRate = PerDiemRate().apply {
            id = 1L
            country = de.littletrotter.shared.country.Country.DE
            region = "DE"
        }
        whenever(taxRateRepo.findAll(0, 60, null)).thenReturn(listOf(taxRate))
        whenever(perDiemRepo.findAll(0, 60, null)).thenReturn(listOf(perDiemRate))
        whenever(taxRateRepo.findDefaults()).thenReturn(emptyList())
        whenever(taxRateRepo.save(taxRate)).thenReturn(taxRate)

        taxRateService.findAllTaxRates()
        perDiemRateService.findAllPerDiemRates()
        taxRateService.saveTaxRate(taxRate)  // only evicts tax rate cache

        taxRateService.findAllTaxRates()      // must hit DB (evicted)
        perDiemRateService.findAllPerDiemRates()  // must NOT hit DB again (cache intact)

        verify(taxRateRepo,  times(2)).findAll(0, 60, null)
        verify(perDiemRepo,  times(1)).findAll(0, 60, null)
    }
}
