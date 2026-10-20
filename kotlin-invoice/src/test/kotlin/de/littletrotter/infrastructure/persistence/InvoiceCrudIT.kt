package de.littletrotter.infrastructure.persistence

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.fixture.anInvoiceDocument
import de.littletrotter.fixture.anInvoiceLlmAnalysis
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.domain.reference.TravelExpenseEntry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceCrudIT {

    @Inject
    lateinit var em: EntityManager

    @Test
    @Transactional
    fun `Invoice with InvoiceItem - insert update delete with cascade`() {
        val invoice = Invoice().edit {
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().edit {
                taxRateRef = em.seededStandardTaxRate()
                positionId = "1"
                description = "Softwareentwicklung Sprint 12"
                quantity = BigDecimal("8.0")
                unitPriceNet = Money(BigDecimal("120.00"))
            })
        }
        em.persist(invoice)
        em.flush()

        assertNotNull(invoice.id)
        assertNotNull(invoice.itemList[0].id)
        assertEquals(0L, invoice.version)
        assertEquals(0L, invoice.itemList[0].version)

        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        em.merge(invoice)
        em.flush()
        em.clear()

        val reloaded = em.find(Invoice::class.java, invoice.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals(InvoiceStatus.IN_REVIEW, reloaded.status)

        val itemId = invoice.itemList[0].id!!
        em.remove(reloaded)
        em.flush()
        em.clear()

        assertNull(em.find(Invoice::class.java, invoice.id), "Invoice must be deleted")
        assertNull(em.find(InvoiceItem::class.java, itemId), "InvoiceItem must be cascade-deleted")
    }

    @Test
    @Transactional
    fun `TravelExpenseEntry - insert update delete`() {
        val invoice = Invoice().edit {
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().edit {
                taxRateRef = em.seededStandardTaxRate()
                positionId = "1"
                description = "Reisekosten Position"
                quantity = BigDecimal.ONE
                unitPriceNet = Money(BigDecimal("0.00"))
            })
        }
        em.persist(invoice)
        em.flush()
        val item = invoice.itemList[0]

        val entry = TravelExpenseEntry().edit {
            invoiceItem = item
            shortName = "Dienstreise Berlin"
            country = de.littletrotter.shared.country.Country.DE
            travelDate = LocalDate.now()
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("28.00")
        }
        em.persist(entry)
        em.flush()

        assertNotNull(entry.id)
        assertEquals(0L, entry.version)

        entry.edit { calculatedAmount = Money.of("14.00") }
        em.merge(entry)
        em.flush()
        em.clear()

        val reloaded = em.find(TravelExpenseEntry::class.java, entry.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals(Money.of("14.00"), reloaded.calculatedAmount)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(TravelExpenseEntry::class.java, entry.id))
    }

    @Test
    @Transactional
    fun `InvoiceDocument - insert update delete`() {
        val invoice = Invoice().edit {
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().edit {
                taxRateRef = em.seededStandardTaxRate()
                positionId = "1"
                description = "Pos 1"
                quantity = BigDecimal.ONE
                unitPriceNet = Money(BigDecimal("50.00"))
            })
        }
        em.persist(invoice)
        em.flush()

        val doc = anInvoiceDocument {
            this.invoice = invoice
            shortName = "rechnung-doc"
            documentType = "PDF"
            objectKey = "invoices/${UUID.randomUUID()}.pdf"
            fileName = "rechnung.pdf"
            mimeType = "application/pdf"
            // sha256_checksum is NOT NULL.
            sha256Checksum = "a".repeat(64)
            generationStatus = de.littletrotter.domain.invoice.DocumentGenerationStatus.PENDING
        }
        em.persist(doc)
        em.flush()

        assertNotNull(doc.id)
        assertEquals(0L, doc.version)

        doc.recordGenerated("b".repeat(64), java.time.OffsetDateTime.now())
        em.merge(doc)
        em.flush()
        em.clear()

        val reloaded = em.find(InvoiceDocument::class.java, doc.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals(de.littletrotter.domain.invoice.DocumentGenerationStatus.COMPLETED, reloaded.generationStatus)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(InvoiceDocument::class.java, doc.id))
    }

    @Test
    @Transactional
    fun `InvoiceLlmAnalysis - insert update delete`() {
        val invoice = Invoice().edit {
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().edit {
                taxRateRef = em.seededStandardTaxRate()
                positionId = "1"
                description = "LLM Test"
                quantity = BigDecimal.ONE
                unitPriceNet = Money(BigDecimal("10.00"))
            })
        }
        em.persist(invoice)
        em.flush()

        val analysis = anInvoiceLlmAnalysis {
            this.invoice = invoice
            summary = "Looks good"
            rating = 4
        }
        em.persist(analysis)
        em.flush()

        assertNotNull(analysis.id)
        assertEquals(0L, analysis.version)

        analysis.complete("Looks great", java.time.OffsetDateTime.now())
        em.merge(analysis)
        em.flush()
        em.clear()

        val reloaded = em.find(InvoiceLlmAnalysis::class.java, analysis.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals("Looks great", reloaded.summary)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(InvoiceLlmAnalysis::class.java, analysis.id))
    }
}
