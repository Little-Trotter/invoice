package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.TaxRateRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Fix M, site 3 — [PanacheTaxRateRepository.findByCode] replaces `taxRateRepo.findAll(0, 1000).find { it.code == … }` (a detekt `MagicNumber` finding whose real defect was loading up to 1000 rows to find one code) in [de.littletrotter.application.invoice.TripService]'s Reverse-Charge rate lookup. New JPQL, so it gets its own Testcontainer IT (hit + miss) against real Postgres, not just a mocked repository.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheTaxRateRepositoryFindByCodeIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: TaxRateRepository

    @AfterEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createQuery("DELETE FROM TaxRate t WHERE t.code LIKE 'ZZ-FIXM3-%'").executeUpdate()
        }
    }

    private fun persist(rate: TaxRate): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            em.persist(rate)
            em.flush()
            rate.id!!
        }

    @Test
    fun `findByCode returns the matching rule and null when no rule carries that code`() {
        persist(
            TaxRate().apply {
                name = "RC-Regel"
                country = Country.DE
                rate = TaxRatePercent(BigDecimal.ZERO)
                category = "AE"
                exemptionReasonCode = "VATEX-EU-AE"
                code = "ZZ-FIXM3-RC-19"
            },
        )

        val found = repository.findByCode("ZZ-FIXM3-RC-19")
        assertEquals("RC-Regel", found?.name)

        assertNull(repository.findByCode("ZZ-FIXM3-NO-SUCH-CODE"))
    }
}
