package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceNumberReadRepository
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Exact number matching against real Postgres: a partial number or another field must not link. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceNumberReadRepositoryImplIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: InvoiceNumberReadRepository

    @Test
    @Transactional
    fun `only exact invoice numbers resolve`() {
        val exact = persistInvoice("NRIT-2026-04-00001")
        persistInvoice("NRIT-2026-04-00010")
        em.flush()

        val found = repository.findIdsByNumbers(setOf("NRIT-2026-04-00001", "NRIT-2026-04-0001", "Q-1"))

        assertEquals(mapOf("NRIT-2026-04-00001" to exact.id!!), found)
    }

    private fun persistInvoice(number: String): Invoice =
        Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            invoiceDate = LocalDate.of(2026, 4, 1)
        }.also { em.persist(it) }
}
