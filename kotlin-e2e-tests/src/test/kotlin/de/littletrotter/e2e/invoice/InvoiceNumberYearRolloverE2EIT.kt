package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import de.littletrotter.fixture.edit

/**
 * The yearly invoice number range across New Year's Eve, driven through the real lifecycle
 * service against a real PostgreSQL: draft, review and send each happen at a fixed moment of the
 * application's [TimeSource].
 *
 * The moments are Berlin wall-clock times on purpose. At 00:00:30 on 1 January in Berlin it is
 * still 31 December in UTC — the invoice must nevertheless carry the new year's date, and its
 * number must start the new year's sequence at 1, because the user works in Berlin time.
 *
 * The years lie far in the future so no other suite, which draws invoice numbers with the real
 * clock from the same shared range, can ever share these two periods.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceNumberYearRolloverE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    private val tag = "YRO"

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun persistParties(): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            name = "Jahreswechsel Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@yro.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
            phone = "+49 30 1234567"
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Jahreswechsel Empfänger AG"
            email = Email.ofNullable("ap@yro.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)
        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    /** Fixes the application's "now" at a Berlin wall-clock moment, then drafts, reviews and sends
     *  one invoice at that moment — the service delivered the same day. */
    private fun sendAt(berlinTime: LocalDateTime, parties: Parties): Invoice {
        QuarkusMock.installMockForType(ClockTimeSource.fixedAtLocal(berlinTime), TimeSource::class.java)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Silvester-Beratung",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
            em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = berlinTime.toLocalDate() }
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }
    }

    private fun journalPeriods(): List<String> = QuarkusTransaction.requiringNew().call {
        em.createNativeQuery("SELECT period_key || ' ' || number FROM number_journal WHERE number LIKE :p ORDER BY seq")
            .setParameter("p", "$tag-%")
            .resultList
            .map { it.toString() }
    }

    @Test
    fun `the invoice number range restarts at 1 in the first second of the new Berlin year`() {
        val parties = persistParties()

        val lastButOne = sendAt(LocalDateTime.of(2098, 12, 31, 23, 59, 58), parties)
        val last = sendAt(LocalDateTime.of(2098, 12, 31, 23, 59, 59), parties)
        val first = sendAt(LocalDateTime.of(2099, 1, 1, 0, 0, 30), parties)

        assertEquals(LocalDate.of(2098, 12, 31), last.invoiceDate)
        assertEquals("$tag-2098-12-31-00001", lastButOne.invoiceNumber.value)
        assertEquals("$tag-2098-12-31-00002", last.invoiceNumber.value)
        assertEquals(
            LocalDate.of(2099, 1, 1), first.invoiceDate,
            "00:00:30 in Berlin is already the new year, although UTC still says 31 December",
        )
        assertEquals("$tag-2099-01-01-00001", first.invoiceNumber.value, "the new year's sequence starts at the start value again")
        assertEquals(listOf(1L, 2L, 1L), listOf(lastButOne, last, first).map { it.invoiceNumberSequence })

        assertEquals(
            listOf("2098 $tag-2098-12-31-00001", "2098 $tag-2098-12-31-00002", "2099 $tag-2099-01-01-00001"),
            journalPeriods(),
            "every drawn number is journaled under the year it counts in",
        )
    }
}
