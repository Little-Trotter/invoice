package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.ValkeyTestResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * -Folge (PO 29.08.2026) — proves the Criteria rewrite of the former string-built queries
 * against REAL Hibernate: no other test exercises `countForOverview` or the
 * tax-rate filter with a live `EntityManager` (SystemWorkflowIT fakes the repository). Two
 * concerns in one place:
 *
 * 1. **Translation correctness** — `lower()` on the converter-mapped `invoiceNumber`, the
 *    fetch-as-join aliases and the enum-converter equality must all survive Hibernate's SQM
 *    translation, not just compile.
 * 2. **Injection regression** — attack-shaped filter inputs must neither error nor widen the
 *    result set: with Criteria + bind-by-construction they can only ever be literal match text.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ValkeyTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class PanacheRepositoryCriteriaOverviewIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceListing: InvoiceListingRepositoryImpl

    @Inject
    lateinit var taxRateRepository: PanacheTaxRateRepository

    // Companion state: JUnit builds a fresh test instance per method, so per-instance fields
    // would re-create the fixtures (and trip the sender-prefix unique constraint).
    companion object {
        private var alphaId: Long = 0
    }

    // Deliberately NO table cleanup: every @QuarkusTest in this module shares one dev-services
    // database, so destructive setup would sabotage neighbouring ITs. All assertions below are
    // therefore relative to this test's own OVIT-* fixtures.
    @BeforeEach
    fun fixtures() {
        if (alphaId == 0L) {
            alphaId = persistInvoice("OVIT-2026-0001", "AlphaOvit Kunde GmbH")
            persistInvoice("OVIT-2026-0002", "BetaOvit Partner AG")
        }
    }

    private fun persistInvoice(number: String, recipientName: String): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            val sender = aSenderProfile {
                name = "Overview Sender"
                email = Email("billing@overview.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                registerCourt = "AG Musterstadt"
                invoicePrefix = "OV${number.takeLast(4)}-"
            }
            val recipient = aRecipient {
                customerNumber = CustomerNumber("KD-$number")
                name = recipientName
                email = Email("kunde@overview.example")
                vatId = VatId("DE987654321")
                iban = Iban("DE02120300000000202051")
            }
            em.persist(sender)
            em.persist(recipient)
            val invoice = Invoice().edit {
                invoiceNumber = InvoiceNumber(number)
                invoiceDate = LocalDate.now()
                senderProfile = sender
                this.recipient = recipient
                totals = InvoiceTotals(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("19.00")), grossAmount = Money(BigDecimal("119.00")))
            }
            em.persist(invoice)
            em.flush()
            invoice.id!!
        }

    @Test
    fun `unfiltered count sees both fixtures`() {
        // Unfiltered COUNT exercises the no-predicate branch without hydrating entities —
        // a full unfiltered read would materialize every row of the SHARED dev-services DB,
        // including other ITs' deliberately malformed fixtures (an invoice number the
        // converter rejects on read).
        assertTrue(invoiceListing.countForOverview(null, null) >= 2L)
        assertTrue(invoiceListing.countForOverview("ovit-2026-000", null) >= 2L)
    }

    @Test
    fun `name filter matches the recipient case-insensitively and via the number converter path`() {
        assertEquals(1L, invoiceListing.countForOverview("alphaovit", null))
        // lower() on the converter-mapped invoiceNumber attribute — the translation that only a
        // real Hibernate run can prove.
        assertEquals(1L, invoiceListing.countForOverview("ovit-2026-0002", null))
        assertEquals(1L, invoiceListing.countForOverview("betaovit", null))
    }

    @Test
    fun `status filter combines with the text filter`() {
        assertEquals(1L, invoiceListing.countForOverview("alphaovit kunde", InvoiceStatus.DRAFT))
        assertEquals(0L, invoiceListing.countForOverview("alphaovit kunde", InvoiceStatus.CANCELLED))
    }

    @Test
    fun `attack-shaped filter input stays literal match text`() {
        for (attack in listOf("x' OR '1'='1", "'; DROP TABLE invoice; --", "\\")) {
            assertEquals(0L, invoiceListing.countForOverview(attack, null), "attack-shaped input must never widen the result set: $attack")
        }
        // Escaping keeps wildcard characters literal: a real % in a name is found by filtering
        // for %, and a plain % filter must NOT match names without one.
        persistInvoice("OVIT-2026-0003", "100% Service GmbH")
        assertEquals(1L, invoiceListing.countForOverview("100% service", null))
        assertTrue(
            invoiceListing.countForOverview("%", null) < invoiceListing.countForOverview(null, null),
            "a % filter must stay literal, never a wildcard",
        )
    }

    @Test
    fun `tax-rate filter runs name, country and non-country shapes without error`() {
        QuarkusTransaction.requiringNew().run {
            em.persist(TaxRate().edit {
                name = "Criteria-IT 19%"
                rate = TaxRatePercent(BigDecimal("19.00"))
                code = "OVIT_19"
                category = "S"
                country = Country.DE
                isSelectable = false
            })
        }
        assertTrue(taxRateRepository.findAll(0, 50, "criteria-it").any { it.code == "OVIT_19" })
        // The country shape matches every DE rate the shared IT database holds (seed plus rows
        // persisted by other ITs); the default order is country, rate, so a page of 50 can end
        // before this 19 % row — read the whole DE set, the assertion is about the filter shape.
        assertTrue(taxRateRepository.findAll(0, Int.MAX_VALUE, "de").any { it.code == "OVIT_19" })
        // The former unbound-:countryEnum crash shape — a 2-char non-country filter must simply
        // filter by name, and attack shapes must stay literal.
        taxRateRepository.findAll(0, 50, "zz")
        taxRateRepository.findAll(0, 50, "x' OR '1'='1")
        taxRateRepository.count("zz")
        taxRateRepository.countInactive("criteria-it")
    }
}
