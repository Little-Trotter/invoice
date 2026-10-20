package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.reference.port.inbound.ManagePaymentTermUseCase
import de.littletrotter.application.reference.port.inbound.ManageTaxRateUseCase
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Catalogue entries (tax rates, payment terms and their siblings share one write helper) hand a
 * DTO back from their save. Its version must be the committed one, so a caller that keeps that DTO
 * can save again without being told the entry changed meanwhile. Calls run as the UI runs them:
 * plain calls on the port, no surrounding transaction.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CatalogueSaveVersionContractIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var manageTaxRate: ManageTaxRateUseCase

    @Inject
    lateinit var managePaymentTerm: ManagePaymentTermUseCase

    private fun committedTaxRateVersion(id: Long): Long? = QuarkusTransaction.requiringNew().call<Long?> {
        em.find(TaxRate::class.java, id).version
    }

    private fun committedPaymentTermVersion(id: Long): Long? = QuarkusTransaction.requiringNew().call<Long?> {
        em.find(PaymentTerm::class.java, id).version
    }

    @Test
    fun `a tax rate save hands back the committed version and the same form saves again`() {
        val draft = TaxRateDto(
            name = "Versionsvertrag 7 %",
            rate = TaxRatePercent(BigDecimal("7.00")),
            category = "S",
            code = "CSVC_RED7",
            validFrom = LocalDate.of(2024, 1, 1),
        )

        val saved = manageTaxRate.saveTaxRate(draft)
        val id = checkNotNull(saved.id)
        assertEquals(committedTaxRateVersion(id), saved.version, "the version handed back must be the committed one")

        val again = manageTaxRate.saveTaxRate(saved.copy(name = "Versionsvertrag 7 %, geändert"))
        assertEquals(committedTaxRateVersion(id), again.version, "and the same holds after an edit")
        assertEquals("Versionsvertrag 7 %, geändert", again.name)
    }

    @Test
    fun `a payment term save hands back the committed version and the same form saves again`() {
        val draft = PaymentTermDto(
            namesByLanguageCode = mapOf("de" to "Versionsvertrag 10 Tage"),
            fallbackLanguageCode = "de",
            dueInDays = 10,
        )

        val saved = managePaymentTerm.savePaymentTerm(draft)
        val id = checkNotNull(saved.id)
        assertEquals(committedPaymentTermVersion(id), saved.version, "the version handed back must be the committed one")

        val again = managePaymentTerm.savePaymentTerm(saved.copy(dueInDays = 12))
        assertEquals(committedPaymentTermVersion(id), again.version, "and the same holds after an edit")
        assertEquals(12, again.dueInDays)
    }
}
