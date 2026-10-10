package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * [InvoiceStornoService
 * .createCreditNote] against real Postgres for a TRIP position: children (expenses, per-diem
 * days, receipt links) are copied, amounts negated via [InvoiceItem.negateForStorno], and the
 * result is persisted successfully.
 *
 * N15 finding — the fixed-price case is the direct regression proof for the baseline fix (removed
 * `chk_invoice_item_trip_fixed_net_price_non_negative`): a storno of a trip with a fixed price
 * must persist `trip_fixed_net_price` negative, not just compute it in memory.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceStornoServiceTripIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var stornoService: InvoiceStornoService

    private fun sender(tag: String) = SenderProfile().apply {
        name = "$tag Sender GmbH"; invoicePrefix = "$tag-"
        email = Email.ofNullable("billing@$tag.example"); taxNumber = TaxNumber("12/345/67890"); vatId = VatId("DE111111111")
        iban = Iban.ofNullable("DE89370400440532013000")
        phone = "+49 30 1234567"
    }

    private fun recipient(tag: String) = Recipient().apply {
        customerNumber = CustomerNumber("$tag-100"); name = "$tag Kunde AG"; email = Email.ofNullable("ap@$tag.example")
        vatId = VatId("DE222222222")
    }

    private fun taxRate(tag: String) = TaxRate().apply {
        // name carries the tag too, not the literal "USt 19%": this module's IT suite shares one
        // Postgres container across every class, and TaxRuleMigrationIT's own "the pre-existing
        // 19pct DE row was backfilled to DE_STANDARD_19" test does
        // `SELECT ... FROM tax_rate WHERE name = 'USt 19%'` expecting exactly the one seeded
        // row -- a second (un-cleaned) fixture row with that same literal name breaks that query
        // with a NonUniqueResultException.
        code = "IT_STORNO_TRIP_${tag}_${System.nanoTime()}"; category = "S"; name = "USt 19% ($tag)"
        rate = TaxRatePercent(BigDecimal("19.00")); country = Country.DE; validFrom = LocalDate.of(2024, 1, 1)
        isDefault = false; isSelectable = false
    }

    private fun receipt(tag: String) = Receipt().apply {
        fileName = "$tag.pdf"; description = "Beleg $tag"; objectKey = "it/storno-trip/$tag-${System.nanoTime()}"
        // Unique per run, not the fixed literal "a".repeat(64): receipt.sha256_checksum carries
        // uq_receipt_sha256_checksum against this shared, never-cleaned container.
        sizeBytes = 1024L; sha256Checksum = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
        uploadedAt = OffsetDateTime.now(); documentDate = LocalDate.of(2026, 3, 14)
    }

    /** A SENT, numbered original with one main SERVICE item and one TRIP item — built directly
     *  (not through the full lifecycle transition gauntlet, same idiom as TripExpenseIT) since
     *  [InvoiceStornoService.createCreditNote] only cares that the original is already SENT and
     *  numbered. */
    private fun sentOriginalWithTrip(tag: String, tripBuilder: (TaxRate) -> InvoiceItem): Invoice {
        val s = sender(tag)
        val r = recipient(tag)
        val rate = taxRate(tag)
        em.persist(s); em.persist(r); em.persist(rate); em.flush()

        val serviceItem = InvoiceItem().apply {
            positionId = "1"; category = InvoiceItemCategory.SERVICE; description = "Beratung"
            quantity = BigDecimal.ONE; unit = InvoiceItemUnit.HOUR; unitPriceNet = Money.of("100.00")
            taxRateRef = rate
        }.also { it.applyTaxRule(rate) }
        serviceItem.calculateAmounts()

        val trip = tripBuilder(rate).also { it.positionId = "2"; it.applyTaxRule(rate) }
        trip.calculateAmounts()

        // The trip_expense freeze guard rejects an INSERT whose owning invoice is
        // already issued (same TripExpenseIT idiom, `markIssued`): persist as DRAFT first, so the
        // cascaded trip_expense/travel_expense_entry inserts see a non-issued invoice, then flip
        // to SENT via a native UPDATE afterward — the guard only ever fires on the row being
        // written, not retroactively on rows already committed.
        val invoice = Invoice().apply {
            senderProfile = s; this.recipient = r
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 3, 1)
            forceStatusForTest(InvoiceStatus.DRAFT)
        }
        invoice.addItem(serviceItem)
        invoice.addItem(trip)
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        em.createNativeQuery("UPDATE invoice SET status = 2 WHERE id = :id")
            .setParameter("id", invoice.id).executeUpdate()
        em.flush()
        return invoice
    }

    @Test
    fun `storno copies a trip's expenses, per-diem days and receipt links, negating amounts`() {
        val invoiceId = QuarkusTransaction.requiringNew().call {
            val hotelReceipt = receipt("hotel")
            em.persist(hotelReceipt); em.flush()

            val invoice = sentOriginalWithTrip("STRP") { rate ->
                val trip = InvoiceItem().apply {
                    category = InvoiceItemCategory.TRIP
                    description = "Dienstreise Hamburg"
                    taxRateRef = rate
                }
                trip.tripExpenses.add(
                    TripExpense().apply {
                        invoiceItem = trip
                        kind = TripExpenseKind.HOTEL; description = "Hotel"
                        quantity = BigDecimal.ONE; unit = InvoiceItemUnit.DAY
                        unitPriceNet = Money.of("158.88"); receipt = hotelReceipt
                        calculateNetAmount()
                    },
                )
                trip.receipts.add(hotelReceipt)
                trip
            }
            invoice.id!!
        }

        val creditNoteId = QuarkusTransaction.requiringNew().call {
            stornoService.createCreditNote(invoiceId, reason = "Test-Storno").id!!
        }

        QuarkusTransaction.requiringNew().run {
            em.clear()
            val creditNote = em.find(Invoice::class.java, creditNoteId)
            val trip = creditNote.itemList.single { it.category == InvoiceItemCategory.TRIP }
            val expense = trip.tripExpenses.single()
            assertEquals("Hotel", expense.description)
            assertEquals(Money.of("-158.88"), expense.unitPriceNet, "the expense's unit price is negated")
            assertEquals(Money.of("-158.88"), expense.netAmount)
            assertNotNull(expense.receipt, "the receipt link is copied onto the new expense row")
            assertTrue(trip.receipts.isNotEmpty(), "the item-level receipt link is duplicated onto the credit note")

            // The original stays untouched.
            val original = em.find(Invoice::class.java, invoiceId)
            val originalTrip = original.itemList.single { it.category == InvoiceItemCategory.TRIP }
            assertEquals(Money.of("158.88"), originalTrip.tripExpenses.single().unitPriceNet, "the original is never mutated")
        }
    }

    @Test
    fun `storno of a fixed-price trip persists a negative trip_fixed_net_price (N15 regression)`() {
        val invoiceId = QuarkusTransaction.requiringNew().call {
            val invoice = sentOriginalWithTrip("STFP") { rate ->
                InvoiceItem().apply {
                    category = InvoiceItemCategory.TRIP
                    description = "Workshop Pauschale"
                    taxRateRef = rate
                    tripFixedNetPrice = Money.of("500.00")
                }
            }
            invoice.id!!
        }

        val creditNoteId = QuarkusTransaction.requiringNew().call {
            stornoService.createCreditNote(invoiceId, reason = "Test-Storno Festpreis").id!!
        }

        QuarkusTransaction.requiringNew().run {
            em.clear()
            val creditNote = em.find(Invoice::class.java, creditNoteId)
            val trip = creditNote.itemList.single { it.category == InvoiceItemCategory.TRIP }
            assertEquals(Money.of("-500.00"), trip.tripFixedNetPrice, "the fixed price persists negative — no CHECK rejects it (N15)")
            assertEquals(Money.of("-500.00"), trip.netAmount)

            // Re-reading via a native query proves the row round-tripped through the actual
            // trip_fixed_net_price column, not just the in-memory entity graph.
            val raw = em.createNativeQuery(
                "SELECT trip_fixed_net_price FROM invoice_item WHERE id = :id",
            ).setParameter("id", trip.id).singleResult as BigDecimal
            assertEquals(0, BigDecimal("-500.00").compareTo(raw))
        }
    }
}
