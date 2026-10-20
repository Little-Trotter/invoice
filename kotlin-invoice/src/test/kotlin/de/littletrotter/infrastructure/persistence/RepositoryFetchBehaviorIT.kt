package de.littletrotter.infrastructure.persistence

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.domain.shared.SortDirection
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber

/** §3+§4 — fetch behavior of the repositories, proven, not assumed. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class RepositoryFetchBehaviorIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var recipientRepository: RecipientRepository

    /** §3 — `findByIdWithItems` must initialize the items collection: after detaching,
     *  an uninitialized collection throws LazyInitializationException (which this test did
     *  before the JOIN FETCH was added). */
    @Test
    @TestTransaction
    fun `findByIdWithItems initializes the items collection`() {
        val invoice = Invoice().edit {
            invoiceDate = LocalDate.of(2031, 2, 1)
            addItem(InvoiceItem().edit {
                taxRateRef = em.seededStandardTaxRate()
                positionId = "1"; description = "Fetch item A"
                quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("10.00"))
            })
            addItem(InvoiceItem().edit {
                taxRateRef = em.seededStandardTaxRate()
                positionId = "2"; description = "Fetch item B"
                quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("20.00"))
            })
        }
        em.persist(invoice)
        em.flush()
        em.clear()

        val loaded = invoiceRepository.findByIdWithItems(invoice.id!!)!!
        em.clear() // detach: from here on only initialized collections are readable

        assertEquals(listOf("Fetch item A", "Fetch item B"), loaded.itemList.map { it.description })
    }

    /** §4 — a recipient page must not degenerate into one address SELECT per row.
     *  Hibernate batch fetching (Quarkus default `fetch.batch-size=16`) loads the addresses
     *  of the whole page in ⌈40/16⌉ batches; this pins that behavior with a JDBC statement
     *  budget so a config regression (batch-size=-1) fails loudly. */
    @Test
    @TestTransaction
    fun `recipient page loads addresses batched, not row by row`() {
        repeat(40) { n ->
            val recipient = aRecipient {
                customerNumber = CustomerNumber("FETCH-${"%03d".format(n)}")
                name = "Fetch Recipient $n"
            }
            recipient.addAddress(
                Address.forRecipient(recipient, Address.AddressType.BILLING, "Weg $n", "50667", "Koeln"),
            )
            em.persist(recipient)
        }
        em.flush()
        em.clear()

        val statistics = em.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()

        val page = recipientRepository.findAll(0, 60, "fetch recipient", "name", SortDirection.ASC)
        assertEquals(40, page.size)

        val statements = statistics.prepareStatementCount
        // 1 page query + ⌈40/16⌉ = 3 address batches (+ headroom). Without batch fetching
        // this is 41+.
        assertTrue(statements <= 10) {
            "expected batched address loading, but findAll issued $statements JDBC statements"
        }
    }

    /** Regression (PO report 2026-08-25): the grid's city sort mapped to a bare `r.city`,
     *  which stopped resolving when Z3 moved city onto the Address entity —
     *  UnknownPathException the moment a user sorted the Kunden grid by Ort. The allowlist now
     *  sorts via a billing-address scalar subquery; this pins both directions actually ordering
     *  by the billing city. */
    @Test
    @TestTransaction
    fun `recipient findAll sorts by the billing address city without UnknownPathException`() {
        listOf("Zwickau" to "CITY-3", "Aachen" to "CITY-1", "Mainz" to "CITY-2").forEach { (city, number) ->
            val recipient = aRecipient {
                customerNumber = CustomerNumber.ofNullable(number)
                name = "City Sort $number"
            }
            recipient.addAddress(
                Address.forRecipient(recipient, Address.AddressType.BILLING, "Weg 1", "12345", city),
            )
            em.persist(recipient)
        }
        em.flush()
        em.clear()

        val asc = recipientRepository.findAll(0, 10, "City Sort", "city", SortDirection.ASC)
        assertEquals(listOf("Aachen", "Mainz", "Zwickau"), asc.map { it.billingAddress?.city })

        val desc = recipientRepository.findAll(0, 10, "City Sort", "city", SortDirection.DESC)
        assertEquals(listOf("Zwickau", "Mainz", "Aachen"), desc.map { it.billingAddress?.city })
    }
}
